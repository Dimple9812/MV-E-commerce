package com.marketflow.chat.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Pushes a delivery to the recipients that have a session on this instance; everyone else is someone else's. */
@Component
public class LocalDelivery {

    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;
    private final Counter delivered;

    public LocalDelivery(SimpMessagingTemplate messaging, SimpUserRegistry users, MeterRegistry meters) {
        this.messaging = messaging;
        this.users = users;
        this.delivered = meters.counter("chat.deliveries.local");
    }

    public void deliver(ChatFanout.Delivery d) {
        for (UUID recipient : d.recipients()) {
            String name = recipient.toString();
            if (users.getUser(name) != null) {
                messaging.convertAndSendToUser(name, d.destination(), d.payload());
                delivered.increment();
            }
        }
    }

    public boolean isLocal(UUID userId) {
        return users.getUser(userId.toString()) != null;
    }

    public int connectedUsers() {
        return users.getUserCount();
    }
}
