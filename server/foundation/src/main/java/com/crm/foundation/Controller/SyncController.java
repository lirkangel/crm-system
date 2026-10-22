package com.crm.foundation.Controller;

import com.crm.foundation.Config.OpenApiConfig;
import com.crm.foundation.DTO.ChangeEventResponse;
import com.crm.foundation.DTO.CommonResponse;
import com.crm.foundation.Security.CorePermissions;
import com.crm.foundation.Service.ChangeEventService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/sync")
@SecurityRequirement(name = OpenApiConfig.JWT_SECURITY_SCHEME)
public class SyncController {

    private final ChangeEventService changeEventService;

    @GetMapping("/changes")
    @PreAuthorize("hasAuthority('" + CorePermissions.SYNC_READ + "')")
    public ResponseEntity<CommonResponse<List<ChangeEventResponse>>> changes(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        List<ChangeEventResponse> changes = changeEventService.changesSince(since).stream()
            .map(ChangeEventResponse::from)
            .toList();
        return ResponseEntity.ok(
            CommonResponse.success("SYNC_CHANGES_OK", "Changes since " + since, changes));
    }
}
