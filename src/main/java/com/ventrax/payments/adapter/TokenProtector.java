package com.ventrax.payments.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
public class TokenProtector {
    private final byte[] key;

    public TokenProtector(@Value("${smartgn.card-key}") String secret) {
        key = Base64.getDecoder().decode(secret);
        if (key.length != 32)
            throw new IllegalArgumentException("CARD_ENCRYPTION_KEY must encode 32 bytes");
    }

    public String protect(String value) {
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return Base64.getEncoder()
                    .encodeToString(
                            java.nio.ByteBuffer.allocate(
                                            12 + value.getBytes(StandardCharsets.UTF_8).length + 16)
                                    .put(iv)
                                    .put(c.doFinal(value.getBytes(StandardCharsets.UTF_8)))
                                    .array());
        } catch (Exception e) {
            throw new IllegalStateException("Token protection failed");
        }
    }

    public String reveal(String protectedValue) {
        try {
            byte[] b = Base64.getDecoder().decode(protectedValue);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(b, 12)));
            return new String(
                    c.doFinal(Arrays.copyOfRange(b, 12, b.length)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed");
        }
    }
}
