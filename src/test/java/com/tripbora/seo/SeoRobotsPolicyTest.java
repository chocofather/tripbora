package com.tripbora.seo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SeoRobotsPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/events", "/mypage", "/diaries/12", "/travel-plans/4",
            "/login", "/register", "/users/reset-password/token",
            "/post/write", "/post/12/edit", "/course/write", "/course/7/edit", "/courses/new", "/courses/7/edit",
            "/search", "/travel-plans/invitations/abc_DEF", "/diaries/demo/new",
            "/api/search", "/board/fragment", "/destinations/list-fragment"
    })
    void privateAndUtilityPathsAreNoindex(String path) {
        assertThat(SeoRobotsPolicy.isNoindex(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/destinations", "/destinations/12", "/travel-info",
            "/travel-info/8", "/festivals/3", "/events", "/events/2",
            "/board/list", "/post/11", "/course/9", "/courses", "/courses/9"
    })
    void publicCanonicalPagesRemainIndexable(String path) {
        assertThat(SeoRobotsPolicy.isNoindex(path)).isFalse();
    }
}
