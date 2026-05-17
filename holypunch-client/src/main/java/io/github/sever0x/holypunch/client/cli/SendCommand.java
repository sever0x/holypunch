package io.github.sever0x.holypunch.client.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sever0x.holypunch.client.net.RelayTransport;
import io.github.sever0x.holypunch.client.net.SignalingClient;
import io.github.sever0x.holypunch.client.net.Transport;
import io.github.sever0x.holypunch.client.transfer.ChunkStreamer;
import io.github.sever0x.holypunch.client.transfer.FileManifest;
import io.github.sever0x.holypunch.client.transfer.FileScanner;
import io.github.sever0x.holypunch.client.transfer.HashWorker;
import io.github.sever0x.holypunch.client.transfer.TransferProtocol;
import io.github.sever0x.holypunch.client.ui.ProgressDisplay;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Command(
        name = "send",
        description = "Scan a directory and send it to a peer.",
        mixinStandardHelpOptions = true
)
public class SendCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1",
                description = "Directory to send (default: current directory)")
    private Path directory = Path.of(".");

    @Option(names = {"--server", "-s"},
            defaultValue = "${HOLYPUNCH_SERVER:-ws://localhost:8080/signal}",
            description = "Signaling server WebSocket URL")
    private String serverUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run() {
        try {
            runSend();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("\nInterrupted.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            System.exit(1);
        }
    }

    private void runSend() throws Exception {
        Path dir = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }

        // ── 1. Scan ──────────────────────────────────────────────────────────
        System.out.print("Scanning directory...");
        FileManifest manifest = new FileScanner().scan(dir);
        System.out.printf("\rScanning complete: %d files, %s%n",
                manifest.totalFiles, ProgressDisplay.formatSize(manifest.totalBytes));

        // ── 2. Connect to signaling server ───────────────────────────────────
        System.out.print("Connecting to server...");
        SignalingClient signaling = new SignalingClient(serverUrl);
        signaling.connect();

        signaling.sendText("{\"type\":\"" + TransferProtocol.JOIN_SENDER + "\"}");
        String resp = signaling.receiveText(10_000);
        if (resp == null) throw new IOException("Server did not respond to JOIN_SENDER");

        JsonNode node = mapper.readTree(resp);
        if (TransferProtocol.ERROR.equals(node.path("type").asText())) {
            throw new IOException("Server: " + node.path("message").asText());
        }
        String code = node.path("code").asText();

        // ── 3. Show code ─────────────────────────────────────────────────────
        System.out.println("\r                              ");
        printCodeBox(code);

        // ── 4. Hash files in background ──────────────────────────────────────
        AtomicInteger hashDone = new AtomicInteger(0);
        HashWorker hashWorker = new HashWorker();
        CompletableFuture<Void> hashFuture = hashWorker.hashAll(
                manifest, dir, (done, total) -> hashDone.set(done));

        // ── 5. Wait for peer while showing hashing progress ──────────────────
        System.out.println("Waiting for receiver...");
        boolean peerJoined = false;
        while (!peerJoined) {
            ProgressDisplay.printHashProgress(hashDone.get(), manifest.totalFiles);
            Transport.Message msg = signaling.receive(300);
            if (msg != null && !msg.binary()) {
                String type = mapper.readTree(msg.text()).path("type").asText();
                if (TransferProtocol.PEER_JOINED.equals(type))  { peerJoined = true; }
                if (TransferProtocol.PEER_DISCONNECTED.equals(type)) {
                    throw new IOException("Server disconnected");
                }
            }
        }
        System.out.printf("\rPeer joined!%-50s%n", "");

        // ── 6. Finish hashing if still in progress ───────────────────────────
        if (!hashFuture.isDone()) {
            while (!hashFuture.isDone()) {
                ProgressDisplay.printHashProgress(hashDone.get(), manifest.totalFiles);
                Thread.sleep(200);
            }
        }
        hashFuture.get(); // propagate any hashing exceptions
        hashWorker.shutdown();
        System.out.printf("\rHashing complete: %d/%d files%n",
                manifest.totalFiles, manifest.totalFiles);

        // ── 7. Compute manifest hash ─────────────────────────────────────────
        manifest.computeAndSetHash(mapper);

        // ── 8. Wait for RELAY_READY (receiver initiates relay) ───────────────
        System.out.print("Establishing relay connection...");
        waitForType(signaling, TransferProtocol.RELAY_READY, 30_000);
        System.out.println(" connected (relay)");

        // ── 9. Stream ────────────────────────────────────────────────────────
        RelayTransport transport = signaling.switchToRelay();
        ProgressDisplay progress = new ProgressDisplay(manifest.totalBytes, "relay");

        ChunkStreamer streamer = new ChunkStreamer(transport, dir, manifest, mapper);
        streamer.setProgressCallback(progress::update);
        streamer.stream();

        progress.complete();
        transport.close();

        System.out.printf("Complete! %d files sent. All checksums verified.%n",
                manifest.totalFiles);
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
                String type = mapper.readTree(msg.text()).path("type").asText();
                if (expected.equals(type)) return;
                if (TransferProtocol.PEER_DISCONNECTED.equals(type)) {
                    throw new IOException("Peer disconnected");
                }
            }
        }
        throw new IOException("Timeout waiting for " + expected);
    }

    private static void printCodeBox(String code) {
        String l1 = "  Code:  " + code;
        String l2 = "  Share this code with your recipient!";
        int inner = Math.max(l1.length(), l2.length()) + 2;
        String top = "╔" + "═".repeat(inner) + "╗";
        String bot = "╚" + "═".repeat(inner) + "╝";
        System.out.println(top);
        System.out.printf("║ %-" + (inner - 1) + "s║%n", l1);
        System.out.printf("║ %-" + (inner - 1) + "s║%n", l2);
        System.out.println(bot);
        System.out.println();
    }
}
