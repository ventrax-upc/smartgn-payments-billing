package com.ventrax.payments.adapter;

import com.ventrax.payments.application.IntegrationEvents;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class EventController {
    private final IntegrationEvents events;

    public EventController(IntegrationEvents events) {
        this.events = events;
    }

    @PostMapping("/internal/v1/events")
    public void receive(@RequestBody Map<String, Object> event) {
        events.receive(event);
    }
}
