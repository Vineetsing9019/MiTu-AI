package com.mitu.ai.art;

import com.mitu.ai.ai.AiException;
import com.mitu.ai.ai.FreeImageClient;
import com.mitu.ai.ai.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a photo into art, trying three ways in order:
 * 1. Gemini's image model edits the photo itself (best likeness, needs a paid Gemini plan);
 * 2. free: Gemini describes the photo, then Pollinations.ai paints that scene in the style;
 * 3. without any AI key the web page uses its own offline cartoon filter.
 */
@Service
public class ArtService {

    private static final Logger log = LoggerFactory.getLogger(ArtService.class);
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    /** After the paid image model refuses, go straight to the free way for this long. */
    private static final long PAID_RETRY_MS = 30 * 60 * 1000;

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

    /** The picture and which way made it ("gemini" or "free"). */
    public record Result(GeminiClient.Image image, String engine) {}

    private final GeminiClient ai;
    private final FreeImageClient freeImages;
    private volatile long paidOffUntil = 0;

    public ArtService(GeminiClient ai, FreeImageClient freeImages) {
        this.ai = ai;
        this.freeImages = freeImages;
    }

    public List<StyleInfo> styles() {
        return Arrays.stream(Style.values()).map(s -> new StyleInfo(s.name(), s.label)).toList();
    }

    public Result create(byte[] photo, String mimeType, Style style) {
        if (photo == null || photo.length == 0) throw new IllegalArgumentException("Please choose a photo.");
        if (photo.length > MAX_BYTES) throw new IllegalArgumentException("The photo is larger than 10 MB.");
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(mime)) throw new IllegalArgumentException("Use a JPG, PNG or WebP photo.");
        if (!ai.isConfigured()) throw new AiException("offline");

        if (System.currentTimeMillis() >= paidOffUntil) {
            try {
                return new Result(ai.editImage(photo, mime, instruction(style)), "gemini");
            } catch (AiException e) {
                if (e.status != 403 && e.status != 404 && e.status != 429) throw e;
                log.info("Gemini image editing unavailable ({}); using the free way", e.status);
                paidOffUntil = System.currentTimeMillis() + PAID_RETRY_MS;
            }
        }
        String scene = ai.describeImage(photo, mime, DESCRIBE);
        return new Result(freeImages.generate(freePrompt(style, scene), 768, 768), "free");
    }

    static final String DESCRIBE = """
            Describe this photo for a portrait artist who must repaint it so the person is clearly
            recognisable, in one paragraph of at most 120 words. For each person: approximate age,
            gender, skin tone, face shape, eye shape, eyebrows, nose, hairstyle and hair colour, facial
            hair, glasses or jewellery, expression, head angle and pose, and exact clothing with colours.
            Then the camera framing (close-up, half body...), the background and the lighting.
            Plain visual description only: no names, no opinions.""";

    static String freePrompt(Style style, String scene) {
        return style.look + ". Keep the exact same framing and pose. " + scene + " No text, no watermark.";
    }

    static String instruction(Style style) {
        return "Redraw this photo as " + style.look + ". Keep the same people, faces, pose, clothing, "
                + "composition and background so it is clearly the same scene. Do not add any text, "
                + "logos, signatures or watermarks.";
    }
}
