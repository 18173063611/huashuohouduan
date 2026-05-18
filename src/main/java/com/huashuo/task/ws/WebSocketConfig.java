package com.huashuo.task.ws;

import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.user.service.UserAuthService;
import java.security.Principal;
import java.util.Arrays;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final UserAuthService userAuthService;
    private final TaskMapper taskMapper;
    private final String allowedOrigins;

    public WebSocketConfig(
            UserAuthService userAuthService,
            TaskMapper taskMapper,
            @Value("${huashuo.websocket.allowed-origins:${huashuo.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}}")
            String allowedOrigins
    ) {
        this.userAuthService = userAuthService;
        this.taskMapper = taskMapper;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(parseAllowedOrigins());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticate(accessor);
                    return message;
                }
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    authorizeSubscription(accessor);
                }
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        String xAuthToken = accessor.getFirstNativeHeader("X-Auth-Token");
        OptionalLong userId = userAuthService.resolveUserIdOptional(authorization, xAuthToken);
        if (userId.isEmpty()) {
            throw new MessagingException("Unauthorized WebSocket connection");
        }
        accessor.setUser(new WebSocketUserPrincipal(userId.getAsLong()));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        long userId = currentUserId(accessor);
        String destination = accessor.getDestination();
        if (!StringUtils.hasText(destination)) {
            throw new MessagingException("Missing WebSocket destination");
        }
        if (destination.startsWith("/topic/tasks/")) {
            long taskId = parseTrailingId(destination, "/topic/tasks/");
            authorizeTaskSubscription(taskId, userId);
            return;
        }
        if (destination.startsWith("/topic/users/") && destination.endsWith("/tasks")) {
            String rawUserId = destination.substring("/topic/users/".length(), destination.length() - "/tasks".length());
            long destinationUserId = parseLong(rawUserId);
            if (destinationUserId == userId) {
                return;
            }
        }
        throw new MessagingException("Forbidden WebSocket subscription");
    }

    private void authorizeTaskSubscription(long taskId, long userId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new MessagingException("Task subscription target not found");
        }
        Long ownerUserId = task.getOwnerUserId();
        if (ownerUserId == null || ownerUserId == userId || ownerUserId.equals(userId)) {
            return;
        }
        throw new MessagingException("Forbidden WebSocket task subscription");
    }

    private long currentUserId(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal instanceof WebSocketUserPrincipal user) {
            return user.userId();
        }
        throw new MessagingException("Unauthorized WebSocket subscription");
    }

    private long parseTrailingId(String destination, String prefix) {
        return parseLong(destination.substring(prefix.length()));
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new MessagingException("Invalid WebSocket destination");
        }
    }

    private String[] parseAllowedOrigins() {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        return origins.length == 0 ? new String[]{"http://localhost:5173", "http://127.0.0.1:5173"} : origins;
    }

    private record WebSocketUserPrincipal(long userId) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(userId);
        }
    }
}
