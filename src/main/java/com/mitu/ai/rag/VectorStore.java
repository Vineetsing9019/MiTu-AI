package com.mitu.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A small in-memory vector database, saved to a JSON file so documents survive a restart.
 * Search uses cosine similarity between embeddings when they exist, and falls back to
 * keyword scoring (TF-IDF style) when MiTu runs without an AI key.
 */
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private static final Pattern WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "are", "was", "of", "to", "in", "on", "and",
            "or", "for", "what", "which", "who", "how", "does", "do", "it", "this", "that", "with", "as", "by", "be",
            "at", "from", "about", "me", "tell", "my", "can", "you", "please", "there");

    /** A chunk with its score for one search. */
    public record Hit(Chunk chunk, double score) {}

    record Saved(List<DocumentInfo> docs, List<Chunk> chunks) {}

    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, DocumentInfo> docs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Path file;
    private final ObjectMapper json;

    /** file may be null for a store that is never saved (tests). */
    public VectorStore(Path file, ObjectMapper json) {
        this.file = file;
        this.json = json;
        load();
    }

    public synchronized void add(DocumentInfo doc, List<Chunk> newChunks) {
        docs.put(doc.id(), doc);
        chunks.addAll(newChunks);
        save();
    }

    public synchronized boolean remove(String docId) {
        boolean had = docs.remove(docId) != null;
        chunks.removeIf(c -> c.docId().equals(docId));
        save();
        return had;
    }

    public List<DocumentInfo> documents() {
        synchronized (docs) {
            return new ArrayList<>(docs.values());
        }
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /** Top {@code k} chunks for a question: by meaning when a query vector is given, else by keywords. */
    public List<Hit> search(String question, float[] queryVector, int k) {
        List<Hit> hits = new ArrayList<>();
        if (queryVector != null) {
            for (Chunk c : chunks) {
                if (c.vector() != null && c.vector().length == queryVector.length) {
                    hits.add(new Hit(c, cosine(queryVector, c.vector())));
                }
            }
        }
        if (hits.isEmpty()) hits = keywordSearch(question);
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.stream().filter(h -> h.score() > 0).limit(k).toList();
    }

    private List<Hit> keywordSearch(String question) {
        List<String> terms = words(question).stream().filter(w -> !STOP.contains(w)).distinct().toList();
        if (terms.isEmpty()) return new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> chunkWords = new ArrayList<>();
        for (Chunk c : chunks) {
            List<String> w = words(c.text());
            chunkWords.add(w);
            for (String t : new HashSet<>(w)) df.merge(t, 1, Integer::sum);
        }
        int n = Math.max(1, chunks.size());
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            List<String> w = chunkWords.get(i);
            double score = 0;
            for (String t : terms) {
                long tf = w.stream().filter(t::equals).count();
                if (tf > 0) score += (1 + Math.log(tf)) * Math.log(1.0 + (double) n / df.getOrDefault(t, 1));
            }
            hits.add(new Hit(chunks.get(i), score / Math.sqrt(Math.max(20, w.size()))));
        }
        return hits;
    }

    static List<String> words(String text) {
        return Arrays.stream(WORD.split(text.toLowerCase(Locale.ROOT))).filter(s -> s.length() > 1).toList();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Saved s = json.readValue(file.toFile(), new TypeReference<Saved>() {});
            s.docs().forEach(d -> docs.put(d.id(), d));
            chunks.addAll(s.chunks());
            log.info("Loaded {} documents ({} chunks)", docs.size(), chunks.size());
        } catch (IOException e) {
            log.warn("Could not read {}; starting empty", file, e);
        }
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            json.writeValue(file.toFile(), new Saved(documents(), new ArrayList<>(chunks)));
        } catch (IOException e) {
            log.warn("Could not save {}", file, e);
        }
    }
}
