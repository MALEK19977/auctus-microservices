package com.auctus.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Server-sent push for the chat.
 *
 * <p>Each signed-in user holds one open stream; the server writes to it the moment
 * something happens. This is genuine push rather than the client asking every few
 * seconds, and unlike a WebSocket it needs no extra dependency on either side -
 * the browser's own EventSource speaks it.
 *
 * <p>A user may be signed in from several tabs, so emitters are kept as a set.
 */
@Service
@Slf4j
public class LiveHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Set<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        byUser.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));

        try {
            // An immediate event settles the connection so the browser does not
            // sit waiting on an empty stream.
            emitter.send(SseEmitter.event().name("connected").data(Map.of("userId", userId)));
        } catch (IOException e) {
            remove(userId, emitter);
        }

        log.info("User {} subscribed ({} stream(s) open)", userId, byUser.get(userId).size());
        return emitter;
    }

    /** Pushes an event to every listed recipient that currently has a stream open. */
    public void publish(List<String> userIds, String event, Object payload) {
        for (String userId : userIds) {
            Set<SseEmitter> emitters = byUser.get(userId);
            if (emitters == null) {
                continue;
            }
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(payload));
                } catch (Exception e) {
                    // A dead stream is normal: the tab was closed or the network moved.
                    remove(userId, emitter);
                }
            }
        }
    }

    public int openStreams() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    private void remove(String userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = byUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                byUser.remove(userId);
            }
        }
    }
}
