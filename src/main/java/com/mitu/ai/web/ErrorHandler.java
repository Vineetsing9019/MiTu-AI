package com.mitu.ai.web;

import com.mitu.ai.ai.AiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;

/** Turns errors into short JSON messages the web page can show. */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(AiException.class)
    public ResponseEntity<Map<String, Object>> ai(AiException e) {
        boolean offline = "offline".equals(e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "message", offline ? "AI image model is off (no API key). Using MiTu's offline filter." : e.getMessage(),
                "offline", offline));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Please type a question (up to 2000 characters)."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "That file is too big (20 MB max)."));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> io(IOException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Could not read that file. Is it a valid PDF?"));
    }
}
