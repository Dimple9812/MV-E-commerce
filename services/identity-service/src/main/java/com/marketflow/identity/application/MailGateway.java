package com.marketflow.identity.application;

/**
 * Outgoing e-mail boundary. Implementations: SMTP (Mailpit in local Docker, a real relay in production) or a
 * log-only sender that records recipient and subject but never the body (which may contain reset links).
 */
public interface MailGateway {
    void send(String to, String subject, String body);
}
