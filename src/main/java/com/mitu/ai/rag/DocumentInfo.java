package com.mitu.ai.rag;

/** A document the user uploaded. smartSearch is true when its chunks have AI embeddings. */
public record DocumentInfo(String id, String name, int pages, int chunks, long uploadedAt, boolean smartSearch) {}
