package com.mitu.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** MiTu AI Assistant: chat with your PDFs and turn photos into anime-style art. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MituApplication {
    public static void main(String[] args) {
        SpringApplication.run(MituApplication.class, args);
    }
}
