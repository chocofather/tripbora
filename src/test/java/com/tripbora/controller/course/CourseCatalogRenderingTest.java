package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogPage;
import com.tripbora.dto.CourseThemeDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.course.CourseCatalogService;
import com.tripbora.service.course.CourseService;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 layout/fragment를 통해 독립 코스 목록과 공통 내비게이션을 함께 검증한다. */
@WebMvcTest(CourseCatalogController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class CourseCatalogRenderingTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourseCatalogService catalog;
    @MockitoBean CourseService courses;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean UserMapper users;

    @Test
    void sharedCardFragmentKeepsRecommendationAndAuthorSemanticsWithoutExposingInternalNames() throws Exception {
        var official = card(40, "OFFICIAL", "추천 <일정>", "내부관리자닉네임");
        official.setRegionSummary("오사카 · 교토");
        official.setThemes(List.of(theme(1, "자연"), theme(2, "문화"), theme(3, "미식")));
        var traveler = card(41, "TRAVELER", "여행자 코스", "여행자");
        traveler.setUserId(12L);
        traveler.setImageNoDerivatives(true);
        var withdrawn = card(42, "TRAVELER", "남아 있는 일정", "내부익명값");
        withdrawn.setUserId(13L); withdrawn.setWriterWithdrawn(true);
        stubMasters();
        when(catalog.browse("recommended", null, null, "latest", 1)).thenReturn(
                new CourseCatalogPage(List.of(official, traveler, withdrawn), 3, 1, 1, "OFFICIAL", null, null, "latest"));

        Document page = render("/courses", "ko");
        assertThat(page.select(".course-catalog-card")).hasSize(3);
        assertThat(page.select(".course-catalog-card-link").eachAttr("href"))
                .containsExactly("/courses/40", "/courses/41", "/courses/42");
        var verifiedCard = page.select(".course-catalog-card").get(0);
        assertThat(verifiedCard.select(".course-official-badge").text()).isEqualTo("TripBora 추천");
        assertThat(verifiedCard.select(".course-catalog-author").text()).isEqualTo("TripBora");
        assertThat(verifiedCard.select(".course-theme-badge").eachText()).containsExactly("자연", "문화", "+1");
        assertThat(verifiedCard.select(".course-catalog-title").text()).isEqualTo("추천 <일정>");
        assertThat(verifiedCard.text()).contains("오사카 · 교토", "1박2일", "조회 15").doesNotContain("대한민국 · 일본");
        assertThat(page.select(".course-catalog-card").get(1).text()).contains("대한민국 · 일본");
        assertThat(page.select(".course-catalog-card .course-official-badge")).hasSize(1);
        assertThat(page.select(".course-catalog-card a[href='/users/12']").text()).isEqualTo("여행자");
        assertThat(page.select(".course-catalog-card").get(1).select("img.is-no-derivatives")).hasSize(1);
        assertThat(page.select(".course-catalog-card a[href='/users/13']")).isEmpty();
        assertThat(page.select(".course-catalog-card").get(2).text()).contains("탈퇴한 회원");
        assertThat(page.text()).doesNotContain("내부관리자닉네임", "내부익명값", "TripBora 검증");
        assertThat(page.select(".course-kind-tabs a").eachText()).containsExactly("추천 코스", "여행자 코스");
        assertThat(page.select(".course-kind-tabs a[aria-current=page]").text()).isEqualTo("추천 코스");
        assertThat(page.select(".course-catalog a[href='/courses/new']")).hasSize(1);
        assertThat(page.select(".course-catalog input[type=search], .course-catalog [role=search]")).isEmpty();
        assertThat(page.select(".main-menu a[href='/courses'][aria-current=page]")).hasSize(1);
        assertThat(page.select("#site-menu .site-menu-entry > a[href='/courses'][aria-current=page]")).hasSize(1);
        assertThat(page.select(".global-submenu a[href*='boardType=course'], #site-menu ul a[href*='boardType=course']")).isEmpty();
    }

    @Test
    void compactFilterLinksApplyImmediatelyAndPaginationPreservesTheSelectedScope() throws Exception {
        stubMasters();
        when(catalog.browse("traveler", 20L, 2L, "views", 2)).thenReturn(
                new CourseCatalogPage(List.of(card(40, "OFFICIAL", "일정", "")), 45, 2, 3,
                        "TRAVELER", 20L, 2L, "views"));
        Document page = render("/courses?kind=traveler&countryId=20&themeId=2&sort=views&page=2", "ko");
        var filters = page.selectFirst(".course-catalog-filters");
        assertThat(filters.select("form, select, button[type=submit], input")).isEmpty();
        assertThat(filters.select("details")).hasSize(3);
        assertThat(filters.select("details[data-filter=country] summary").text()).contains("일본");
        assertThat(filters.select("details[data-filter=theme] summary").text()).contains("문화");
        assertThat(filters.select("details[data-filter=sort] summary").text()).contains("인기순");
        assertThat(filters.select("a")).isNotEmpty().allSatisfy(link ->
                assertThat(link.attr("href")).startsWith("/courses?").doesNotContain("page=", "kind=ALL", "OFFICIAL", "TRAVELER"));
        assertThat(page.select(".course-kind-tabs a[aria-current=page]").text()).isEqualTo("여행자 코스");
        assertThat(page.select(".course-catalog-pagination a")).isNotEmpty().allSatisfy(link ->
                assertThat(link.attr("href")).contains("kind=traveler", "countryId=20", "themeId=2", "sort=views", "page="));
        assertThat(page.select(".course-kind-tabs a")).allSatisfy(link ->
                assertThat(link.attr("href")).contains("countryId=20", "themeId=2", "sort=views").doesNotContain("page=2"));
    }

    @ParameterizedTest
    @CsvSource({"ko, 여행코스", "en, Travel Courses", "ja, 旅行コース", "zh-CN, 旅行路线", "zh-TW, 旅遊路線"})
    void anEmptyCatalogKeepsTheCreateEntryAndLocalizedNavigation(String language, String label) throws Exception {
        stubMasters();
        when(catalog.browse("recommended", null, null, "latest", 1)).thenReturn(
                new CourseCatalogPage(List.of(), 0, 1, 0, "OFFICIAL", null, null, "latest"));
        Document page = render("/courses", language);
        assertThat(page.select(".course-catalog h1").text()).isEqualTo(label);
        assertThat(page.select(".main-menu a[href='/courses']").text()).isEqualTo(label);
        assertThat(page.select(".course-catalog-empty").text()).isNotBlank().doesNotContain("??");
        assertThat(page.select(".course-catalog a[href='/courses/new']")).hasSize(1);
        assertThat(page.select(".course-catalog-pagination")).isEmpty();
        assertThat(page.select(".course-catalog-card")).isEmpty();
    }

    @Test
    void unknownPositiveFilterIdsKeepTheFilterButtonsReadable() throws Exception {
        stubMasters();
        when(catalog.browse("recommended", 999L, 999L, "latest", 1)).thenReturn(
                new CourseCatalogPage(List.of(), 0, 1, 1, "OFFICIAL", 999L, 999L, "latest"));
        var page = render("/courses?countryId=999&themeId=999", "ko");
        assertThat(page.select("details[data-filter=country] summary").text()).isEqualTo("지역");
        assertThat(page.select("details[data-filter=theme] summary").text()).isEqualTo("테마");
    }

    private void stubMasters() {
        var korea = new CountryCategory(); korea.setId(10L); korea.setRegionName("대한민국"); korea.setCode("KR");
        var japan = new CountryCategory(); japan.setId(20L); japan.setRegionName("일본"); japan.setCode("JP");
        when(courses.getCourseCountries(any(SupportedLanguage.class))).thenReturn(List.of(korea, japan));
        when(courses.getCourseThemes()).thenReturn(List.of(theme(1, "자연"), theme(2, "문화"), theme(3, "미식")));
    }

    private Document render(String path, String language) throws Exception {
        return Jsoup.parse(mvc.perform(get(path).cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, language)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static CourseThemeDto theme(long id, String name) {
        var theme = new CourseThemeDto(); theme.setId(id); theme.setName(name); return theme;
    }

    private static CourseCardDto card(long id, String kind, String title, String nickname) {
        var card = new CourseCardDto(); card.setId(id); card.setCourseKind(kind); card.setTitle(title);
        card.setNickname(nickname); card.setUserId(1L); card.setViews(15); card.setDayCount(2);
        card.setDurationLabel("1박2일");
        card.setRepresentativeImageUrl("/uploads/course/cover/example.jpg");
        card.setCountryNames(List.of("대한민국", "일본")); return card;
    }
}
