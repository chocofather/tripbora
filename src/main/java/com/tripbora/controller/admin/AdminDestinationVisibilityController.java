package com.tripbora.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbora.service.destination.DestinationNotFoundException;
import com.tripbora.service.destination.DestinationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/destinations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDestinationVisibilityController {
    private final DestinationService destinationService;

    public record VisibilityResponse(boolean publicVisible) {}

    @PostMapping("/{id}/visibility")
    public VisibilityResponse changeVisibility(@PathVariable Long id, @RequestBody JsonNode body) {
        if (id <= 0 || !body.isObject() || body.size() != 1
                || !body.has("publicVisible") || !body.get("publicVisible").isBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공개 상태 값이 올바르지 않습니다.");
        }
        try {
            return new VisibilityResponse(destinationService.setPublicVisible(id, body.get("publicVisible").booleanValue()));
        } catch (DestinationNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행지를 찾을 수 없습니다.");
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "공개 상태를 저장하지 못했습니다.");
        }
    }
}
