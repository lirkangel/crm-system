package com.crm.hotel.booking;

import com.crm.hotel.domain.Room;
import com.crm.hotel.domain.RoomType;
import com.crm.hotel.repo.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Search for free rooms over a stay. */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final RoomRepository rooms;

    /**
     * In-service rooms of active types that sleep {@code adults} and have no CONFIRMED reservation
     * overlapping {@code [checkIn, checkOut)}, grouped by room type (by code, then room number) in a
     * single query. Types too small for the party are omitted, not an error.
     */
    @Transactional(readOnly = true)
    public AvailabilityView search(LocalDate checkIn, LocalDate checkOut, int adults) {
        // Dates and party size >= 1 are checked here; capacity is a per-type filter below.
        StayPolicy.check(checkIn, checkOut, adults, Integer.MAX_VALUE, LocalDate.now(hotelClock));
        int nights = StayPolicy.nights(checkIn, checkOut);

        Map<UUID, List<AvailableRoom>> roomsByType = new LinkedHashMap<>();
        Map<UUID, RoomType> types = new LinkedHashMap<>();
        for (Object[] row : rooms.findAvailable(checkIn, checkOut, adults)) {
            Room room = (Room) row[0];
            RoomType type = (RoomType) row[1];
            types.putIfAbsent(type.getId(), type);
            roomsByType.computeIfAbsent(type.getId(), id -> new ArrayList<>())
                .add(new AvailableRoom(room.getId(), room.getNumber(), room.getFloor()));
        }

        List<RoomTypeAvailability> result = new ArrayList<>();
        types.forEach((id, type) -> result.add(new RoomTypeAvailability(
            id, type.getCode(), type.getNameI18n(), type.getNightlyRateGrossVnd(),
            StayPolicy.totalGross(type.getNightlyRateGrossVnd(), nights), roomsByType.get(id))));
        return new AvailabilityView(checkIn, checkOut, nights, result);
    }
}
