package com.ventrax.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ventrax.payments.adapter.MqttEvents;
import com.ventrax.payments.application.IntegrationEvents;

import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class MqttEventsTest {
    @Test
    void malformedJsonIsQuarantinedAndAcknowledged() throws Exception {
        var db = mock(JdbcTemplate.class);
        var events = mock(IntegrationEvents.class);
        var client = mock(MqttAsyncClient.class);
        var adapter = new MqttEvents(db, events, "", "", "", "test");
        ReflectionTestUtils.setField(adapter, "client", client);
        var message = new MqttMessage("{invalid json".getBytes());
        message.setQos(1);
        adapter.messageArrived("smartgn/events/support/TechnicalVisitCompleted", message);
        verify(client).messageArrivedComplete(0, 1);
        verifyNoInteractions(events);
        verify(db).update(anyString(), any(), isNull(), eq("INVALID_JSON_EVENT"));
    }

    @Test
    void transientDependencyFailureDoesNotAcknowledge() throws Exception {
        var db = mock(JdbcTemplate.class);
        var events = mock(IntegrationEvents.class);
        var client = mock(MqttAsyncClient.class);
        var adapter = new MqttEvents(db, events, "", "", "", "test");
        ReflectionTestUtils.setField(adapter, "client", client);
        doThrow(
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(events)
                .receive(anyMap());
        var message = new MqttMessage("{\"event_type\":\"TechnicalVisitCompleted\"}".getBytes());
        message.setQos(1);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        adapter.messageArrived(
                                "smartgn/events/support/TechnicalVisitCompleted", message));
        verify(client, never()).messageArrivedComplete(anyInt(), anyInt());
    }

    @Test
    void wrongContextCannotClaimSupportEvent() throws Exception {
        var db = mock(JdbcTemplate.class);
        var events = mock(IntegrationEvents.class);
        var client = mock(MqttAsyncClient.class);
        var adapter = new MqttEvents(db, events, "", "", "", "test");
        ReflectionTestUtils.setField(adapter, "client", client);
        var message = new MqttMessage("{\"event_type\":\"TechnicalVisitCompleted\"}".getBytes());
        message.setQos(1);
        adapter.messageArrived("smartgn/events/installation/TechnicalVisitCompleted", message);
        verifyNoInteractions(events);
        verify(client).messageArrivedComplete(0, 1);
    }

    @Test
    void nullEventIsAlsoQuarantinedAndAcknowledged() throws Exception {
        var db = mock(JdbcTemplate.class);
        var events = mock(IntegrationEvents.class);
        var client = mock(MqttAsyncClient.class);
        var adapter = new MqttEvents(db, events, "", "", "", "test");
        ReflectionTestUtils.setField(adapter, "client", client);
        var message = new MqttMessage("null".getBytes());
        message.setQos(1);
        adapter.messageArrived("smartgn/events/support/TechnicalVisitCompleted", message);
        verifyNoInteractions(events);
        verify(client).messageArrivedComplete(0, 1);
    }

    @Test
    void informationalSupportEventsAreAcknowledgedWithoutFinancialProcessing() throws Exception {
        var db = mock(JdbcTemplate.class);
        var events = mock(IntegrationEvents.class);
        var client = mock(MqttAsyncClient.class);
        var adapter = new MqttEvents(db, events, "", "", "", "test");
        ReflectionTestUtils.setField(adapter, "client", client);
        var message = new MqttMessage("{\"event_type\":\"TicketCreated\"}".getBytes());
        message.setQos(1);
        adapter.messageArrived("smartgn/events/support/TicketCreated", message);
        verifyNoInteractions(events);
        verifyNoInteractions(db);
        verify(client).messageArrivedComplete(0, 1);
    }
}
