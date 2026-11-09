package com.tripbora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class SocialSignupForm {

    /**
     * 화면에서 체크된 policy_versions.id. 일반 회원가입과 같은 정책 세트를 쓰고,
     * 필수 동의 판정도 서버가 현재 정책 세트로 다시 한다.
     */
    private List<Long> agreedPolicyVersionIds = new ArrayList<>();

    /** 열람 전용을 포함해 가입 화면에 표시한 정책 버전 전체. */
    private List<Long> shownPolicyVersionIds = new ArrayList<>();

    /**
     * 연령 확인용 생년월일(yyyy-MM-dd). 판정에만 쓰고 저장하지 않는다.
     * 신규 users 를 만드는 Google/Kakao/Naver 가입에 모두 필요하다.
     */
    @NotBlank(message = "{signup.error.birthDate.required}")
    private String birthDate;

    // 길이/문구 정책은 일반 회원가입(RegistrationForm)과 같은 key 를 쓴다.
    @NotBlank(message = "{signup.error.nickname.required}")
    @Size(min = 2, max = 16, message = "{signup.error.nickname.size}")
    private String nickname;

    /**
     * Tripbora 이메일 인증이 필요한 provider(Kakao/Naver)에서만 쓰는 입력값.
     * Google 은 provider 가 인증한 이메일을 쓰므로 이 값을 보내도 무시한다.
     * 필수 여부와 형식은 provider 별 정책이라 Bean Validation 이 아니라 서비스에서 본다.
     */
    @Size(max = 100, message = "{signup.error.email.invalid}")
    private String userEmail;
}
