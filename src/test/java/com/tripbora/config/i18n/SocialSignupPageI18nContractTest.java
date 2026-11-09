package com.tripbora.config.i18n;

import com.tripbora.security.AccountAbuseGuard;
import com.tripbora.controller.user.SocialSignupController;
import com.tripbora.model.PendingSocialSignup;
import com.tripbora.model.SocialProvider;
import com.tripbora.service.user.SocialSignupAuthenticationService;
import com.tripbora.service.user.SocialSignupService;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpSession;
import com.tripbora.service.policy.SignupPolicyService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소셜 회원가입 화면의 5개 언어 계약.
 * 브랜드명은 번역하지 않고, 닉네임 문구는 일반 회원가입과 같은 공용 fragment 로 내려간다.
 */
@WebMvcTest(SocialSignupController.class)
@Import(I18nConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class SocialSignupPageI18nContractTest {
    @Test
    void snapshotIncludesEveryShownPolicyWithoutCheckingOptionalConsents() throws Exception {
        Document page=render("ko");
        var policies=com.tripbora.service.policy.SignupPolicyFixtures.activeSignupPolicies();
        assertThat(page.select("input[type=hidden][name=shownPolicyVersionIds]").eachAttr("value"))
                .containsExactlyElementsOf(policies.policies().stream().map(p->p.policyVersionId().toString()).toList());
        assertThat(page.select("input[name=agreedPolicyVersionIds][checked]")).isEmpty();
    }

    @Autowired
    private MockMvc mockMvc;

    // 가입 인증메일의 IP 제한은 이 화면 계약의 관심사가 아니라 통과시키는 가짜를 쓴다.

    @MockitoBean private AccountAbuseGuard accountAbuseGuard;

    @MockitoBean private SignupPolicyService signupPolicyService;

    @MockitoBean
    private SocialSignupService socialSignupService;
    @MockitoBean
    private SocialSignupAuthenticationService authenticationService;
    @MockitoBean
    private com.tripbora.service.user.SocialEmailAccountResolver
            socialEmailAccountResolver;
    @MockitoBean
    private com.tripbora.service.user.SocialLoginLinkService socialLoginLinkService;
    @MockitoBean
    private com.tripbora.repository.user.UserMapper userMapper;

    @org.junit.jupiter.api.BeforeEach
    void activatePolicies() {
        // 약관 항목은 DB 정책 세트에서 나온다. 활성화 후 화면을 재현한다.
        org.mockito.Mockito.when(signupPolicyService.loadSignupPolicies())
                .thenReturn(com.tripbora.service.policy.SignupPolicyFixtures
                        .activeSignupPolicies());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | ko    | 가입을 완료해주세요            | 닉네임      | 자동 추천   | 가입하고 시작하기 | 필수",
            "en    | en    | Finish creating your account | Nickname   | Suggest one| Sign up and start| Required",
            "ja    | ja    | 登録を完了してください         | ニックネーム | 自動提案    | 登録して始める    | 必須",
            "zh-CN | zh-CN | 请完成注册                   | 昵称        | 随机推荐    | 注册并开始       | 必选",
            "zh-TW | zh-TW | 請完成註冊                   | 暱稱        | 隨機建議    | 註冊並開始       | 必填"
    })
    void socialSignupRendersInEverySupportedLanguage(String cookie, String expectedLang,
                                                     String title, String nickname,
                                                     String suggest, String submit,
                                                     String required) throws Exception {
        Document page = render(cookie);

        assertThat(page.selectFirst("html").attr("lang")).isEqualTo(expectedLang);
        assertThat(page.title()).isNotBlank().doesNotContain("??");
        assertThat(page.selectFirst("#socialSignupTitle").text()).isEqualTo(title);
        assertThat(page.selectFirst("label[for=nickname]").text()).isEqualTo(nickname);
        assertThat(page.selectFirst("#generateNickname").text()).isEqualTo(suggest);
        assertThat(page.selectFirst("#socialSignupSubmit").text()).isEqualTo(submit);
        // 필수/선택 badge 도 일반 회원가입과 같은 markup 을 쓴다.
        assertThat(page.selectFirst(".term-badge.required").text()).isEqualTo(required);
        // 설명 / 도움말 / 안내도 번들에서 온다.
        assertThat(page.selectFirst("#socialSignupNewHeader p").text()).isNotBlank();
        // 단계 안내도 일반 회원가입과 같은 구조다.
        assertThat(page.select("[data-step-indicator]")).hasSize(2);
        assertThat(page.selectFirst("#step1-next").text()).isNotBlank();
        assertThat(page.selectFirst("#nicknameMessage").text()).isNotBlank();
        assertThat(page.selectFirst(".social-signup__notice").text()).isNotBlank();
        assertThat(page.selectFirst("#nickname").attr("placeholder")).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({"ko", "en", "ja", "zh-CN", "zh-TW"})
    void noUnresolvedMessageOrPlaceholderSurvives(String cookie) throws Exception {
        String html = mockMvc.perform(get("/social-signup")
                        .session(pendingSession()).cookie(localeCookie(cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("unresolved message in %s", cookie).doesNotContain("??");
        assertThat(Jsoup.parse(html).selectFirst(".social-signup").text())
                .doesNotContain("{0}").doesNotContain("{1}");
    }

    /** 브랜드명은 어느 언어에서도 번역하지 않는다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | Google",
            "en    | Google",
            "ja    | Google",
            "zh-CN | Google",
            "zh-TW | Google"
    })
    void providerBrandNameIsNeverTranslated(String cookie, String brand) throws Exception {
        assertThat(render(cookie).selectFirst(".social-signup__account span").text())
                .contains(brand);
    }

    /** 일반 회원가입과 같은 언어 selector / 닉네임 문구 fragment 를 재사용한다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | 한국어",
            "en    | English",
            "ja    | 日本語",
            "zh-CN | 简体中文",
            "zh-TW | 繁體中文"
    })
    void sharedLanguageMenuAndNicknameMessagesAreReused(String cookie, String nativeName)
            throws Exception {
        Document page = render(cookie);

        var menu = page.selectFirst(".register-page__topbar .language-menu");
        assertThat(menu).isNotNull();
        assertThat(menu.selectFirst(".language-menu-current").text()).isEqualTo(nativeName);
        assertThat(menu.select("form.locale-option-form[action=/locale][method=post]")).hasSize(5);

        var nicknameMessages = page.selectFirst("#nickname-messages");
        assertThat(nicknameMessages).isNotNull();
        for (String attribute : new String[]{"data-checking", "data-available", "data-taken",
                "data-forbidden", "data-invalid", "data-check-failed", "data-generate-failed"}) {
            assertThat(nicknameMessages.attr(attribute))
                    .as("%s in %s", attribute, cookie).isNotBlank();
        }
        // 추천은 기존 공용 API 를 그대로 쓴다.
        assertThat(page.select("script[src='/js/nickname-availability.js']")).isNotEmpty();
    }

    /** 두 화면이 같은 닉네임 정책(2~16자)과 같은 문구 fragment 를 쓴다. */
    @Test
    void standardAndSocialSignupShareTheSameNicknameContract() throws Exception {
        String register = read("src/main/resources/templates/register.html");
        String social = read("src/main/resources/templates/social-signup.html");

        for (String template : new String[]{register, social}) {
            assertThat(template).contains("maxlength=\"16\"");
            assertThat(template).contains("~{fragments/nickname-messages :: nicknameMessages}");
            assertThat(template).contains("#{signup.field.nickname.help}");
        }
        assertThat(read("src/main/resources/static/js/nickname-availability.js"))
                .contains("{2,16}");
    }

    /** 템플릿이 쓰는 message key 가 5개 번들에 모두 존재한다. */
    @Test
    void everyMessageKeyUsedBySocialSignupExistsInAllFiveBundles() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (String template : new String[]{
                "src/main/resources/templates/social-signup.html",
                "src/main/resources/templates/fragments/nickname-messages.html"}) {
            Matcher matcher = Pattern.compile("#\\{([A-Za-z0-9._]+)").matcher(read(template));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        assertThat(keys).isNotEmpty();
        assertThat(keys).noneMatch(key ->
                key.endsWith("_ko") || key.endsWith("_en") || key.endsWith("_ja")
                        || key.endsWith("_zh_CN") || key.endsWith("_zh_TW"));

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        for (SupportedLanguage language : SupportedLanguage.all()) {
            for (String key : keys) {
                assertThat(messages.getMessage(key, null, null, language.getLocale()))
                        .as("%s in %s bundle", key, language.getLanguageTag()).isNotNull();
            }
        }
    }

    /** Kakao/Naver 는 Tripbora 이메일 인증을 받으므로 입력 칸이 5개 언어로 나온다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | 이메일   | 중복확인",
            "en    | Email   | Check",
            "ja    | メールアドレス | 重複確認",
            "zh-CN | 邮箱     | 重复确认",
            "zh-TW | 電子郵件  | 重複確認"
    })
    void kakaoSignupRendersTheEmailFieldInEverySupportedLanguage(
            String cookie, String label, String check) throws Exception {
        Document page = renderKakao(cookie);

        assertThat(page.selectFirst("label[for=userEmail]").text()).isEqualTo(label);
        assertThat(page.selectFirst("#checkEmailStatus").text()).isEqualTo(check);
        assertThat(page.selectFirst("#emailMessage").text()).isNotBlank();
        assertThat(page.selectFirst("#userEmail").attr("placeholder")).isNotBlank();
        // 안내 문구도 번들에서 온다.
        var field = page.selectFirst("#socialSignupEmailField");
        for (String attribute : new String[]{"data-msg-available", "data-msg-existing",
                "data-msg-unavailable", "data-msg-invalid", "data-msg-check-failed"}) {
            assertThat(field.attr(attribute)).as("%s in %s", attribute, cookie).isNotBlank();
        }
        assertThat(page.selectFirst(".social-signup").text()).doesNotContain("??", "{0}");
    }

    /** Naver 가 준 연락처 이메일은 초기값일 뿐이라 읽기 전용이 아니다. */
    @Test
    void naverProviderEmailIsPrefilledButStillEditable() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignup.SESSION_ATTRIBUTE, new PendingSocialSignup(
                "naver-flow", SocialProvider.NAVER, "naver-id", "naver@example.com",
                null, now.minusSeconds(10), now.plusSeconds(590)));

        Document page = Jsoup.parse(mockMvc.perform(get("/social-signup")
                        .session(session).cookie(localeCookie("ko")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var input = page.selectFirst("#userEmail");
        assertThat(input.attr("value")).isEqualTo("naver@example.com");
        assertThat(input.hasAttr("readonly")).isFalse();
        assertThat(input.hasAttr("disabled")).isFalse();
    }

    /** Google 화면에는 이메일 입력 칸이 생기지 않는다. */
    @Test
    void googleSignupStillHasNoEmailField() throws Exception {
        Document page = render("ko");

        assertThat(page.selectFirst("#socialSignupEmailField")).isNull();
        assertThat(page.selectFirst("#userEmail")).isNull();
    }


    /**
     * 기존 계정 연결은 새 users 를 만들지 않으므로 닉네임·약관을 다시 받지 않는다.
     * 두 상태가 각각 하나의 영역으로 갈라져 있어야 JS 가 한쪽만 남길 수 있다.
     */
    @Test
    void theSignupAndLinkModesAreSeparateRegionsWithTheLinkOneClosedByDefault()
            throws Exception {
        Document page = renderKakao("ko");

        // 연령·약관·단계 UI·닉네임·가입 버튼은 모두 신규가입 전용으로 표시된다.
        // 연결 모드에서 JS 가 이 표시만 보고 한 번에 감춘다.
        for (String selector : new String[]{"#socialSignupProgress", "#step-1",
                "#socialSignupBirthDateField", ".terms-card", "#nickname", "#generateNickname"}) {
            assertThat(page.selectFirst(selector).closest("[data-signup-only]"))
                    .as("%s belongs to the signup-only region", selector).isNotNull();
        }
        assertThat(page.selectFirst("#socialSignupSubmit").closest("[data-signup-only]"))
                .isNotNull();
        // 이메일 칸은 두 상태에서 모두 보여야 하므로 신규가입 전용 표시가 붙지 않는다.
        assertThat(page.selectFirst("#userEmail")).isNotNull();
        assertThat(page.selectFirst("#userEmail").closest("[data-signup-only]")).isNull();

        // 연결 전용 영역은 처음에는 닫혀 있고, 신규가입 문맥이 열려 있다.
        assertThat(page.selectFirst("#socialSignupLinkHeader").hasAttr("hidden")).isTrue();
        assertThat(page.selectFirst("#socialSignupExistingAccount").hasAttr("hidden")).isTrue();
        assertThat(page.selectFirst("#socialSignupNewHeader").hasAttr("hidden")).isFalse();
        // 신규가입은 1단계에서 시작한다. 2단계는 닫혀 있다.
        assertThat(page.selectFirst("#step-1").hasAttr("hidden")).isFalse();
        assertThat(page.selectFirst("#step-2").hasAttr("hidden")).isTrue();
        // 연결 form 은 가입 form 밖에 있어 닉네임/약관 검증과 무관하다.
        assertThat(page.selectFirst("form[action=/social-signup] #linkExistingEmail")).isNull();
        assertThat(page.selectFirst("#socialSignupExistingAccount #linkExistingEmail"))
                .isNotNull();
    }

    /** 연결 문맥 제목과 설명도 5개 언어에서 번들로 내려온다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | 카카오",
            "en    | Kakao",
            "ja    | Kakao",
            "zh-CN | Kakao",
            "zh-TW | Kakao"
    })
    void theLinkModeHeaderIsTranslatedInEverySupportedLanguage(String cookie, String brand)
            throws Exception {
        Document page = renderKakao(cookie);

        assertThat(page.selectFirst("#socialSignupLinkTitle").text())
                .as("link title in %s", cookie).isNotBlank().doesNotContain("??");
        String description = page.selectFirst("#socialSignupLinkHeader p").text();
        assertThat(description).isNotBlank().doesNotContain("??", "{0}");
        // provider 표시명은 마이페이지와 같은 key 에서 그대로 온다.
        assertThat(description).contains(brand);
    }

    /** Google 화면에는 연결 전용 영역 자체가 없다. */
    @Test
    void googleStillHasNeitherTheEmailFieldNorTheLinkMode() throws Exception {
        Document page = render("ko");

        assertThat(page.selectFirst("#socialSignupEmailField")).isNull();
        assertThat(page.selectFirst("#socialSignupLinkHeader")).isNull();
        assertThat(page.selectFirst("#socialSignupExistingAccount")).isNull();
        assertThat(page.selectFirst("#nickname")).isNotNull();
    }

    private Document renderKakao(String cookie) throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignup.SESSION_ATTRIBUTE, new PendingSocialSignup(
                "kakao-flow", SocialProvider.KAKAO, "kakao-sub", null, null,
                now.minusSeconds(10), now.plusSeconds(590)));
        return Jsoup.parse(mockMvc.perform(get("/social-signup")
                        .session(session).cookie(localeCookie(cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private Document render(String cookie) throws Exception {
        return Jsoup.parse(mockMvc.perform(get("/social-signup")
                        .session(pendingSession()).cookie(localeCookie(cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MockHttpSession pendingSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignup.SESSION_ATTRIBUTE, new PendingSocialSignup(
                "flow-123", SocialProvider.GOOGLE, "google-sub", "travel@example.com",
                true, Instant.now().minusSeconds(10), Instant.now().plusSeconds(590)));
        return session;
    }

    private Cookie localeCookie(String languageTag) {
        return new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag);
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
