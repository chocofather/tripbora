package com.tripbora.controller.comment;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.PolicyController;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import com.tripbora.service.policy.SignupPolicyService;
import com.tripbora.service.policy.SignupPolicySet;
import com.tripbora.service.policy.GalleryConsentService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retired account opt-in stays absent from real authenticated/public layouts. */
@WebMvcTest({MyReviewImageController.class, PolicyController.class})
@Import(SecurityConfig.class)
class GalleryConsentStage4RenderingTest {
    @Test void onlyPastGalleryGranteesHaveAccessToTheWithdrawalPanel() throws Exception {
        var legacy = new com.tripbora.dto.ReviewImageAdminDto();
        legacy.setId(30L); legacy.setCommentId(20L); legacy.setDestinationId(10L);
        legacy.setDestinationName("과거 리뷰"); legacy.setContent("과거 선택 동의 사진");
        legacy.setImageUrl("/fixture/review.png"); legacy.setGalleryStatus("APPROVED");
        var licensed = new com.tripbora.dto.ReviewImageAdminDto();
        licensed.setId(31L); licensed.setCommentId(21L); licensed.setDestinationId(10L);
        licensed.setDestinationName("신규 리뷰"); licensed.setContent("업로드 이용조건 사진");
        licensed.setImageUrl("/fixture/review.png"); licensed.setGalleryStatus("APPROVED");
        when(photos.myPhotos(7L, 0)).thenReturn(List.of(legacy, licensed));
        Path directory = Path.of("build/reports/legacy-gallery-withdrawal");
        Files.createDirectories(directory);
        var states = List.of(
                new com.tripbora.service.policy.PolicyConsentService.LegacyGalleryWithdrawal(true, true, false, 17L, "legacy-v1"),
                new com.tripbora.service.policy.PolicyConsentService.LegacyGalleryWithdrawal(true, false, true, 17L, "legacy-v1"),
                new com.tripbora.service.policy.PolicyConsentService.LegacyGalleryWithdrawal(false, false, false, null, null));
        var names = List.of("agreement.html", "withdrawn.html", "no-agreement.html");
        for (int index = 0; index < states.size(); index++) {
            legacy.setGalleryStatus(index == 1 ? "REVOKED" : "APPROVED");
            when(galleryConsent.legacyWithdrawalState(7L)).thenReturn(states.get(index));
            String html = mvc.perform(get("/mypage/review-images").param("userId", "999").with(user(member())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var page = Jsoup.parse(html);
            assertThat(page.select("[data-legacy-gallery-withdrawal]")).hasSize(index == 2 ? 0 : 1);
            if (index == 0) {
                assertThat(page.select("[data-legacy-gallery-withdraw]")).hasSize(1);
                assertThat(page.select("[data-legacy-gallery-withdrawal]").text())
                        .contains("과거", "신규", "원본 리뷰");
            } else assertThat(page.select("[data-legacy-gallery-withdraw]")).isEmpty();
            assertThat(page.select("[data-consent-action], input[type=checkbox], dialog")).isEmpty();
            assertThat(page.select("[data-review-photo-delete]")).hasSize(2);
            String css = page.select("link[href*=review-image-management-]").attr("href");
            assertThat(css).matches("/css/review-image-management-[a-f0-9]{32}\\.css");
            mvc.perform(get(css).with(user(member()))).andExpect(status().isOk());
            Files.writeString(directory.resolve(names.get(index)), html);
        }
    }
    @Autowired MockMvc mvc;
    @MockitoBean ReviewImageService photos;
    @MockitoBean SignupPolicyService signupPolicies;
    @MockitoBean GalleryConsentService galleryConsent;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;

    @Test void authenticatedAccountAndPublicPagesHaveNoPhotoConsentPromptOrSettings() throws Exception {
        when(photos.myPhotos(7L, 0)).thenReturn(List.of());
        when(signupPolicies.loadSignupPolicies()).thenReturn(SignupPolicySet.empty());
        for (String path : List.of("/mypage/review-images", "/terms")) {
            String html = mvc.perform(get(path).with(user(member()))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var page = Jsoup.parse(html);
            assertThat(page.select("script[src*=gallery-consent], link[href*=gallery-consent], "
                    + "[data-gallery-consent-dialog], [data-gallery-consent-settings], [data-consent-action]")).isEmpty();
            assertThat(page.select("meta[name=_csrf]")).hasSize(1);
            if (path.contains("review-images")) {
                assertThat(page.select("h1").text()).contains("내 리뷰 사진 관리");
                var script = page.selectFirst("script[src~=review-image-management-[a-f0-9]{32}\\.js]");
                assertThat(script).isNotNull();
                var response = mvc.perform(get(script.attr("src")).with(user(member())))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentType()).contains("javascript");
            }
        }
    }

    @Test void guestsNeverReceiveTheAuthenticatedConsentClient() throws Exception {
        when(signupPolicies.loadSignupPolicies()).thenReturn(SignupPolicySet.empty());
        String html = mvc.perform(get("/terms")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(Jsoup.parse(html).select("script[src*=gallery-consent-], dialog[data-gallery-consent-dialog]")).isEmpty();
        mvc.perform(get("/mypage/review-images")).andExpect(status().is3xxRedirection());
    }

    private CustomUserDetails member() {
        User member = new User();
        member.setId(7L);
        member.setUserRole(UserRole.USER);
        return new CustomUserDetails(member);
    }
}
