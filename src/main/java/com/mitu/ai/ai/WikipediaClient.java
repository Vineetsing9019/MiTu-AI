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

    /** Most of a lead section; the facts people ask about ("current president", "won the final") are in it. */
    static final int MAX_CHARS = 2500;

    /**
     * The lead sections (introductions) of the top {@code max} articles for a question, in
     * search order; empty when nothing fits or Wikipedia is down. Two requests: search, then
     * all the introductions at once.
     */
    public List<Article> lookup(String question, int max) {
        List<Article> out = new ArrayList<>();
        try {
            String q = URLEncoder.encode(question, StandardCharsets.UTF_8);
            List<String> titles = new ArrayList<>();
            for (JsonNode p : get("https://en.wikipedia.org/w/rest.php/v1/search/page?limit=" + max + "&q=" + q).path("pages")) {
                String title = p.path("title").asText("");
                if (!title.isBlank()) titles.add(title);
            }
            if (titles.isEmpty()) return out;
            String joined = URLEncoder.encode(String.join("|", titles), StandardCharsets.UTF_8);
            JsonNode pages = get("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
                    + "&prop=extracts&exintro=1&explaintext=1&exlimit=" + titles.size() + "&titles=" + joined).path("query").path("pages");
            java.util.Map<String, String> intro = new java.util.HashMap<>();
            for (JsonNode p : pages) intro.put(p.path("title").asText(""), p.path("extract").asText(""));
            for (String title : titles) {
                String text = intro.getOrDefault(title, "").trim();
                if (text.isBlank() || text.contains("may refer to:")) continue;
                if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "…";
                out.add(new Article(title, "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'), text));
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
