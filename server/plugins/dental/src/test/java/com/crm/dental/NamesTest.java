package com.crm.dental;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamesTest {

    @Test
    void folds_accents_and_case() {
        assertThat(Names.fold("Nguyễn Văn An")).isEqualTo("nguyen van an");
        assertThat(Names.fold("ĐŨNG")).isEqualTo("dung");
        assertThat(Names.fold("đặng")).isEqualTo("dang");
    }

    @Test
    void collapses_whitespace() {
        assertThat(Names.fold("  Lê   Thị \t Hoa ")).isEqualTo("le thi hoa");
    }

    @Test
    void a_hundred_syllable_hangul_name_folds_to_more_than_200_characters() {
        // name_search is TEXT: NFD expands each syllable into up to three jamo
        String folded = Names.fold("\ud55c".repeat(100));
        assertThat(folded).hasSize(300);
        assertThat(folded.length()).isGreaterThan(200);
    }

    @Test
    void null_is_rejected() {
        assertThatThrownBy(() -> Names.fold(null)).isInstanceOf(NullPointerException.class);
    }
}
