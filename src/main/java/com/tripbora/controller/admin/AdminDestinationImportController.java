package com.tripbora.controller.admin;

import com.tripbora.dto.destinationimport.DestinationImportPreview;
import com.tripbora.dto.destinationimport.DestinationImportResult;
import com.tripbora.dto.destinationimport.DestinationUpdatePreview;
import com.tripbora.service.destinationimport.DestinationImportUpdateService;
import jakarta.servlet.http.HttpSession;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.destinationimport.DestinationImportMasterExportService;
import com.tripbora.service.destinationimport.DestinationImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 여행지 JSON 일괄등록 API. 미리보기는 저장하지 않고, 등록은 여행지 한 건씩 받는다.
 * 화면은 선택한 여행지마다 등록 요청을 차례로 보내 진행 상태를 표시한다.
 */
@RestController
@RequestMapping("/admin/api/destinations/import-json")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminDestinationImportController {

    private final DestinationImportService importService;
    private final DestinationImportMasterExportService masterExportService;
    private final DestinationImportUpdateService updateService;

    @PostMapping("/update/preview")
    public ResponseEntity<?> updatePreview(@RequestBody DestinationUpdatePreview.Request request,
                                           HttpSession session,
                                           @RequestHeader(value = "X-Import-Request-Id", required = false) String requestId,
                                           @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("로그인 정보를 확인할 수 없습니다."));
        return traced("preview", requestId, () -> updateService.preview(request, session, userDetails.getId()));
    }

    @PostMapping("/update/apply")
    public ResponseEntity<?> applyUpdate(@RequestBody DestinationUpdatePreview.ApplyRequest request,
                                         HttpSession session,
                                         @RequestHeader(value = "X-Import-Request-Id", required = false) String requestId,
                                         @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("로그인 정보를 확인할 수 없습니다."));
        return traced("apply", requestId, () -> updateService.apply(request, session, userDetails.getId()));
    }

    /** Read-only receipt lookup. The token stays in the POST body rather than URL/access logs. */
    @PostMapping("/update/status")
    public ResponseEntity<?> updateStatus(@RequestBody DestinationUpdatePreview.StatusRequest request,
                                          HttpSession session,
                                          @RequestHeader(value = "X-Import-Request-Id", required = false) String requestId,
                                          @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("로그인 정보를 확인할 수 없습니다."));
        return traced("status", requestId, () -> updateService.applyStatus(request.token(), session, userDetails.getId()));
    }

    private ResponseEntity<?> traced(String operation, String requestId, Supplier<?> action) {
        String trace = requestId != null && requestId.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}") ? requestId : UUID.randomUUID().toString();
        long started = System.nanoTime();
        String previous = MDC.get("destinationImportRequest");
        MDC.put("destinationImportRequest", trace);
        log.info("destination JSON request started operation={} requestId={}", operation, trace);
        try {
            Object response = action.get();
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Import-Request-Id", trace)
                    .header("Server-Timing", operation + ";dur=" + String.format(java.util.Locale.ROOT, "%.3f",
                            (System.nanoTime() - started) / 1_000_000.0))
                    .body(response);
        } finally {
            log.info("destination JSON request finished operation={} requestId={} elapsedMs={}", operation, trace,
                    (System.nanoTime() - started) / 1_000_000.0);
            if (previous == null) MDC.remove("destinationImportRequest"); else MDC.put("destinationImportRequest", previous);
        }
    }

    /** 본문은 JSON 파일 내용 그대로다. 문법 오류도 미리보기 결과(fileErrors)로 돌려준다. */
    @PostMapping("/preview")
    public DestinationImportPreview preview(@RequestBody(required = false) String json) {
        return importService.preview(json);
    }

    /** 본문: {"index": 0, "allowPossibleDuplicate": false, "item": {...destinations[0]...}} */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody(required = false) String body,
                                      @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("로그인 정보를 확인할 수 없습니다."));
        }
        DestinationImportResult result = importService.register(body, userDetails.getId());
        return ResponseEntity.ok(result);
    }

    /** AI 가 JSON 을 만들 때 참고할 현재 등록 기준 데이터. 읽기 전용이며 DB 번호는 담지 않는다. */
    @GetMapping("/master")
    public ResponseEntity<DestinationImportMasterExportService.MasterExport> master() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(DestinationImportMasterExportService.FILE_NAME).build().toString())
                .body(masterExportService.export());
    }

    private record ErrorResponse(String message) {
    }
}
