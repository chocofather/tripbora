package com.tripbora.service.policy;

import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.policy.PolicyMapper;
import com.tripbora.repository.user.UserPolicyConsentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GalleryConsentServiceTest {
    @Test void originalLegacyGrantCanBeWithdrawnWithoutAnyActiveGalleryPolicyAndOnlyOnce() {
        active(7L);
        PolicyVersion old = new PolicyVersion();
        old.setId(17L); old.setPolicyType("REVIEW_GALLERY"); old.setVersion("legacy-v1"); old.setRequiresConsent(true);
        when(policies.findVersionById(17L)).thenReturn(old);
        UserPolicyConsent grant = new UserPolicyConsent();
        grant.setId(1L); grant.setUserId(7L); grant.setPolicyVersionId(17L);
        grant.setAgreed(true); grant.setDecisionAction(PolicyConsentAction.AGREE);
        grant.setCreatedAt(Timestamp.valueOf(now.minusDays(1))); grant.setDecisionWallTime(now.minusDays(1));
        when(consents.findLatestAgreementForType(eq(7L), eq("REVIEW_GALLERY"), any())).thenReturn(grant);
        var saved = new java.util.concurrent.atomic.AtomicReference<UserPolicyConsent>();
        when(consents.insertConsent(any())).thenAnswer(call -> {
            UserPolicyConsent withdrawal = call.getArgument(0);
            withdrawal.setId(2L); withdrawal.setCreatedAt(Timestamp.valueOf(now)); withdrawal.setDecisionWallTime(now);
            saved.set(withdrawal); return 1;
        });
        when(consents.findLatestStopForType(eq(7L), eq("REVIEW_GALLERY"), any())).thenAnswer(call -> saved.get());
        when(consents.findLatestForType(eq(7L), eq("REVIEW_GALLERY"), any())).thenAnswer(call -> saved.get());
        var initial = service.legacyWithdrawalState(7L);
        assertThat(initial.canWithdraw()).isTrue();
        var result = service.decide(7L, "WITHDRAW", "legacy-v1", 17L);
        assertThat(result.agreed()).isFalse();
        assertThat(result.decisionAction()).isEqualTo(PolicyConsentAction.WITHDRAW);
        assertThat(service.legacyWithdrawalState(7L).withdrawn()).isTrue();
        service.decide(7L, "WITHDRAW", "legacy-v1", 17L);
        verify(consents, times(1)).insertConsent(any());
        verify(consents, times(1)).revokeGalleryImages(7L);
        assertThat(grant.getDecisionAction()).isEqualTo(PolicyConsentAction.AGREE);
        assertThat(grant.getCreatedAt()).isEqualTo(Timestamp.valueOf(now.minusDays(1)));
    }
    @Mock PolicyMapper policies;
    @Mock UserPolicyConsentMapper consents;
    @Mock DestinationCommentImageMapper images;
    GalleryConsentService service;
    PolicyVersion version;
    final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 30, 0, 123456000);

    @BeforeEach void setup() {
        service = new GalleryConsentService(policies, new PolicyConsentService(policies, consents, images), images);
        version = new PolicyVersion();
        version.setId(50L); version.setPolicyType("PHOTO_UPLOAD_NOTICE");
        version.setVersion("20261106-v1"); version.setActive(true);
        version.setPublishedAt(now.minusDays(1)); version.setEffectiveAt(now.minusDays(1));
        lenient().when(policies.databaseWallTime()).thenReturn(now);
        lenient().when(policies.findActiveVersions(eq(List.of("PHOTO_UPLOAD_NOTICE")), any())).thenReturn(List.of(version));
        lenient().when(policies.findVersionById(50L)).thenReturn(version);
        PolicyTranslation text = new PolicyTranslation();
        text.setPolicyVersionId(50L); text.setLocale("ko"); text.setTitle("리뷰 사진 업로드 약관");
        text.setContent("<p>리뷰가 공개되어 있는 동안 서비스 내에서 사진을 활용합니다.</p><script>unsafe()</script>");
        lenient().when(policies.findTranslations(eq(List.of(50L)), anyString(), eq("ko"))).thenReturn(List.of(text));
    }

    private void active(Long id) {
        User user = new User(); user.setId(id); user.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(id)).thenReturn(user);
    }

    private void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
    }

    @Test void publicTermsUseActualVersionAndSanitizedHtmlWithoutMemberConsent() {
        var terms = service.uploadTerms();
        assertThat(terms.available()).isTrue();
        assertThat(terms.policyVersionId()).isEqualTo(50L);
        assertThat(terms.version()).isEqualTo("20261106-v1");
        assertThat(terms.contentHtml()).contains("<p>", "서비스 내").doesNotContain("script", "unsafe()");
        assertThat(terms.policyUrl()).isEqualTo("/review-photo-terms?versionId=50");
        verifyNoInteractions(consents, images);
    }

    @Test void fallbackRecordsTheLanguageActuallyDisplayed() {
        Locale original = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.JAPANESE);
            assertThat(service.uploadTerms().locale()).isEqualTo("ko");
        } finally { LocaleContextHolder.setLocale(original); }
    }

    @Test void newAndPreviouslyWithdrawnMembersReceiveTheSamePerUploadLicense() {
        // 회원별 과거 AGREE/DECLINE/WITHDRAW 조회 자체를 하지 않는다.
        for (long id : List.of(7L, 8L)) {
            active(id);
            var grant = service.requireUploadLicense(id, 50L);
            DestinationCommentImage image = new DestinationCommentImage();
            service.applyToNewImage(image, grant);
            assertThat(image.getGalleryStatus()).isEqualTo("PENDING");
            assertThat(image.getGalleryLicensePolicyId()).isEqualTo(50L);
            assertThat(image.getGalleryLicenseAcceptedAt()).isEqualTo(now);
            assertThat(image.getGalleryConsentId()).isNull();
            assertThat(image.getGalleryConsentAt()).isNull();
            assertThat(image.getGalleryConsentVersion()).isNull();
            assertThat(image.getGalleryConsentWithdrawnAt()).isNull();
        }
        verify(images).lockUser(7L); verify(images).lockUser(8L);
        verifyNoInteractions(consents);
    }

    @Test void missingOrStaleExpectedVersionNeverSilentlyCreatesNonePhoto() {
        active(7L);
        conflict(() -> service.requireUploadLicense(7L, null));
        conflict(() -> service.requireUploadLicense(7L, 49L));
        DestinationCommentImage image = new DestinationCommentImage();
        conflict(() -> service.applyToNewImage(7L, image));
        assertThat(image.getGalleryStatus()).isEqualTo("NONE");
        assertThat(image.getGalleryLicensePolicyId()).isNull();
        verifyNoInteractions(consents);
    }

    @Test void inactiveOrMissingCurrentTermsCannotGrantNewUploads() {
        active(7L); version.setActive(false);
        assertThat(service.uploadTerms().available()).isFalse();
        conflict(() -> service.requireUploadLicense(7L, 50L));
        version.setActive(true);
        when(policies.findActiveVersions(anyList(), any())).thenReturn(List.of());
        assertThat(service.uploadTerms().available()).isFalse();
        conflict(() -> service.requireUploadLicense(7L, 50L));
        verifyNoInteractions(consents);
    }

    @Test void aMemberConsentPolicyCannotBeMistakenForUploadLicenseTerms() {
        active(7L);
        version.setRequiresConsent(true);
        assertThat(service.uploadTerms().available()).isFalse();
        conflict(() -> service.requireUploadLicense(7L, 50L));
        version.setRequiresConsent(false);
        version.setRequired(true);
        assertThat(service.uploadTerms().available()).isFalse();
        conflict(() -> service.requireUploadLicense(7L, 50L));
        verifyNoInteractions(consents);
    }

    @Test void emptySanitizedTextCannotGrantNewUploads() {
        active(7L);
        PolicyTranslation empty = new PolicyTranslation(); empty.setPolicyVersionId(50L);
        empty.setLocale("ko"); empty.setTitle("업로드 약관"); empty.setContent("<script>unsafe()</script>");
        when(policies.findTranslations(anyList(), anyString(), eq("ko"))).thenReturn(List.of(empty));
        assertThat(service.uploadTerms().available()).isFalse();
        conflict(() -> service.requireUploadLicense(7L, 50L));
        verifyNoInteractions(consents);
    }

    @Test void inactiveUsersCannotCreateUploadLicenseEvenWithCurrentVersion() {
        assertThatThrownBy(() -> service.requireUploadLicense(7L, 50L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(consents);
    }

    @Test void existingNoneAndEverySavedStatusKeepTheirOriginalSnapshot() {
        for (String status : List.of("NONE", "PENDING", "APPROVED", "REJECTED", "REVOKED")) {
            DestinationCommentImage image = new DestinationCommentImage();
            image.setId(80L); image.setGalleryStatus(status);
            image.setGalleryConsentId(100L); image.setGalleryConsentVersion("legacy-v1");
            image.setGalleryConsentAt(Timestamp.valueOf(now.minusDays(10)));
            service.applyToNewImage(7L, image, 50L);
            assertThat(image.getGalleryStatus()).isEqualTo(status);
            assertThat(image.getGalleryConsentId()).isEqualTo(100L);
            assertThat(image.getGalleryConsentVersion()).isEqualTo("legacy-v1");
            assertThat(image.getGalleryConsentAt()).isEqualTo(Timestamp.valueOf(now.minusDays(10)));
            assertThat(image.getGalleryLicensePolicyId()).isNull();
        }
        verifyNoInteractions(images, consents);
    }

    @Test void legacySnapshotsAndAlreadyLicensedObjectsAreNeverOverwritten() {
        DestinationCommentImage legacy = new DestinationCommentImage(); legacy.setGalleryConsentVersion("old");
        service.applyToNewImage(7L, legacy, 50L);
        assertThat(legacy.getGalleryStatus()).isEqualTo("NONE");
        assertThat(legacy.getGalleryLicensePolicyId()).isNull();
        DestinationCommentImage licensed = new DestinationCommentImage();
        licensed.setGalleryLicensePolicyId(49L); licensed.setGalleryLicenseAcceptedAt(now.minusDays(1));
        service.applyToNewImage(7L, licensed, 50L);
        assertThat(licensed.getGalleryLicensePolicyId()).isEqualTo(49L);
        assertThat(licensed.getGalleryLicenseAcceptedAt()).isEqualTo(now.minusDays(1));
        verifyNoInteractions(images, consents);
    }

    @Test void historicalTermsRemainReadableAfterDeactivation() {
        version.setActive(false);
        assertThat(service.uploadTerms(50L).available()).isTrue();
        verifyNoInteractions(images, consents);
    }

    @Test void historicalLookupRejectsDifferentTypeDraftAndFuturePolicies() {
        version.setPolicyType("TERMS_OF_SERVICE"); notFound();
        version.setPolicyType("PHOTO_UPLOAD_NOTICE"); version.setPublishedAt(null); notFound();
        version.setPublishedAt(now.plusDays(1)); notFound();
        version.setPublishedAt(now.minusDays(1)); version.setEffectiveAt(now.plusDays(1)); notFound();
    }

    private void notFound() {
        assertThatThrownBy(() -> service.uploadTerms(50L)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
    }
}
