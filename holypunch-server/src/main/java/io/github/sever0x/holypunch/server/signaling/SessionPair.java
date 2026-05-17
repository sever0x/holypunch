package io.github.sever0x.holypunch.server.signaling;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionPair {

    static final long TTL_SECONDS = 1800; // 30 minutes

    final String code;
    final Instant createdAt = Instant.now();
    final AtomicBoolean relayMode = new AtomicBoolean(false);

    volatile ClientSession sender;
    volatile ClientSession receiver;

    SessionPair(String code, ClientSession sender) {
        this.code = code;
        this.sender = sender;
    }

    boolean isExpired() {
        return Instant.now().isAfter(createdAt.plusSeconds(TTL_SECONDS));
    }

    ClientSession peerOf(ClientSession session) {
        if (session == sender) return receiver;
        if (session == receiver) return sender;
        return null;
    }
}
