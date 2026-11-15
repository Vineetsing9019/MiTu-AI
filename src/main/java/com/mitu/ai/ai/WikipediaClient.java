package com.mitu.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Free fact lookup with Wikipedia's public API (no key). Used when Google Search
 * grounding isn't available on the Gemini key, so MiTu can still answer with sources.
 */
@Component
public class WikipediaClient {

    private static final Logger log = LoggerFactory.getLogger(WikipediaClient.class);
    private static final String UA = "MiTu-AI/1.0 (https://github.com/Vineetsing9019/MiTu-AI)";

    /** One article summary. */
    public record Article(String title, String url, String summary) {}

    private final RestClient http;
    private final ObjectMapper json;

    public WikipediaClient(ObjectMapper json) {
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(8_000);
        this.http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", UA).build();
    }

    /** Up to {@code max} article summaries for a question; empty when nothing fits or Wikipedia is down. */
    public List<Article> lookup(String question, int max) {
        List<Article> out = new ArrayList<>();
        try {
            String q = URLEncoder.encode(question, StandardCharsets.UTF_8);
            JsonNode pages = get("https://en.wikipedia.org/w/rest.php/v1/search/page?limit=" + max + "&q=" + q).path("pages");
            for (JsonNode p : pages) {
                String key = p.path("key").asText("");
                if (key.isBlank()) continue;
                JsonNode s = get("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
                String extract = s.path("extract").asText("");
                if (extract.isBlank() || "disambiguation".equals(s.path("type").asText())) continue;
                String url = s.path("content_urls").path("desktop").path("page").asText("https://en.wikipedia.org/wiki/" + key);
                out.add(new Article(s.path("title").asText(key), url, extract));
            }
        } catch (Exception e) {
            log.warn("Wikipedia lookup failed: {}", e.getMessage());
        }
        return out;
    }

    private JsonNode get(String url) throws Exception {
        return json.readTree(http.get().uri(URI.create(url)).retrieve().body(String.class));
    }
}
