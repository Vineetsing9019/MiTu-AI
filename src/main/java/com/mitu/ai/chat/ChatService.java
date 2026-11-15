package com.mitu.ai.chat;

import com.mitu.ai.ai.AiException;
import com.mitu.ai.ai.GeminiClient;
import com.mitu.ai.ai.GeminiClient.Turn;
import com.mitu.ai.rag.DocumentService;
import com.mitu.ai.rag.VectorStore.Hit;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MiTu's chat. Retrieval-augmented generation: find the most relevant pieces of the
 * user's documents, give them to the AI with the question, and return the answer with
 * its sources. Without an AI key (or when the AI is unavailable) MiTu still answers,
 * by quoting the best-matching passages.
 */
@Service
public class ChatService {

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

            How to answer:
            - If CONTEXT passages from the user's documents are given and they answer the question,
              answer from them and cite the passages you used like [1] or [2].
            - If the question is clearly about the user's documents but the context doesn't contain
              the answer, say you couldn't find it in their documents.
            - For everything else (greetings, dates, general facts, coding, studies, advice), just
              answer helpfully from your own knowledge. Don't mention "context" or "documents" then.
            - Keep answers short and clear: a direct answer first, then bullet points if useful.
            - Never invent page numbers, names or figures.""";

    /** Below this similarity a passage is not really about the question, so it isn't used. */
    static final double MIN_SIMILARITY = 0.45;

    public static String systemPrompt(ZonedDateTime now) {
        return SYSTEM.formatted(NOW.format(now));
    }

    /** One source shown under an answer. */
    public record Source(int n, String document, int page, String snippet, double score) {}

    /** MiTu's reply. mode: "ai" (from documents), "general" (AI's own knowledge) or "offline" (quoted passages). */
    public record Reply(String answer, List<Source> sources, String mode) {}

    private final GeminiClient ai;
    private final DocumentService documents;
    private final Map<String, Deque<Turn>> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Turn>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    public ChatService(GeminiClient ai, DocumentService documents) {
        this.ai = ai;
        this.documents = documents;
    }

    public Reply ask(String sessionId, String question) {
        String q = question.trim();
        float[] qv = queryVector(q);
        List<Hit> hits = documents.store().search(q, qv, TOP_K);
        // Meaning search always returns something; drop passages that aren't really related.
        if (qv != null) hits = hits.stream().filter(h -> h.chunk().vector() == null || h.score() >= MIN_SIMILARITY).toList();
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sources.add(new Source(i + 1, h.chunk().docName(), h.chunk().page(), snippet(h.chunk().text()), round(h.score())));
        }

        if (!ai.isConfigured()) return offline(q, hits, sources, null);

        Deque<Turn> history = sessions.computeIfAbsent(sessionId == null ? "default" : sessionId, k -> new ArrayDeque<>());
        List<Turn> turns;
        synchronized (history) {
            turns = new ArrayList<>(history);
        }
        // Tell the AI which files exist, so "my resume" or "he" makes sense to it.
        String files = documents.list().stream().map(d -> d.name()).collect(java.util.stream.Collectors.joining(", "));
        String prompt = promptWithContext(q, hits);
        if (!files.isEmpty()) prompt = "(Files the user has uploaded: " + files + ")\n\n" + prompt;
        turns.add(new Turn("user", prompt));
        try {
            String answer = ai.generate(systemPrompt(ZonedDateTime.now(ZONE)), turns);
            synchronized (history) {
                history.addLast(new Turn("user", q));
                history.addLast(new Turn("model", answer));
                while (history.size() > HISTORY_TURNS * 2) history.removeFirst();
            }
            List<Source> used = cited(answer, sources);
            return new Reply(answer, used, used.isEmpty() ? "general" : "ai");
        } catch (AiException e) {
            return offline(q, hits, sources, e.getMessage());
        }
    }

    /** Only the sources the answer actually cites ([1], [2]...); none when it answered from general knowledge. */
    public static List<Source> cited(String answer, List<Source> sources) {
        return sources.stream().filter(s -> answer.contains("[" + s.n() + "]")).toList();
    }

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
            sb.append("Hi, I'm MiTu! Upload a PDF in the Documents tab and ask me anything about it. ")
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
