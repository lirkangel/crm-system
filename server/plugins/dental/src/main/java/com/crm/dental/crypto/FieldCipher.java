package com.crm.dental.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM encryption of single text fields at rest. Output is {@code IV (12 bytes) || ciphertext+tag}, a
 * fresh random IV per call. Never logs plaintext or key material; failures carry fixed messages only.
 */
public final class FieldCipher {

    public static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** A cipher that refuses to work, for when the public route is off and no key is configured. */
    public static FieldCipher disabled() {
        return new FieldCipher(null);
    }

    public FieldCipher(byte[] key) {
        if (key == null) {
            this.key = null;
        } else {
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Field key must be exactly " + KEY_BYTES + " bytes");
            }
            this.key = new SecretKeySpec(Arrays.copyOf(key, key.length), "AES");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public byte[] encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed");
        }
    }

    public String decrypt(byte[] data) {
        requireKey();
        if (data == null || data.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Field decryption failed");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Field decryption failed");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Field encryption is not configured");
        }
    }
}
