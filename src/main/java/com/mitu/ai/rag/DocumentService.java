package com.mitu.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mitu.ai.ai.AiException;
import com.mitu.ai.ai.GeminiClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads uploaded PDFs page by page, splits them into chunks, embeds them and keeps them searchable. */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    static final int CHUNK_SIZE = 900;
    static final int CHUNK_OVERLAP = 150;

    private final GeminiClient ai;
    private final VectorStore store;

    @Autowired
    public DocumentService(GeminiClient ai, ObjectMapper json, @Value("${mitu.data-dir}") String dataDir) {
        this(ai, new VectorStore(Path.of(dataDir, "mitu-index.json"), json));
    }

    public DocumentService(GeminiClient ai, VectorStore store) {
        this.ai = ai;
        this.store = store;
    }

    public VectorStore store() {
        return store;
    }

    /** Reads a PDF's text page by page and adds it. */
    public DocumentInfo addPdf(String fileName, byte[] bytes) throws IOException {
        List<String> pages = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(pdf));
            }
        }
        if (pages.stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("This PDF has no readable text (it may be a scanned photo).");
        }
        return addPages(fileName, pages);
    }

    /** Adds a document given as the text of each page (page 1 first). */
    public DocumentInfo addPages(String name, List<String> pages) {
        String id = UUID.randomUUID().toString();
        boolean smart = ai.isConfigured();
        List<Chunk> chunks = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            for (String piece : Chunker.split(pages.get(p), CHUNK_SIZE, CHUNK_OVERLAP)) {
                float[] vector = null;
                if (smart) {
                    try {
                        vector = ai.embed(piece);
                    } catch (AiException e) {
                        log.warn("Embedding failed, keyword search will be used for {}: {}", name, e.getMessage());
                        smart = false;
                    }
                }
                chunks.add(new Chunk(id, name, p + 1, piece, vector));
            }
        }
        if (!smart) chunks.replaceAll(c -> new Chunk(c.docId(), c.docName(), c.page(), c.text(), null));
        // Uploading a file again replaces the old copy instead of adding a duplicate.
        store.documents().stream().filter(d -> d.name().equals(name)).forEach(d -> store.remove(d.id()));
        DocumentInfo doc = new DocumentInfo(id, name, pages.size(), chunks.size(), System.currentTimeMillis(), smart);
        store.add(doc, chunks);
        return doc;
    }

    public List<DocumentInfo> list() {
        return store.documents();
    }

    public boolean delete(String id) {
        return store.remove(id);
    }
}
