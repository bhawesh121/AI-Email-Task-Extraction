package com.poc.aiassistant.realtime;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class RealtimeSseBroadcaster {

    private static final Logger log =
            LoggerFactory.getLogger(RealtimeSseBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final Map<UUID, SseEmitter> emitters =
            new ConcurrentHashMap<>();

    public RealtimeSseBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        UUID connectionId = UUID.randomUUID();
        emitters.put(connectionId, emitter);

        Runnable cleanup = () -> emitters.remove(connectionId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(
                    SseEmitter.event()
                            .data(Map.of(
                                    "type", "CONNECTED",
                                    "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
                            ))
            );
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }

        return emitter;
    }

    public void heartbeat() {
        emitters.forEach((connectionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException exception) {
                log.debug("Removing inactive realtime connection {}", connectionId, exception);
                emitters.remove(connectionId);
                emitter.completeWithError(exception);
            }
        });
    }

    public void broadcast(RealtimeOutboxEvent event) throws IOException {
        Map<String, Object> payload =
                objectMapper.readValue(
                        event.getPayload(),
                        new TypeReference<>() {
                        }
                );

        Map<String, Object> clientEvent = Map.of(
                "id", event.getId().toString(),
                "type", event.getEventType().name(),
                "entityType", event.getEntityType(),
                "entityId", event.getEntityId(),
                "payload", payload,
                "createdAt", event.getCreatedAt().toString()
        );

        emitters.forEach((connectionId, emitter) -> {
            try {
                emitter.send(
                        SseEmitter.event()
                                .id(event.getId().toString())
                                .data(clientEvent)
                );
            } catch (IOException exception) {
                log.debug("Removing broken realtime connection {}", connectionId, exception);
                emitters.remove(connectionId);
                emitter.completeWithError(exception);
            }
        });
    }
}
