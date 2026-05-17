package io.github.sever0x.holypunch.client.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sever0x.holypunch.client.net.EncryptedTransport;
import io.github.sever0x.holypunch.client.net.Transport;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * End-to-end encryption key exchange using a SPAKE2-like construction over X25519.
 *
 * Protocol (two-message key exchange):
 *   1. Both sides derive w = SHA-256("holypunch-v1:" || sessionCode)
 *   2. Both generate an ephemeral X25519 keypair (sk, pk)
 *   3. Initiator sends  {"type":"KEY_EXCHANGE","pk": base64(pk_A XOR w)}
 *   4. Responder replies {"type":"KEY_EXCHANGE","pk": base64(pk_B XOR w)}
 *   5. Both compute shared = X25519(sk_self, pk_peer) — DH shared secret
 *   6. Both derive direction-specific keys via HKDF:
 *        ikm = shared || SHA-256(pk_A || pk_B)   (deterministic from transcript)
 *        initiatorToResponderKey = HKDF(ikm, "i2r", 32)
 *        responderToInitiatorKey = HKDF(ikm, "r2i", 32)
 *
 * Security properties:
 *   - Passive attacker with the session code: cannot compute DH shared secret
 *     (needs the ephemeral private key, which is never transmitted)
 *   - Active MitM without the code: cannot unmask the public keys (w is unknown)
 *   - Session code adds binding between the DH transcript and the code channel
 *
 * All standard JCE / no Bouncy Castle — GraalVM native-image compatible.
 */
public class Crypto {

    private static final byte[] X25519_DER_PREFIX = {
            48, 42, 48, 5, 6, 3, 43, 101, 110, 3, 33, 0
    };

    private final String sessionCode;
    private final ObjectMapper mapper;
    private byte[] w;
    private PrivateKey privateKey;
    private byte[] rawPublicKey;   // 32-byte X25519 u-coordinate
    private byte[] sharedKey;      // set after exchange

    public Crypto(String sessionCode, ObjectMapper mapper) {
        this.sessionCode = sessionCode;
        this.mapper = mapper;
    }

    /** Called by the file SENDER. Sends its masked key first, then reads the peer's. */
    public void initiatorExchange(Transport transport) throws Exception {
        init();
        transport.sendText(buildKeyMsg(rawPublicKey));
        byte[] peerRaw = readPeerKey(transport);
        deriveSharedKey(peerRaw, rawPublicKey, peerRaw);
    }

    /** Called by the file RECEIVER. Reads the peer's masked key first, then replies. */
    public void responderExchange(Transport transport) throws Exception {
        init();
        byte[] peerRaw = readPeerKey(transport);
        transport.sendText(buildKeyMsg(rawPublicKey));
        deriveSharedKey(peerRaw, peerRaw, rawPublicKey);
    }

    /** Returns an EncryptedTransport with initiator-direction keys. */
    public EncryptedTransport wrapAsInitiator(Transport inner) {
        return new EncryptedTransport(inner, deriveKey("i2r"), deriveKey("r2i"));
    }

    /** Returns an EncryptedTransport with responder-direction keys. */
    public EncryptedTransport wrapAsResponder(Transport inner) {
        return new EncryptedTransport(inner, deriveKey("r2i"), deriveKey("i2r"));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void init() throws Exception {
        // w = SHA-256("holypunch-v1:" || sessionCode) — 32-byte password verifier
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("holypunch-v1:".getBytes());
        w = sha.digest(sessionCode.getBytes());

        // Ephemeral X25519 keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();
        // Raw public key = last 32 bytes of the SubjectPublicKeyInfo DER
        byte[] encoded = kp.getPublic().getEncoded(); // 44 bytes
        rawPublicKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private String buildKeyMsg(byte[] pk) throws Exception {
        byte[] masked = xor(pk, w);
        return String.format("{\"type\":\"KEY_EXCHANGE\",\"pk\":\"%s\"}",
                Base64.getEncoder().encodeToString(masked));
    }

    private byte[] readPeerKey(Transport transport) throws Exception {
        while (true) {
            Transport.Message msg = transport.receive();
            if (msg == null) throw new java.io.IOException("Transport closed during key exchange");
            if (msg.binary()) continue; // unexpected binary before encryption — skip
            JsonNode node = mapper.readTree(msg.text());
            if ("KEY_EXCHANGE".equals(node.path("type").asText())) {
                byte[] masked = Base64.getDecoder().decode(node.path("pk").asText());
                return xor(masked, w); // unmask → raw peer public key
            }
        }
    }

    private void deriveSharedKey(byte[] peerRaw, byte[] firstPk, byte[] secondPk) throws Exception {
        // Reconstruct peer's X25519 public key from raw bytes
        byte[] der = new byte[X25519_DER_PREFIX.length + peerRaw.length];
        System.arraycopy(X25519_DER_PREFIX, 0, der, 0, X25519_DER_PREFIX.length);
        System.arraycopy(peerRaw, 0, der, X25519_DER_PREFIX.length, peerRaw.length);
        PublicKey peerPk = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(der));

        // X25519 DH
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(privateKey);
        ka.doPhase(peerPk, true);
        byte[] dhShared = ka.generateSecret();

        // ikm = dhShared || SHA-256(firstPk || secondPk)  — binds the transcript
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(firstPk);
        sha.update(secondPk);
        byte[] transcript = sha.digest();

        sharedKey = hkdf(concat(dhShared, transcript), "holypunch-root-v1".getBytes(), 32);
    }

    private byte[] deriveKey(String label) {
        return hkdf(sharedKey, label.getBytes(), 32);
    }

    // ── Crypto primitives (pure JCE, no external deps) ───────────────────────

    static byte[] hkdf(byte[] ikm, byte[] info, int len) {
        try {
            // HKDF-Extract: prk = HMAC-SHA256(salt=zeros, ikm)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            // HKDF-Expand: one block (len ≤ 32)
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 0x01);
            return Arrays.copyOf(mac.doFinal(), len);
        } catch (Exception e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
