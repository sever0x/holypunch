package io.github.sever0x.holypunch.client.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sever0x.holypunch.client.net.RelayTransport;
import io.github.sever0x.holypunch.client.net.SignalingClient;
import io.github.sever0x.holypunch.client.net.Transport;
import io.github.sever0x.holypunch.client.transfer.ChunkReceiver;
import io.github.sever0x.holypunch.client.transfer.TransferProtocol;
import io.github.sever0x.holypunch.client.ui.ProgressDisplay;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // ── 1. Prompt for code if not provided ───────────────────────────────
        if (code == null || code.isBlank()) {
            System.out.print("Enter code: ");
            code = stdin.nextLine().trim();
        }

        // ── 2. Prompt for destination directory ──────────────────────────────
        if (destination == null) {
            String defaultDir = System.getProperty("user.dir");
            System.out.printf("Save to [%s]: ", defaultDir);
            String input = stdin.nextLine().trim();
            destination = input.isEmpty() ? Path.of(defaultDir) : Path.of(input);
        }
        destination = destination.toAbsolutePath().normalize();
        Files.createDirectories(destination);

        // ── 3. Connect to signaling server ───────────────────────────────────
        System.out.print("Connecting...");
        SignalingClient signaling = new SignalingClient(serverUrl);
        signaling.connect();

        String joinMsg = String.format(
                "{\"type\":\"%s\",\"code\":\"%s\"}", TransferProtocol.JOIN_RECEIVER, code);
        signaling.sendText(joinMsg);

        // ── 4. Wait for PAIRED ───────────────────────────────────────────────
        String resp = signaling.receiveText(15_000);
        if (resp == null) throw new IOException("Server did not respond to JOIN_RECEIVER");

        JsonNode node = mapper.readTree(resp);
        String type = node.path("type").asText();
        if (TransferProtocol.ERROR.equals(type)) {
            throw new IOException("Server: " + node.path("message").asText());
        }
        if (!TransferProtocol.PAIRED.equals(type)) {
            throw new IOException("Unexpected response: " + resp);
        }
        System.out.println(" paired with sender!");

        // ── 5. Initiate relay (relay-only path; ICE added in Phase 7) ────────
        System.out.print("Establishing relay connection...");
        signaling.sendText("{\"type\":\"" + TransferProtocol.RELAY_REQUEST + "\"}");

        waitForType(signaling, TransferProtocol.RELAY_READY, 15_000);
        System.out.println(" connected (relay)");

        // ── 6. Receive files ─────────────────────────────────────────────────
        RelayTransport transport = signaling.switchToRelay();
        ProgressDisplay progress = new ProgressDisplay(0, "relay");

        // totalBytes becomes known only after the sender sends MANIFEST;
        // ChunkReceiver passes it via the (bytesDone, totalBytes) callback.
        ChunkReceiver receiver = new ChunkReceiver(transport, destination, mapper);
        receiver.setProgressCallback(progress::update);
        receiver.receive();

        progress.complete();
        transport.close();

        System.out.println("Complete! All files received and verified.");
        System.out.println("Saved to: " + destination);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void waitForType(SignalingClient signaling, String expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            Transport.Message msg = signaling.receive(Math.min(remaining, 1_000));
            if (msg == null) continue;
            if (!msg.binary()) {
                String t = mapper.readTree(msg.text()).path("type").asText();
                if (expected.equals(t)) return;
                if (TransferProtocol.PEER_DISCONNECTED.equals(t)) {
                    throw new IOException("Peer disconnected");
                }
            }
        }
        throw new IOException("Timeout waiting for " + expected);
    }
}
