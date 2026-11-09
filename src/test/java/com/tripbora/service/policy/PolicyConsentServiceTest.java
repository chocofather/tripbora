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
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyConsentServiceTest {
    @Test void legacyWithdrawalIsOfferedOnlyForAnActualAgreementNotDeclineOrNewUploadTerms() {
        var state = service.legacyGalleryWithdrawal(7L);
        assertThat(state.hasAgreement()).isFalse();
        assertThat(state.canWithdraw()).isFalse();
        verify(consents).findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW);
        verify(consents, never()).findLatestForType(anyLong(), anyString(), any());
        verifyNoInteractions(images);
    }

    @Test void anOlderAgreementRemainsWithdrawableAfterADeclineOrPolicyDeactivation() {
        PolicyVersion original = version(1, null, false); original.setActive(false);
        when(policies.findVersionById(1L)).thenReturn(original);
        var grant = event(10, 1, PolicyConsentAction.AGREE);
        grant.setDecisionAction(null); // ambiguous legacy action, but agreed=true is affirmative evidence.
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(grant);
        var state = service.legacyGalleryWithdrawal(7L);
        assertThat(state.hasAgreement()).isTrue();
        assertThat(state.canWithdraw()).isTrue();
        assertThat(state.policyVersionId()).isEqualTo(1L);
        assertThat(state.version()).isEqualTo("v1");
        verify(consents, never()).findLatestForType(anyLong(), anyString(), any());
        verify(consents, never()).insertConsent(any());
    }

    @Test void completedWithdrawalHidesTheActionButDoesNotEraseAgreementEvidence() {
        when(policies.findVersionById(1L)).thenReturn(version(1, null, false));
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(10, 1, PolicyConsentAction.AGREE));
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(20, 1, PolicyConsentAction.WITHDRAW));
        var state = service.legacyGalleryWithdrawal(7L);
        assertThat(state.hasAgreement()).isTrue();
        assertThat(state.canWithdraw()).isFalse();
        assertThat(state.withdrawn()).isTrue();
        verify(consents, never()).revokeGalleryImages(any());
    }

    @Test void aUserWithoutAnAgreementCannotCreateAWithdrawalRecord() {
        User owner = new User(); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        current(version(2, null, false));
        assertThatThrownBy(() -> service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L))
                .isInstanceOf(ResponseStatusException.class);
        verify(consents, never()).insertConsent(any());
        verify(consents, never()).revokeGalleryImages(any());
    }
    @Test void originalGalleryGrantCanBeWithdrawnAfterThePolicyIsRetired() {
        User owner = new User(); owner.setId(7L); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        PolicyVersion original = version(1, null, false); original.setActive(false);
        lenient().when(policies.findVersionById(1L)).thenReturn(original);
        lenient().when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW))
                .thenReturn(event(10, 1, PolicyConsentAction.AGREE));
        lenient().when(consents.insertConsent(any())).thenReturn(1);
        service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 1L);
        verify(consents).insertConsent(argThat(c -> c.getPolicyVersionId().equals(1L)
                && c.getDecisionAction() == PolicyConsentAction.WITHDRAW && !c.isAgreed()));
        verify(consents).revokeGalleryImages(7L);
    }
    @Test void retiredOptionalChoicesNeverCreateNewHistory() {
        for (PolicyConsentAction action : List.of(PolicyConsentAction.AGREE, PolicyConsentAction.DECLINE))
            assertThatThrownBy(() -> service.decideGallery(7L, action, 2L))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(410));
        verifyNoInteractions(consents, images);
    }
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);
    @Mock PolicyMapper policies;
    @Mock UserPolicyConsentMapper consents;
    @Mock DestinationCommentImageMapper images;
    PolicyConsentService service;

    @BeforeEach void setup() {
        service = new PolicyConsentService(policies, consents, images);
        lenient().when(policies.databaseWallTime()).thenReturn(NOW);
    }

    private PolicyVersion version(long id, Long basis, boolean reconsent) {
        PolicyVersion v = new PolicyVersion();
        v.setId(id); v.setVersion("v" + id); v.setPolicyType("REVIEW_GALLERY");
        v.setConsentBasisVersionId(basis); v.setRequiresReconsent(reconsent);
        v.setRequiresConsent(true); v.setActive(true);
        v.setPublishedAt(NOW.minusDays(10).plusHours(id));
        v.setEffectiveAt(NOW.minusDays(9).plusHours(id));
        return v;
    }

    private void current(PolicyVersion v) {
        when(policies.findActiveVersions(List.of("REVIEW_GALLERY"), NOW)).thenReturn(List.of(v));
        lenient().when(policies.findVersionById(v.getId())).thenReturn(v);
    }

    private UserPolicyConsent event(long id, long versionId, PolicyConsentAction action) {
        UserPolicyConsent c = new UserPolicyConsent();
        c.setId(id); c.setUserId(7L); c.setPolicyVersionId(versionId);
        c.setDecisionAction(action); c.setAgreed(action == PolicyConsentAction.AGREE);
        c.setCreatedAt(Timestamp.valueOf(NOW.minusHours(10).plusMinutes(id)));
        c.setDecisionWallTime(c.getCreatedAt().toLocalDateTime());
        return c;
    }

    private PolicyConsentEvaluation evaluate() { return service.evaluate(7L, PolicyType.REVIEW_GALLERY, NOW); }

    @Test void noActivePolicyDeniesWithoutConsentQueries() {
        assertThat(evaluate().status()).isEqualTo(PolicyConsentStatus.NO_ACTIVE_POLICY);
        verifyNoInteractions(consents);
    }

    @Test void noRecordDoesNotGrantAnything() {
        current(version(1, null, false));
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.NO_RECORD);
        assertThat(result.currentScopeAuthorized()).isFalse();
        assertThat(result.legacyUsageAllowed()).isFalse();
        assertThat(service.galleryUploadGrant(7L)).isNull();
    }

    @Test void nullBasisMeansSelfEvenWhenReconsentFlagIsFalse() {
        current(version(2, null, false));
        UserPolicyConsent previous = event(1, 1, PolicyConsentAction.AGREE);
        when(consents.findLatestAgreementOnOtherBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(previous);
        when(policies.findVersionById(1L)).thenReturn(version(1, null, false));
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.LEGACY_ONLY_VALID);
        assertThat(result.previousBasisAgreement()).isEqualTo(previous);
        assertThat(result.currentScopeAuthorized()).isFalse();
        assertThat(result.legacyUsageAllowed()).isFalse();
    }

    @Test void explicitMinorRevisionUsesLatestEventAcrossDisplayedVersions() {
        current(version(3, 1L, false));
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        UserPolicyConsent agreement = event(10, 1, PolicyConsentAction.AGREE);
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 1L, NOW)).thenReturn(agreement);
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.CURRENT_AGREED);
        assertThat(result.currentBasisVersionId()).isEqualTo(1L);
        assertThat(result.currentScopeAuthorized()).isTrue();
        assertThat(result.currentDecision()).isEqualTo(agreement);
    }

    @Test void newMaterialVersionRequiresNewConsentEvenAfterOldAgreement() {
        current(version(2, null, true));
        UserPolicyConsent previous = event(1, 1, PolicyConsentAction.AGREE);
        when(consents.findLatestAgreementOnOtherBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(previous);
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        assertThat(evaluate().status()).isEqualTo(PolicyConsentStatus.NEW_CONSENT_REQUIRED);
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
        assertThat(service.galleryUploadGrant(7L)).isNull();
    }

    @Test void canonicalPhotoRightsDenialCannotBeReplacedByMemberConsent() {
        assertThatThrownBy(() -> service.requirePhotoConsent(30L)).isInstanceOf(ResponseStatusException.class);
        when(images.photoRights(30L)).thenReturn(true);
        assertThatCode(() -> service.requirePhotoConsent(30L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePhotoConsent(null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(policies, consents);
    }

    @Test void invalidOrForeignAgreementNeverCreatesAnUploadGrant() {
        current(version(1, null, true));
        UserPolicyConsent malformed = event(10, 1, PolicyConsentAction.AGREE);
        malformed.setId(null);
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 1L, NOW)).thenReturn(malformed);
        assertThat(service.galleryUploadGrant(7L)).isNull();
        malformed.setId(10L);
        malformed.setUserId(8L);
        assertThat(service.galleryUploadGrant(7L)).isNull();
        malformed.setUserId(7L);
        malformed.setDecisionWallTime(null);
        assertThat(service.galleryUploadGrant(7L)).isNull();
    }

    @Test void liveClockAndEvidenceQueriesPreserveDatabaseWallTime() throws Exception {
        var config = new org.apache.ibatis.session.Configuration();
        for (String mapper : List.of("PolicyMapper", "UserPolicyConsentMapper")) {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/mapper", mapper + ".xml"))) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config, mapper, config.getSqlFragments()).parse();
            }
        }
        String clock = config.getMappedStatement(PolicyMapper.class.getName() + ".databaseWallTime")
                .getBoundSql(null).getSql();
        assertThat(clock).contains("CURRENT_TIMESTAMP(6)");
        for (String method : List.of("findLatestForType", "findLatestForBasis", "findLatestStopForType", "findLatestAgreementOnOtherBasis")) {
            var query = config.getMappedStatement(UserPolicyConsentMapper.class.getName() + "." + method)
                    .getBoundSql(java.util.Map.of("userId", 7L, "policyType", "REVIEW_GALLERY", "basisId", 1L, "asOf", NOW));
            assertThat(query.getSql()).contains("c.created_at", "CAST(c.created_at AS DATETIME(6)) AS decision_wall_time");
            assertThat(query.getParameterMappings()).extracting(mapping -> mapping.getProperty()).contains("asOf");
        }
    }

    @Test void minorRevisionInheritsTheMaterialBasisReconsentRequirement() {
        current(version(3, 2L, false));
        when(policies.findVersionById(2L)).thenReturn(version(2, null, true));
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        when(consents.findLatestAgreementOnOtherBasis(7L, "REVIEW_GALLERY", 2L, NOW))
                .thenReturn(event(1, 1, PolicyConsentAction.AGREE));
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.NEW_CONSENT_REQUIRED);
        assertThat(result.currentScopeAuthorized()).isFalse();
    }

    @Test void minorRevisionCannotSkipAnInterveningMaterialRevision() {
        current(version(3, 1L, false));
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        when(policies.hasInterveningMaterialVersion(1L, 3L)).thenReturn(true);
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
        assertThat(evaluate().status()).isEqualTo(PolicyConsentStatus.NEW_CONSENT_REQUIRED);
        verifyNoInteractions(consents);
    }

    @Test void referencesToOtherTypesIntermediateMinorVersionsOrFutureVersionsAreRejected() {
        PolicyVersion minor = version(3, 2L, false);
        current(minor);
        PolicyVersion referenced = version(2, 1L, false);
        when(policies.findVersionById(2L)).thenReturn(referenced);
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
        referenced.setConsentBasisVersionId(null);
        referenced.setPolicyType("MARKETING_EMAIL");
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
        referenced.setPolicyType("REVIEW_GALLERY");
        referenced.setEffectiveAt(NOW.plusDays(1));
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
        verifyNoInteractions(consents);
    }

    @Test void latestDeclineDoesNotErasePreviousBasisEvidenceOrRevokePhotos() {
        current(version(2, null, true));
        UserPolicyConsent decline = event(20, 2, PolicyConsentAction.DECLINE);
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(decline);
        UserPolicyConsent previous = event(1, 1, PolicyConsentAction.AGREE);
        when(consents.findLatestAgreementOnOtherBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(previous);
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.DECLINED);
        assertThat(result.previousBasisAgreement()).isSameAs(previous);
        assertThat(result.currentScopeAuthorized()).isFalse();
        verify(consents, never()).revokeGalleryImages(any());
    }

    @Test void withdrawalFromAnotherBasisBlocksEarlierCurrentAgreement() {
        current(version(2, null, true));
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(event(10, 2, PolicyConsentAction.AGREE));
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(11, 1, PolicyConsentAction.WITHDRAW));
        assertThat(evaluate().status()).isEqualTo(PolicyConsentStatus.WITHDRAWN);
        assertThat(evaluate().currentScopeAuthorized()).isFalse();
    }

    @Test void unknownLegacyFalseDeniesWithoutInventingADeclineOrWithdrawal() {
        current(version(1, null, false));
        UserPolicyConsent unknown = event(20, 1, null);
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 1L, NOW)).thenReturn(unknown);
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(unknown);
        PolicyConsentEvaluation result = evaluate();
        assertThat(result.status()).isEqualTo(PolicyConsentStatus.NEW_CONSENT_REQUIRED);
        assertThat(result.currentDecision().getDecisionAction()).isNull();
        assertThat(result.currentScopeAuthorized()).isFalse();
    }

    @Test void newAgreementAfterWithdrawalGrantsOnlyCurrentBasis() {
        current(version(2, null, true));
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 2L, NOW)).thenReturn(event(12, 2, PolicyConsentAction.AGREE));
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(11, 1, PolicyConsentAction.WITHDRAW));
        assertThat(evaluate().currentScopeAuthorized()).isTrue();
    }

    @Test void newCurrentAgreementNeverExpandsAnOldPhotoSnapshot() {
        assertThat(service.authorizePhoto(7L, 1L, NOW.minusDays(1), "APPROVED", 2L, NOW)).isFalse();
    }

    @Test void oldPhotoMayUseOnlyItsOriginalBasisAndPrewithdrawalPhotosNeverRevive() {
        current(version(1, null, true));
        UserPolicyConsent original = event(1, 1, PolicyConsentAction.AGREE);
        LocalDateTime snapshot = original.getCreatedAt().toLocalDateTime();
        when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 1L, snapshot)).thenReturn(original);
        assertThat(service.authorizePhoto(7L, 1L, snapshot, "APPROVED", 1L, NOW)).isTrue();
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(400, 2, PolicyConsentAction.WITHDRAW));
        assertThat(service.authorizePhoto(7L, 1L, snapshot, "APPROVED", 1L, NOW)).isFalse();
        assertThat(service.authorizePhoto(7L, 1L, snapshot, "REVOKED", 1L, NOW)).isFalse();
    }

    @Test void anOldBasisCannotAuthorizePhotosWithoutAnActiveMatchingScope() {
        UserPolicyConsent original = event(1, 1, PolicyConsentAction.AGREE);
        LocalDateTime snapshot = original.getCreatedAt().toLocalDateTime();
        lenient().when(policies.findVersionById(1L)).thenReturn(version(1, null, true));
        lenient().when(consents.findLatestForBasis(7L, "REVIEW_GALLERY", 1L, snapshot)).thenReturn(original);
        assertThat(service.authorizePhoto(7L, 1L, snapshot, "APPROVED", 1L, NOW)).isFalse();
        current(version(2, null, true));
        assertThat(service.authorizePhoto(7L, 1L, snapshot, "APPROVED", 1L, NOW)).isFalse();
    }

    @Test void retiredDeclineCannotWriteAnotherOptionalConsentOrChangePhotos() {
        assertThatThrownBy(() -> service.decideGallery(7L, PolicyConsentAction.DECLINE, 2L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(410));
        verifyNoInteractions(images, consents);
    }

    @Test void explicitWithdrawalAppendsEventAndRevokesPhotos() {
        decide(PolicyConsentAction.WITHDRAW);
        verify(consents).insertConsent(argThat(c -> !c.isAgreed() && c.getDecisionAction() == PolicyConsentAction.WITHDRAW));
        verify(consents).revokeGalleryImages(7L);
    }

    @Test void repeatedLegacyWithdrawalIsSuppressedWithoutChangingPhotosAgain() {
        User owner = new User(); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        current(version(2, null, true));
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(1, 2, PolicyConsentAction.AGREE));
        when(consents.findLatestStopForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(4, 2, PolicyConsentAction.WITHDRAW));
        service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L);
        verify(consents, never()).insertConsent(any());
        verify(consents, never()).revokeGalleryImages(any());
    }

    private void decide(PolicyConsentAction action) {
        User owner = new User(); owner.setId(7L); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        when(policies.findActiveVersions(eq(List.of("REVIEW_GALLERY")), any())).thenReturn(List.of(version(2, null, true)));
        when(consents.insertConsent(any())).thenReturn(1);
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(1, 2, PolicyConsentAction.AGREE));
        service.decideGallery(7L, action, 2L);
    }

    @Test void staleVersionAndInactiveOwnerCannotWrite() {
        assertThatThrownBy(() -> service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(consents);
        User owner = new User(); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        when(policies.findActiveVersions(eq(List.of("REVIEW_GALLERY")), any())).thenReturn(List.of(version(3, null, true)));
        assertThatThrownBy(() -> service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(consents);
    }

    @Test void failedEventInsertStopsBeforePhotoRevocation() {
        User owner = new User(); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        when(policies.findActiveVersions(eq(List.of("REVIEW_GALLERY")), any())).thenReturn(List.of(version(2, null, true)));
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(1, 2, PolicyConsentAction.AGREE));
        assertThatThrownBy(() -> service.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L))
                .isInstanceOf(PolicyConsentPersistenceException.class);
        verify(consents, never()).revokeGalleryImages(any());
    }

    @Test void photoRevocationFailureRollsBackTheDecisionTransaction() {
        User owner = new User(); owner.setStatus(UserStatus.ACTIVE);
        when(images.lockUser(7L)).thenReturn(owner);
        when(policies.findActiveVersions(eq(List.of("REVIEW_GALLERY")), any())).thenReturn(List.of(version(2, null, true)));
        when(consents.findLatestAgreementForType(7L, "REVIEW_GALLERY", NOW)).thenReturn(event(1, 2, PolicyConsentAction.AGREE));
        when(consents.insertConsent(any())).thenReturn(1);
        when(consents.revokeGalleryImages(7L)).thenThrow(new IllegalStateException("revocation failed"));
        RecordingTransactions transactions = new RecordingTransactions();
        var advice = new org.springframework.transaction.interceptor.TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(advice);
        PolicyConsentService proxy = (PolicyConsentService) factory.getProxy();
        assertThatThrownBy(() -> proxy.decideGallery(7L, PolicyConsentAction.WITHDRAW, 2L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    private static class RecordingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int rollbacks;
        int commits;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
    }
}
