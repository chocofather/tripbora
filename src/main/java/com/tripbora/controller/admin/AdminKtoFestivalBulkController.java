package com.tripbora.controller.admin;

import com.tripbora.dto.kto.*;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.kto.*;
import com.tripbora.service.travelinfo.FestivalValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/kto/festivals/bulk")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminKtoFestivalBulkController {
    private final KtoFestivalBulkPreparationService preparation;
    private final KtoFestivalBulkJobService jobs;

    @PostMapping("/preview")
    public KtoFestivalBulkPreview preview(@Valid @RequestBody KtoFestivalBulkPreviewRequest request,
                                         @AuthenticationPrincipal CustomUserDetails user) {
        return preparation.preview(request, owner(user));
    }

    @PostMapping("/start")
    public KtoFestivalBulkJob start(@Valid @RequestBody KtoFestivalBulkStartRequest request,
                                   @AuthenticationPrincipal CustomUserDetails user) {
        return jobs.start(request, owner(user));
    }

    @GetMapping("/jobs/{id}")
    public KtoFestivalBulkJob status(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails user) {
        return jobs.get(id, owner(user));
    }

    @ExceptionHandler(FestivalValidationException.class)
    ResponseEntity<ErrorMessage> validation(FestivalValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorMessage(exception.getMessage()));
    }

    @ExceptionHandler(KtoTourApiException.class)
    ResponseEntity<ErrorMessage> upstream(KtoTourApiException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorMessage(exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorMessage> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorMessage("등록 여부를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorMessage> statusError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorMessage(exception.getReason()));
    }

    private Long owner(CustomUserDetails user) {
        if (user == null || user.getId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 정보를 확인할 수 없습니다.");
        return user.getId();
    }
    private record ErrorMessage(String message) {}
}
