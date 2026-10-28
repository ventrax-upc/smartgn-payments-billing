package com.smartgn.billing.adapter;

import com.smartgn.billing.application.*;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Component
public class MqttEvents implements MqttCallbackExtended {
    private final JdbcTemplate db;
    private final IntegrationEvents events;
    private final String url, user, password, clientId;
    private MqttAsyncClient client;
    private final tools.jackson.databind.ObjectMapper json =
            new tools.jackson.databind.ObjectMapper();

    public MqttEvents(
            JdbcTemplate db,
            IntegrationEvents events,
            @Value("${smartgn.mqtt.url:}") String url,
            @Value("${smartgn.mqtt.user:}") String user,
            @Value("${smartgn.mqtt.password:}") String password,
            @Value("${smartgn.mqtt.client-id:smartgn-billing}") String clientId) {
        this.db = db;
        this.events = events;
        this.url = url;
        this.user = user;
        this.password = password;
        this.clientId = clientId;
    }

    @Scheduled(fixedDelay = 3000)
    public synchronized void connect() {
        if (url.isBlank()) return;
        try {
            if (client == null) {
                client = new MqttAsyncClient(url, clientId, new MemoryPersistence());
                client.setCallback(this);
                client.setManualAcks(true);
            }
            if (!client.isConnected()) {
                var opts = new MqttConnectOptions();
                opts.setUserName(user);
                opts.setPassword(password.toCharArray());
                opts.setCleanSession(false);
                opts.setConnectionTimeout(3);
                opts.setKeepAliveInterval(20);
                client.connect(opts).waitForCompletion(5000);
            }
        } catch (Exception unavailable) {
        }
    }

    public void connectComplete(boolean reconnect, String server) {
        try {
            client.subscribe(
                    new String[] {"smartgn/events/installation/#", "smartgn/events/support/#"},
                    new int[] {1, 1});
        } catch (MqttException e) {
            throw new IllegalStateException(e);
        }
    }

    public void connectionLost(Throwable cause) {}

    public void deliveryComplete(IMqttDeliveryToken token) {}

    public void messageArrived(String topic, MqttMessage message) throws Exception {
        Map<String, Object> event = null;
        try {
            if (message.getPayload().length > 65536)
                throw new IllegalArgumentException("Event exceeds limit");
            event = json.readValue(message.getPayload(), Map.class);
            if (event == null) throw new IllegalArgumentException("Event object required");
            String type = String.valueOf(event.get("event_type"));
            if (type.equals("TechnicalVisitCompleted")
                    && !topic.startsWith("smartgn/events/support/"))
                throw new IllegalArgumentException("Event origin mismatch");
            if (type.equals("InstallationCompleted")
                    && !topic.startsWith("smartgn/events/installation/"))
                throw new IllegalArgumentException("Event origin mismatch");
            events.receive(event);
        } catch (tools.jackson.core.JacksonException e) {
            quarantine(event, "INVALID_JSON_EVENT");
        } catch (IllegalArgumentException e) {
            quarantine(event, "INVALID_EVENT");
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() >= 500) throw e;
            quarantine(event, "EVENT_CONFLICT");
        }
        client.messageArrivedComplete(message.getId(), message.getQos());
    }

    private void quarantine(Map<String, Object> event, String reason) {
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(event.get("event_id")));
        } catch (Exception e) {
            id = UUID.randomUUID();
        }
        db.update(
                "INSERT INTO mqtt_inbox_errors(event_id,event_type,reason) VALUES (?,?,?) ON"
                        + " CONFLICT DO NOTHING",
                id,
                event == null ? null : String.valueOf(event.get("event_type")),
                reason);
    }

    @Scheduled(fixedDelayString = "${smartgn.worker-delay:1000}")
    public void publish() {
        if (client == null || !client.isConnected()) return;
        UUID claim = UUID.randomUUID();
        var rows =
                db.queryForList(
                        "UPDATE outbox SET mqtt_lease_id=?,mqtt_lease_until=now()+interval '20"
                            + " seconds' WHERE id=(SELECT id FROM outbox WHERE mqtt_published_at IS"
                            + " NULL AND mqtt_available_at<=now() AND (mqtt_lease_until IS NULL OR"
                            + " mqtt_lease_until<now()) AND (event_type<>'PaymentConfirmed' OR"
                            + " aggregate_id NOT IN (SELECT id FROM payment_orders WHERE"
                            + " reason='INITIAL' AND account_id IS NULL)) ORDER BY created_at LIMIT"
                            + " 1 FOR UPDATE SKIP LOCKED) RETURNING *",
                        claim);
        for (var row : rows)
            try {
                Map payload = json.readValue(row.get("payload").toString(), Map.class);
                if (row.get("event_type").equals("PaymentConfirmed")) {
                    var order =
                            db.queryForMap(
                                    "SELECT account_id FROM payment_orders WHERE id=?",
                                    row.get("aggregate_id"));
                    payload.put("accountId", order.get("account_id"));
                }
                var envelope = new LinkedHashMap<String, Object>();
                envelope.put("event_id", row.get("id"));
                envelope.put("event_type", row.get("event_type"));
                envelope.put("aggregate_id", row.get("aggregate_id"));
                envelope.put("correlation_id", row.get("correlation_id"));
                envelope.put("payload", payload);
                var m = new MqttMessage(json.writeValueAsBytes(envelope));
                m.setQos(1);
                client.publish("smartgn/events/payments/" + row.get("event_type"), m)
                        .waitForCompletion(5000);
                db.update(
                        "UPDATE outbox SET mqtt_published_at=now(),mqtt_lease_until=NULL WHERE id=?"
                                + " AND mqtt_lease_id=?",
                        row.get("id"),
                        claim);
            } catch (Exception e) {
                db.update(
                        "UPDATE outbox SET mqtt_available_at=now()+interval '15"
                                + " seconds',mqtt_lease_until=NULL WHERE id=? AND mqtt_lease_id=?",
                        row.get("id"),
                        claim);
            }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        try {
            if (client != null) {
                if (client.isConnected()) client.disconnect().waitForCompletion(3000);
                client.close();
            }
        } catch (Exception e) {
        }
    }
}
