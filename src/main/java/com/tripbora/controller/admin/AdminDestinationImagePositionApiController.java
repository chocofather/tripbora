package com.tripbora.controller.admin;

import com.tripbora.service.destination.DestinationImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.math.BigDecimal;

/** ADMIN 및 CSRF는 기존 /admin/** SecurityFilterChain 정책을 따른다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/api/destinations")
public class AdminDestinationImagePositionApiController {
    private final DestinationImageService images;

    @PostMapping("/{id}/images/{imageId}/card-position")
    public ResponseEntity<?> save(@PathVariable Long id, @PathVariable Long imageId,
                                  @RequestBody(required = false) PositionRequest request) {
        try {
            Integer x = request == null || request.x() == null ? null : request.x().intValueExact();
            Integer y = request == null || request.y() == null ? null : request.y().intValueExact();
            images.saveCardPosition(id, imageId, x, y);
            return ResponseEntity.ok(Map.of("x", x, "y", y));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (ArithmeticException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", "가로·세로 위치는 0~100 사이의 정수여야 합니다."));
        }
    }

    public record PositionRequest(BigDecimal x, BigDecimal y) {}
}
