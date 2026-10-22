package com.crm.foundation.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Guests over HTTP: accent-insensitive search, shared phones, validation, change events. */
class GuestIT extends HotelITSupport {

    @Test
    void search_ignores_accents() {
        String token = adminToken();
        String tag = tag();
        createGuest(token, "Nguyễn Văn An " + tag, phone());
        createGuest(token, "Đặng Thị Hồng " + tag, phone());

        assertThat(searchNames(token, "nguyen van an " + tag)).containsExactly("Nguyễn Văn An " + tag);
        assertThat(searchNames(token, "NGUYỄN VĂN AN " + tag)).containsExactly("Nguyễn Văn An " + tag);
        assertThat(searchNames(token, "dang thi hong " + tag)).containsExactly("Đặng Thị Hồng " + tag);
        assertThat(searchNames(token, "Đặng  Thị")).contains("Đặng Thị Hồng " + tag);
        assertThat(searchNames(token, "zzz-" + tag)).isEmpty();
    }

    @Test
    void search_by_phone_fragment() {
        String token = adminToken();
        String digits = String.valueOf(10_000_000 + (long) (Math.random() * 89_000_000));
        createGuest(token, "Phone Guest " + tag(), "09" + digits);

        List<String> found = searchNames(token, digits.substring(2, 7));

        assertThat(found).anyMatch(name -> name.startsWith("Phone Guest"));
    }

    @Test
    void search_matches_like_wildcards_literally() {
        String token = adminToken();
        String tag = tag();
        createGuest(token, "Percent 100%" + tag, phone());
        createGuest(token, "Percent 100x" + tag, phone());
        createGuest(token, "Under_score" + tag, phone());
        createGuest(token, "UnderXscore" + tag, phone());

        assertThat(searchNames(token, "100%" + tag)).containsExactly("Percent 100%" + tag);
        assertThat(searchNames(token, "under_score" + tag)).containsExactly("Under_score" + tag);
        assertThat(searchNames(token, "\\")).isEmpty();
    }

    @Test
    void search_is_capped_at_50_and_ordered_by_name() {
        String token = adminToken();
        String tag = tag();
        for (int i = 0; i < 55; i++) {
            createGuest(token, String.format("Cap %s %02d", tag, i), phone());
        }

        List<String> names = searchNames(token, "cap " + tag);

        assertThat(names).hasSize(50);
        assertThat(names).isSorted();
        assertThat(names.get(0)).isEqualTo("Cap " + tag + " 00");
    }

    @Test
    void same_phone_creates_two_guests() {
        String token = adminToken();
        String phone = phone();

        ResponseEntity<String> first = post(HOTEL_API + "/guests", token, guestBody("Family One " + tag(), phone, null));
        ResponseEntity<String> second = post(HOTEL_API + "/guests", token, guestBody("Family Two " + tag(), phone, "two@example.com"));

        assertThat(first.getStatusCode().value()).as(first.getBody()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(201);
        assertThat(data(first).path("id").asText()).isNotEqualTo(data(second).path("id").asText());
        assertThat(data(second).path("email").asText()).isEqualTo("two@example.com");
        assertThat(data(second).path("phone").asText()).isEqualTo(phone);
        assertThat(data(second).path("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.guest WHERE phone = ?", Integer.class, phone)).isEqualTo(2);
    }

    @Test
    void blank_query_is_400() {
        String token = adminToken();

        for (String path : List.of("/guests", "/guests?q=", "/guests?q=  ", "/guests?q=\u0300")) {
            ResponseEntity<String> response = get(HOTEL_API + path, token);
            assertThat(response.getStatusCode().value()).as(path + " " + response.getBody()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_INVALID_QUERY");
        }
    }

    @Test
    void create_emits_change_event() {
        String token = adminToken();

        JsonNode guest = createGuest(token, "Event Guest " + tag(), phone());

        UUID id = UUID.fromString(guest.path("id").asText());
        List<String> events = jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? ORDER BY version",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, id);
        assertThat(events).containsExactly("Guest:CREATE:0");
    }

    @Test
    void invalid_guest_is_400() {
        String token = adminToken();

        ResponseEntity<String> noName = post(HOTEL_API + "/guests", token, guestBody(" ", "0901", null));
        ResponseEntity<String> noPhone = post(HOTEL_API + "/guests", token, guestBody("No Phone", "", null));
        ResponseEntity<String> anonymous = get(HOTEL_API + "/guests?q=x", null);

        assertThat(noName.getStatusCode().value()).as(noName.getBody()).isEqualTo(400);
        assertThat(json(noName).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(noPhone.getStatusCode().value()).as(noPhone.getBody()).isEqualTo(400);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    }

    private JsonNode createGuest(String token, String fullName, String phone) {
        ResponseEntity<String> created = post(HOTEL_API + "/guests", token, guestBody(fullName, phone, null));
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return data(created);
    }

    /** Names found by {@code GET /guests?q=}; {@code q} is sent raw and encoded by the HTTP client. */
    private List<String> searchNames(String token, String q) {
        ResponseEntity<String> response = get(HOTEL_API + "/guests?q=" + q, token);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        data(response).forEach(guest -> names.add(guest.path("fullName").asText()));
        return names;
    }

    private static Map<String, Object> guestBody(String fullName, String phone, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("phone", phone);
        body.put("email", email);
        return body;
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String phone() {
        return "09" + (10_000_000 + (long) (Math.random() * 89_000_000));
    }
}
