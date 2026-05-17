package io.github.sever0x.holypunch.client.ice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.net.InetSocketAddress;

/**
 * One ICE candidate — a (type, ip, port) triple that represents a potential
 * address through which this peer can be reached.
 *
 * Types and their source:
 *   host  – a local interface address (LAN reachability)
 *   srflx – server-reflexive address learned via STUN (NAT public address)
 *   relay – address on the relay server (always reachable as fallback)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IceCandidate {

    public String type;  // "host" | "srflx" | "relay"
    public String ip;
    public int    port;

    public IceCandidate() {}

    public IceCandidate(String type, String ip, int port) {
        this.type = type;
        this.ip   = ip;
        this.port = port;
    }

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(ip, port);
    }

    /** Higher value = tried first. host > srflx > relay. */
    public int priority() {
        return switch (type) {
            case "host"  -> 30;
            case "srflx" -> 20;
            case "relay" -> 10;
            default      -> 0;
        };
    }

    @Override
    public String toString() {
        return type + ":" + ip + ":" + port;
    }
}
