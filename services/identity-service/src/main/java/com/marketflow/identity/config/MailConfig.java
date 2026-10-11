package com.marketflow.identity.config;

import com.marketflow.identity.application.MailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    @ConditionalOnProperty(name = "marketflow.mail.mode", havingValue = "smtp")
    MailGateway smtpMailGateway(JavaMailSender sender, @Value("${marketflow.mail.from}") String from) {
        return (to, subject, body) -> {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "marketflow.mail.mode", havingValue = "log", matchIfMissing = true)
    MailGateway loggingMailGateway() {
        return (to, subject, body) -> log.info("Mail (log mode, body suppressed) toDomain={} subject='{}'",
                to.substring(to.indexOf('@') + 1), subject);
    }
}
