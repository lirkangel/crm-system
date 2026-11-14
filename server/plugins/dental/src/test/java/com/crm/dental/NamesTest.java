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
    void null_is_rejected() {
        assertThatThrownBy(() -> Names.fold(null)).isInstanceOf(NullPointerException.class);
    }
}
