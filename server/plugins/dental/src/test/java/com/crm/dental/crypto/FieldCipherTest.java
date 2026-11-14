package com.crm.dental.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCipherTest {

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @Test
    void round_trip() {
        FieldCipher cipher = new FieldCipher(key(7));

        byte[] sealed = cipher.encrypt("Dị ứng penicillin");

        assertThat(cipher.decrypt(sealed)).isEqualTo("Dị ứng penicillin");
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("penicillin");
    }

    @Test
    void two_encryptions_differ() {
        FieldCipher cipher = new FieldCipher(key(7));

        byte[] first = cipher.encrypt("same text");
        byte[] second = cipher.encrypt("same text");

        assertThat(first).isNotEqualTo(second);
        assertThat(Arrays.copyOf(first, 12)).isNotEqualTo(Arrays.copyOf(second, 12));
    }

    @Test
    void tampered_fails() {
        FieldCipher cipher = new FieldCipher(key(7));
        byte[] sealed = cipher.encrypt("secret answer");
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(sealed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("secret");
    }

    @Test
    void wrong_key_fails() {
        byte[] sealed = new FieldCipher(key(7)).encrypt("secret answer");

        assertThatThrownBy(() -> new FieldCipher(key(8)).decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void short_key_rejected_without_echoing_it() {
        byte[] shortKey = new byte[19];
        Arrays.fill(shortKey, (byte) 'Q');

        assertThatThrownBy(() -> new FieldCipher(shortKey))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("QQQ")
            .hasMessageNotContaining("19");
    }

    @Test
    void flipped_iv_byte_fails() {
        FieldCipher cipher = new FieldCipher(key(7));
        byte[] sealed = cipher.encrypt("secret answer");
        sealed[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncated_input_fails() {
        FieldCipher cipher = new FieldCipher(key(7));
        byte[] sealed = cipher.encrypt("secret answer");

        assertThatThrownBy(() -> cipher.decrypt(Arrays.copyOf(sealed, 20))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(Arrays.copyOf(sealed, sealed.length - 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[0])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void null_input_fails() {
        FieldCipher cipher = new FieldCipher(key(7));

        assertThatThrownBy(() -> cipher.decrypt(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabled_cipher_throws() {
        FieldCipher cipher = FieldCipher.disabled();

        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[40])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aad_round_trip_and_wrong_aad_fails() {
        FieldCipher cipher = new FieldCipher(key(7));
        byte[] aad = "appointment-1:allergies_enc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sealed = cipher.encrypt("secret answer", aad);

        assertThat(cipher.decrypt(sealed, aad)).isEqualTo("secret answer");
        byte[] other = "appointment-2:allergies_enc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] otherColumn = "appointment-1:reason_enc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cipher.decrypt(sealed, other)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> cipher.decrypt(sealed, otherColumn)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, new byte[0])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }
}
