package com.mitu.ai;

import com.mitu.ai.web.RateLimitFilterAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mitu.gemini.api-key=", "mitu.data-dir=target/test-data", "mitu.rate-limit.per-minute=1000"})
@AutoConfigureMockMvc
class ApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void statusShowsOfflineModeAndStyles() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MiTu AI Assistant"))
                .andExpect(jsonPath("$.aiOn").value(false))
                .andExpect(jsonPath("$.styles[0].id").value("ANIME"));
    }

    @Test
    void emptyQuestionIsRefused() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonPdfUploadIsRefused() throws Exception {
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please upload a PDF file."));
    }

    @Test
    void artWithoutKeyTellsThePageToUseTheOfflineFilter() throws Exception {
        mvc.perform(multipart("/api/art").file(new MockMultipartFile("photo", "me.png", "image/png", new byte[] {1, 2, 3})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.offline").value(true));
    }

    @Test
    void rateLimiterRefillsOverTime() {
        RateLimitFilterAccess limiter = new RateLimitFilterAccess(2);
        assertThat(limiter.take("ip", 0)).isTrue();
        assertThat(limiter.take("ip", 0)).isTrue();
        assertThat(limiter.take("ip", 0)).isFalse();
        assertThat(limiter.take("ip", 31_000_000_000L)).isTrue(); // 31 s later: one token back
    }
}
