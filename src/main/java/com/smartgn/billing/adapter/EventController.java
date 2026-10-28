package com.smartgn.billing.adapter;

import com.smartgn.billing.application.BillingService;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
public class EventController {
    private final BillingService service;
    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();

    public EventController(BillingService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/events")
    public void receive(@RequestBody Map<String, Object> event) {
        if (!"InstallationCompleted".equals(event.get("event_type"))) return;
        Map payload =
                event.get("payload") instanceof String s
                        ? json.readValue(s, Map.class)
                        : (Map) event.get("payload");
        service.installed(
                UUID.fromString(event.get("event_id").toString()),
                UUID.fromString(payload.get("accountId").toString()),
                Instant.parse(payload.get("completedAt").toString()),
                BillingService.hash(
                        event.get("event_type")
                                + "|"
                                + event.get("aggregate_id")
                                + "|"
                                + event.get("payload")));
    }
}
