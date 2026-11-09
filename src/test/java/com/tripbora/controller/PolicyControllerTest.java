package com.tripbora.controller;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.model.PolicyType;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.policy.SignupPolicy;
import com.tripbora.service.policy.SignupPolicyService;
import com.tripbora.service.policy.SignupPolicySet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(PolicyController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SignupPolicyService signupPolicyService;
    @MockitoBean
    private com.tripbora.service.policy.GalleryConsentService galleryConsentService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Test
    void anonymousReviewPhotoTermsExposeTheSameActualVersionAsHtmlAndJson() throws Exception {
        var terms = new com.tripbora.dto.ReviewPhotoUploadTerms(true, 50L, "20261106-v1",
                "리뷰 사진 업로드 약관", "<p>공개 리뷰 사진의 서비스 내 활용</p>", "ko",
                "/review-photo-terms?versionId=50");
        when(galleryConsentService.uploadTerms(null)).thenReturn(terms);
        when(galleryConsentService.uploadTerms(50L)).thenReturn(terms);
        String html = mockMvc.perform(get("/review-photo-terms").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String stylesheet = org.jsoup.Jsoup.parse(html).select("link[href*=policy-]").attr("href");
        assertThat(stylesheet).matches("/css/policy-[a-f0-9]{32}\\.css");
        mockMvc.perform(get(stylesheet)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("text/css"));
        mockMvc.perform(get("/review-photo-terms").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andExpect(view().name("policy/detail"))
                .andExpect(result -> {
                    var document = org.jsoup.Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("h1").text()).contains(terms.title());
                    assertThat(document.select(".policy-document").text()).contains("서비스 내 활용");
                });
        mockMvc.perform(get("/review-photo-terms").param("versionId", "50")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.policyVersionId").value(50))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contentHtml").value(terms.contentHtml()));
    }

    @Test
    void unknownHistoricalPhotoTermsReturnNotFoundAndNewMethodRemainsCsrfProtected() throws Exception {
        when(galleryConsentService.uploadTerms(999L)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        mockMvc.perform(get("/review-photo-terms").param("versionId", "999")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/review-photo-terms"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserCanReadTheActiveTermsOfService() throws Exception {
        SignupPolicy terms = policy(1L, PolicyType.TERMS_OF_SERVICE, "서비스 이용약관");
        SignupPolicy privacy = policy(2L, PolicyType.PRIVACY_POLICY, "개인정보처리방침");
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(new SignupPolicySet(List.of(terms, privacy)));

        mockMvc.perform(get("/terms"))
                .andExpect(status().isOk())
                .andExpect(view().name("policy/detail"))
                .andExpect(model().attribute("policy", terms))
                .andExpect(result -> {
                    var document = org.jsoup.Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("main .policy-page h1").text())
                            .isEqualTo("서비스 이용약관");
                    assertThat(document.select(".policy-document").text())
                            .contains("TERMS_OF_SERVICE 본문");
                    assertThat(document.select(".footer-operations")).isEmpty();
                });
    }

    @Test
    void anonymousUserCanReadTheActivePrivacyPolicy() throws Exception {
        SignupPolicy terms = policy(1L, PolicyType.TERMS_OF_SERVICE, "서비스 이용약관");
        SignupPolicy privacy = policy(2L, PolicyType.PRIVACY_POLICY, "개인정보처리방침");
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(new SignupPolicySet(List.of(terms, privacy)));

        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(view().name("policy/detail"))
                .andExpect(model().attribute("policy", privacy))
                .andExpect(result -> {
                    var document = org.jsoup.Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("main .policy-page h1").text())
                            .isEqualTo("개인정보처리방침");
                    assertThat(document.select(".policy-document").text())
                            .contains("PRIVACY_POLICY 본문");
                });
    }

    private SignupPolicy policy(long id, PolicyType type, String title) {
        return new SignupPolicy(
                id,
                type,
                "1.0",
                type != PolicyType.PRIVACY_POLICY,
                type == PolicyType.TERMS_OF_SERVICE,
                title,
                "<p>" + type.name() + " 본문</p>",
                "ko",
                LocalDateTime.of(2026, 9, 1, 0, 0));
    }
}
