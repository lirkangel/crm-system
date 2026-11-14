package com.crm.dental;

import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhonesTest {

    @Test
    void local_format_becomes_international() {
        assertThat(Phones.normalize("0912345678")).isEqualTo("+84912345678");
        assertThat(Phones.normalize("0356789012")).isEqualTo("+84356789012");
        assertThat(Phones.normalize("0587654321")).isEqualTo("+84587654321");
        assertThat(Phones.normalize("0701234567")).isEqualTo("+84701234567");
        assertThat(Phones.normalize("0801234567")).isEqualTo("+84801234567");
    }

    @Test
    void international_format_is_kept() {
        assertThat(Phones.normalize("+84912345678")).isEqualTo("+84912345678");
    }

    @Test
    void spaces_dots_and_dashes_are_tolerated() {
        assertThat(Phones.normalize(" 0912 345 678 ")).isEqualTo("+84912345678");
        assertThat(Phones.normalize("091.234.5678")).isEqualTo("+84912345678");
        assertThat(Phones.normalize("+84-912-345-678")).isEqualTo("+84912345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0212345678",      // landline prefix 2
        "0112345678",      // prefix 1
        "0412345678",      // prefix 4
        "0612345678",      // prefix 6
        "091234567",       // too short
        "09123456789",     // too long
        "+840912345678",   // trunk zero kept after the country code
        "84912345678",     // no plus
        "+85212345678",    // another country
        "09123x5678",      // letters
        "(091) 2345678",   // brackets not tolerated
        "", "   "})
    void anything_else_is_rejected(String raw) {
        assertThatThrownBy(() -> Phones.normalize(raw))
            .isInstanceOfSatisfying(PluginProblem.class, p -> {
                assertThat(p.status()).isEqualTo(400);
                assertThat(p.code()).isEqualTo("DENTAL_INVALID_PHONE");
                assertThat(p.getMessage()).doesNotContain(raw.trim().isEmpty() ? "\u0000" : raw.trim());
            });
    }

    @Test
    void null_is_rejected() {
        assertThatThrownBy(() -> Phones.normalize(null))
            .isInstanceOfSatisfying(PluginProblem.class, p -> assertThat(p.code()).isEqualTo("DENTAL_INVALID_PHONE"));
    }
}
