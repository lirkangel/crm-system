package com.crm.hotel.checkin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedNameTest {

    @Test
    void keeps_the_first_letter_of_each_word_with_vietnamese_diacritics() {
        assertThat(MaskedName.of("Nguyễn Văn An")).isEqualTo("N***** V** A*");
        assertThat(MaskedName.of("Đặng Thị Ánh")).isEqualTo("Đ*** T** Á**");
    }

    @Test
    void decomposed_diacritics_count_as_one_letter() {
        String decomposed = java.text.Normalizer.normalize("Nguyễn Văn An", java.text.Normalizer.Form.NFD);

        assertThat(MaskedName.of(decomposed)).isEqualTo("N***** V** A*");
    }

    @Test
    void single_letter_word_stays_as_it_is() {
        assertThat(MaskedName.of("A")).isEqualTo("A");
        assertThat(MaskedName.of("Tran B Long")).isEqualTo("T*** B L***");
    }

    @Test
    void extra_spaces_collapse_to_one() {
        assertThat(MaskedName.of("  Le   Van\tCam ")).isEqualTo("L* V** C**");
    }

    @Test
    void counts_code_points_not_chars() {
        assertThat(MaskedName.of("😀😀😀 x")).isEqualTo("😀** x");
    }

    @Test
    void blank_or_null_is_empty() {
        assertThat(MaskedName.of(null)).isEmpty();
        assertThat(MaskedName.of("   ")).isEmpty();
    }
}
