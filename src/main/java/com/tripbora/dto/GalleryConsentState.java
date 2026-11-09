package com.tripbora.dto;

import com.tripbora.model.PolicyConsentAction;
import com.tripbora.service.policy.PolicyConsentStatus;
import java.time.LocalDateTime;

/** 결정하지 않은 회원과 선택 동의를 거부한 회원을 구분한다. */
public record GalleryConsentState(boolean decided, boolean agreed, String version, String policyText,
                                  Long policyVersionId, Long basisVersionId, String policyTitle, String policyUrl,
                                  PolicyConsentStatus status, boolean promptRequired, String decisionVersion,
                                  PolicyConsentAction decisionAction, LocalDateTime decisionAt) {
    public GalleryConsentState(boolean decided, boolean agreed, String version, String policyText) {
        this(decided, agreed, version, policyText, null, null, null, null, null, false, null, null, null);
    }
    public GalleryConsentState(boolean decided, boolean agreed, String version) {
        this(decided, agreed, version, null);
    }

    public GalleryConsentState withPromptRequired(boolean required) {
        return new GalleryConsentState(decided, agreed, version, policyText, policyVersionId, basisVersionId,
                policyTitle, policyUrl, status, required, decisionVersion, decisionAction, decisionAt);
    }
}
