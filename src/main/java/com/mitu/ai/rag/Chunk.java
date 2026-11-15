package com.mitu.ai.rag;

/** One small piece of a document, with the page it came from and (when the AI is on) its embedding. */
public record Chunk(String docId, String docName, int page, String text, float[] vector) {}
