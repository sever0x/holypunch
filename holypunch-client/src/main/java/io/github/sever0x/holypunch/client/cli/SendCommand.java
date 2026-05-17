package io.github.sever0x.holypunch.client.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sever0x.holypunch.client.ice.ConnectionEstablisher;
import io.github.sever0x.holypunch.client.ice.IceAgent;
import io.github.sever0x.holypunch.client.ice.IceCandidate;
import io.github.sever0x.holypunch.client.net.DirectTransport;
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
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

        // ── 2. Connect + JOIN_SENDER ─────────────────────────────────────────
        System.out.print("Connecting to server...");
        SignalingClient signaling = new SignalingClient(serverUrl);
        signaling.connect();
        signaling.sendText("{\"type\":\"" + TransferProtocol.JOIN_SENDER + "\"}");

        String resp = signaling.receiveText(10_000);
        if (resp == null) throw new IOException("Server did not respond to JOIN_SENDER");
        JsonNode node = mapper.readTree(resp);
        if (TransferProtocol.ERROR.equals(node.path("type").asText()))
            throw new IOException("Server: " + node.path("message").asText());
        String code = node.path("code").asText();

        // ── 3. Show code ─────────────────────────────────────────────────────
        System.out.println("\r                              ");
        printCodeBox(code);

        // ── 4. Hash files in background + gather ICE candidates ──────────────
        AtomicInteger hashDone = new AtomicInteger(0);
        HashWorker hashWorker  = new HashWorker();
        CompletableFuture<Void> hashFuture = hashWorker.hashAll(
                manifest, dir, (done, total) -> hashDone.set(done));

        IceAgent iceAgent = new IceAgent();
        CompletableFuture<Void> iceFuture = CompletableFuture.runAsync(() -> {
            try { iceAgent.gatherCandidates(); }
            catch (IOException e) { /* ICE unavailable — relay fallback */ }
        });

        // ── 5. Wait for peer + show hashing progress ─────────────────────────
        System.out.println("Waiting for receiver...");
        boolean peerJoined = false;
        while (!peerJoined) {
            ProgressDisplay.printHashProgress(hashDone.get(), manifest.totalFiles);
            Transport.Message msg = signaling.receive(300);
            if (msg != null && !msg.binary()) {
                String type = mapper.readTree(msg.text()).path("type").asText();
                if (TransferProtocol.PEER_JOINED.equals(type))      peerJoined = true;
                if (TransferProtocol.PEER_DISCONNECTED.equals(type)) throw new IOException("Server disconnected");
            }
        }
        System.out.printf("\rPeer joined!%-50s%n", "");

        // ── 6. Finish hashing ────────────────────────────────────────────────
        while (!hashFuture.isDone()) {
            ProgressDisplay.printHashProgress(hashDone.get(), manifest.totalFiles);
            Thread.sleep(200);
        }
        hashFuture.get();
        hashWorker.shutdown();
        System.out.printf("\rHashing complete: %d/%d files%n",
                manifest.totalFiles, manifest.totalFiles);
        manifest.computeAndSetHash(mapper);

        // ── 7. ICE candidate exchange ────────────────────────────────────────
        iceFuture.join(); // ICE gathering must be done before sending candidates
        if (!iceAgent.getLocalCandidates().isEmpty()) {
            signaling.sendText(iceAgent.buildJson(mapper));
        }

        // ── 8. Try P2P, fall back to relay ───────────────────────────────────
        Transport transport;
        String mode;

        List<IceCandidate> remoteCandidates = waitForRemoteCandidates(signaling, 10_000);

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

        // ── 9. Stream ────────────────────────────────────────────────────────
        ProgressDisplay progress = new ProgressDisplay(manifest.totalBytes, mode);
        ChunkStreamer streamer = new ChunkStreamer(transport, dir, manifest, mapper);
        streamer.setProgressCallback(progress::update);
        streamer.stream();

        progress.complete();
        transport.close();
        iceAgent.close();

        System.out.printf("Complete! %d files sent. All checksums verified.%n",
                manifest.totalFiles);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads messages from signaling until ICE_CANDIDATES arrive or timeout.
     * Other message types (PEER_DISCONNECTED) are handled inline.
     */
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
                throw new IOException("Peer disconnected during ICE exchange");
        }
        return List.of(); // timeout — skip P2P, use relay
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

    private static void printCodeBox(String code) {
        String l1 = "  Code:  " + code;
        String l2 = "  Share this code with your recipient!";
        int inner = Math.max(l1.length(), l2.length()) + 2;
        System.out.println("╔" + "═".repeat(inner) + "╗");
        System.out.printf("║ %-" + (inner - 1) + "s║%n", l1);
        System.out.printf("║ %-" + (inner - 1) + "s║%n", l2);
        System.out.println("╚" + "═".repeat(inner) + "╝");
        System.out.println();
    }
}
