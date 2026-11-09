package com.tripbora.controller.destination;

import com.tripbora.config.*;
import com.tripbora.model.*;
import com.tripbora.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.jsoup.Jsoup;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DB 없이 실제 상세 템플릿의 작성 폼 및 내용 해시 리소스를 렌더링한다. */
@WebMvcTest(ReviewFormRenderingTest.PreviewController.class)
@Import({SecurityConfig.class, ReviewFormRenderingTest.PreviewController.class})
class ReviewFormRenderingTest {
    @Controller static class PreviewController {
        @GetMapping("/review-form-preview") String form() { return "destination/detail :: #comment-form"; }
        @GetMapping("/review-section-preview") String section() { return "destination/detail :: #traveler-reviews"; }
        @GetMapping("/review-messages-preview") String messages() { return "destination/detail :: #destination-detail-i18n"; }
    }
    @Autowired MockMvc mvc;
    @Autowired ResourceUrlProvider resources;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean com.tripbora.security.LoginThrottle throttle;
    @MockitoBean com.tripbora.repository.user.UserMapper users;

    @Test void realTemplateProvidesFiveRequiredStarsAndUnchangedPhotoControls() throws Exception {
        var owner = new User(); owner.setId(7L); owner.setUserRole(UserRole.USER);
        var html = mvc.perform(get("/review-form-preview").with(user(new CustomUserDetails(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var form = Jsoup.parse(html);
        assertThat(form.select("input[name=rating][required]").eachAttr("value")).containsExactly("1", "2", "3", "4", "5");
        assertThat(form.select(".comment-controls .image-upload-label, .comment-controls .submit-btn")).hasSize(2);
        assertThat(form.select("#comment-image-preview")).hasSize(1);
        Path directory = Path.of("build/reports/review-ui"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("form.html"), html);
        var section = mvc.perform(get("/review-section-preview").with(user(new CustomUserDetails(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var reviewSection = Jsoup.parse(section);
        assertThat(reviewSection.select("[data-rating-score]")).hasSize(5);
        assertThat(reviewSection.select("[data-comment-sort]").eachAttr("data-comment-sort"))
                .containsExactly("latest", "likes", "rating_desc", "rating_asc");
        assertThat(reviewSection.select("[data-date-sort]")).hasSize(1);
        assertThat(reviewSection.select("#review-composer[hidden]")).hasSize(1);
        Files.writeString(directory.resolve("section.html"), section);
        for (var language : com.tripbora.config.i18n.SupportedLanguage.values()) {
            var cookie = new jakarta.servlet.http.Cookie(com.tripbora.config.i18n.TripBoraLocaleResolver.COOKIE_NAME, language.getLanguageTag());
            for (String part : new String[]{"section", "messages"}) {
                var localized = mvc.perform(get("/review-" + part + "-preview").cookie(cookie).with(user(new CustomUserDetails(owner))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(localized).doesNotContain("??");
                Files.writeString(directory.resolve(part + "-" + language.getLanguageTag() + ".html"), localized);
            }
        }
        for (String resource : new String[]{"/css/comment.css", "/css/content-comment.css", "/css/destination-detail-mobile.css",
                "/js/comment/events.js", "/js/comment/render.js", "/js/comment/api.js", "/js/comment/init.js", "/js/comment/uploadTerms.js"}) {
            String url = resources.getForLookupPath(resource);
            assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
            var response = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            Files.writeString(directory.resolve(resource.substring(resource.lastIndexOf('/') + 1)), response.getContentAsString());
        }
    }
}
