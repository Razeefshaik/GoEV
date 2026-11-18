package com.goev.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** STOMP over WebSocket for pushing live EV/EVCS/assignment state to the dashboard (architecture doc section 2). */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] allowedOrigins;

    public WebSocketConfig(@Value("${goev.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = com.goev.web.CorsConfig.parseOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var endpoint = registry.addEndpoint("/ws");
        if (allowedOrigins.length > 0) {
            // Lets a separately hosted frontend (Netlify) open the SockJS connection.
            endpoint.setAllowedOriginPatterns(allowedOrigins);
        }
        endpoint.withSockJS();
    }
}
