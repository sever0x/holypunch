package io.github.sever0x.holypunch.client.ice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gathers local ICE candidates (host + server-reflexive) and builds the
 * ICE_CANDIDATES signaling message to exchange with the peer.
 *
 * Public STUN servers tried in order; first successful srflx wins.
 */
public class IceAgent {

    private static final String[] STUN_HOSTS = {
            "stun.l.google.com",
            "stun1.l.google.com",
            "stun.cloudflare.com"
    };
    private static final int STUN_PORT = 19302;

    private DatagramChannel channel;
    private final List<IceCandidate> candidates = new ArrayList<>();

    /**
     * Opens a local UDP socket and gathers host + srflx candidates.
     * Must be called before getLocalCandidates() / getChannel().
     */
    public void gatherCandidates() throws IOException {
        channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress(0));                    // any free port
        int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();

        // Host candidates — all up, non-loopback IPv4 interfaces
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
            ni.getInterfaceAddresses().stream()
                    .filter(ia -> ia.getAddress() instanceof Inet4Address)
                    .forEach(ia -> candidates.add(
                            new IceCandidate("host", ia.getAddress().getHostAddress(), port)));
        }

        // Server-reflexive candidate — first STUN server that responds
        for (String host : STUN_HOSTS) {
            try {
                InetSocketAddress srflx = StunClient.query(
                        channel,
                        new InetSocketAddress(java.net.InetAddress.getByName(host), STUN_PORT));
                if (srflx == null) continue;

                String srflxIp = srflx.getAddress().getHostAddress();
                boolean dup = candidates.stream()
                        .anyMatch(c -> c.ip.equals(srflxIp) && c.port == srflx.getPort());
                if (!dup) {
                    candidates.add(new IceCandidate("srflx", srflxIp, srflx.getPort()));
                }
                break;
            } catch (Exception ignored) {}
        }
    }

    public List<IceCandidate> getLocalCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public DatagramChannel getChannel() {
        return channel;
    }

    /** Builds the ICE_CANDIDATES JSON message to send via the signaling channel. */
    public String buildJson(ObjectMapper mapper) throws IOException {
        ObjectNode msg  = mapper.createObjectNode();
        ArrayNode  list = mapper.createArrayNode();
        msg.put("type", "ICE_CANDIDATES");
        msg.set("candidates", list);
        for (IceCandidate c : candidates) {
            ObjectNode n = mapper.createObjectNode();
            n.put("type", c.type);
            n.put("ip",   c.ip);
            n.put("port", c.port);
            list.add(n);
        }
        return mapper.writeValueAsString(msg);
    }

    public void close() {
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
}
