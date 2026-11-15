package com.mitu.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to Google's Gemini REST API: text answers (optionally grounded with Google Search),
 * embeddings (for document search), photo descriptions and image editing. Every call needs
 * an API key; without one MiTu falls back to its offline modes.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    /** One turn of a conversation: role is "user" or "model". */
    public record Turn(String role, String text) {}

    /** A generated picture. */
    public record Image(String mimeType, byte[] bytes) {}

    /** A web page an answer was based on. */
    public record WebSource(String title, String url) {}

    /** The model's answer, plus the web pages Google Search used for it (if any). */
    public record Answer(String text, List<WebSource> web) {}

    private final GeminiProperties props;
    private final ObjectMapper json;
    private final RestClient http;

    public GeminiClient(GeminiProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return props.hasKey();
    }

    /** Asks the chat model, with a system instruction and the conversation so far. */
    public String generate(String system, List<Turn> turns) {
        return generate(system, turns, false).text();
    }

    /**
     * Asks the chat model. With {@code googleSearch}, the model may search Google itself
     * when the question needs fresh or factual information, and the pages it used come back.
     */
    public Answer generate(String system, List<Turn> turns, boolean googleSearch) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        ArrayNode contents = body.putArray("contents");
        for (Turn t : turns) {
            ObjectNode c = contents.addObject();
            c.put("role", t.role());
            c.putArray("parts").addObject().put("text", t.text());
        }
        if (googleSearch) body.putArray("tools").addObject().putObject("google_search");
        body.putObject("generationConfig").put("temperature", 0.3);
        JsonNode candidate = postWithFallback(props.chatModel(), ":generateContent", body).path("candidates").path(0);
        StringBuilder out = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            out.append(part.path("text").asText(""));
        }
        if (out.isEmpty()) throw new AiException("The AI gave an empty answer. Please try again.");
        return new Answer(out.toString().trim(), webSources(candidate.path("groundingMetadata")));
    }

    /** The distinct web pages listed in a Google Search grounding block. */
    static List<WebSource> webSources(JsonNode grounding) {
        Map<String, WebSource> byTitle = new LinkedHashMap<>();
        for (JsonNode chunk : grounding.path("groundingChunks")) {
            JsonNode web = chunk.path("web");
            String url = web.path("uri").asText("");
            if (url.isBlank()) continue;
            String title = web.path("title").asText(url);
            byTitle.putIfAbsent(title, new WebSource(title, url));
        }
        return new ArrayList<>(byTitle.values()).subList(0, Math.min(5, byTitle.size()));
    }

    /** Turns text into a vector of numbers that captures its meaning. */
    public float[] embed(String text) {
        ObjectNode body = json.createObjectNode();
        body.putObject("content").putArray("parts").addObject().put("text", text);
        body.put("outputDimensionality", 768);
        JsonNode values = post(props.embedModel() + ":embedContent", body).path("embedding").path("values");
        float[] v = new float[values.size()];
        for (int i = 0; i < v.length; i++) v[i] = (float) values.get(i).asDouble();
        if (v.length == 0) throw new AiException("No embedding returned.");
        return v;
    }

    /** Looks at a photo and answers an instruction about it in text (works on the free plan). */
    public String describeImage(byte[] photo, String mimeType, String instruction) {
        ObjectNode body = json.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        ObjectNode inline = parts.addObject().putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", Base64.getEncoder().encodeToString(photo));
        parts.addObject().put("text", instruction);
        JsonNode res = postWithFallback(props.chatModel(), ":generateContent", body);
        StringBuilder out = new StringBuilder();
        for (JsonNode part : res.path("candidates").path(0).path("content").path("parts")) {
            out.append(part.path("text").asText(""));
        }
        if (out.isEmpty()) throw new AiException("The AI could not describe the photo.");
        return out.toString().trim();
    }

    /** Sends a photo with an instruction and returns the redrawn picture (needs a paid plan). */
    public Image editImage(byte[] photo, String mimeType, String instruction) {
        ObjectNode body = json.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        ObjectNode inline = parts.addObject().putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", Base64.getEncoder().encodeToString(photo));
        parts.addObject().put("text", instruction);
        ArrayNode modalities = body.putObject("generationConfig").putArray("responseModalities");
        modalities.add("TEXT");
        modalities.add("IMAGE");
        JsonNode res;
        try {
            res = postWithFallback(props.imageModel(), ":generateContent", body);
        } catch (AiException e) {
            if (e.status == 429) throw new AiException("AI image editing needs billing turned on for your Gemini key (it is not in the free plan).", 429);
            throw e;
        }
        for (JsonNode part : res.path("candidates").path(0).path("content").path("parts")) {
            JsonNode data = part.has("inlineData") ? part.get("inlineData") : part.path("inline_data");
            if (data.has("data")) {
                String mime = data.has("mimeType") ? data.get("mimeType").asText() : data.path("mime_type").asText("image/png");
                return new Image(mime, Base64.getDecoder().decode(data.get("data").asText()));
            }
        }
        throw new AiException("The image model did not return a picture.");
    }

    /**
     * Tries each model in a comma-separated list ("primary,backup") until one answers.
     * Google retires and rate-limits models often, so a busy (503), limited (429) or
     * retired (404) model falls through to the next one instead of failing the user.
     */
    JsonNode postWithFallback(String models, String method, ObjectNode body) {
        AiException last = null;
        for (String model : models.split(",")) {
            if (model.isBlank()) continue;
            try {
                return post(model.trim() + method, body);
            } catch (AiException e) {
                last = e;
                if (e.status != 404 && e.status != 429 && e.status != 503) throw e;
            }
        }
        throw last != null ? last : new AiException("No AI model is configured.");
    }

    /** The first model of a comma-separated list, for display. */
    public static String primary(String models) {
        return models == null ? "" : models.split(",")[0].trim();
    }

    private JsonNode post(String path, ObjectNode body) {
        if (!isConfigured()) throw new AiException("No Gemini API key is set.");
        try {
            String raw = http.post()
                    // Full URL: a bare "model:method" would be read as a URL scheme.
                    .uri(URI.create(BASE + path))
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return json.readTree(raw);
        } catch (RestClientResponseException e) {
            log.warn("Gemini {} failed: {} {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            String why = switch (e.getStatusCode().value()) {
                case 400 -> "The AI could not read that request.";
                case 401, 403 -> "The Gemini API key was refused. Check GEMINI_API_KEY.";
                case 404 -> "This AI model is not available for your key.";
                case 429 -> "Free AI limit reached. Wait a minute and try again.";
                default -> "The AI service is busy. Please try again.";
            };
            throw new AiException(why, e.getStatusCode().value());
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gemini {} failed", path, e);
            throw new AiException("Could not reach the AI service.");
        }
    }
}
