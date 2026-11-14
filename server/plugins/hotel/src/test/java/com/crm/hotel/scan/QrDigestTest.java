package com.crm.hotel.scan;

import com.crm.hotel.stay.IdDocType;
import com.crm.hotel.stay.StayGuestRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QrDigestTest {

    private static final ScanFields SCAN = new ScanFields("CCCD", "079090123456", "NGUYEN VAN AN",
        LocalDate.of(1990, 5, 17), "VN", "12 Le Loi, Phu Hoi, Hue", "Nam", LocalDate.of(2021, 6, 10));

    private static StayGuestRequest request(String idNumber, String fullName, LocalDate dob, String address) {
        return new StayGuestRequest(IdDocType.CCCD, idNumber, fullName, dob, "VN", address, null, null, null);
    }

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
    void identical_values_give_no_change() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            request("079090123456", "NGUYEN VAN AN", LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue"))).isEmpty();
    }

    @Test
    void surrounding_spaces_are_not_a_change() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            request(" 079090123456 ", "NGUYEN VAN AN ", LocalDate.of(1990, 5, 17), " 12 Le Loi, Phu Hoi, Hue"))).isEmpty();
    }

    @Test
    void nfd_input_gives_no_change() {
        // the QR says the name precomposed (NFC); the guest's keyboard sent it decomposed (NFD)
        ScanFields scan = new ScanFields("CCCD", "079090123456", "NGUY\u1EC4N V\u0102N AN",
            LocalDate.of(1990, 5, 17), "VN", "12 Le Loi, Phu Hoi, Hue", "Nam", LocalDate.of(2021, 6, 10));
        String nfd = java.text.Normalizer.normalize("NGUY\u1EC4N V\u0102N AN", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("NGUY\u1EC4N V\u0102N AN");

        assertThat(QrDigest.changed(QrDigest.of(scan),
            request("079090123456", nfd, LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue"))).isEmpty();
        // and the digest of an NFD scan equals that of the NFC scan
        ScanFields decomposed = new ScanFields("CCCD", "079090123456", nfd,
            LocalDate.of(1990, 5, 17), "VN", "12 Le Loi, Phu Hoi, Hue", "Nam", LocalDate.of(2021, 6, 10));
        assertThat(QrDigest.of(decomposed)).isEqualTo(QrDigest.of(scan));
    }

    @Test
    void a_changed_name_gives_full_name() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest,
            request("079090123456", "Nguyen Van An", LocalDate.of(1990, 5, 17), "12 Le Loi, Phu Hoi, Hue")))
            .isEqualTo(List.of("fullName"));
    }

    @Test
    void several_changes_come_in_digest_order() {
        String digest = QrDigest.of(SCAN);

        assertThat(QrDigest.changed(digest, request("079090123457", "X", LocalDate.of(1991, 1, 1), null)))
            .containsExactly("idNumber", "fullName", "dateOfBirth", "address");
    }

    @Test
    void a_missing_digest_cannot_tell() {
        assertThat(QrDigest.changed(null, request("1", "X", LocalDate.of(1991, 1, 1), null))).isEmpty();
        assertThat(QrDigest.changed("", request("1", "X", LocalDate.of(1991, 1, 1), null))).isEmpty();
    }
}
