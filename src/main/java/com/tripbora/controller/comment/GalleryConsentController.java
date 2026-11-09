package com.tripbora.controller.comment;

import com.tripbora.dto.GalleryConsentState;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.policy.GalleryConsentService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mypage/gallery-consent")
public class GalleryConsentController {
    private final GalleryConsentService service;

    @GetMapping
    public GalleryConsentState state(@AuthenticationPrincipal CustomUserDetails user, HttpSession session) {
        id(user);
        throw retired();
    }

    @PutMapping
    public GalleryConsentState decide(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Decision request, HttpSession session) {
        Long userId = id(user);
        boolean legacyWithdrawal = request.action() == null && Boolean.FALSE.equals(request.agreed());
        boolean explicitWithdrawal = "WITHDRAW".equals(request.action()) && request.agreed() == null;
        if (!legacyWithdrawal && !explicitWithdrawal) throw retired();
        if (request.version() == null || request.version().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인한 정책 버전이 필요합니다.");
        // 과거 선택 동의의 철회 권리만 유지하며 새 업로드 약관 사진에는 영향을 주지 않는다.
        if (legacyWithdrawal) return service.decide(userId, false, request.version());
        return service.decide(userId, "WITHDRAW", request.version(), request.policyVersionId());
    }

    private ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE, "사진 활용 선택 동의는 종료되었습니다. 사진 업로드 시 업로드 약관이 적용됩니다.");
    }

    private Long id(CustomUserDetails user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.getId();
    }

    public record Decision(Boolean agreed, String version, String action, Long policyVersionId, String context) {
        public Decision(Boolean agreed, String version) { this(agreed, version, null, null, null); }
    }
}
