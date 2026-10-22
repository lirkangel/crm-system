package com.crm.hotel.guest;

import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Guest;
import com.crm.hotel.repo.GuestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Guests: accent-insensitive search and creation (a phone may be shared, so it is not a key). */
@Service
@RequiredArgsConstructor
public class GuestService {

    /** Upper bound of a search result. */
    static final int MAX_RESULTS = 50;

    private final GuestRepository guests;
    private final ChangeRecorder changes;

    /** Up to {@value #MAX_RESULTS} guests whose name or phone contains {@code q}, by full name then id. */
    @Transactional(readOnly = true)
    public List<GuestView> search(String q) {
        String key = GuestSearchKey.normalize(q);
        if (key.isEmpty()) {
            throw HotelErrors.invalidQuery("Search text q must not be blank");
        }
        return guests.search("%" + escapeLike(key) + "%", PageRequest.of(0, MAX_RESULTS))
            .stream().map(GuestView::of).toList();
    }

    @Transactional
    public GuestView create(GuestRequest request) {
        Guest guest = new Guest();
        guest.setFullName(request.fullName().trim());
        guest.setPhone(request.phone().trim());
        guest.setEmail(request.email() == null || request.email().isBlank() ? null : request.email().trim());
        guest.setSearchKey(GuestSearchKey.of(guest.getFullName(), guest.getPhone()));
        Guest saved = guests.saveAndFlush(guest);
        changes.record("Guest", saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        return GuestView.of(saved);
    }

    /** Makes {@code %}, {@code _} and the escape character {@code \} match literally in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
