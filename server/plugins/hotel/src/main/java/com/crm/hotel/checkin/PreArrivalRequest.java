package com.crm.hotel.checkin;

import com.crm.hotel.stay.StayGuestRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** One adult's identity, sent by the guest through their link, with their consent to it being kept. */
public record PreArrivalRequest(@NotNull @Valid StayGuestRequest identity, boolean consent) {
}
