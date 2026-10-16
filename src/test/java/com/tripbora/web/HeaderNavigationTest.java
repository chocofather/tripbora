package com.tripbora.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderNavigationTest {

    @ParameterizedTest
    @CsvSource({
            "/destinations,,domestic",
            "/destinations,domestic,domestic",
            "/destinations,overseas,overseas",
            "/courses,,courses",
            "/courses/12,,courses",
            "/courses/12/edit,,courses",
            "/courses/new,,courses",
            "/board/list,,community",
            "/post/12,,community",
            "/post/write,,community",
            "/travel-info,,travelInfo",
            "/travel-info/12,,travelInfo",
            "/festivals/12,,travelInfo",
            "/diaries,,diaries",
            "/diaries/demo/edit,,diaries",
            "/travel-plans/12,,diaries",
            "/random-travel,,diaries",
            "/support/notices/12,,support",
            "/support/faq,,support",
            "/support/inquiries/new,,support",
            "/events,,events",
            "/events/12,,events"
    })
    void actualRoutesSelectTheirOwningSection(String path, String requestedType, String section) {
        assertThat(new HeaderNavigation(path, requestedType).resolve(null, false)).isEqualTo(section);
    }

    @Test
    void detailUsesResolvedGeographyRatherThanAQueryClaim() {
        assertThat(new HeaderNavigation("/destinations/12", "overseas")
                .resolve("domestic", false)).isEqualTo("domestic");
        assertThat(new HeaderNavigation("/destinations/12", "domestic")
                .resolve("overseas", false)).isEqualTo("overseas");
        assertThat(new HeaderNavigation("/destinations/12", "domestic")
                .resolve(null, false)).isEmpty();
    }

    @Test
    void destinationListFollowsItsTypeParameterAndDomesticDefault() {
        assertThat(new HeaderNavigation("/destinations", "").resolve(null, false))
                .isEqualTo("domestic");
        assertThat(new HeaderNavigation("/destinations", "unknown").resolve(null, false))
                .isEmpty();
    }

    @Test
    void adminCoursePreviewKeepsThePublicCourseSection() {
        assertThat(new HeaderNavigation("/admin/courses/12/preview", null).resolve(null, true))
                .isEqualTo("courses");
        assertThat(new HeaderNavigation("/admin/courses/12/edit", null).resolve(null, false))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"/", "/about", "/search", "/mypage", "/courses-extra", "/events-extra",
            "/destinations-extra", "/admin/destinations", "/course/12"})
    void unrelatedRoutesDoNotGuessASection(String path) {
        assertThat(new HeaderNavigation(path, null).resolve(null, false)).isEmpty();
    }
}
