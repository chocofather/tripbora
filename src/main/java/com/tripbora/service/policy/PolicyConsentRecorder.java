package com.tripbora.service.policy;

import com.tripbora.model.PolicyConsentSource;
import com.tripbora.model.PolicyConsentAction;
import com.tripbora.model.UserPolicyConsent;
import com.tripbora.repository.user.UserPolicyConsentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 동의 결정을 user_policy_consents 에 남긴다.
 *
 * <p>여기에는 트랜잭션을 걸지 않는다. 반드시 회원을 만드는 트랜잭션 안에서 불러야 하며,
 * 한 건이라도 실패하면 예외가 그대로 올라가 users INSERT 까지 되돌아간다.
 *
 * <p>append-only 기록이라 기존 행을 찾아 고치지 않는다. 거절(agreed = false)도 그대로 INSERT 한다.
 */
@Service
@RequiredArgsConstructor
public class PolicyConsentRecorder {

    private final UserPolicyConsentMapper userPolicyConsentMapper;

    /**
     * @param userId 방금 만든 회원 id
     * @param decisions 서버가 현재 정책 세트로 판정한 결정 목록
     * @param source 가입 화면 종류
     * @param locale 가입 화면의 현재 locale
     */
    public void record(Long userId,
                       List<PolicyConsentDecision> decisions,
                       PolicyConsentSource source,
                       String locale) {
        if (userId == null) {
            throw new PolicyConsentPersistenceException("동의 이력을 남길 회원을 찾을 수 없습니다.");
        }
        if (decisions == null || decisions.isEmpty()) {
            return;
        }

        for (PolicyConsentDecision decision : decisions) {
            UserPolicyConsent consent = new UserPolicyConsent();
            consent.setUserId(userId);
            consent.setPolicyVersionId(decision.policyVersionId());
            consent.setAgreed(decision.agreed());
            consent.setDecisionAction(decision.agreed() ? PolicyConsentAction.AGREE : PolicyConsentAction.DECLINE);
            consent.setLocale(locale);
            consent.setConsentSource(source);

            if (userPolicyConsentMapper.insertConsent(consent) != 1) {
                throw new PolicyConsentPersistenceException(
                        "약관 동의 이력을 저장하지 못했습니다: " + decision.type());
            }
        }
    }
}
