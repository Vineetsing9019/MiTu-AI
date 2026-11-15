package com.mitu.ai.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a page's text into overlapping pieces of about {@code size} characters,
 * cutting at a sentence or word end where possible, so each piece is small
 * enough to search and to quote back to the AI.
 */
public final class Chunker {

    private Chunker() {}

    public static List<String> split(String text, int size, int overlap) {
        String clean = text == null ? "" : text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        List<String> out = new ArrayList<>();
        if (clean.isEmpty()) return out;
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + size);
            if (end < clean.length()) {
                int cut = Math.max(clean.lastIndexOf(". ", end), clean.lastIndexOf('\n', end));
                if (cut > start + size / 2) {
                    end = cut + 1;
                } else {
                    int space = clean.lastIndexOf(' ', end);
                    if (space > start + size / 2) end = space;
                }
            }
            String piece = clean.substring(start, end).trim();
            if (!piece.isEmpty()) out.add(piece);
            if (end >= clean.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return out;
    }
}
