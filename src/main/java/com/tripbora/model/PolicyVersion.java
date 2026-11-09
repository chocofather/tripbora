package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * policy_versions 한 행. 동의 필요 여부와 필수 여부를 화면과 서버 검증이 함께 쓴다.
 * policy_type 은 알 수 없는 값이 들어올 수 있어 문자열 그대로 담고 {@link PolicyType} 으로 해석한다.
 */
@Data
@NoArgsConstructor
public class PolicyVersion {

    private Long id;
    private String policyType;
    private String version;
    private boolean requiresConsent;
    private boolean required;
    private boolean requiresReconsent;
    /** null은 이 버전 자체의 범위. 경미 개정의 재사용은 명시 참조가 있어야 한다. */
    private Long consentBasisVersionId;
    private LocalDateTime effectiveAt;
    private LocalDateTime publishedAt;
    private boolean active;
}
