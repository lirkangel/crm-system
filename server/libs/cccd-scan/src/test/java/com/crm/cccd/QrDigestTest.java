package com.crm.cccd;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QrDigestTest {

    private static Map<String, String> fields(String idNumber, String fullName, LocalDate dob, String address) {
        Map<String, String> fields = new HashMap<>();
        fields.put("idType", "CCCD");
        fields.put("idNumber", idNumber);
        fields.put("fullName", fullName);
        fields.put("dateOfBirth", dob == null ? null : dob.toString());
        fields.put("nationality", "VN");
        fields.put("address", address);
        return fields;
    }

    private static final Map<String, String> SCAN =
        fields("079090123456", "NGUYEN VAN AN", LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue");

    @Test
    void digest_names_the_six_fields_and_hides_their_values() {
        String digest = QrDigest.of(SCAN);

        assertThat(digest.split(";")).extracting(part -> part.substring(0, part.indexOf('=')))
            .containsExactly("idType", "idNumber", "fullName", "dateOfBirth", "nationality", "address");
        assertThat(digest).matches("(\\w+=[0-9a-f]{64};){5}\\w+=[0-9a-f]{64}")
            .doesNotContain("079090123456").doesNotContain("NGUYEN");
        assertThat(digest.length()).isLessThanOrEqualTo(600);
    }

    @Test
    void digest_format_is_stable_so_stored_digests_still_compare() {
        // stored in production by the hotel plugin: changing field names, order, hashing or date format breaks them
        assertThat(QrDigest.of(SCAN)).isEqualTo("idType=66240df0baa6728d68903c2eefdbb5dc55a44c1002affea9fec76b3ffef2e9c9;idNumber=27e1a56b2aff5f196ec34a28e4c0de1ded09f85055339ccffb1678138c8bdf58;fullName=6e7fa5cdab75299c883688406a9c3a31822c714a2221828efc9fab3129bb9cc1;dateOfBirth=7e9284d7145e31c8e2b24a07999ea040618cd0e8ccc53774dc98de36211c3b19;nationality=576123d7a7d57a6b3bc169802313fa3026181fe8b86121e5bee2602af0f6abc0;address=53b7cc2ddd94dfb2f95c577e88b499d8915935d3e881e9fa79158bd6e4be4052");
    }

    @Test
    void identical_values_give_no_change() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            fields("079090123456", "NGUYEN VAN AN", LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue"))).isEmpty();
    }

    @Test
    void surrounding_spaces_are_not_a_change() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            fields(" 079090123456 ", "NGUYEN VAN AN ", LocalDate.of(1990, 5, 17), " 12 Le Loi, Phu Hoi, Hue"))).isEmpty();
    }

    @Test
    void nfd_input_gives_no_change() {
        // the QR says the name precomposed (NFC); the guest's keyboard sent it decomposed (NFD)
        Map<String, String> scan = fields("079090123456", "NGUY\u1EC4N V\u0102N AN",
            LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue");
        String nfd = java.text.Normalizer.normalize("NGUY\u1EC4N V\u0102N AN", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("NGUY\u1EC4N V\u0102N AN");

        assertThat(QrDigest.changed(QrDigest.of(scan),
            fields("079090123456", nfd, LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue"))).isEmpty();
        // and the digest of an NFD scan equals that of the NFC scan
        Map<String, String> decomposed = fields("079090123456", nfd,
            LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue");
        assertThat(QrDigest.of(decomposed)).isEqualTo(QrDigest.of(scan));
    }

    @Test
    void a_changed_name_gives_full_name() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            fields("079090123456", "Nguyen Van An", LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue")))
            .isEqualTo(List.of("fullName"));
    }

    @Test
    void several_changes_come_in_digest_order() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest, fields("079090123457", "X", LocalDate.of(1991, 1, 1), null)))
            .containsExactly("idNumber", "fullName", "dateOfBirth", "address");
    }

    @Test
    void a_missing_digest_cannot_tell() {
        assertThat(QrDigest.changed(null, fields("1", "X", LocalDate.of(1991, 1, 1), null))).isEmpty();
        assertThat(QrDigest.changed("", fields("1", "X", LocalDate.of(1991, 1, 1), null))).isEmpty();
    }
}
