package com.tripbora.service.policy;

import com.tripbora.dto.GalleryConsentState;
import com.tripbora.dto.ReviewPhotoUploadTerms;
import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.policy.PolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;

/** 회원의 선택 동의는 가입 때와 같은 append-only 정책 이력으로 보관한다. */
@Service
@RequiredArgsConstructor
public class GalleryConsentService {
    private final PolicyMapper policies;
    private final PolicyConsentService policyConsents;
    private final DestinationCommentImageMapper images;

    @Transactional(readOnly = true)
    public ReviewPhotoUploadTerms uploadTerms() {
        return uploadTerms(null);
    }

    /** 과거 버전은 게시·시행된 동일 종류의 약관만 열람한다. 비활성 버전도 근거 열람은 유지한다. */
    @Transactional(readOnly = true)
    public ReviewPhotoUploadTerms uploadTerms(Long versionId) {
        LocalDateTime now = Objects.requireNonNull(policies.databaseWallTime(), "databaseClock");
        PolicyVersion policy = versionId == null ? currentUploadPolicy(now) : policies.findVersionById(versionId);
        if (versionId != null && !publishedUploadPolicy(policy, now))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return uploadTerms(policy, now);
    }

    public record UploadLicenseGrant(Long policyVersionId, LocalDateTime acceptedAt) {}

    /** 실제 신규 파일 저장 트랜잭션에서만 호출하며 기존 선택 동의 이력은 생성하지 않는다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UploadLicenseGrant requireUploadLicense(Long userId, Long expectedVersionId) {
        requireActiveUser(userId);
        LocalDateTime now = Objects.requireNonNull(policies.databaseWallTime(), "databaseClock");
        ReviewPhotoUploadTerms terms = uploadTerms(currentUploadPolicy(now), now);
        if (!terms.available() || expectedVersionId == null || !expectedVersionId.equals(terms.policyVersionId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "사진 업로드 약관이 변경되었거나 본문을 확인할 수 없습니다. 약관을 확인한 뒤 다시 업로드해 주세요.");
        return new UploadLicenseGrant(terms.policyVersionId(), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void applyToNewImage(Long userId, DestinationCommentImage image, Long expectedVersionId) {
        if (!newImage(image)) return;
        applyToNewImage(image, requireUploadLicense(userId, expectedVersionId));
    }

    public void applyToNewImage(DestinationCommentImage image, UploadLicenseGrant grant) {
        if (!newImage(image)) return;
        Objects.requireNonNull(grant, "uploadLicenseGrant");
        Objects.requireNonNull(grant.policyVersionId(), "uploadLicensePolicyId");
        Objects.requireNonNull(grant.acceptedAt(), "uploadLicenseAcceptedAt");
        image.setGalleryStatus("PENDING");
        image.setGalleryLicensePolicyId(grant.policyVersionId());
        image.setGalleryLicenseAcceptedAt(grant.acceptedAt());
    }

    private boolean newImage(DestinationCommentImage image) {
        return image != null && image.getId() == null && "NONE".equals(image.getGalleryStatus())
                && image.getGalleryLicensePolicyId() == null && image.getGalleryLicenseAcceptedAt() == null
                && image.getGalleryConsentId() == null && image.getGalleryConsentAt() == null
                && image.getGalleryConsentVersion() == null && image.getGalleryConsentWithdrawnAt() == null;
    }

    private PolicyVersion currentUploadPolicy(LocalDateTime now) {
        List<PolicyVersion> active = policies.findActiveVersions(List.of(PolicyType.PHOTO_UPLOAD_NOTICE.name()), now);
        return active == null ? null : active.stream().filter(v -> v != null && v.isActive()
                && !v.isRequiresConsent() && !v.isRequired()
                && publishedUploadPolicy(v, now)).findFirst().orElse(null);
    }

    private boolean publishedUploadPolicy(PolicyVersion policy, LocalDateTime now) {
        return policy != null && policy.getId() != null && PolicyType.PHOTO_UPLOAD_NOTICE.name().equals(policy.getPolicyType())
                && policy.getVersion() != null && !policy.getVersion().isBlank()
                && policy.getPublishedAt() != null && !policy.getPublishedAt().isAfter(now)
                && policy.getEffectiveAt() != null && !policy.getEffectiveAt().isAfter(now);
    }

    private ReviewPhotoUploadTerms uploadTerms(PolicyVersion policy, LocalDateTime now) {
        PolicyText text = publishedUploadPolicy(policy, now) ? policyText(policy) : null;
        return new ReviewPhotoUploadTerms(text != null, policy == null ? null : policy.getId(),
                policy == null ? null : policy.getVersion(), text == null ? null : text.title(),
                text == null ? null : text.html(), text == null ? null : text.locale(),
                policy == null ? "/review-photo-terms" : "/review-photo-terms?versionId=" + policy.getId());
    }

    @Transactional
    public GalleryConsentState state(Long userId) {
        requireActiveUser(userId);
        PolicyConsentEvaluation evaluation = policyConsents.evaluateCurrent(userId, PolicyType.REVIEW_GALLERY);
        PolicyVersion policy = evaluation.currentVersionId() == null ? null : policies.findVersionById(evaluation.currentVersionId());
        UserPolicyConsent latest = policyConsents.latestGalleryDecision(userId);
        // 조회 결과를 저장하지 않는다. 다른 탭의 결정도 다음 GET에서 곧바로 반영한다.
        UserPolicyConsent decision = latest == null ? evaluation.currentDecision() : latest;
        PolicyVersion previous = decision == null ? null : policies.findVersionById(decision.getPolicyVersionId());
        PolicyText text = policy == null ? null : policyText(policy);
        boolean prompt = false;
        if (policy != null && text != null && evaluation.currentBasisVersionId() != null
                && !evaluation.currentScopeAuthorized() && evaluation.currentDecision() == null) {
            if (decision == null) prompt = true;
            else if (previous != null) {
                PolicyVersion basis = policies.findVersionById(evaluation.currentBasisVersionId());
                Long oldBasis = previous.getConsentBasisVersionId() == null ? previous.getId() : previous.getConsentBasisVersionId();
                prompt = basis != null && basis.isRequiresReconsent()
                        && !evaluation.currentBasisVersionId().equals(oldBasis);
            }
        }
        PolicyConsentStatus status = prompt && decision != null ? PolicyConsentStatus.NEW_CONSENT_REQUIRED : evaluation.status();
        return new GalleryConsentState(decision != null, evaluation.currentScopeAuthorized(),
                policy == null ? null : policy.getVersion(), text == null ? null : text.text(),
                evaluation.currentVersionId(), evaluation.currentBasisVersionId(), text == null ? null : text.title(), null,
                status, prompt, previous == null ? null : previous.getVersion(),
                decision == null ? null : decision.getDecisionAction(),
                decision == null ? null : decision.getDecisionWallTime());
    }

    @Transactional
    public PolicyConsentService.LegacyGalleryWithdrawal legacyWithdrawalState(Long userId) {
        requireActiveUser(userId);
        return policyConsents.legacyGalleryWithdrawal(userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GalleryConsentState decide(Long userId, boolean agreed) {
        PolicyVersion shown = currentPolicy();
        return decide(userId, agreed, shown == null ? null : shown.getVersion());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GalleryConsentState decide(Long userId, boolean agreed, String expectedVersion) {
        PolicyVersion policy = currentPolicy();
        validatePolicy(policy, expectedVersion, policy == null ? null : policy.getId(), agreed);
        PolicyConsentAction action = agreed ? PolicyConsentAction.AGREE : PolicyConsentAction.WITHDRAW;
        policyConsents.decideGallery(userId, action, policy.getId(), PolicyConsentSource.MYPAGE, displayedLocale(policy));
        return state(userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GalleryConsentState decide(Long userId, String action, String expectedVersion, Long expectedPolicyId) {
        return decide(userId, action, expectedVersion, expectedPolicyId, "MYPAGE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GalleryConsentState decide(Long userId, String action, String expectedVersion, Long expectedPolicyId, String context) {
        if (!List.of("AGREE", "DECLINE", "WITHDRAW", "LATER").contains(action == null ? "" : action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 결정을 선택해 주세요.");
        if (!List.of("PROMPT", "MYPAGE").contains(context == null ? "" : context))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 화면을 확인해 주세요.");
        requireActiveUser(userId);
        if ("WITHDRAW".equals(action)) {
            var legacy = policyConsents.legacyGalleryWithdrawal(userId);
            if (legacy.hasAgreement() && expectedPolicyId != null && expectedPolicyId.equals(legacy.policyVersionId())
                    && Objects.equals(expectedVersion, legacy.version())) {
                policyConsents.decideGallery(userId, PolicyConsentAction.WITHDRAW, expectedPolicyId,
                        PolicyConsentSource.MYPAGE, SignupPolicyService.currentLocaleTag());
                return state(userId);
            }
        }
        PolicyVersion policy = currentPolicy();
        validatePolicy(policy, expectedVersion, expectedPolicyId, !"WITHDRAW".equals(action));
        GalleryConsentState before = state(userId);
        if (before.basisVersionId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 사진 활용 정책을 다시 확인해 주세요.");
        if ("LATER".equals(action)) return before;
        PolicyConsentSource source = "PROMPT".equals(context) && before.promptRequired()
                ? PolicyConsentSource.RECONSENT : PolicyConsentSource.MYPAGE;
        policyConsents.decideGallery(userId, PolicyConsentAction.valueOf(action), expectedPolicyId, source, displayedLocale(policy));
        return state(userId);
    }

    private void validatePolicy(PolicyVersion policy, String expectedVersion, Long expectedPolicyId, boolean requireText) {
        if (policy == null || expectedPolicyId == null || !expectedPolicyId.equals(policy.getId())
                || expectedVersion == null || !Objects.equals(expectedVersion, policy.getVersion()) || (requireText && policyText(policy) == null))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "사진 활용 정책이 변경되었거나 본문을 확인할 수 없습니다. 새 정책을 확인한 뒤 다시 선택해 주세요.");
    }

    private String displayedLocale(PolicyVersion policy) {
        PolicyText text = policyText(policy);
        // 본문 장애 때문에 철회 자체를 막지 않는다. 새 동의에는 이 fallback을 사용하지 않는다.
        return text == null ? SignupPolicyService.currentLocaleTag() : text.locale();
    }

    /** 신규 사진 INSERT 전에만 호출한다. 호출자는 users 행을 먼저 잠근다. */
    public void applyToNewImage(Long userId, DestinationCommentImage image) {
        applyToNewImage(userId, image, null);
    }

    /** 회원의 현재 업로드 동의. 개별 사진 심사에는 requirePhotoValid를 사용한다. */
    public void requireValid(Long userId) {
        if (!policyConsents.evaluateCurrent(userId, PolicyType.REVIEW_GALLERY).currentScopeAuthorized()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "회원의 갤러리 활용 동의가 없거나 철회되었습니다.");
        }
    }

    public void requirePhotoValid(Long imageId) {
        policyConsents.requirePhotoConsent(imageId);
    }

    private PolicyVersion currentPolicy() {
        List<PolicyVersion> active = policies.findActiveVersions(List.of(PolicyType.REVIEW_GALLERY.name()),
                Objects.requireNonNull(policies.databaseWallTime(), "databaseClock"));
        return active == null || active.isEmpty() ? null : active.get(0);
    }

    private record PolicyText(String title, String text, String html, String locale) {}

    private PolicyText policyText(PolicyVersion policy) {
        String locale = SignupPolicyService.currentLocaleTag();
        List<PolicyTranslation> translations = policies.findTranslations(List.of(policy.getId()), locale, "ko");
        if (translations == null || translations.isEmpty()) return null;
        PolicyTranslation translation = translations.stream().filter(t -> locale.equals(t.getLocale())).findFirst()
                .orElseGet(() -> translations.stream().filter(t -> "ko".equals(t.getLocale())).findFirst().orElse(null));
        if (translation == null) return null;
        String safe = PolicyContent.toSafeHtml(translation.getContent());
        String body = safe == null ? "" : org.jsoup.Jsoup.parseBodyFragment(safe).text();
        if (translation.getTitle() == null || translation.getTitle().isBlank() || body.isBlank()) return null;
        String title = org.jsoup.Jsoup.parseBodyFragment(translation.getTitle()).text();
        if (title.isBlank()) return null;
        return new PolicyText(title, title + "\n" + body, safe, translation.getLocale());
    }

    private void requireActiveUser(Long userId) {
        User user = userId == null ? null : images.lockUser(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 회원만 사진 활용 동의를 변경할 수 있습니다.");
        }
    }
}
