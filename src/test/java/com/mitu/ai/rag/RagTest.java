package com.mitu.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mitu.ai.ai.GeminiClient;
import com.mitu.ai.ai.GeminiProperties;
import com.mitu.ai.chat.ChatService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagTest {

    private final ObjectMapper json = new ObjectMapper();
    private final GeminiClient offlineAi = new GeminiClient(new GeminiProperties("", "m", "e", "i"), json);

    @Test
    void chunkerKeepsEveryWordAndOverlaps() {
        String text = "Sentence number one is here. ".repeat(100);
        List<String> parts = Chunker.split(text, 200, 50);
        assertThat(parts).hasSizeGreaterThan(10);
        assertThat(parts).allMatch(p -> p.length() <= 200);
        assertThat(String.join(" ", parts)).contains("Sentence number one");
        assertThat(Chunker.split("   ", 200, 50)).isEmpty();
    }

    @Test
    void latestDocumentChunksComeInReadingOrder() {
        VectorStore store = new VectorStore(null, json);
        store.add(new DocumentInfo("old", "old.pdf", 1, 1, 0, false), List.of(new Chunk("old", "old.pdf", 1, "old text", null)));
        store.add(new DocumentInfo("new", "cv.pdf", 2, 3, 0, false), List.of(
                new Chunk("new", "cv.pdf", 1, "first", null),
                new Chunk("new", "cv.pdf", 1, "second", null),
                new Chunk("new", "cv.pdf", 2, "third", null)));
        assertThat(store.latestDocumentChunks(2)).extracting(h -> h.chunk().text()).containsExactly("first", "second");
        assertThat(new VectorStore(null, json).latestDocumentChunks(5)).isEmpty();
    }

    @Test
    void imageRequestsAreRecognised() {
        assertThat(ChatService.imagePrompt("IMAGE: a cat in **anime** style")).isEqualTo("a cat in anime style");
        assertThat(ChatService.imagePrompt("**IMAGE:** a red car")).isEqualTo("a red car");
        assertThat(ChatService.imagePrompt("Here is an IMAGE: idea")).isNull();
        assertThat(com.mitu.ai.ai.FreeImageClient.url("a cat & dog", 512, 512))
                .startsWith("https://image.pollinations.ai/prompt/a%20cat%20%26%20dog?width=512&height=512&nologo=true&seed=");
    }

    @Test
    void cosineSimilarity() {
        assertThat(VectorStore.cosine(new float[] {1, 0}, new float[] {1, 0})).isEqualTo(1.0);
        assertThat(VectorStore.cosine(new float[] {1, 0}, new float[] {0, 1})).isEqualTo(0.0);
        assertThat(VectorStore.cosine(new float[] {0, 0}, new float[] {1, 1})).isEqualTo(0.0);
    }

    @Test
    void vectorSearchRanksByMeaning() {
        VectorStore store = new VectorStore(null, json);
        store.add(new DocumentInfo("d", "doc.pdf", 2, 2, 0, true), List.of(
                new Chunk("d", "doc.pdf", 1, "cats", new float[] {1, 0, 0}),
                new Chunk("d", "doc.pdf", 2, "cars", new float[] {0, 1, 0})));
        List<VectorStore.Hit> hits = store.search("anything", new float[] {0.1f, 0.9f, 0}, 1);
        assertThat(hits).singleElement().satisfies(h -> assertThat(h.chunk().page()).isEqualTo(2));
    }

    @Test
    void uploadedPdfIsSearchableOfflineWithPageNumbers(@TempDir Path dir) throws Exception {
        DocumentService docs = new DocumentService(offlineAi, new VectorStore(dir.resolve("index.json"), json));
        DocumentInfo info = docs.addPdf("policy.pdf", pdf(
                "Welcome to the company handbook.",
                "Employees get 24 days of paid leave every year."));
        assertThat(info.pages()).isEqualTo(2);
        assertThat(info.smartSearch()).isFalse();

        ChatService.Reply reply = new ChatService(offlineAi, docs, new com.mitu.ai.ai.WikipediaClient(json)).ask("s1", "How many days of paid leave?");
        assertThat(reply.mode()).isEqualTo("offline");
        assertThat(reply.answer()).contains("24 days");
        assertThat(reply.sources().get(0).page()).isEqualTo(2);
        assertThat(reply.sources().get(0).document()).isEqualTo("policy.pdf");

        // The index survives a restart.
        VectorStore reloaded = new VectorStore(dir.resolve("index.json"), json);
        assertThat(reloaded.documents()).extracting(DocumentInfo::name).containsExactly("policy.pdf");
        // Uploading the same file again replaces it.
        DocumentInfo again = docs.addPdf("policy.pdf", pdf("Welcome.", "Employees get 24 days of paid leave every year."));
        assertThat(docs.list()).extracting(DocumentInfo::id).containsExactly(again.id());
        info = again;
        assertThat(docs.delete(info.id())).isTrue();
        assertThat(docs.list()).isEmpty();
    }

    @Test
    void emptyPdfIsRefused() throws Exception {
        DocumentService docs = new DocumentService(offlineAi, new VectorStore(null, json));
        assertThatThrownBy(() -> docs.addPdf("blank.pdf", pdf("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no readable text");
    }

    @Test
    void offlineChatWithNoDocumentsGreets() {
        DocumentService docs = new DocumentService(offlineAi, new VectorStore(null, json));
        ChatService.Reply reply = new ChatService(offlineAi, docs, new com.mitu.ai.ai.WikipediaClient(json)).ask("s", "hello");
        assertThat(reply.answer()).contains("MiTu");
        assertThat(reply.sources()).isEmpty();
    }

    @Test
    void onlyCitedSourcesAreShown() {
        var a = new ChatService.Source(1, "a.pdf", 2, "x", 0.9);
        var b = new ChatService.Source(2, "a.pdf", 1, "y", 0.5);
        assertThat(ChatService.cited("You get 24 days [1].", List.of(a, b))).containsExactly(a);
        assertThat(ChatService.cited("No citation here.", List.of(a, b))).isEmpty();
        assertThat(ChatService.cited("Both say so [1, 2].", List.of(a, b))).containsExactly(a, b);
        assertThat(ChatService.cited("See [1-2].", List.of(a, b))).containsExactly(a, b);
        assertThat(ChatService.cited("Only this [2][9].", List.of(a, b))).containsExactly(b);
    }

    @Test
    void theAiIsToldTodaysDate() {
        var now = java.time.ZonedDateTime.of(2026, 11, 15, 20, 31, 0, 0, java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(ChatService.systemPrompt(now)).contains("Sunday, 15 November 2026, 8:31 PM");
    }

    /** Builds a small PDF with one page per string. */
    static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text.isEmpty()) continue;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
