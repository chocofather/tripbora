package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소셜 신규가입이 일반 회원가입과 같은 2단계 구조를 쓰는지 고정한다.
 * 빌드에 JS 런타임이 없어 실행 대신 단계 전환 규칙을 문장으로 고정한다.
 */
class SocialSignupStepUiContractTest {

    /** 1) 신규 소셜가입은 1단계(약관 동의)에서 시작한다. 2단계는 닫혀 있다. */
    @Test
    void theSignupStartsOnTheConsentStep() throws IOException {
        String template = socialSignupHtml();

        assertThat(template)
                .contains("id=\"step-1\" class=\"form-step\"")
                .contains("id=\"step-2\" class=\"form-step\" aria-labelledby=\"social-account-title\" hidden")
                .contains("data-step-indicator=\"1\"")
                .contains("data-step-indicator=\"2\"");
        assertThat(socialSignupJs())
                .contains("showSocialSignupStep(initialSocialSignupStep())")
                // 오류 없이 처음 열면 언제나 1단계다.
                .contains("function initialSocialSignupStep() {\n"
                        + "    const field = $(\"[data-field-error]\").first().data(\"field-error\");");
    }

    /**
     * 2) 필수 약관 미동의 → 다음 단계 불가.
     * 3) 만 14세 미만 → 다음 단계 불가.
     * 6) 선택 항목(마케팅)은 다음 단계 조건이 아니다.
     */
    @Test
    void theNextStepNeedsTheAgeAndTheRequiredConsentsOnly() throws IOException {
        String script = socialSignupJs();

        assertThat(script).contains(
                "$(\"#step1-next\").prop(\"disabled\",\n"
                        + "        socialSignupBirthDateStatus() !== \"ok\" "
                        + "|| !socialSignupRequiredPoliciesAccepted());");
        // 필수 여부는 서버가 내려준 policy_versions.is_required 만 본다. 선택 항목은 조건이 아니다.
        assertThat(script).contains(
                "function socialSignupRequiredPoliciesAccepted() {\n"
                        + "    return $(\"[data-policy-consent][data-policy-required='true']\")");
        // 버튼은 비활성으로 시작하고, 넘어가기 직전에 연령을 한 번 더 본다.
        assertThat(socialSignupHtml()).contains("id=\"step1-next\" disabled");
        assertThat(script)
                .contains("const status = socialSignupBirthDateStatus();")
                .contains("if (status !== \"ok\")");
    }

    /** 4) 조건을 채우면 2단계로, 5) 이전 버튼으로 1단계로 돌아온다. */
    @Test
    void theNextAndPreviousButtonsMoveBetweenTheTwoSteps() throws IOException {
        String script = socialSignupJs();

        assertThat(script)
                .contains("$(\".next-step\").on(\"click\"")
                .contains("showSocialSignupStep(2);")
                .contains("$(\".prev-step\").on(\"click\", () => showSocialSignupStep(1));");
        assertThat(socialSignupHtml())
                .contains("class=\"button-primary next-step\"")
                .contains("class=\"button-secondary prev-step\"");
    }

    /**
     * 7) 기존 계정 연결 모드에서는 단계 UI·연령·약관 동의를 요구하지 않는다.
     * 8) 이메일을 고쳐 신규가입으로 돌아오면 다시 2단계 흐름으로 복귀한다.
     */
    @Test
    void theExistingAccountModeHidesTheWholeSignupFlowAndRestoresItOnReturn() throws IOException {
        String script = socialSignupJs();

        assertThat(script)
                .contains("$(\"[data-signup-only]\").prop(\"hidden\", show)")
                // 감춘 필수 입력이 브라우저 검증을 막지 않도록 required 도 함께 내린다.
                .contains("$(\"#nickname\").prop(\"required\", !show)")
                .contains("$(\"#birthDate\").prop(\"required\", !show)")
                // 이메일을 고치면 신규가입 흐름으로 되돌아간다.
                .contains("showSocialSignupStep(socialSignupStep);")
                .contains("showExistingAccount(false)")
                .contains("showExistingAccount(status === \"EXISTING_ACTIVE\")");

        // 단계 UI·연령·약관은 모두 신규가입 전용 표시를 달고 있어 한 번에 감춰진다.
        String template = socialSignupHtml();
        for (String marked : new String[]{
                "id=\"socialSignupProgress\" data-signup-only",
                "id=\"step-1\" class=\"form-step\" aria-labelledby=\"social-terms-title\""
                        + " data-signup-only"}) {
            assertThat(template).contains(marked);
        }
        // 이메일 칸은 연결 모드에서도 남아야 하므로 그 표시가 붙지 않는다.
        assertThat(template).contains("id=\"socialSignupEmailField\"");
        assertThat(template.substring(
                template.indexOf("id=\"socialSignupEmailField\""),
                template.indexOf("id=\"userEmail\"")))
                .doesNotContain("data-signup-only");
    }

    /** 9) UI 개편과 무관하게 서버의 필수 동의 재검증과 동의 저장은 그대로다. */
    @Test
    void theServerSideConsentCheckAndStorageAreUnchanged() throws IOException {
        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/user/"
                        + "SocialSignupService.java"), StandardCharsets.UTF_8);

        assertThat(service)
                .contains("signupPolicyService.loadSignupPolicies().decide(agreed,")
                .contains("form.getShownPolicyVersionIds()")
                .contains("AgeVerificationPolicy.verify(")
                .contains("PolicyConsentSource.SOCIAL_SIGNUP")
                .contains("policyConsentRecorder.record(");
    }

    private String socialSignupHtml() throws IOException {
        return resource("templates/social-signup.html");
    }

    private String socialSignupJs() throws IOException {
        return resource("static/js/social-signup.js");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/resources").resolve(relativePath),
                StandardCharsets.UTF_8);
    }
}
