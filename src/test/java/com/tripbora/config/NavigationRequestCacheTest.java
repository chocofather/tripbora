package com.tripbora.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 후 복귀 대상 저장 규칙.
 * 브라우저 보조 요청이 원래 페이지의 SavedRequest 를 덮어쓰면 로그인 후 404 로 이동한다.
 */
class NavigationRequestCacheTest {

    private final NavigationRequestMatcher matcher = new NavigationRequestMatcher();

    @Test
    void chromeDevtoolsProbeIsNotSaved() {
        // 실제 오류 URL. DevTools 가 열려 있으면 배경에서 전송된다.
        MockHttpServletRequest request = get("/.well-known/appspecific/com.chrome.devtools.json");
        request.addHeader("Accept", "*/*");
        request.addHeader("Sec-Fetch-Dest", "empty");

        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    void browserHelperRequestsAreNotSaved() {
        assertThat(matcher.matches(get("/favicon.ico"))).isFalse();
        assertThat(matcher.matches(get("/error"))).isFalse();
        assertThat(matcher.matches(get("/css/detail.css"))).isFalse();
        assertThat(matcher.matches(get("/js/comment/init.js"))).isFalse();
        assertThat(matcher.matches(get("/uploads/icons/marker.png"))).isFalse();

        MockHttpServletRequest ajax = get("/comments/list/page");
        ajax.addHeader("X-Requested-With", "XMLHttpRequest");
        assertThat(matcher.matches(ajax)).isFalse();

        MockHttpServletRequest fetch = get("/comments/list/page");
        fetch.addHeader("Sec-Fetch-Dest", "empty");
        assertThat(matcher.matches(fetch)).isFalse();

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/destinations/7");
        assertThat(matcher.matches(post)).isFalse();
    }

    @Test
    void realPageNavigationIsStillSaved() {
        MockHttpServletRequest navigation = get("/destinations/7");
        navigation.addHeader("Accept", "text/html,application/xhtml+xml");
        navigation.addHeader("Sec-Fetch-Dest", "document");

        assertThat(matcher.matches(navigation)).isTrue();
        // 헤더가 없는 요청도 페이지 이동으로 본다
        assertThat(matcher.matches(get("/destinations/7"))).isTrue();
        assertThat(matcher.matches(get("/mypage"))).isTrue();
    }

    @Test
    void devtoolsProbeCannotOverwriteTheSavedDetailPage() {
        RequestCache cache = navigationRequestCache();
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest detail = get("/destinations/7");
        detail.addHeader("Sec-Fetch-Dest", "document");
        cache.saveRequest(detail, response);

        MockHttpServletRequest devtools = get("/.well-known/appspecific/com.chrome.devtools.json");
        devtools.setSession(detail.getSession());
        devtools.addHeader("Sec-Fetch-Dest", "empty");
        cache.saveRequest(devtools, response);

        SavedRequest saved = cache.getRequest(detail, response);
        assertThat(saved).isNotNull();
        assertThat(saved.getRedirectUrl())
                .contains("/destinations/7")
                .doesNotContain("com.chrome.devtools.json");
    }

    @Test
    void securityConfigUsesTheNavigationRequestCache() throws IOException {
        String config = readFile(
                "src/main/java/com/tripbora/config/SecurityConfig.java");

        assertThat(config)
                .contains("new NavigationRequestMatcher()")
                .contains("http.requestCache(cache -> cache.requestCache(navigationRequestCache))");
        // RESTRICTED 우선 이동 로직은 그대로 둔다
        assertThat(readFile("src/main/java/com/tripbora/config/"
                + "CustomLoginSuccessHandler.java"))
                .contains("UserStatus.RESTRICTED")
                .contains("RestrictedAccountFilter.RESTRICTED_PATH");
    }

    /** SecurityConfig 의 빈과 같은 구성 */
    private RequestCache navigationRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(new NavigationRequestMatcher());
        return cache;
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
    }
}
