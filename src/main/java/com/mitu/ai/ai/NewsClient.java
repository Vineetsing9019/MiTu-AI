package com.mitu.ai.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Free, key-less news headlines from Google News RSS (India edition), so questions like
 * "what's in the news today?" get real, current headlines with links.
 */
@Component
public class NewsClient {

    private static final Logger log = LoggerFactory.getLogger(NewsClient.class);
    private static final Pattern NEWSY = Pattern.compile(
            "\\b(news|headlines?|latest|this week|current affairs|breaking|happening|score|match|election|stock market|weather)\\b");
    private static final Pattern FILLER = Pattern.compile(
            "\\b(what|what's|whats|who|who's|is|are|was|were|the|in|on|of|a|an|me|tell|give|show|please|today|todays|today's|latest|news|headlines?|any|about|happening|going|top|current|currently|now|present|recent|recently|did|does|do)\\b");

    /** One headline. */
    public record Headline(String title, String source, String url, String published) {}

    private final org.springframework.web.client.RestClient http;

    public NewsClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(8_000);
        this.http = org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
    }

    private static final Pattern FRESH = Pattern.compile(
            "\\b(current|currently|now|present|incumbent|latest|last|recent|recently|won|wins|winner|champions?|who is the|who's the|this year|20[2-3]\\d)\\b");
    private static final Pattern INDIA = Pattern.compile(
            "\\b(india|indian|delhi|mumbai|maharashtra|bengaluru|bangalore|chennai|kolkata|hyderabad|pune|gujarat|kerala|tamil|karnataka|bihar|punjab|rajasthan|bollywood|ipl|bcci|modi|rupee|sensex|nifty)\\b");
    private static final Pattern WORLD = Pattern.compile("\\b(world|international|global|worldwide|abroad)\\b");

    /** True for factual questions whose answer can change over time ("who is the current…", "who won…"). */
    public static boolean isFreshFact(String q) {
        return FRESH.matcher(q.toLowerCase(Locale.ROOT)).find();
    }

    /** Which Google News feed to read: India edition, international edition, or the World section. */
    static String feedUrl(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        String topic = topic(question);
        boolean india = INDIA.matcher(lower).find();
        String edition = india ? "hl=en-IN&gl=IN&ceid=IN:en" : "hl=en-US&gl=US&ceid=US:en";
        if (WORLD.matcher(lower).find() && topic.replaceAll(WORLD.pattern(), "").isBlank()) {
            return "https://news.google.com/rss/headlines/section/topic/WORLD?" + edition;
        }
        if (topic.isEmpty()) return "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en";
        return "https://news.google.com/rss/search?q=" + URLEncoder.encode(topic, StandardCharsets.UTF_8) + "&" + edition;
    }

    /** True for questions that want current events rather than settled facts. */
    public static boolean isNewsQuestion(String q) {
        return NEWSY.matcher(q.toLowerCase(Locale.ROOT)).find();
    }

    /** The search words left after removing filler ("what's in the news in India today?" → "India"). */
    static String topic(String q) {
        String t = FILLER.matcher(q.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", " ")).replaceAll(" ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Up to {@code max} current headlines for the question's topic (top stories when there is none). */
    public List<Headline> headlines(String question, int max) {
        String url = feedUrl(question);
        List<Headline> out = new ArrayList<>();
        try {
            byte[] xml = http.get().uri(URI.create(url)).retrieve().body(byte[].class);
            if (xml == null) return out;
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList items = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagName("item");
            for (int i = 0; i < items.getLength() && out.size() < max; i++) {
                Element item = (Element) items.item(i);
                out.add(new Headline(text(item, "title"), text(item, "source"), text(item, "link"), text(item, "pubDate")));
            }
        } catch (Exception e) {
            log.warn("Google News lookup failed: {}", e.getMessage());
        }
        return out;
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }
}
