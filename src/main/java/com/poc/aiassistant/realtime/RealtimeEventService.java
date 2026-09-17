package com.poc.aiassistant.realtime;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.aiassistant.repository.RealtimeOutboxEventRepository;

@Service
public class RealtimeEventService {

    private static final Logger log =
            LoggerFactory.getLogger(RealtimeEventService.class);

    private final RealtimeOutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public RealtimeEventService(
            RealtimeOutboxEventRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(
            RealtimeEventType eventType,
            String entityType,
            String entityId,
            Map<String, Object> payload
    ) {
        try {
            String json = objectMapper.writeValueAsString(
                    payload == null ? Map.of() : payload
            );

            repository.save(
                    new RealtimeOutboxEvent(
                            eventType,
                            entityType,
                            entityId,
                            json
                    )
            );
        } catch (JsonProcessingException exception) {
            // Event serialization failure should never silently turn a
            // successful business update into an invisible failure.
            log.error(
                    "Unable to serialize realtime event: eventType={}, entityType={}, entityId={}",
                    eventType,
                    entityType,
                    entityId,
                    exception
            );
            throw new IllegalStateException(
                    "Unable to serialize realtime event",
                    exception
            );
        }
    }
}
