package com.tripbora.service.policy;

/** 가입 화면에 표시한 정책과 현재 정책이 다르거나 필수 정책 구성이 잘못된 경우. */
public class PolicySnapshotException extends RuntimeException {
    private final String messageCode;

    public PolicySnapshotException(boolean unavailable) {
        super(unavailable
                ? "필수 약관을 확인할 수 없어 가입할 수 없습니다. 잠시 후 다시 시도해 주세요."
                : "약관이 변경되었습니다. 최신 약관을 확인하고 동의 여부를 다시 선택해 주세요.");
        this.messageCode = unavailable ? "signup.error.terms.unavailable" : "signup.error.terms.changed";
    }

    public String getMessageCode() {
        return messageCode;
    }
}
