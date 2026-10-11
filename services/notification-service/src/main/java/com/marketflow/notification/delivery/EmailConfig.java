package com.marketflow.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    /**
     * Development fault injection (applies to every transport, off in production via
     * {@code marketflow.notification.fault-injection=false}):
     * recipients containing "+bounce" fail permanently; "+flaky" fail twice, then succeed.
     */
    static EmailSender withFaultInjection(EmailSender delegate, boolean enabled) {
        if (!enabled) {
            return delegate;
        }
        Map<String, AtomicInteger> flaky = new ConcurrentHashMap<>();
        return (to, subject, body) -> {
            if (to.contains("+bounce")) {
                throw new EmailSender.PermanentDeliveryException("mailbox does not exist");
            }
            if (to.contains("+flaky") && flaky.computeIfAbsent(to + subject, k -> new AtomicInteger()).incrementAndGet() <= 2) {
                throw new EmailSender.TransientDeliveryException("simulated SMTP timeout", null);
            }
            delegate.send(to, subject, body);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "marketflow.mail.mode", havingValue = "smtp")
    EmailSender smtpEmailSender(JavaMailSender mail, @Value("${marketflow.mail.from}") String from,
                                @Value("${marketflow.notification.fault-injection:true}") boolean faults) {
        EmailSender smtp = (to, subject, body) -> {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            try {
                mail.send(m);
            } catch (MailSendException e) {
                throw new EmailSender.TransientDeliveryException("SMTP send failed", e);
            } catch (MailException e) {
                throw new EmailSender.TransientDeliveryException("mail transport error", e);
            }
        };
        return withFaultInjection(smtp, faults);
    }

    @Bean
    @ConditionalOnProperty(name = "marketflow.mail.mode", havingValue = "log", matchIfMissing = true)
    EmailSender loggingEmailSender(@Value("${marketflow.notification.fault-injection:true}") boolean faults) {
        // logs only the recipient domain and subject - never the body or the full address
        return withFaultInjection((to, subject, body) -> log.info("E-mail (log mode) toDomain={} subject='{}'",
                to.substring(to.indexOf('@') + 1), subject), faults);
    }
}
