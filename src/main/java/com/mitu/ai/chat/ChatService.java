package com.mitu.ai.chat;

import com.mitu.ai.ai.AiException;
import com.mitu.ai.ai.FreeImageClient;
import com.mitu.ai.ai.GeminiClient;
import com.mitu.ai.ai.GeminiClient.Turn;
import com.mitu.ai.ai.GeminiClient.WebSource;
import com.mitu.ai.ai.WikipediaClient;
import com.mitu.ai.rag.DocumentService;
import com.mitu.ai.rag.VectorStore.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MiTu's chat. Retrieval-augmented generation over the user's documents, plus:
 * Google Search grounding (or Wikipedia when that isn't available) for facts,
 * and free picture generation for "draw me…" requests. Every answer comes back
 * with its sources. Without an AI key MiTu still answers by quoting the best passages.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int TOP_K = 5;
    private static final int HISTORY_TURNS = 8;
    private static final int MAX_SESSIONS = 500;

    /** The AI has no clock, so every request tells it the current date and time (India). */
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH);

    static final String SYSTEM = """
            You are MiTu, a friendly, smart and precise AI assistant made by Vineetsing Girase.
            Right now it is %s (India Standard Time). Use this for any question about today's
            date, day or time, and for "how many days until..." style questions.
            Your built-in knowledge ends some time before today, so events dated before today have
            already happened even if you don't remember them. When WEB passages give newer facts,
            trust them over your memory.

            How to answer:
            - If the user asks you to draw, create, generate, make or show an image, picture, photo,
              drawing, logo, poster, wallpaper or any art, reply with exactly ONE line and nothing else:
              IMAGE: <a vivid, detailed English description of the picture to paint, including its style>
            - If CONTEXT passages from the user's documents are given and they answer the question,
              answer from them and cite the passages you used like [1] or [2].
            - If the question is clearly about the user's documents but the context doesn't contain
              the answer, say you couldn't find it in their documents.
            - If WEB passages (news headlines or Wikipedia) are given and they are relevant, use them
              and cite them like [W1] or [W2]. For news, list the main headlines briefly. If they only
              partly answer, combine them with your own knowledge and give a direct answer; never
              reply with "the text doesn't say" or "the provided information".
            - Never say you will search, look something up or check later: answer now. If a fact may
              have changed recently and no WEB passage covers it, give what you know and say it may be
              out of date.
            - For everything else (greetings, general knowledge, coding, studies, advice), just answer
              helpfully. Don't mention "context", "passages" or "documents" then.
            - Don't introduce yourself unless asked who you are.
            - Keep answers short and clear: a direct answer first, then bullet points if useful.
            - Never invent page numbers, names, links or figures.""";

    /** Below this similarity a passage is not really about the question, so it isn't used. */
    static final double MIN_SIMILARITY = 0.45;

    /** How much of the latest document is sent when a question matches nothing (~10,000 characters). */
    static final int WHOLE_DOC_CHUNKS = 12;

    /** After Google Search grounding is refused, use Wikipedia for this long before trying again. */
    private static final long GROUNDING_RETRY_MS = 10 * 60 * 1000;

    public static String systemPrompt(ZonedDateTime now) {
        return SYSTEM.formatted(NOW.format(now));
    }

    /** One document source shown under an answer. */
    public record Source(int n, String document, int page, String snippet, double score) {}

    /**
     * MiTu's reply. mode: "ai" (from documents), "web" (looked up online), "general" (AI's own
     * knowledge), "image" (a generated picture at {@code image}) or "offline" (quoted passages).
     */
    public record Reply(String answer, List<Source> sources, String mode, List<WebSource> web, String image) {
        public Reply(String answer, List<Source> sources, String mode) {
            this(answer, sources, mode, List.of(), null);
        }
    }

    private final GeminiClient ai;
    private final DocumentService documents;
    private final WikipediaClient wikipedia;
    private final com.mitu.ai.ai.NewsClient news;
    private volatile long groundingOffUntil = 0;
    private final Map<String, Deque<Turn>> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Turn>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    public ChatService(GeminiClient ai, DocumentService documents, WikipediaClient wikipedia, com.mitu.ai.ai.NewsClient news) {
        this.ai = ai;
        this.documents = documents;
        this.wikipedia = wikipedia;
        this.news = news;
    }

    public Reply ask(String sessionId, String question) {
        String q = question.trim();
        float[] qv = queryVector(q);
        List<Hit> hits = documents.store().search(q, qv, TOP_K);
        // Meaning search always returns something; drop passages that aren't really related.
        if (qv != null) hits = hits.stream().filter(h -> h.chunk().vector() == null || h.score() >= MIN_SIMILARITY).toList();
        // "Summarise it", "make questions from it", "score it": nothing matches by meaning, so give
        // the AI the latest document itself. It still ignores it for unrelated questions.
        if (hits.isEmpty()) hits = documents.store().latestDocumentChunks(WHOLE_DOC_CHUNKS);
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sources.add(new Source(i + 1, h.chunk().docName(), h.chunk().page(), snippet(h.chunk().text()), round(h.score())));
        }

        if (!ai.isConfigured()) return offline(q, hits, sources, null);

        Deque<Turn> history = sessions.computeIfAbsent(sessionId == null ? "default" : sessionId, k -> new ArrayDeque<>());
        List<Turn> past;
        synchronized (history) {
            past = new ArrayList<>(history);
        }
        // Tell the AI which files exist, so "my resume" or "he" makes sense to it.
        String files = documents.list().stream().map(d -> d.name()).collect(Collectors.joining(", "));
        String prompt = promptWithContext(q, hits);
        if (!files.isEmpty()) prompt = "(Files the user has uploaded: " + files + ")\n\n" + prompt;
        String system = systemPrompt(ZonedDateTime.now(ZONE));

        try {
            String answer;
            List<WebSource> web;
            if (com.mitu.ai.ai.NewsClient.isNewsQuestion(q)) {
                GeminiClient.Answer a = withWikipedia(system, past, prompt, q);
                answer = a.text();
                web = a.web();
            } else if (System.currentTimeMillis() >= groundingOffUntil) {
                try {
                    GeminiClient.Answer a = ai.generate(system, withTurn(past, prompt), true);
                    answer = a.text();
                    web = a.web();
                } catch (AiException e) {
                    if (e.status != 400 && e.status != 403 && e.status != 429) throw e;
                    log.info("Google Search grounding unavailable ({}); using Wikipedia for a while", e.status);
                    groundingOffUntil = System.currentTimeMillis() + GROUNDING_RETRY_MS;
                    GeminiClient.Answer a = withWikipedia(system, past, prompt, q);
                    answer = a.text();
                    web = a.web();
                }
            } else {
                GeminiClient.Answer a = withWikipedia(system, past, prompt, q);
                answer = a.text();
                web = a.web();
            }

            String imagePrompt = imagePrompt(answer);
            String remembered = imagePrompt != null ? "(I painted a picture: " + imagePrompt + ")" : answer;
            synchronized (history) {
                history.addLast(new Turn("user", q));
                history.addLast(new Turn("model", remembered));
                while (history.size() > HISTORY_TURNS * 2) history.removeFirst();
            }
            if (imagePrompt != null) {
                return new Reply("Here's your picture 🎨 _" + imagePrompt + "_", List.of(), "image", List.of(),
                        FreeImageClient.url(imagePrompt, 768, 768));
            }
            List<Source> used = cited(answer, sources);
            String mode = !used.isEmpty() ? "ai" : !web.isEmpty() ? "web" : "general";
            return new Reply(answer, used, mode, web, null);
        } catch (AiException e) {
            return offline(q, hits, sources, e.getMessage());
        }
    }

    /**
     * Answers without Google Search: news questions get current Google News headlines, other
     * questions get Wikipedia summaries. The pages the answer cites ([W1]...) come back as links.
     */
    private GeminiClient.Answer withWikipedia(String system, List<Turn> past, String prompt, String q) {
        List<WebSource> found = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        if (com.mitu.ai.ai.NewsClient.isNewsQuestion(q)) {
            List<com.mitu.ai.ai.NewsClient.Headline> headlines = news.headlines(q, 8);
            if (!headlines.isEmpty()) sb.append("WEB (Google News headlines, newest first):\n");
            for (com.mitu.ai.ai.NewsClient.Headline h : headlines) {
                found.add(new WebSource(h.source().isBlank() ? "Google News" : h.source(), h.url()));
                sb.append("[W").append(found.size()).append("] ").append(h.title())
                  .append(h.published().isBlank() ? "" : " (" + h.published() + ")").append("\n");
            }
        }
        if (found.isEmpty() && q.length() >= 12) {
            List<WikipediaClient.Article> articles = wikipedia.lookup(q, 3);
            if (!articles.isEmpty()) sb.append("WEB (Wikipedia):\n");
            for (WikipediaClient.Article a : articles) {
                found.add(new WebSource("Wikipedia: " + a.title(), a.url()));
                sb.append("[W").append(found.size()).append("] ").append(a.title()).append(": ").append(a.summary()).append("\n\n");
            }
            // "Who is the current…", "who won the last…": add today's headlines, which are newer than Wikipedia.
            if (com.mitu.ai.ai.NewsClient.isFreshFact(q)) {
                List<com.mitu.ai.ai.NewsClient.Headline> headlines = news.headlines(q, 5);
                if (!headlines.isEmpty()) sb.append("WEB (Google News headlines, newest first):\n");
                for (com.mitu.ai.ai.NewsClient.Headline h : headlines) {
                    found.add(new WebSource(h.source().isBlank() ? "Google News" : h.source(), h.url()));
                    sb.append("[W").append(found.size()).append("] ").append(h.title())
                      .append(h.published().isBlank() ? "" : " (" + h.published() + ")").append("\n");
                }
            }
        }
        if (found.isEmpty()) return ai.generate(system, withTurn(past, prompt), false);
        String answer = ai.generate(system, withTurn(past, sb + "\n" + prompt), false).text();
        // Citations come as [W1], [W1][W2] or [W1, W2]: collect every W number.
        Set<Integer> nums = new HashSet<>();
        Matcher m = WEB_CITATION.matcher(answer);
        while (m.find()) {
            Matcher n = Pattern.compile("\\d+").matcher(m.group());
            while (n.find()) nums.add(Integer.parseInt(n.group()));
        }
        List<WebSource> used = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            if (nums.contains(i + 1)) used.add(found.get(i));
        }
        return new GeminiClient.Answer(answer, used);
    }

    private static List<Turn> withTurn(List<Turn> past, String prompt) {
        List<Turn> turns = new ArrayList<>(past);
        turns.add(new Turn("user", prompt));
        return turns;
    }

    private static final Pattern IMAGE_LINE = Pattern.compile("^\\W*IMAGE\\W*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The picture description when the AI decided to paint ("IMAGE: …"), else null. */
    public static String imagePrompt(String answer) {
        Matcher m = IMAGE_LINE.matcher(answer.trim());
        if (!m.matches()) return null;
        String p = m.group(1).replaceAll("[*_`]", "").replaceAll("\\s+", " ").trim();
        return p.isEmpty() ? null : p;
    }

    /** Only the sources the answer actually cites ([1], [2]...); none when it answered from general knowledge. */
    public static List<Source> cited(String answer, List<Source> sources) {
        // Models write citations as [1], [1][2], [1, 2] or [1-3]; collect every number used.
        Set<Integer> used = new HashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String[] range = part.trim().split("[-–]");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = from; n <= Math.min(to, from + 20); n++) used.add(n);
            }
        }
        return sources.stream().filter(s -> used.contains(s.n())).toList();
    }

    private static final Pattern WEB_CITATION = Pattern.compile("\\[W\\d+(?:\\s*,\\s*W?\\d+)*\\]");

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[-–,]\\s*\\d+)*)\\]");

    public void reset(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    private float[] queryVector(String q) {
        if (!ai.isConfigured() || documents.store().isEmpty()) return null;
        try {
            return ai.embed(q);
        } catch (AiException e) {
            return null; // keyword search still works
        }
    }

    static String promptWithContext(String question, List<Hit> hits) {
        if (hits.isEmpty()) {
            return question; // nothing relevant in the documents: a normal chat question
        }
        StringBuilder sb = new StringBuilder("CONTEXT:\n");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sb.append('[').append(i + 1).append("] (").append(h.chunk().docName())
              .append(", page ").append(h.chunk().page()).append(")\n")
              .append(h.chunk().text()).append("\n\n");
        }
        return sb.append("QUESTION: ").append(question).toString();
    }

    private Reply offline(String q, List<Hit> hits, List<Source> sources, String why) {
        StringBuilder sb = new StringBuilder();
        if (why != null) sb.append("(").append(why).append(" Showing the best matches from your documents instead.)\n\n");
        if (documents.store().isEmpty()) {
            sb.append("Hi, I'm MiTu! Attach a PDF with 📎 and ask me anything about it. ")
              .append("Add a Gemini API key to unlock full AI answers.");
        } else if (hits.isEmpty()) {
            sb.append("I couldn't find anything about \"").append(q).append("\" in your documents. Try other words.");
        } else {
            sb.append("Here is what your documents say:\n\n");
            for (int i = 0; i < Math.min(2, hits.size()); i++) {
                Hit h = hits.get(i);
                sb.append("[").append(i + 1).append("] ").append(snippet(h.chunk().text(), 420)).append("\n\n");
            }
        }
        return new Reply(sb.toString().trim(), sources, "offline");
    }

    static String snippet(String text) {
        return snippet(text, 220);
    }

    static String snippet(String text, int max) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max) + "…";
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
