package com.tripbora.service.policy;

import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.policy.PolicyMapper;
import com.tripbora.repository.user.UserPolicyConsentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 버전별 표시와 동의 범위를 분리하는 공통 판정 기반.
 * 업로드는 실제 동의 이벤트를 보존하고, 사진별 권한은 공개/심사 공통 SQL로 판정한다.
 */
@Service
@RequiredArgsConstructor
public class PolicyConsentService {
    private final PolicyMapper policies;
    private final UserPolicyConsentMapper consents;
    private final DestinationCommentImageMapper images;

    @Transactional(readOnly = true)
    public PolicyConsentEvaluation evaluate(Long userId, PolicyType type, LocalDateTime now) {
        return evaluate(userId, type, now, false);
    }

    /** 사진 runtime은 DB 시계와 DB 원본 벽시각을 함께 사용한다. */
    @Transactional(readOnly = true)
    public PolicyConsentEvaluation evaluateCurrent(Long userId, PolicyType type) {
        return evaluate(userId, type, Objects.requireNonNull(policies.databaseWallTime(), "databaseClock"), true);
    }

    private PolicyConsentEvaluation evaluate(Long userId, PolicyType type, LocalDateTime now, boolean live) {
        Objects.requireNonNull(type, "policyType");
        Objects.requireNonNull(now, "now");
        PolicyVersion displayed = current(type, now);
        if (displayed == null) return result(PolicyConsentStatus.NO_ACTIVE_POLICY, null, null, null, null, null);
        Long basis = resolveBasis(displayed, now);
        if (basis == null) return result(PolicyConsentStatus.NEW_CONSENT_REQUIRED, displayed, null, null, null, null);
        if (userId == null) return result(PolicyConsentStatus.NO_RECORD, displayed, basis, null, null, null);

        UserPolicyConsent latest = consents.findLatestForBasis(userId, type.name(), basis, now);
        UserPolicyConsent stop = consents.findLatestStopForType(userId, type.name(), now);
        UserPolicyConsent previous = consents.findLatestAgreementOnOtherBasis(userId, type.name(), basis, now);
        if (!validAgreement(previous, userId, type, null, now, live) || !afterStop(previous, stop)) previous = null;

        PolicyConsentStatus status;
        if (stop != null && !afterStop(latest, stop)) {
            status = stop.getDecisionAction() == PolicyConsentAction.WITHDRAW
                    ? PolicyConsentStatus.WITHDRAWN : PolicyConsentStatus.NEW_CONSENT_REQUIRED;
        } else if (latest != null) {
            if (validAgreement(latest, userId, type, basis, now, live)) status = PolicyConsentStatus.CURRENT_AGREED;
            else if (!latest.isAgreed() && latest.getDecisionAction() == PolicyConsentAction.DECLINE)
                status = PolicyConsentStatus.DECLINED;
            else if (!latest.isAgreed() && latest.getDecisionAction() == PolicyConsentAction.WITHDRAW)
                status = PolicyConsentStatus.WITHDRAWN;
            else status = PolicyConsentStatus.NEW_CONSENT_REQUIRED;
        } else if (previous != null) {
            PolicyVersion basisVersion = basis.equals(displayed.getId()) ? displayed : policies.findVersionById(basis);
            status = basisVersion == null || basisVersion.isRequiresReconsent() ? PolicyConsentStatus.NEW_CONSENT_REQUIRED
                    : PolicyConsentStatus.LEGACY_ONLY_VALID;
        } else status = PolicyConsentStatus.NO_RECORD;
        return result(status, displayed, basis, latest, previous, stop);
    }

    public record GalleryUploadGrant(Long consentId, String version, java.sql.Timestamp consentAt) {}

    /** 표시 버전 대신 실제 동의 이벤트의 ID·버전·시각을 신규 사진에 복사한다. */
    @Transactional(readOnly = true)
    public GalleryUploadGrant galleryUploadGrant(Long userId) {
        PolicyConsentEvaluation evaluation = evaluateCurrent(userId, PolicyType.REVIEW_GALLERY);
        if (!evaluation.currentScopeAuthorized()) return null;
        UserPolicyConsent event = evaluation.currentDecision();
        PolicyVersion actual = policies.findVersionById(event.getPolicyVersionId());
        if (actual == null || actual.getVersion() == null) return null;
        return new GalleryUploadGrant(event.getId(), actual.getVersion(), event.getCreatedAt());
    }

    /** PENDING 승인과 APPROVED 유지 모두 원본 사진의 근거를 검사한다. 호출자는 먼저 행을 잠근다. */
    @Transactional(readOnly = true)
    public void requirePhotoConsent(Long imageId) {
        if (imageId == null || !images.photoRights(imageId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "사진의 원본 동의 근거 또는 공개 상태가 변경되었습니다.");
    }

    /**
     * 원래 사진의 기준에만 사용할 수 있다. 현재 기준에 대한 새 동의로 과거 사진을 확장하지 않는다.
     * 정밀도가 보존된 원본 스냅샷만 다루는 참조 API다. 저장된 사진의 런타임 판정은 requirePhotoConsent를 사용한다.
     * ID가 없는 기존 사진의 유일 근거 확인과 저장 시각 정밀도 처리는 공개·심사 공통 SQL이 맡는다.
     */
    @Transactional(readOnly = true)
    public boolean authorizePhoto(Long userId, Long snapshotVersionId, LocalDateTime snapshotAt,
                                  String galleryStatus, Long targetBasisVersionId, LocalDateTime now) {
        if (userId == null || snapshotVersionId == null || snapshotAt == null || now == null
                || snapshotAt.isAfter(now) || !"APPROVED".equals(galleryStatus)) return false;
        PolicyVersion displayed = current(PolicyType.REVIEW_GALLERY, now);
        Long currentBasis = displayed == null ? null : resolveBasis(displayed, now);
        // 과거 범위의 별도 사용 승인은 구현하지 않는다. 현재 범위와 다르면 보수적으로 거부한다.
        if (currentBasis == null || !currentBasis.equals(targetBasisVersionId)) return false;
        PolicyVersion snapshotVersion = policies.findVersionById(snapshotVersionId);
        if (snapshotVersion == null || !PolicyType.REVIEW_GALLERY.name().equals(snapshotVersion.getPolicyType())) return false;
        Long originalBasis = resolveBasis(snapshotVersion, snapshotAt);
        if (originalBasis == null || !originalBasis.equals(targetBasisVersionId)) return false;
        UserPolicyConsent grant = consents.findLatestForBasis(userId, PolicyType.REVIEW_GALLERY.name(), originalBasis, snapshotAt);
        if (!validAgreement(grant, userId, PolicyType.REVIEW_GALLERY, originalBasis, snapshotAt, false)
                || !snapshotVersionId.equals(grant.getPolicyVersionId())) return false;
        // 사진에는 동의 결정 시각을 복사하므로, 임의 시각을 옛 동의로 정당화하지 않는다.
        if (!grant.getCreatedAt().toLocalDateTime().equals(snapshotAt)) return false;
        UserPolicyConsent stop = consents.findLatestStopForType(userId, PolicyType.REVIEW_GALLERY.name(), now);
        return stop == null || (afterStop(grant, stop) && stop.getCreatedAt() != null
                && snapshotAt.isAfter(stop.getCreatedAt().toLocalDateTime()));
    }

    /**
     * 명시 결정 API.
     * AGREE는 향후 업로드 범위의 동의만 기록하고, DECLINE은 사진 상태를 바꾸지 않는다.
     * WITHDRAW는 회원 잠금 안에서 기록과 기존 철회를 같은 트랜잭션으로 처리한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decideGallery(Long userId, PolicyConsentAction action, Long expectedVersionId) {
        decideGallery(userId, action, expectedVersionId, PolicyConsentSource.MYPAGE);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decideGallery(Long userId, PolicyConsentAction action, Long expectedVersionId, PolicyConsentSource source) {
        decideGallery(userId, action, expectedVersionId, source, SignupPolicyService.currentLocaleTag());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decideGallery(Long userId, PolicyConsentAction action, Long expectedVersionId,
                              PolicyConsentSource source, String displayedLocale) {
        if (action != PolicyConsentAction.WITHDRAW)
            throw new ResponseStatusException(HttpStatus.GONE, "사진 활용 선택 동의는 종료되었습니다. 사진 업로드 시 업로드 약관이 적용됩니다.");
        User owner = userId == null ? null : images.lockUser(userId);
        if (owner == null || owner.getStatus() != UserStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 회원만 동의를 변경할 수 있습니다.");
        if (action == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 결정을 선택해 주세요.");
        LocalDateTime now = Objects.requireNonNull(policies.databaseWallTime(), "databaseClock");
        PolicyVersion displayed = current(PolicyType.REVIEW_GALLERY, now);
        Long basis = displayed == null ? null : resolveBasis(displayed, now);
        boolean currentVersion = displayed != null && expectedVersionId != null
                && expectedVersionId.equals(displayed.getId()) && basis != null;
        if (!currentVersion) {
            displayed = expectedVersionId == null ? null : policies.findVersionById(expectedVersionId);
            if (displayed == null || !PolicyType.REVIEW_GALLERY.name().equals(displayed.getPolicyType()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "과거 사진 활용 동의 정보를 다시 확인해 주세요.");
        }
        UserPolicyConsent agreement = consents.findLatestAgreementForType(userId, PolicyType.REVIEW_GALLERY.name(), now);
        if (agreement == null || (!currentVersion && !Objects.equals(expectedVersionId, agreement.getPolicyVersionId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 사진 활용 정책을 다시 확인해 주세요.");
        UserPolicyConsent stop = consents.findLatestStopForType(userId, PolicyType.REVIEW_GALLERY.name(), now);
        if (stop != null && stop.getDecisionAction() == PolicyConsentAction.WITHDRAW && !afterStop(agreement, stop)) return;
        UserPolicyConsent decision = new UserPolicyConsent();
        decision.setUserId(userId);
        decision.setPolicyVersionId(displayed.getId());
        decision.setAgreed(action == PolicyConsentAction.AGREE);
        decision.setDecisionAction(action);
        if (!List.of("ko", "en", "ja", "zh-CN", "zh-TW").contains(displayedLocale == null ? "" : displayedLocale))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "확인한 정책 언어를 다시 확인해 주세요.");
        decision.setLocale(displayedLocale);
        decision.setConsentSource(source == PolicyConsentSource.RECONSENT ? source : PolicyConsentSource.MYPAGE);
        if (consents.insertConsent(decision) != 1)
            throw new PolicyConsentPersistenceException("사진 활용 동의를 기록하지 못했습니다.");
        if (action == PolicyConsentAction.WITHDRAW) consents.revokeGalleryImages(userId);
    }

    @Transactional(readOnly = true)
    public UserPolicyConsent latestGalleryDecision(Long userId) {
        return consents.findLatestForType(userId, PolicyType.REVIEW_GALLERY.name(),
                Objects.requireNonNull(policies.databaseWallTime(), "databaseClock"));
    }

    public record LegacyGalleryWithdrawal(boolean hasAgreement, boolean canWithdraw, boolean withdrawn,
                                          Long policyVersionId, String version) {}

    /** 최신 DECLINE만으로 과거 허가가 사라졌다고 보지 않는다. 새 이용조건 사진은 조회하지 않는다. */
    @Transactional(readOnly = true)
    public LegacyGalleryWithdrawal legacyGalleryWithdrawal(Long userId) {
        LocalDateTime now = Objects.requireNonNull(policies.databaseWallTime(), "databaseClock");
        UserPolicyConsent agreement = consents.findLatestAgreementForType(userId, PolicyType.REVIEW_GALLERY.name(), now);
        if (agreement == null) return new LegacyGalleryWithdrawal(false, false, false, null, null);
        UserPolicyConsent stop = consents.findLatestStopForType(userId, PolicyType.REVIEW_GALLERY.name(), now);
        boolean withdrawn = stop != null && stop.getDecisionAction() == PolicyConsentAction.WITHDRAW && !afterStop(agreement, stop);
        PolicyVersion original = policies.findVersionById(agreement.getPolicyVersionId());
        boolean valid = original != null && PolicyType.REVIEW_GALLERY.name().equals(original.getPolicyType())
                && original.getVersion() != null && !original.getVersion().isBlank();
        return new LegacyGalleryWithdrawal(true, valid && !withdrawn, withdrawn,
                valid ? original.getId() : null, valid ? original.getVersion() : null);
    }

    private PolicyVersion current(PolicyType type, LocalDateTime now) {
        List<PolicyVersion> active = policies.findActiveVersions(List.of(type.name()), now);
        return active == null || active.isEmpty() ? null : active.get(0);
    }

    /** null/self는 독립 범위. 경미 개정은 같은 타입의 실질 기준을 직접 가리켜야 한다. */
    private Long resolveBasis(PolicyVersion version, LocalDateTime now) {
        if (!publishedBy(version, now) || !version.isRequiresConsent()) return null;
        Long ref = version.getConsentBasisVersionId();
        if (ref == null || ref.equals(version.getId())) return version.getId();
        if (version.isRequiresReconsent() || ref >= version.getId()) return null;
        PolicyVersion basis = policies.findVersionById(ref);
        if (!publishedBy(basis, now) || !basis.isRequiresConsent()
                || !Objects.equals(basis.getPolicyType(), version.getPolicyType())
                || (basis.getConsentBasisVersionId() != null && !basis.getId().equals(basis.getConsentBasisVersionId()))
                || basis.getEffectiveAt().isAfter(version.getEffectiveAt())
                || basis.getPublishedAt().isAfter(version.getPublishedAt())
                || policies.hasInterveningMaterialVersion(ref, version.getId())) return null;
        return ref;
    }

    private boolean publishedBy(PolicyVersion version, LocalDateTime now) {
        return version != null && version.getId() != null && version.getPublishedAt() != null
                && version.getEffectiveAt() != null && !version.getPublishedAt().isAfter(now)
                && !version.getEffectiveAt().isAfter(now);
    }

    private boolean validAgreement(UserPolicyConsent event, Long userId, PolicyType type, Long expectedBasis,
                                   LocalDateTime asOf, boolean live) {
        if (event == null || event.getId() == null || event.getPolicyVersionId() == null
                || !Objects.equals(userId, event.getUserId())
                || event.getCreatedAt() == null
                || !event.isAgreed() || (event.getDecisionAction() != null && event.getDecisionAction() != PolicyConsentAction.AGREE))
            return false;
        LocalDateTime decisionAt = live ? event.getDecisionWallTime() : event.getCreatedAt().toLocalDateTime();
        if (decisionAt == null || decisionAt.isAfter(asOf)) return false;
        PolicyVersion version = policies.findVersionById(event.getPolicyVersionId());
        if (version == null || !type.name().equals(version.getPolicyType())) return false;
        Long basis = resolveBasis(version, decisionAt);
        return basis != null && (expectedBasis == null || expectedBasis.equals(basis));
    }

    private boolean afterStop(UserPolicyConsent event, UserPolicyConsent stop) {
        return event != null && event.getId() != null && (stop == null
                || (stop.getId() != null && event.getId() > stop.getId()));
    }

    private PolicyConsentEvaluation result(PolicyConsentStatus status, PolicyVersion displayed, Long basis,
                                            UserPolicyConsent latest, UserPolicyConsent previous, UserPolicyConsent stop) {
        return new PolicyConsentEvaluation(status, displayed == null ? null : displayed.getId(), basis,
                status == PolicyConsentStatus.CURRENT_AGREED, false, latest, previous, stop);
    }
}
