package io.github.sever0x.holypunch.server.config;

import io.github.sever0x.holypunch.server.signaling.SignalingWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

@Configuration
public class SignalingWebSocketConfig {

    // CHUNK_SIZE (4 MB) + encryption overhead (IV + type + GCM tag) = ~4 MB per frame.
    // Netty defaults to 64 KB; raise to 5 MB so relay frames are accepted.
    private static final int MAX_FRAME_BYTES = 5 * 1024 * 1024;

    @Bean
    public HandlerMapping webSocketHandlerMapping(SignalingWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/signal", handler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        var strategy = new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(MAX_FRAME_BYTES));
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(strategy));
    }
}
