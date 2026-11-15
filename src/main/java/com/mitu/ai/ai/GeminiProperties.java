package com.mitu.ai.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings from application.properties (mitu.gemini.*). */
@ConfigurationProperties(prefix = "mitu.gemini")
public record GeminiProperties(String apiKey, String chatModel, String embedModel, String imageModel) {

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
