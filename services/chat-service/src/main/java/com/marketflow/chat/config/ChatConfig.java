package com.marketflow.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.application.ChatFanout;
import com.marketflow.chat.application.LocalDelivery;
import com.marketflow.chat.application.RateLimiter;
import com.marketflow.chat.application.RedisWindowCounter;
import com.marketflow.chat.domain.ContentPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ContentPolicy contentPolicy(ChatProperties props) {
        return new ContentPolicy(props.maxMessageLength(), props.blockedLinkHosts());
    }

    @Bean
    RateLimiter rateLimiter(StringRedisTemplate redis, Clock clock, ChatProperties props) {
        ChatProperties.RateLimit rl = props.rateLimit();
        return new RateLimiter(new RedisWindowCounter(redis), clock, rl.messages(), rl.window(), rl.typingInterval());
    }

    /**
     * Subscribes this instance to the fan-out channel. Each delivery is pushed to recipients connected here; the
     * container reconnects on its own after a Redis outage.
     */
    @Bean
    RedisMessageListenerContainer chatFanoutListener(RedisConnectionFactory connections, ObjectMapper json,
                                                     LocalDelivery local, ChatProperties props) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener((message, pattern) -> {
            try {
                ChatFanout.Delivery d = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                        ChatFanout.Delivery.class);
                local.deliver(d);
            } catch (Exception e) {
                log.warn("Dropped malformed fan-out message: {}", e.getClass().getSimpleName());
            }
        }, new ChannelTopic(props.fanoutChannel()));
        return container;
    }
}
