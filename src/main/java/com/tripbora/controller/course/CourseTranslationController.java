package com.tripbora.controller.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.security.ClientIpResolver;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.translation.MachineTranslationException;
import com.tripbora.service.translation.TitleContentTranslationResponse;
import com.tripbora.service.translation.TitleContentTranslationService;
import com.tripbora.service.translation.TranslatableContentType;
import com.tripbora.service.translation.TranslationDailyLimitException;
import com.tripbora.service.translation.TranslationMonthlyLimitException;
import com.tripbora.service.translation.TranslationNotFoundException;
import com.tripbora.service.translation.TranslationRateLimitException;
import com.tripbora.service.translation.TranslationStaleException;
import com.tripbora.service.translation.TranslationTooLongException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/courses")
public class CourseTranslationController {
    private final TitleContentTranslationService translationService;

    @GetMapping("/{courseId}/translation")
    public ResponseEntity<?> translateCourse(
            @PathVariable Long courseId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest request) {
        String targetLanguage = SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN)
                .getLanguageTag();
        Long userId = userDetails == null ? null : userDetails.getId();
        try {
            TitleContentTranslationResponse response = translationService.translate(
                    TranslatableContentType.COURSE, courseId,
                    targetLanguage, ClientIpResolver.of(request), userId);
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
                    .body(Map.of("message", "번역할 수 있는 코스 길이를 초과했습니다."));
        } catch (TranslationRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(e.retryAfterSeconds()))
                    .body(Map.of("message", "번역 요청이 많습니다. 잠시 후 다시 시도해주세요."));
        } catch (TranslationDailyLimitException | TranslationMonthlyLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "현재 번역 요청을 이용할 수 없습니다. 잠시 후 다시 시도해주세요."));
        } catch (TranslationStaleException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "코스가 변경되었습니다. 다시 시도해주세요."));
        } catch (MachineTranslationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "지금은 번역을 사용할 수 없습니다."));
        }
    }
}
