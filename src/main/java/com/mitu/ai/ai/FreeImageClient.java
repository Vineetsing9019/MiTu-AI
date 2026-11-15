package com.mitu.ai.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free text-to-image generation with Pollinations.ai (no account or key needed).
 * MiTu uses it for "draw me…" requests in chat and for free anime art: Gemini
 * describes the photo, then Pollinations paints that description in the chosen style.
 */
@Component
public class FreeImageClient {

    private static final Logger log = LoggerFactory.getLogger(FreeImageClient.class);
    static final String BASE = "https://image.pollinations.ai/prompt/";
    private static final int MAX_PROMPT = 900;

    private final RestClient http;

    public FreeImageClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** A link that renders the picture when opened (the browser loads it directly). */
    public static String url(String prompt, int width, int height) {
        String p = prompt.length() > MAX_PROMPT ? prompt.substring(0, MAX_PROMPT) : prompt;
        String encoded = URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20");
        int seed = ThreadLocalRandom.current().nextInt(1, 1_000_000);
        return BASE + encoded + "?width=" + width + "&height=" + height + "&nologo=true&seed=" + seed;
    }

    /** Generates a picture and returns its bytes. */
    public GeminiClient.Image generate(String prompt, int width, int height) {
        // The free service sometimes turns requests away when busy: try up to 3 times.
        AiException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return generateOnce(prompt, width, height);
            } catch (AiException e) {
                last = e;
                log.info("Pollinations attempt {} failed: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private GeminiClient.Image generateOnce(String prompt, int width, int height) {
        try {
            ResponseEntity<byte[]> res = http.get().uri(URI.create(url(prompt, width, height))).retrieve().toEntity(byte[].class);
            byte[] body = res.getBody();
            String type = res.getHeaders().getContentType() == null ? "" : res.getHeaders().getContentType().toString();
            if (body == null || body.length < 1000 || !type.startsWith("image/")) {
                throw new AiException("The free image service returned no picture. Please try again.");
            }
            return new GeminiClient.Image(type, body);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Pollinations failed", e);
            throw new AiException("The free image service is busy. Please try again in a moment.", 503);
        }
    }
}
