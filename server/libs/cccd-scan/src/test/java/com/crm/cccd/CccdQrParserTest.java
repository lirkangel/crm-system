package com.crm.cccd;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CccdQrParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final String VALID =
        "079203012345|285123456|Nguyễn Văn An|15081990|Nam|12 Lê Lợi, Bến Nghé, Quận 1, TP.HCM|20042021";

    @Test
    void parses_valid_payload() {
        CccdData data = CccdQrParser.parse(VALID, TODAY).orElseThrow();

        assertThat(data.idNumber()).isEqualTo("079203012345");
        assertThat(data.oldIdNumber()).isEqualTo("285123456");
        assertThat(data.fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(data.dateOfBirth()).isEqualTo(LocalDate.of(1990, 8, 15));
        assertThat(data.sex()).isEqualTo("Nam");
        assertThat(data.address()).isEqualTo("12 Lê Lợi, Bến Nghé, Quận 1, TP.HCM");
        assertThat(data.issueDate()).isEqualTo(LocalDate.of(2021, 4, 20));
    }

    @Test
    void trims_fields() {
        String text = " 079203012345 | 285123456 | Nguyễn Văn An |15081990| Nữ | 12 Lê Lợi |20042021 ";
        CccdData data = CccdQrParser.parse(text, TODAY).orElseThrow();

        assertThat(data.idNumber()).isEqualTo("079203012345");
        assertThat(data.fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(data.sex()).isEqualTo("Nữ");
    }

    @Test
    void empty_old_id_becomes_null() {
        String text = VALID.replace("|285123456|", "||");

        assertThat(CccdQrParser.parse(text, TODAY).orElseThrow().oldIdNumber()).isNull();
    }

    @Test
    void rejects_wrong_field_count() {
        assertThat(CccdQrParser.parse("079203012345|285123456|Nguyễn Văn An|15081990|Nam|addr", TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID + "|extra", TODAY)).isEmpty();
        assertThat(CccdQrParser.parse("", TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(null, TODAY)).isEmpty();
    }

    @Test
    void rejects_non_12_digit_id() {
        assertThat(CccdQrParser.parse(VALID.replace("079203012345", "07920301234"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("079203012345", "0792030123456"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("079203012345", "07920301234X"), TODAY)).isEmpty();
    }

    @Test
    void rejects_bad_or_future_dates() {
        assertThat(CccdQrParser.parse(VALID.replace("15081990", "32131990"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("15081990", "abcdefgh"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("15081990", "01012027"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("20042021", "08112026"), TODAY)).isEmpty();
        assertThat(CccdQrParser.parse(VALID.replace("20042021", "01011980"), TODAY)).isEmpty();
        // today itself is allowed
        assertThat(CccdQrParser.parse(VALID.replace("20042021", "07112026"), TODAY)).isPresent();
    }

    @Test
    void rejects_blank_name() {
        assertThat(CccdQrParser.parse(VALID.replace("Nguyễn Văn An", "   "), TODAY)).isEmpty();
    }

    @Test
    void rejects_url_qr() {
        Optional<CccdData> parsed = CccdQrParser.parse("https://example.com/vcard?x=1", TODAY);

        assertThat(parsed).isEmpty();
    }

    @Test
    void rejects_trailing_pipe() {
        assertThat(CccdQrParser.parse(VALID + "|", TODAY)).isEmpty();
    }

    @Test
    void toString_is_redacted() {
        String text = CccdQrParser.parse(VALID, TODAY).orElseThrow().toString();

        assertThat(text).doesNotContain("079203012345", "Nguyễn", "Lê Lợi", "285123456", "1990");
    }
}
