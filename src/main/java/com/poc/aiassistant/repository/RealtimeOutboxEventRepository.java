package com.poc.aiassistant.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.poc.aiassistant.realtime.RealtimeOutboxEvent;

public interface RealtimeOutboxEventRepository
        extends JpaRepository<RealtimeOutboxEvent, UUID> {
}
