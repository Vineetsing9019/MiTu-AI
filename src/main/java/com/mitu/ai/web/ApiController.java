package com.mitu.ai.web;

import com.mitu.ai.ai.GeminiClient;
import com.mitu.ai.ai.GeminiProperties;
import com.mitu.ai.art.ArtService;
import com.mitu.ai.chat.ChatService;
import com.mitu.ai.rag.DocumentInfo;
import com.mitu.ai.rag.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MiTu's REST API, used by the web page in src/main/resources/static. */
@RestController
@RequestMapping("/api")
public class ApiController {

    public record ChatRequest(@NotBlank @Size(max = 2000) String question, String sessionId) {}

    private final GeminiClient ai;
    private final GeminiProperties props;
    private final DocumentService documents;
    private final ChatService chat;
    private final ArtService art;

    public ApiController(GeminiClient ai, GeminiProperties props, DocumentService documents, ChatService chat, ArtService art) {
        this.ai = ai;
        this.props = props;
        this.documents = documents;
        this.chat = chat;
        this.art = art;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "name", "MiTu AI Assistant",
                "aiOn", ai.isConfigured(),
                "chatModel", GeminiClient.primary(props.chatModel()),
                "imageModel", GeminiClient.primary(props.imageModel()),
                "documents", documents.list().size(),
                "styles", art.styles());
    }

    @PostMapping("/chat")
    public ChatService.Reply chat(@Valid @RequestBody ChatRequest req) {
        return chat.ask(req.sessionId(), req.question());
    }

    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Void> resetChat(@PathVariable String sessionId) {
        chat.reset(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents")
    public List<DocumentInfo> documents() {
        return documents.list();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentInfo upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename();
        if (file.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Please upload a PDF file.");
        }
        return documents.addPdf(name, file.getBytes());
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return documents.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping(value = "/art", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> art(@RequestParam("photo") MultipartFile photo,
                                      @RequestParam(value = "style", required = false) String style) throws IOException {
        ArtService.Result r = art.create(photo.getBytes(), photo.getContentType(), ArtService.Style.from(style));
        return ResponseEntity.ok()
                .header("X-Mitu-Engine", r.engine()) // "gemini" (edits the photo) or "free" (describe + paint)
                .header("Access-Control-Expose-Headers", "X-Mitu-Engine")
                .contentType(MediaType.parseMediaType(r.image().mimeType()))
                .body(r.image().bytes());
    }
}
