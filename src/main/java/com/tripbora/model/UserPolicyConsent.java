package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * user_policy_consents 한 행. 과거 agreed=false의 거절/철회 의미는 소급 추정하지 않는다.
 * append-only 라 이 모델로 UPDATE 하지 않는다.
 */
@Data
@NoArgsConstructor
public class UserPolicyConsent {

    private Long id;
    private Long userId;
    private Long policyVersionId;
    private boolean agreed;
    /** 과거 행은 null을 유지한다. 새 결정만 명시적인 행위를 기록한다. */
    private PolicyConsentAction decisionAction;
    /** 동의 당시 화면 locale. ko/en/ja/zh-CN/zh-TW 만 허용된다. */
    private String locale;
    private PolicyConsentSource consentSource;
    private java.sql.Timestamp createdAt;
    /** 조회 전용: DB 세션의 원본 TIMESTAMP 벽시각. 정책 DATETIME과 비교할 때 JVM 변환을 피한다. */
    private java.time.LocalDateTime decisionWallTime;
}
