package com.mitu.ai.ai;

/** A problem talking to the AI service, with a message safe to show the user. */
public class AiException extends RuntimeException {
    public AiException(String message) {
        super(message);
    }
}
