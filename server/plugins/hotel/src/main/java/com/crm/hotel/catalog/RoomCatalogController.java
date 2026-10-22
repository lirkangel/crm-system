package com.crm.hotel.catalog;

import com.crm.foundation.DTO.CommonResponse;
import com.crm.hotel.HotelPermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Served under {@code /api/v1/plugins/hotel}. */
@RestController
@RequiredArgsConstructor
public class RoomCatalogController {

    private static final String IF_MATCH = "If-Match";

    private final RoomCatalogService catalog;

    @GetMapping("/room-types")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_READ + "')")
    public CommonResponse<List<RoomTypeView>> listRoomTypes() {
        return CommonResponse.success(catalog.listRoomTypes());
    }

    @PostMapping("/room-types")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_MANAGE + "')")
    public ResponseEntity<CommonResponse<RoomTypeView>> createRoomType(@Valid @RequestBody RoomTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(catalog.createRoomType(request)));
    }

    /** The {@code If-Match} header is optional here so a missing one is the hotel's 428, not a generic 400. */
    @PutMapping("/room-types/{id}")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_MANAGE + "')")
    public CommonResponse<RoomTypeView> updateRoomType(
            @PathVariable("id") UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RoomTypeRequest request) {
        return CommonResponse.success(catalog.updateRoomType(id, request, ifMatch));
    }

    @GetMapping("/rooms")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_READ + "')")
    public CommonResponse<List<RoomView>> listRooms() {
        return CommonResponse.success(catalog.listRooms());
    }

    @PostMapping("/rooms")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_MANAGE + "')")
    public ResponseEntity<CommonResponse<RoomView>> createRoom(@Valid @RequestBody RoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(catalog.createRoom(request)));
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('" + HotelPermissions.ROOMS_MANAGE + "')")
    public CommonResponse<RoomView> updateRoom(
            @PathVariable("id") UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RoomRequest request) {
        return CommonResponse.success(catalog.updateRoom(id, request, ifMatch));
    }
}
