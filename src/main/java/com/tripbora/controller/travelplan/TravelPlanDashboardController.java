package com.tripbora.controller.travelplan;

import com.tripbora.dto.TravelPlanDashboardDto;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/travel-plans/{planId:\\d+}/dashboard")
public class TravelPlanDashboardController {
    private final TravelPlanDashboardService service;

    @GetMapping
    public TravelPlanDashboardDto read(@PathVariable Long planId, @AuthenticationPrincipal CustomUserDetails user) {
        return service.read(userId(user), planId);
    }

    @PostMapping("/checklist")
    public TravelPlanDashboardDto addChecklist(@PathVariable Long planId,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.ChecklistCreate form) {
        return service.addChecklist(userId(user), planId, form.title());
    }

    @PostMapping("/checklist/{id:\\d+}/update")
    public TravelPlanDashboardDto updateChecklist(@PathVariable Long planId, @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.ChecklistUpdate form) {
        return service.updateChecklist(userId(user), planId, id, form.title(), form.completed(), form.version());
    }

    @PostMapping("/checklist/{id:\\d+}/delete")
    public TravelPlanDashboardDto deleteChecklist(@PathVariable Long planId, @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.Version form) {
        return service.deleteChecklist(userId(user), planId, id, form.version());
    }

    @PostMapping("/notice")
    public TravelPlanDashboardDto notice(@PathVariable Long planId,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.NoticeSave form) {
        return service.saveNotice(userId(user), planId, form.content(), form.version());
    }

    @PostMapping("/expenses")
    public TravelPlanDashboardDto addExpense(@PathVariable Long planId,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.ExpenseCreate form) {
        return service.addExpense(userId(user), planId, form.title(), form.amount(), form.memo());
    }

    @PostMapping("/expenses/{id:\\d+}/delete")
    public TravelPlanDashboardDto deleteExpense(@PathVariable Long planId, @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user, @RequestBody TravelPlanDashboardDto.Version form) {
        return service.deleteExpense(userId(user), planId, id, form.version());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failed(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformed() {
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식이 올바르지 않습니다."));
    }

    private static Long userId(CustomUserDetails user) {
        return user == null ? null : user.getId();
    }
}
