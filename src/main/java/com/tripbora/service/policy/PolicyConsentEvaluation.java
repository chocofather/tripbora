package com.tripbora.service.policy;

import com.tripbora.model.UserPolicyConsent;

/**
 * 새 범위 권한과 과거 범위 증거를 분리한다. 이전 동의가 있어도 사용을 자동 허용하지 않는다.
 * 사진은 반드시 원래 스냅샷을 authorizePhoto로 별도 판정해야 한다.
 */
public record PolicyConsentEvaluation(
        PolicyConsentStatus status,
        Long currentVersionId,
        Long currentBasisVersionId,
        boolean currentScopeAuthorized,
        boolean legacyUsageAllowed,
        UserPolicyConsent currentDecision,
        UserPolicyConsent previousBasisAgreement,
        UserPolicyConsent stopBarrier) {
}
