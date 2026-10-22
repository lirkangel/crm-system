package com.crm.hotel.guest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestSearchKeyTest {

    @Test
    void strips_vietnamese_diacritics_and_lower_cases() {
        assertThat(GuestSearchKey.normalize("Nguyễn Văn An")).isEqualTo("nguyen van an");
    }

    @Test
    void maps_d_with_stroke_explicitly() {
        assertThat(GuestSearchKey.normalize("Đặng Thị Hồng")).isEqualTo("dang thi hong");
        assertThat(GuestSearchKey.normalize("đỗ")).isEqualTo("do");
    }

    @Test
    void collapses_whitespace_and_trims() {
        assertThat(GuestSearchKey.normalize("  TRẦN   Bảo ")).isEqualTo("tran bao");
        assertThat(GuestSearchKey.normalize("a\t\nb")).isEqualTo("a b");
    }

    @Test
    void precomposed_and_decomposed_input_normalize_alike() {
        assertThat(GuestSearchKey.normalize("ê")).isEqualTo(GuestSearchKey.normalize("ê")).isEqualTo("e");
    }

    @Test
    void keeps_digits_and_punctuation_and_handles_blank() {
        assertThat(GuestSearchKey.normalize("Lê Văn Tám +84 901-234")).isEqualTo("le van tam +84 901-234");
        assertThat(GuestSearchKey.normalize("   ")).isEmpty();
        assertThat(GuestSearchKey.normalize(null)).isEmpty();
    }
}
