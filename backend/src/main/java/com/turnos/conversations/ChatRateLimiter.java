package com.turnos.conversations;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// Sliding one-minute window per key (an IP, a chat session). Every message
// can cost an LLM call, so the public chat needs this before anything else.
// ponytail: in memory and per instance; move to Redis or the DB if the
// backend ever runs more than one instance.
@Component
public class ChatRateLimiter {

    private final int perMinute;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public ChatRateLimiter(@Value("${app.chat.rate-limit-per-minute:20}") int perMinute) {
        this.perMinute = perMinute;
    }

    public void check(String... keys) {
        long now = System.currentTimeMillis();
        for (String key : keys) {
            Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                    window.pollFirst();
                }
                if (window.size() >= perMinute) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages, wait a minute");
                }
                window.addLast(now);
            }
        }
        if (hits.size() > 50_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > 60_000);
        }
    }
}
