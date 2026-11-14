package com.crm.dental;

import com.crm.dental.crypto.FieldCipher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cipher bean, called directly (no Spring context). */
class DentalConfigTest {

    private final DentalConfig config = new DentalConfig();

    @Test
    void malformed_key_fails_even_with_public_disabled() {
        assertThatThrownBy(() -> config.fieldCipher(false, "not base64 !!"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("not base64");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> config.fieldCipher(false, shortKey))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(shortKey);
    }

    @Test
    void blank_key_disables_cipher_when_public_disabled_and_fails_when_enabled() {
        assertThat(config.fieldCipher(false, "").enabled()).isFalse();
        assertThatThrownBy(() -> config.fieldCipher(true, " ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valid_key_enables_cipher() {
        FieldCipher cipher = config.fieldCipher(false, Base64.getEncoder().encodeToString(new byte[32]));

        assertThat(cipher.enabled()).isTrue();
    }
}
