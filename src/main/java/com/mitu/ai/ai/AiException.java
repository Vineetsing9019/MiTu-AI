package com.mitu.ai.ai;

/** A problem talking to the AI service, with a message safe to show the user. */
public class AiException extends RuntimeException {
    /** HTTP status from the AI service, or 0 when there was none. */
    public final int status;

    public AiException(String message) {
        this(message, 0);
    }

    public AiException(String message, int status) {
        super(message);
        this.status = status;
    }
}
