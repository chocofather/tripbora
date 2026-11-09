package com.tripbora.model;

import java.util.Optional;

/**
 * policy_versions.policy_type. DB 에 들어 있는 값과 1:1 이다.
 *
 * <p>동의 필요 여부(requires_consent)와 필수 여부(is_required)는 여기에 두지 않는다.
 * 그 판단은 운영이 DB 에서 바꿀 수 있어야 하므로 언제나 policy_versions 행이 기준이다.
 * 이 enum 이 갖는 건 화면 문구 key 뿐이다.
 */
public enum PolicyType {

    TERMS_OF_SERVICE("signup.terms.service", "signup.error.terms.service"),
    PRIVACY_POLICY("signup.terms.privacyPolicy", "signup.error.terms.required"),
    PRIVACY_COLLECTION("signup.terms.privacy", "signup.error.terms.privacy"),
    MARKETING_EMAIL("signup.terms.marketing", "signup.error.terms.required"),
    PHOTO_UPLOAD_NOTICE("signup.terms.photoUpload", "signup.error.terms.required"),
    REVIEW_GALLERY("signup.terms.reviewGallery", "signup.error.terms.required");

    /** 번역 제목이 아직 없을 때 쓰는 messages key. */
    private final String labelCode;
    /** 필수 동의가 빠졌을 때 보여줄 messages key. */
    private final String consentRequiredCode;

    PolicyType(String labelCode, String consentRequiredCode) {
        this.labelCode = labelCode;
        this.consentRequiredCode = consentRequiredCode;
    }

    public String getLabelCode() {
        return labelCode;
    }

    public String getConsentRequiredCode() {
        return consentRequiredCode;
    }

    /** DB 에 알 수 없는 policy_type 이 들어와도 조회가 깨지지 않도록 비어 있는 값을 준다. */
    public static Optional<PolicyType> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (PolicyType type : values()) {
            if (type.name().equals(value.strip())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
