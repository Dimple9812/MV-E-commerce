package com.marketflow.chat.ws;

import com.marketflow.chat.config.ChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over plain WebSocket at {@code /ws/chat} (no SockJS: its HTTP fallbacks need sticky sessions across
 * instances, and every supported browser has WebSocket).
 *
 * <p>Each instance runs Spring's in-memory broker for its own sessions only; deliveries between instances go
 * through Redis ({@link com.marketflow.chat.application.ChatFanout}). Client frames from one session are handled
 * in order, so a quick "send, send, read" sequence is applied as sent.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatProperties props;
    private final JwtChannelInterceptor jwt;
    private TaskScheduler heartbeatScheduler;

    public WebSocketConfig(ChatProperties props, JwtChannelInterceptor jwt) {
        this.props = props;
        this.jwt = jwt;
    }

    @Autowired
    void setHeartbeatScheduler(@Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.heartbeatScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws/chat");
        if (!props.allowedOrigins().isEmpty()) {
            endpoint.setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
        }
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long hb = props.heartbeat().toMillis();
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {hb, hb})
                .setTaskScheduler(heartbeatScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwt);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(15_000)
                .setTimeToFirstMessage(30_000);
    }
}
