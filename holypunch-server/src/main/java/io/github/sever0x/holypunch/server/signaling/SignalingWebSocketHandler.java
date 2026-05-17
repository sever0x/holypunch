package io.github.sever0x.holypunch.server.signaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Component
public class SignalingWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);

    private final SessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public SignalingWebSocketHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Void> handle(WebSocketSession ws) {
        ClientSession client = new ClientSession(ws);
        String remoteIp = remoteIp(ws);

        Mono<Void> receive = ws.receive()
                .doOnNext(msg -> {
                    try {
                        processMessage(client, msg, remoteIp);
                    } catch (Exception e) {
                        log.error("Error processing message from {}", ws.getId(), e);
                    }
                })
                .doOnComplete(client::complete)
                .doOnError(e -> client.complete())
                .then();

        Mono<Void> send = ws.send(client.outgoing.asFlux());

        return Mono.zip(receive, send)
                .doFinally(signal -> onDisconnect(client))
                .then();
    }

    private void processMessage(ClientSession client, WebSocketMessage msg, String remoteIp) throws Exception {
        // Once relay is active forward everything (text and binary) without parsing
        if (client.pair != null && client.pair.relayMode.get()) {
            if (msg.getType() == WebSocketMessage.Type.BINARY) {
                handleBinaryRelay(client, msg);
            } else {
                handleForwardTopeer(client, msg.getPayloadAsText());
            }
            return;
        }

        if (msg.getType() == WebSocketMessage.Type.BINARY) {
            return; // binary before relay mode — ignore
        }

        JsonNode root = mapper.readTree(msg.getPayloadAsText());
        String type = root.path("type").asText();

        switch (type) {
            case "JOIN_SENDER"    -> handleJoinSender(client);
            case "JOIN_RECEIVER"  -> handleJoinReceiver(client, root, remoteIp);
            case "ICE_CANDIDATES" -> handleForwardTopeer(client, msg.getPayloadAsText());
            case "RELAY_REQUEST"  -> handleRelayRequest(client);
            default -> log.warn("Unknown signaling type '{}' from {}", type, client.ws.getId());
        }
    }

    private void handleJoinSender(ClientSession client) throws Exception {
        SessionPair pair = registry.createSession(client);
        ObjectNode resp = mapper.createObjectNode();
        resp.put("type", "SESSION_CREATED");
        resp.put("code", pair.code);
        client.sendText(mapper.writeValueAsString(resp));
        log.info("[{}] Sender joined, code={}", client.ws.getId(), pair.code);
    }

    private void handleJoinReceiver(ClientSession client, JsonNode root, String remoteIp) throws Exception {
        String code = root.path("code").asText();
        SessionPair pair = registry.joinReceiver(code, client, remoteIp);

        if (pair == null) {
            client.sendText("{\"type\":\"ERROR\",\"message\":\"Code not found or expired\"}");
            return;
        }

        // Notify receiver: pairing confirmed
        client.sendText("{\"type\":\"PAIRED\"}");

        // Notify sender: peer has joined
        if (pair.sender != null) {
            pair.sender.sendText("{\"type\":\"PEER_JOINED\"}");
        }
        log.info("[{}] Receiver joined, code={}", client.ws.getId(), code);
    }

    private void handleForwardTopeer(ClientSession client, String rawJson) {
        SessionPair pair = client.pair;
        if (pair == null) return;
        ClientSession peer = pair.peerOf(client);
        if (peer != null) {
            peer.sendText(rawJson);
        }
    }

    private void handleRelayRequest(ClientSession client) throws Exception {
        SessionPair pair = client.pair;
        if (pair == null || !pair.relayMode.compareAndSet(false, true)) return;

        String msg = "{\"type\":\"RELAY_READY\"}";
        if (pair.sender != null) pair.sender.sendText(msg);
        if (pair.receiver != null) pair.receiver.sendText(msg);
        log.info("Relay mode activated for code={}", pair.code);
    }

    private void handleBinaryRelay(ClientSession client, WebSocketMessage msg) {
        SessionPair pair = client.pair;
        if (pair == null || !pair.relayMode.get()) return;
        ClientSession peer = pair.peerOf(client);
        if (peer == null) return;

        // Copy payload to avoid Netty buffer release before the peer can send it
        byte[] bytes = new byte[msg.getPayload().readableByteCount()];
        msg.getPayload().read(bytes);
        peer.send(peer.ws.binaryMessage(factory -> factory.wrap(bytes)));
    }

    private void onDisconnect(ClientSession client) {
        SessionPair pair = client.pair;
        if (pair == null) return;

        registry.remove(pair.code);
        ClientSession peer = pair.peerOf(client);
        if (peer != null) {
            peer.sendText("{\"type\":\"PEER_DISCONNECTED\"}");
            peer.complete();
        }
        log.info("[{}] Disconnected, code={}", client.ws.getId(), pair.code);
    }

    private String remoteIp(WebSocketSession ws) {
        InetSocketAddress addr = ws.getHandshakeInfo().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }
}
