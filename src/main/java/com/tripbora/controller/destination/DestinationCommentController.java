package com.tripbora.controller.destination;

import com.tripbora.dto.CommentDto;
import com.tripbora.dto.CommentImageDto;
import com.tripbora.dto.CommentLocationDto;
import com.tripbora.dto.PageResult;
import com.tripbora.security.ClientIpResolver;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.CommentImageLimitException;
import com.tripbora.service.file.UnsupportedImageFormatException;
import com.tripbora.service.comment.CommentLikeService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationNotFoundException;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.service.translation.ContentTranslationResponse;
import com.tripbora.service.translation.ContentTranslationService;
import com.tripbora.service.translation.MachineTranslationException;
import com.tripbora.service.translation.TranslatableContentType;
import com.tripbora.service.translation.TranslationNotFoundException;
import com.tripbora.service.translation.TranslationDailyLimitException;
import com.tripbora.service.translation.TranslationMonthlyLimitException;
import com.tripbora.service.translation.TranslationRateLimitException;
import com.tripbora.service.translation.TranslationStaleException;
import com.tripbora.service.translation.TranslationTooLongException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/comments")
public class DestinationCommentController {

    private final CommentLikeService commentLikeService;
    private final DestinationCommentService destinationCommentService;
    private final ContentTranslationService contentTranslationService;

    @ExceptionHandler(DestinationNotFoundException.class)
    public ResponseEntity<Void> destinationNotFound() {
        return ResponseEntity.notFound().build();
    }

    private boolean isAdministrator(CustomUserDetails userDetails) {
        return userDetails != null && userDetails.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    // 댓글 좋아요 토글 API
    @PostMapping("/{commentId}/like-toggle")
    public ResponseEntity<String> toggleLike(@PathVariable Long commentId,
                                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인 필요");
        }

        Long userId = userDetails.getId();
        boolean liked = commentLikeService.toggleLike(userId, commentId);
        return ResponseEntity.ok(liked ? "liked" : "unliked");
    }

    @GetMapping("/list")
    public ResponseEntity<List<CommentDto>> getComments(@RequestParam Long destinationId,
                                                        @RequestParam(defaultValue = "oldest") String sort,
                                                        @AuthenticationPrincipal CustomUserDetails userDetails) {
        destinationCommentService.requireDestinationAccessible(destinationId, isAdministrator(userDetails));
        Long userId = (userDetails != null) ? userDetails.getId() : null;
        List<CommentDto> comments = destinationCommentService.getCommentDtosWithWriter(destinationId, userId, sort);
        return ResponseEntity.ok(comments);
    }

    // 댓글 삭제 (소프트 삭제: deleted = true 처리)
    @DeleteMapping("/{commentId}")
    public ResponseEntity<?> delete(@PathVariable Long commentId,
                                    @AuthenticationPrincipal CustomUserDetails userDetails) {
        // 로그인한 사용자가 해당 댓글의 작성자인 경우만 삭제 허용
        boolean deleted = destinationCommentService.softDelete(commentId, userDetails.getId());

        if (deleted) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한 없음");
        }
    }

    // 댓글 수정: 로그인한 사용자가 본인 댓글만 수정 가능
    @PutMapping("/{commentId}")
    public ResponseEntity<?> update(@PathVariable Long commentId,
                                    @RequestBody Map<String, String> payload,
                                    @AuthenticationPrincipal CustomUserDetails userDetails) {

        String content = payload.get("content"); // 프론트에서 받은 수정된 댓글 내용

        // 서비스 호출: 수정 성공 여부 반환
        boolean result = destinationCommentService.updateComment(commentId, userDetails.getId(), content);

        if (result) {
            return ResponseEntity.ok().build(); // 성공
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("수정 권한 없음 또는 실패");
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam("destinationId") Long destinationId,
                                    @RequestParam("content") String content,
                                    @RequestParam(value = "images", required = false) List<MultipartFile> images,
                                    @RequestParam(value = "parentCommentId", required = false) Long parentCommentId,
                                    @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            destinationCommentService.requireDestinationAccessible(destinationId, isAdministrator(userDetails));
            CommentDto dto = destinationCommentService.create(
                    destinationId, userDetails.getId(), content, images, parentCommentId);
            return ResponseEntity.ok(dto);
        } catch (CommentImageLimitException | UnsupportedImageFormatException e) {
            // 프런트에서 그대로 안내할 수 있도록 메시지를 JSON 으로 돌려준다. (사진 수·형식 오류)
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // 댓글 이미지 추출
    @GetMapping("/images")
    public ResponseEntity<List<CommentImageDto>> getCommentImages(@RequestParam Long destinationId,
                                                                 @AuthenticationPrincipal CustomUserDetails userDetails) {
        destinationCommentService.requireDestinationAccessible(destinationId, isAdministrator(userDetails));
        List<CommentImageDto> images = destinationCommentService.getCommentImageDtos(destinationId);
        return ResponseEntity.ok(images);
    }

    @GetMapping("/list/page")
    public ResponseEntity<PageResult<CommentDto>> getCommentsPaged(
            @RequestParam Long destinationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        destinationCommentService.requireDestinationAccessible(destinationId, isAdministrator(userDetails));
        Long userId = (userDetails != null) ? userDetails.getId() : null;
        PageResult<CommentDto> result = destinationCommentService.getCommentsPaged(destinationId, userId, page, size, sort);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{commentId}/location")
    public ResponseEntity<CommentLocationDto> getCommentLocation(
            @PathVariable Long commentId,
            @RequestParam Long destinationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        destinationCommentService.requireDestinationAccessible(destinationId, isAdministrator(userDetails));
        return destinationCommentService.getCommentLocation(destinationId, commentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{commentId}/translation")
    public ResponseEntity<?> translateComment(
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest request) {
        destinationCommentService.requireCommentDestinationAccessible(commentId, isAdministrator(userDetails));
        String targetLanguage = SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN)
                .getLanguageTag();
        Long userId = userDetails == null ? null : userDetails.getId();
        try {
            ContentTranslationResponse response = contentTranslationService.translate(
                    TranslatableContentType.DESTINATION_COMMENT,
                    commentId,
                    targetLanguage,
                    ClientIpResolver.of(request),
                    userId);
            if ("PROCESSING".equals(response.status())) {
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .header("Retry-After", Long.toString(response.retryAfterSeconds()))
                        .body(response);
            }
            return ResponseEntity.ok(response);
        } catch (TranslationNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (TranslationTooLongException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("message", "번역할 수 있는 댓글 길이를 초과했습니다."));
        } catch (TranslationRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(e.retryAfterSeconds()))
                    .body(Map.of("message", "번역 요청이 많습니다. 잠시 후 다시 시도해주세요."));
        } catch (TranslationDailyLimitException | TranslationMonthlyLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "현재 번역 요청을 이용할 수 없습니다. 잠시 후 다시 시도해주세요."));
        } catch (TranslationStaleException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "댓글이 변경되었습니다. 다시 시도해주세요."));
        } catch (MachineTranslationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "지금은 번역을 사용할 수 없습니다."));
        }
    }

}
