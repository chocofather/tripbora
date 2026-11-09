package com.tripbora.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class RegistrationForm {

    /**
     * 화면에서 체크된 policy_versions.id. 신뢰하지 않는 입력이다.
     *
     * <p>필수 동의 판정은 Bean Validation 이 아니라 서버가 다시 조회한 현재 정책 세트가 한다.
     * 어떤 정책이 필수인지는 화면 문자열이 아니라 policy_versions.is_required 가 정하기 때문이다.
     * 표시 버전과 현재 정책을 대조하므로 예전 버전 id 는 동의로 처리되지 않는다.
     */
    private List<Long> agreedPolicyVersionIds = new ArrayList<>();

    /** 열람 전용을 포함해 가입 화면에 표시한 정책 버전 전체. 서버에서 현재 목록과 대조한다. */
    private List<Long> shownPolicyVersionIds = new ArrayList<>();

    /**
     * 연령 확인용 생년월일(yyyy-MM-dd). 판정에만 쓰고 저장하지 않는다.
     * 형식/경계 판정은 AgeVerificationPolicy 가 서버에서 다시 한다.
     */
    @NotBlank(message = "{signup.error.birthDate.required}")
    private String birthDate;

    @NotBlank(message = "{signup.error.email.required}")
    @Email(message = "{signup.error.email.invalid}")
    @Size(max = 100, message = "{signup.error.email.tooLong}")
    private String userEmail;

    @NotBlank(message = "{signup.error.password.required}")
    private String userPassword;

    @NotBlank(message = "{signup.error.passwordConfirm.required}")
    private String passwordConfirm;

    @NotBlank(message = "{signup.error.nickname.required}")
    @Size(min = 2, max = 16, message = "{signup.error.nickname.size}")
    private String nickname;

    @AssertTrue(message = "{signup.error.passwordConfirm.mismatch}")
    public boolean isPasswordConfirmed() {
        return userPassword != null && userPassword.equals(passwordConfirm);
    }
}
