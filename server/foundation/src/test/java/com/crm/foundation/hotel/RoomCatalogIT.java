package com.crm.foundation.hotel;

import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Room types and rooms over HTTP: CRUD, optimistic locking, duplicate keys, change events, permissions. */
class RoomCatalogIT extends HotelITSupport {

    private static final String READER_USERNAME = "hotel_it_reader";
    private static final String READER_PASSWORD = "correct horse battery staple";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void create_and_list_room_types() {
        String token = adminToken();
        String code = uniqueCode("DBL");

        ResponseEntity<String> created = post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 1_200_000, true));

        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        JsonNode type = data(created);
        assertThat(type.path("id").asText()).isNotBlank();
        assertThat(type.path("code").asText()).isEqualTo(code);
        assertThat(type.path("maxGuests").asInt()).isEqualTo(2);
        assertThat(type.path("nightlyRateGrossVnd").asLong()).isEqualTo(1_200_000L);
        assertThat(type.path("active").asBoolean()).isTrue();
        assertThat(type.path("version").asLong()).isZero();
        // jsonb round trip, including non-ASCII text
        assertThat(type.path("nameI18n").path("vi").asText()).isEqualTo("Phòng đôi " + code);
        assertThat(type.path("nameI18n").path("en").asText()).isEqualTo("Double room " + code);

        ResponseEntity<String> listed = get(HOTEL_API + "/room-types", token);

        assertThat(listed.getStatusCode().value()).as(listed.getBody()).isEqualTo(200);
        JsonNode match = null;
        for (JsonNode candidate : data(listed)) {
            if (candidate.path("code").asText().equals(code)) {
                match = candidate;
            }
        }
        assertThat(match).as("created room type is listed").isNotNull();
        assertThat(match.path("id").asText()).isEqualTo(type.path("id").asText());
        assertThat(match.path("nameI18n").path("vi").asText()).isEqualTo("Phòng đôi " + code);
        assertThat(match.path("nameI18n").path("en").asText()).isEqualTo("Double room " + code);
    }

    @Test
    void duplicate_room_type_code_is_409() {
        String token = adminToken();
        String code = uniqueCode("DUP");
        assertThat(post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 900_000, true)).getStatusCode().value())
            .isEqualTo(201);

        ResponseEntity<String> duplicate = post(HOTEL_API + "/room-types", token, roomTypeBody(code, 3, 950_000, true));

        assertThat(duplicate.getStatusCode().value()).as(duplicate.getBody()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asText()).isEqualTo("HOTEL_DUPLICATE");
    }

    @Test
    void create_room_for_type() {
        String token = adminToken();
        UUID typeId = createRoomType(token, uniqueCode("RFT"), 2);
        String number = uniqueNumber();

        ResponseEntity<String> created = post(HOTEL_API + "/rooms", token, roomBody(number, typeId, 3, true));

        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        JsonNode room = data(created);
        assertThat(room.path("number").asText()).isEqualTo(number);
        assertThat(room.path("roomTypeId").asText()).isEqualTo(typeId.toString());
        assertThat(room.path("floor").asInt()).isEqualTo(3);
        assertThat(room.path("inService").asBoolean()).isTrue();
        assertThat(room.path("version").asLong()).isZero();

        ResponseEntity<String> listed = get(HOTEL_API + "/rooms", token);

        assertThat(listed.getStatusCode().value()).as(listed.getBody()).isEqualTo(200);
        List<String> numbers = new ArrayList<>();
        data(listed).forEach(candidate -> numbers.add(candidate.path("number").asText()));
        assertThat(numbers).contains(number);

        // same room number again, and a room of an unknown type
        ResponseEntity<String> duplicate = post(HOTEL_API + "/rooms", token, roomBody(number, typeId, 3, true));
        assertThat(duplicate.getStatusCode().value()).as(duplicate.getBody()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asText()).isEqualTo("HOTEL_DUPLICATE");

        ResponseEntity<String> unknownType =
            post(HOTEL_API + "/rooms", token, roomBody(uniqueNumber(), UUID.randomUUID(), 1, true));
        assertThat(unknownType.getStatusCode().value()).as(unknownType.getBody()).isEqualTo(404);
        assertThat(json(unknownType).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void update_with_current_if_match_bumps_version() {
        String token = adminToken();
        String code = uniqueCode("UPD");
        JsonNode type = data(post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 1_000_000, true)));
        String typeId = type.path("id").asText();

        ResponseEntity<String> updated = put(
            HOTEL_API + "/room-types/" + typeId, token, roomTypeBody(code, 4, 1_500_000, false), "0");

        assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
        assertThat(data(updated).path("version").asLong()).isEqualTo(1L);
        assertThat(data(updated).path("maxGuests").asInt()).isEqualTo(4);
        assertThat(data(updated).path("nightlyRateGrossVnd").asLong()).isEqualTo(1_500_000L);
        assertThat(data(updated).path("active").asBoolean()).isFalse();

        // quoted If-Match is tolerated; the version is now 1
        ResponseEntity<String> again = put(
            HOTEL_API + "/room-types/" + typeId, token, roomTypeBody(code, 5, 1_500_000, false), "\"1\"");
        assertThat(again.getStatusCode().value()).as(again.getBody()).isEqualTo(200);
        assertThat(data(again).path("version").asLong()).isEqualTo(2L);

        // rooms update the same way
        UUID roomTypeId = createRoomType(token, uniqueCode("UPR"), 2);
        JsonNode room = data(post(HOTEL_API + "/rooms", token, roomBody(uniqueNumber(), roomTypeId, 1, true)));
        ResponseEntity<String> roomUpdated = put(
            HOTEL_API + "/rooms/" + room.path("id").asText(), token,
            roomBody(room.path("number").asText(), roomTypeId, 2, false), "0");
        assertThat(roomUpdated.getStatusCode().value()).as(roomUpdated.getBody()).isEqualTo(200);
        assertThat(data(roomUpdated).path("version").asLong()).isEqualTo(1L);
        assertThat(data(roomUpdated).path("floor").asInt()).isEqualTo(2);
        assertThat(data(roomUpdated).path("inService").asBoolean()).isFalse();

        // renaming onto a taken code is a duplicate, not a server error
        String otherCode = uniqueCode("OTH");
        createRoomType(token, otherCode, 2);
        ResponseEntity<String> clash = put(
            HOTEL_API + "/room-types/" + typeId, token, roomTypeBody(otherCode, 5, 1_500_000, false), "2");
        assertThat(clash.getStatusCode().value()).as(clash.getBody()).isEqualTo(409);
        assertThat(json(clash).path("code").asText()).isEqualTo("HOTEL_DUPLICATE");

        // unknown id
        ResponseEntity<String> missing = put(
            HOTEL_API + "/room-types/" + UUID.randomUUID(), token, roomTypeBody(uniqueCode("NF"), 2, 1, true), "0");
        assertThat(missing.getStatusCode().value()).as(missing.getBody()).isEqualTo(404);
        assertThat(json(missing).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void stale_if_match_is_conflict() {
        String token = adminToken();
        String code = uniqueCode("STL");
        JsonNode type = data(post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 1_000_000, true)));
        String url = HOTEL_API + "/room-types/" + type.path("id").asText();
        assertThat(put(url, token, roomTypeBody(code, 3, 1_000_000, true), "0").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> stale = put(url, token, roomTypeBody(code, 4, 1_000_000, true), "0");

        assertThat(stale.getStatusCode().value()).as(stale.getBody()).isEqualTo(409);
        assertThat(json(stale).path("code").asText()).isEqualTo("CONFLICT");
        // the losing write changed nothing
        Integer maxGuests = jdbc.queryForObject(
            "SELECT max_guests FROM hotel.room_type WHERE id = ?", Integer.class, UUID.fromString(type.path("id").asText()));
        assertThat(maxGuests).isEqualTo(3);
    }

    @Test
    void missing_if_match_is_428() {
        String token = adminToken();
        String code = uniqueCode("NIM");
        JsonNode type = data(post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 1_000_000, true)));

        ResponseEntity<String> response = exchange(
            HttpMethod.PUT, HOTEL_API + "/room-types/" + type.path("id").asText(), token,
            roomTypeBody(code, 3, 1_000_000, true));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(428);
        assertThat(json(response).path("code").asText()).isEqualTo("HOTEL_VERSION_REQUIRED");
    }

    @Test
    void writes_emit_change_events() {
        String token = adminToken();
        String code = uniqueCode("CHG");
        JsonNode type = data(post(HOTEL_API + "/room-types", token, roomTypeBody(code, 2, 1_000_000, true)));
        UUID typeId = UUID.fromString(type.path("id").asText());
        put(HOTEL_API + "/room-types/" + typeId, token, roomTypeBody(code, 3, 1_000_000, true), "0");
        JsonNode room = data(post(HOTEL_API + "/rooms", token, roomBody(uniqueNumber(), typeId, 1, true)));
        UUID roomId = UUID.fromString(room.path("id").asText());
        put(HOTEL_API + "/rooms/" + roomId, token, roomBody(room.path("number").asText(), typeId, 2, true), "0");

        assertThat(changes(typeId)).containsExactly("RoomType:CREATE:0", "RoomType:UPDATE:1");
        assertThat(changes(roomId)).containsExactly("Room:CREATE:0", "Room:UPDATE:1");
    }

    @Test
    void reader_without_manage_gets_403_on_post() {
        adminToken();
        String reader = readerToken();

        ResponseEntity<String> list = get(HOTEL_API + "/room-types", reader);
        ResponseEntity<String> forbiddenType =
            post(HOTEL_API + "/room-types", reader, roomTypeBody(uniqueCode("FRB"), 2, 1_000_000, true));
        ResponseEntity<String> forbiddenRoom =
            post(HOTEL_API + "/rooms", reader, roomBody(uniqueNumber(), UUID.randomUUID(), 1, true));
        ResponseEntity<String> forbiddenPut = put(
            HOTEL_API + "/room-types/" + UUID.randomUUID(), reader, roomTypeBody(uniqueCode("FRB"), 2, 1, true), "0");
        ResponseEntity<String> anonymous = get(HOTEL_API + "/room-types", null);

        assertThat(list.getStatusCode().value()).as(list.getBody()).isEqualTo(200);
        assertThat(forbiddenType.getStatusCode().value()).as(forbiddenType.getBody()).isEqualTo(403);
        assertThat(forbiddenRoom.getStatusCode().value()).as(forbiddenRoom.getBody()).isEqualTo(403);
        assertThat(forbiddenPut.getStatusCode().value()).as(forbiddenPut.getBody()).isEqualTo(403);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalid_room_type_is_400_with_no_row() {
        String token = adminToken();
        String code = uniqueCode("BAD");

        ResponseEntity<String> response = post(HOTEL_API + "/room-types", token, roomTypeBody(code, 0, -1, true));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel.room_type WHERE code = ?", Integer.class, code)).isZero();
    }

    private UUID createRoomType(String token, String code, int maxGuests) {
        ResponseEntity<String> created = post(HOTEL_API + "/room-types", token, roomTypeBody(code, maxGuests, 1_000_000, true));
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }

    /** {@code Type:OP:version} of every hotel change event of one entity, oldest first. */
    private List<String> changes(UUID entityId) {
        return jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? ORDER BY version",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, entityId);
    }

    private static Map<String, Object> roomTypeBody(String code, int maxGuests, long rate, boolean active) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("vi", "Phòng đôi " + code);
        names.put("en", "Double room " + code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("nameI18n", names);
        body.put("maxGuests", maxGuests);
        body.put("nightlyRateGrossVnd", rate);
        body.put("active", active);
        return body;
    }

    private static Map<String, Object> roomBody(String number, UUID roomTypeId, Integer floor, boolean inService) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number);
        body.put("roomTypeId", roomTypeId);
        body.put("floor", floor);
        body.put("inService", inService);
        return body;
    }

    /** At most 32 characters (the column size), unique per call. */
    private static String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** At most 16 characters (the column size), unique per call. */
    private static String uniqueNumber() {
        return "R" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Token of a user whose only hotel permission is {@code hotel.rooms.read}. */
    private String readerToken() {
        jdbc.update("""
            INSERT INTO roles (id, code, name_i18n, builtin)
            VALUES (gen_random_uuid(), 'hotel_it_reader', '{"en":"Hotel IT reader"}'::jsonb, FALSE)
            ON CONFLICT (code) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO role_permissions (role_id, permission_id)
            SELECT r.id, p.id FROM roles r, permissions p
            WHERE r.code = 'hotel_it_reader' AND p.key = 'hotel.rooms.read'
            ON CONFLICT DO NOTHING
            """);
        if (userRepository.findByUsername(READER_USERNAME).isEmpty()) {
            User reader = new User();
            reader.setUsername(READER_USERNAME);
            reader.setEmail(READER_USERNAME + "@example.com");
            reader.setPassword(BCrypt.hashpw(READER_PASSWORD, BCrypt.gensalt()));
            reader.getRoles().add(roleRepository.findByCode("hotel_it_reader").orElseThrow());
            userRepository.save(reader);
        }
        ResponseEntity<String> login = rest.postForEntity(
            "/api/v1/auth/login", Map.of("username", READER_USERNAME, "password", READER_PASSWORD), String.class);
        assertThat(login.getStatusCode().value()).as(login.getBody()).isEqualTo(200);
        return data(login).path("accessToken").asText();
    }
}
