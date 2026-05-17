package io.github.sever0x.holypunch.client.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sever0x.holypunch.client.ice.ConnectionEstablisher;
import io.github.sever0x.holypunch.client.ice.IceAgent;
import io.github.sever0x.holypunch.client.ice.IceCandidate;
import io.github.sever0x.holypunch.client.net.DirectTransport;
import io.github.sever0x.holypunch.client.net.SignalingClient;
import io.github.sever0x.holypunch.client.net.Transport;
import io.github.sever0x.holypunch.client.transfer.ChunkReceiver;
import io.github.sever0x.holypunch.client.transfer.TransferProtocol;
import io.github.sever0x.holypunch.client.ui.ProgressDisplay;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Command(
        name = "receive",
        description = "Receive files from a sender using a session code.",
        mixinStandardHelpOptions = true
)
public class ReceiveCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1",
                description = "Session code (prompted if omitted)")
    private String code;

    @Parameters(index = "1", arity = "0..1",
                description = "Destination directory (prompted if omitted)")
    private Path destination;

    @Option(names = {"--server", "-s"},
            defaultValue = "${HOLYPUNCH_SERVER:-ws://localhost:8080/signal}",
            description = "Signaling server WebSocket URL")
    private String serverUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Scanner stdin = new Scanner(System.in);

    @Override
    public void run() {
        try {
            runReceive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("\nInterrupted.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            System.exit(1);
        }
    }

    private void runReceive() throws Exception {
        // ── 1. Prompt for inputs ─────────────────────────────────────────────
        if (code == null || code.isBlank()) {
            System.out.print("Enter code: ");
            code = stdin.nextLine().trim();
        }
        if (destination == null) {
            String def = System.getProperty("user.dir");
            System.out.printf("Save to [%s]: ", def);
            String input = stdin.nextLine().trim();
            destination = input.isEmpty() ? Path.of(def) : Path.of(input);
        }
        destination = destination.toAbsolutePath().normalize();
        Files.createDirectories(destination);

        // ── 2. Connect + JOIN_RECEIVER ───────────────────────────────────────
        System.out.print("Connecting...");
        SignalingClient signaling = new SignalingClient(serverUrl);
        signaling.connect();

        String joinMsg = String.format(
                "{\"type\":\"%s\",\"code\":\"%s\"}", TransferProtocol.JOIN_RECEIVER, code);
        signaling.sendText(joinMsg);

        String resp = signaling.receiveText(15_000);
        if (resp == null) throw new IOException("Server did not respond");
        JsonNode node = mapper.readTree(resp);
        String msgType = node.path("type").asText();
        if (TransferProtocol.ERROR.equals(msgType))
            throw new IOException("Server: " + node.path("message").asText());
        if (!TransferProtocol.PAIRED.equals(msgType))
            throw new IOException("Unexpected response: " + resp);
        System.out.println(" paired with sender!");

        // ── 3. Gather ICE candidates (parallel with user reading code) ────────
        IceAgent iceAgent = new IceAgent();
        try { iceAgent.gatherCandidates(); } catch (IOException ignored) {}

        // ── 4. Send our ICE candidates to sender ──────────────────────────────
        if (!iceAgent.getLocalCandidates().isEmpty()) {
            signaling.sendText(iceAgent.buildJson(mapper));
        }

        // ── 5. Wait for sender's ICE candidates ───────────────────────────────
        List<IceCandidate> remoteCandidates = waitForRemoteCandidates(signaling, 10_000);

        // ── 6. Try P2P, fall back to relay ────────────────────────────────────
        Transport transport;
        String mode;

        if (!remoteCandidates.isEmpty() && !iceAgent.getLocalCandidates().isEmpty()) {
            System.out.print("Establishing direct connection...");
            DatagramChannel p2pCh = ConnectionEstablisher.tryConnect(
                    iceAgent.getChannel(),
                    iceAgent.getLocalCandidates(),
                    remoteCandidates,
                    15_000);

            if (p2pCh != null) {
                transport = new DirectTransport(p2pCh);
                mode = "direct";
            } else {
                System.out.print("\rNAT blocked, using relay...          ");
                transport = requestRelay(signaling);
                mode = "relay";
            }
        } else {
            System.out.print("Establishing relay connection...");
            transport = requestRelay(signaling);
            mode = "relay";
        }
        System.out.println(" connected (" + mode + ")");

        // ── 7. Receive files ──────────────────────────────────────────────────
        ProgressDisplay progress = new ProgressDisplay(0, mode);
        ChunkReceiver receiver = new ChunkReceiver(transport, destination, mapper);
        receiver.setProgressCallback(progress::update);
        receiver.receive();

        progress.complete();
        transport.close();
        iceAgent.close();

        System.out.println("Complete! All files received and verified.");
        System.out.println("Saved to: " + destination);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<IceCandidate> waitForRemoteCandidates(SignalingClient signaling, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long wait = deadline - System.currentTimeMillis();
            Transport.Message msg = signaling.receive(Math.min(wait, 1_000));
            if (msg == null) continue;
            if (msg.binary()) continue;
            JsonNode node = mapper.readTree(msg.text());
            String type = node.path("type").asText();
            if (TransferProtocol.ICE_CANDIDATES.equals(type)) {
                List<IceCandidate> list = new ArrayList<>();
                node.path("candidates").forEach(n ->
                        list.add(new IceCandidate(
                                n.path("type").asText(),
                                n.path("ip").asText(),
                                n.path("port").asInt())));
                return list;
            }
            if (TransferProtocol.PEER_DISCONNECTED.equals(type))
                throw new IOException("Peer disconnected");
        }
        return List.of();
    }

    private Transport requestRelay(SignalingClient signaling) throws Exception {
        signaling.sendText("{\"type\":\"" + TransferProtocol.RELAY_REQUEST + "\"}");
        waitForType(signaling, TransferProtocol.RELAY_READY, 15_000);
        return signaling.switchToRelay();
    }

    private void waitForType(SignalingClient signaling, String expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Transport.Message msg = signaling.receive(Math.min(1_000, deadline - System.currentTimeMillis()));
            if (msg == null) continue;
            if (!msg.binary()) {
                String type = mapper.readTree(msg.text()).path("type").asText();
                if (expected.equals(type)) return;
                if (TransferProtocol.PEER_DISCONNECTED.equals(type)) throw new IOException("Peer disconnected");
            }
        }
        throw new IOException("Timeout waiting for " + expected);
    }
}
