package io.github.sever0x.holypunch.server.signaling;

import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

public class ClientSession {

    public enum Role { SENDER, RECEIVER }

    final WebSocketSession ws;
    final Sinks.Many<WebSocketMessage> outgoing = Sinks.many().unicast().onBackpressureBuffer();

    volatile Role role;
    volatile SessionPair pair;

    ClientSession(WebSocketSession ws) {
        this.ws = ws;
    }

    void send(WebSocketMessage msg) {
        outgoing.tryEmitNext(msg);
    }

    void sendText(String json) {
        send(ws.textMessage(json));
    }

    void complete() {
        outgoing.tryEmitComplete();
    }
}
