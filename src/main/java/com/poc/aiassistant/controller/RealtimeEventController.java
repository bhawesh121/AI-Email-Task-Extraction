package com.poc.aiassistant.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.poc.aiassistant.realtime.RealtimeSseBroadcaster;

@RestController
@RequestMapping("/api/realtime")
public class RealtimeEventController {

    private final RealtimeSseBroadcaster broadcaster;

    public RealtimeEventController(RealtimeSseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream() {
        return broadcaster.connect();
    }
}
