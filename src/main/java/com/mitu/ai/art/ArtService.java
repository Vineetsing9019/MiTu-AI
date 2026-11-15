package com.mitu.ai.art;

import com.mitu.ai.ai.AiException;
import com.mitu.ai.ai.GeminiClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a photo into art with Gemini's image model. When no AI key is set the
 * web page uses its own offline cartoon filter instead, so the feature always works.
 */
@Service
public class ArtService {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** The art styles MiTu offers. */
    public enum Style {
        ANIME("Anime art", "a soft hand-painted anime film still: watercolor backgrounds, gentle pastel sunlight, "
                + "clean thin line art, big expressive eyes, warm and nostalgic mood"),
        WATERCOLOR("Watercolor", "a delicate watercolor painting with soft bleeding colours and visible paper texture"),
        SKETCH("Pencil sketch", "a detailed graphite pencil sketch with cross-hatching on white paper"),
        COMIC("Comic book", "a bold comic book panel with thick ink outlines, halftone dots and vivid flat colours"),
        PIXEL("Pixel art", "16-bit retro video game pixel art with a limited colour palette");

        public final String label;
        final String look;

        Style(String label, String look) {
            this.label = label;
            this.look = look;
        }

        public static Style from(String s) {
            if (s == null) return ANIME;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ANIME;
            }
        }
    }

    public record StyleInfo(String id, String label) {}

    private final GeminiClient ai;

    public ArtService(GeminiClient ai) {
        this.ai = ai;
    }

    public List<StyleInfo> styles() {
        return Arrays.stream(Style.values()).map(s -> new StyleInfo(s.name(), s.label)).toList();
    }

    public GeminiClient.Image create(byte[] photo, String mimeType, Style style) {
        if (photo == null || photo.length == 0) throw new IllegalArgumentException("Please choose a photo.");
        if (photo.length > MAX_BYTES) throw new IllegalArgumentException("The photo is larger than 10 MB.");
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(mime)) throw new IllegalArgumentException("Use a JPG, PNG or WebP photo.");
        if (!ai.isConfigured()) throw new AiException("offline");
        return ai.editImage(photo, mime, instruction(style));
    }

    static String instruction(Style style) {
        return "Redraw this photo as " + style.look + ". Keep the same people, faces, pose, clothing, "
                + "composition and background so it is clearly the same scene. Do not add any text, "
                + "logos, signatures or watermarks.";
    }
}
