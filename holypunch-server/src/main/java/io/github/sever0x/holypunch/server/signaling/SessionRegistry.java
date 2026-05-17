package io.github.sever0x.holypunch.server.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private static final int MAX_JOIN_ATTEMPTS_PER_MINUTE = 20;

    private final Map<String, SessionPair> sessions = new ConcurrentHashMap<>();
    // ip → [attemptCount, windowStartEpochSecond]
    private final Map<String, long[]> rateLimitMap = new ConcurrentHashMap<>();

    private final CodeGenerator codeGenerator;

    public SessionRegistry(CodeGenerator codeGenerator) {
        this.codeGenerator = codeGenerator;
    }

    public SessionPair createSession(ClientSession sender) {
        String code;
        SessionPair pair;
        // Retry on collision (astronomically unlikely, but correct)
        do {
            code = codeGenerator.generate();
            pair = new SessionPair(code, sender);
        } while (sessions.putIfAbsent(code, pair) != null);

        sender.role = ClientSession.Role.SENDER;
        sender.pair = pair;
        log.info("Session created: {}", code);
        return pair;
    }

    /**
     * Returns null if code not found, expired, already has a receiver, or rate-limited.
     */
    public SessionPair joinReceiver(String code, ClientSession receiver, String clientIp) {
        if (!checkRateLimit(clientIp)) {
            log.warn("Rate limit exceeded for {}", clientIp);
            return null;
        }

        // Atomic: only one receiver wins per code
        SessionPair[] result = new SessionPair[1];
        sessions.computeIfPresent(code, (k, pair) -> {
            if (pair.isExpired() || pair.receiver != null) return pair; // leave in map, don't assign
            pair.receiver = receiver;
            receiver.role = ClientSession.Role.RECEIVER;
            receiver.pair = pair;
            result[0] = pair;
            return pair;
        });
        return result[0];
    }

    public void remove(String code) {
        sessions.remove(code);
        log.info("Session removed: {}", code);
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    void cleanExpired() {
        int removed = 0;
        for (Map.Entry<String, SessionPair> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired()) {
                sessions.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) log.info("Cleaned {} expired sessions", removed);
    }

    private boolean checkRateLimit(String ip) {
        long now = Instant.now().getEpochSecond();
        long[] bucket = rateLimitMap.computeIfAbsent(ip, k -> new long[]{0, now});
        synchronized (bucket) {
            if (now - bucket[1] >= 60) {
                bucket[0] = 0;
                bucket[1] = now;
            }
            if (bucket[0] >= MAX_JOIN_ATTEMPTS_PER_MINUTE) return false;
            bucket[0]++;
            return true;
        }
    }
}
