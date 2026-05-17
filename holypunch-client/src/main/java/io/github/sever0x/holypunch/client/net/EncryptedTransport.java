package io.github.sever0x.holypunch.client.net;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Transport decorator that adds end-to-end encryption via AES-256-GCM.
 *
 * All messages — text (JSON control) and binary (chunk frames) — are encrypted
 * before being sent to the inner transport. The encrypted payload is always
 * delivered as a binary message, regardless of the original type.
 *
 * Wire format of each encrypted message:
 *   [4 bytes: counter (big-endian)] [8 bytes: random nonce half]  ← 12-byte GCM IV
 *   [ciphertext]                                                    ← AES-256-GCM output
 *   (GCM tag is appended to ciphertext by the Cipher, 16 bytes)
 *
 * Plaintext layout (before encryption):
 *   [1 byte: type — 0x00=text, 0x01=binary] [payload bytes...]
 *
 * Direction isolation: sendKey ≠ recvKey (derived from separate HKDF labels
 * in Crypto.wrapAsInitiator / wrapAsResponder).  Counter-per-direction ensures
 * the IV never repeats.
 */
public class EncryptedTransport implements Transport {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES     = 12;
    private static final byte TYPE_TEXT   = 0x00;
    private static final byte TYPE_BINARY = 0x01;

    private final Transport inner;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec recvKey;
    private long sendCounter = 0;
    private final byte[] nonceSuffix = new byte[8]; // random half of IV, fixed per session

    public EncryptedTransport(Transport inner, byte[] sendKeyBytes, byte[] recvKeyBytes) {
        this.inner   = inner;
        this.sendKey = new SecretKeySpec(sendKeyBytes, "AES");
        this.recvKey = new SecretKeySpec(recvKeyBytes, "AES");
        new SecureRandom().nextBytes(nonceSuffix);
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        inner.sendBinary(encrypt(TYPE_BINARY, data));
    }

    @Override
    public void sendText(String json) throws IOException {
        inner.sendBinary(encrypt(TYPE_TEXT, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public Message receive() throws IOException, InterruptedException {
        while (true) {
            Message raw = inner.receive();
            if (raw == null) return null;

            // Only binary frames carry encrypted content
            if (!raw.binary()) continue;

            byte[] plain;
            try {
                plain = decrypt(raw.data());
            } catch (AEADBadTagException e) {
                throw new IOException("Decryption failed — authentication tag mismatch", e);
            } catch (Exception e) {
                throw new IOException("Decryption error", e);
            }
            if (plain.length < 1) continue;

            byte type    = plain[0];
            byte[] payload = Arrays.copyOfRange(plain, 1, plain.length);
            return type == TYPE_TEXT
                    ? Message.ofText(new String(payload, StandardCharsets.UTF_8))
                    : Message.ofBinary(payload);
        }
    }

    @Override
    public boolean isOpen() { return inner.isOpen(); }

    @Override
    public void close() { inner.close(); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private byte[] encrypt(byte type, byte[] payload) throws IOException {
        byte[] plain = new byte[1 + payload.length];
        plain[0] = type;
        System.arraycopy(payload, 0, plain, 1, payload.length);

        byte[] iv = buildIv(sendCounter++);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain);

            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IOException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] encrypted) throws Exception {
        if (encrypted.length < IV_BYTES + 1) throw new IOException("Frame too short");
        byte[] iv         = Arrays.copyOf(encrypted, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_BYTES, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, recvKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    /**
     * IV = [4-byte big-endian counter][8-byte fixed random nonce suffix].
     * Counter increments per message; nonce suffix is random per session.
     * This guarantees IV uniqueness for up to 2^32 messages on the send key.
     */
    private byte[] buildIv(long counter) {
        byte[] iv = new byte[IV_BYTES];
        ByteBuffer.wrap(iv).putInt((int) counter);
        System.arraycopy(nonceSuffix, 0, iv, 4, 8);
        return iv;
    }
}
