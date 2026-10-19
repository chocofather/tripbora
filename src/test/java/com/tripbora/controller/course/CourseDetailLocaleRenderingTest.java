package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseDayDto;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코스 상세를 언어별로 그려 본다.
 *
 * <p>고정 문구는 언어에 맞게 바뀌고, 사용자가 쓴 제목·소개·닉네임은 그대로 남아야 한다.
 */
@WebMvcTest(CourseController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class CourseDetailLocaleRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;
    @MockitoBean
    private CountryCategoryService countryCategoryService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    static Stream<Arguments> localizedScreens() {
        return Stream.of(
                Arguments.of(SupportedLanguage.KOREAN,
                        new String[]{"나만의 여행 코스", "작성자", "작성일", "수정일", "조회수",
                                "코스 소개", "총 2개의 여행지", "여행 일정", "지역",
                                "수정", "삭제", "댓글", "최신순", "등록", "여행코스 목록",
                                "댓글을 작성하려면 로그인하세요."},
                        "이 여행 코스를 삭제하시겠습니까?"),
                Arguments.of(SupportedLanguage.ENGLISH,
                        new String[]{"My Travel Course", "Author", "Posted", "Updated", "Views",
                                "Course Overview", "2 destinations in total", "Itinerary", "Area",
                                "Edit", "Delete", "Comments", "Newest", "Post", "Back to travel courses",
                                "Log in to leave a comment."},
                        "Delete this travel course?"),
                Arguments.of(SupportedLanguage.JAPANESE,
                        new String[]{"わたしだけの旅行コース", "投稿者", "投稿日", "更新日", "閲覧数",
                                "コース紹介", "全2カ所の旅行スポット", "旅行日程", "地域",
                                "編集", "削除", "コメント", "新着順", "投稿", "旅行コース一覧へ",
                                "コメントするにはログインしてください。"},
                        "この旅行コースを削除しますか？"),
                Arguments.of(SupportedLanguage.CHINESE_SIMPLIFIED,
                        new String[]{"我的旅行路线", "作者", "发布日期", "更新日期", "浏览量",
                                "路线简介", "共 2 个旅行地", "旅行行程", "地区",
                                "编辑", "删除", "评论", "最新", "发布", "返回旅行路线列表",
                                "请登录后发表评论。"},
                        "确定要删除这条旅行路线吗？"),
                Arguments.of(SupportedLanguage.CHINESE_TRADITIONAL,
                        new String[]{"我的旅行路線", "作者", "發布日期", "更新日期", "瀏覽次數",
                                "路線簡介", "共 2 個旅遊景點", "旅遊行程", "地區",
                                "編輯", "刪除", "留言", "最新", "發布", "返回旅遊路線列表",
                                "請登入後留言。"},
                        "確定要刪除這條旅行路線嗎？"));
    }

    @ParameterizedTest
    @MethodSource("localizedScreens")
    void fixedUiFollowsTheChosenLanguageWhileUserWrittenTextStays(
            SupportedLanguage language, String[] expectedTexts, String expectedDeleteConfirm)
            throws Exception {
        when(courseService.getCourseDetail(7L, null, language)).thenReturn(course());

        String body = mockMvc.perform(get("/courses/{id}", 7L)
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME,
                                language.getLanguageTag())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(expectedTexts);
        // 삭제 확인 문구는 화면이 스크립트에 건네준다.
        assertThat(body).contains("data-confirm=\"" + expectedDeleteConfirm + "\"");
        // 사용자가 쓴 제목·소개·닉네임과 STOP 이름·지역명은 서버가 준 값 그대로다.
        assertThat(body)
                .contains("여행 코스 테스트")
                .contains("여행 코스 입니다")
                .contains("여행자민준")
                .contains("경복궁")
                .contains("종로구");
        // 비로그인 안내는 textarea 안에만 있고, 클릭 이동은 destination과 같은 data 계약으로 처리한다.
        assertThat(body)
                .contains("data-guest-comment-redirect")
                .doesNotContain("href=\"/login?redirect=/courses/7\"");
        // 없는 메시지 키가 남아 있지 않다.
        assertThat(body).doesNotContain("??");
    }

    @Test
    void multiDayScheduleRendersTimesAndEscapesDayTitlesAndMemos() throws Exception {
        CourseDetailDto course = course();
        CourseDayDto first = new CourseDayDto();
        first.setDayNumber(1);
        first.setTitle("<script>첫날 제목</script>");
        CourseStopDto stop = course.getStops().get(0);
        stop.setStartTime("23:00");
        stop.setEndTime("01:00");
        stop.setItemType("STAY");
        stop.setMemo("<img src=x onerror=alert(1)> 메모");
        first.setItems(List.of(stop));
        CourseDayDto second = new CourseDayDto();
        second.setDayNumber(2);
        second.setTitle("둘째 날");
        second.setItems(List.of(course.getStops().get(1)));
        course.setDays(List.of(first, second));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);

        String body = mockMvc.perform(get("/courses/7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Day 1", "Day 2", "23:00", "숙소", "둘째 날",
                "&lt;script&gt;첫날 제목&lt;/script&gt;", "&lt;img src=x onerror=alert(1)&gt; 메모")
                .doesNotContain("01:00", "<script>첫날 제목</script>", "<img src=x onerror=alert(1)>");
        var document = org.jsoup.Jsoup.parse(body);
        var memo = document.selectFirst(".course-day-route details.course-item-memo");
        assertThat(memo).isNotNull();
        assertThat(memo.selectFirst("summary").text()).isEqualTo("<img src=x onerror=alert(1)> 메모");
        assertThat(memo.parents().stream().anyMatch(parent -> parent.tagName().equals("a"))).isFalse();
        assertThat(document.select(".course-day-route a[href='/destinations/15']")).hasSize(2);
        var rows = document.select(".course-day-route .course-stop");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).selectFirst(".course-stop-heading").text()).isEqualTo("23:00 · 경복궁");
        assertThat(rows.get(1).selectFirst(".course-stop-heading").text()).isEqualTo("북촌한옥마을");
        assertThat(rows.select(".course-stop-body h3, .course-stop-body .course-item-time")).isEmpty();
        assertThat(rows.get(1).select(".course-item-memo")).isEmpty();
        assertThat(document.select(".course-day-route > .course-timeline > .course-stop:last-child")).hasSize(2);
    }

    static Stream<Arguments> optionalScheduleTimes() {
        return Stream.of(
                Arguments.of("09:00", null, "09:00"),
                Arguments.of(null, "18:00", null),
                Arguments.of("23:00", "01:00", "23:00"),
                Arguments.of(null, null, null));
    }

    @Test
    void representativeImagePrecedesIntroductionAndDaysStayReadableWithoutJavascript() throws Exception {
        CourseDetailDto course = course();
        course.setRepresentativeImageUrl("/uploads/course/cover.jpg");
        CourseDayDto first = new CourseDayDto();
        first.setDayNumber(1);
        first.setItems(List.of(course.getStops().get(0)));
        CourseDayDto second = new CourseDayDto();
        second.setDayNumber(2);
        second.setItems(List.of(course.getStops().get(1)));
        course.setDays(List.of(first, second));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);

        String body = mockMvc.perform(get("/courses/7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = org.jsoup.Jsoup.parse(body);
        assertThat(document.selectFirst("[data-course-hero-image]").attr("src"))
                .isEqualTo("/uploads/course/cover.jpg");
        assertThat(body.indexOf("data-course-hero")).isLessThan(body.indexOf("class=\"course-introduction\""));
        assertThat(document.select("section[data-course-day]")).hasSize(2);
        assertThat(document.select("section[data-course-day][hidden]")).isEmpty();
        assertThat(document.select("[data-course-day-nav][hidden] button")).hasSize(3);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = "/images/default.png")
    void absentAndDefaultRepresentativeImagesDoNotReserveABlankHero(String imageUrl) throws Exception {
        CourseDetailDto course = course();
        course.setRepresentativeImageUrl(imageUrl);
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(body).select("[data-course-hero]")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("optionalScheduleTimes")
    void optionalTimesOnlyRenderTheirEnteredValues(String start, String end, String expected)
            throws Exception {
        CourseDetailDto course = course();
        CourseStopDto stop = course.getStops().get(0);
        stop.setName("줄바꿈이 필요한 아주 긴 여행지 이름 산굼부리와 주변 탐방로");
        stop.setStartTime(start);
        stop.setEndTime(end);
        CourseDayDto day = new CourseDayDto();
        day.setDayNumber(1);
        day.setItems(List.of(stop));
        course.setDays(List.of(day));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);

        String body = mockMvc.perform(get("/courses/7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = org.jsoup.Jsoup.parse(body);
        var row = document.selectFirst(".course-day-route .course-stop");
        var header = row.selectFirst(".course-stop-heading");
        assertThat(header).isNotNull();
        assertThat(header.selectFirst(".course-stop-title-link").text()).isEqualTo(stop.getName());
        assertThat(row.select(".course-stop-title-link")).hasSize(1);
        assertThat(row.select(".course-stop-body h3, .course-stop-body .course-item-time")).isEmpty();
        var times = header.select(".course-item-time");
        if (expected == null) {
            assertThat(times).isEmpty();
            assertThat(header.select(".course-item-time-separator")).isEmpty();
        } else {
            assertThat(times).hasSize(1);
            assertThat(times.first().text()).isEqualTo(expected);
            assertThat(times.first().select("span:empty")).isEmpty();
            assertThat(header.text()).isEqualTo(expected + " · " + stop.getName());
        }
    }

    private CourseDetailDto course() {
        CourseDetailDto course = new CourseDetailDto();
        course.setId(7L);
        course.setUserId(5L);
        course.setTitle("여행 코스 테스트");
        course.setContent("<p>여행 코스 입니다</p>");
        course.setNickname("여행자민준");
        course.setCreatedAt(Timestamp.valueOf("2026-01-02 10:00:00"));
        course.setUpdatedAt(Timestamp.valueOf("2026-01-03 11:00:00"));
        course.setViews(12);
        // 수정·삭제 버튼까지 그려 보려면 내 코스여야 한다.
        course.setMyCourse(true);
        course.setStops(List.of(
                stop(15L, 1, "경복궁", "종로구"),
                stop(16L, 2, "북촌한옥마을", "종로구")));
        return course;
    }

    @Test
    void mixedTimelineOffersPlaceIdAndNameSearchEvenWithoutCoordinates() throws Exception {
        CourseDetailDto course = course();
        CourseStopDto linked = course.getStops().get(0);
        linked.setItemId(100L);
        linked.setLatitude(new java.math.BigDecimal("37.5"));
        linked.setLongitude(new java.math.BigDecimal("127.1"));
        linked.setCountryCode("KR");
        CourseDayDto day = new CourseDayDto(); day.setDayNumber(1);
        java.util.ArrayList<CourseStopDto> items = new java.util.ArrayList<>(); items.add(linked);
        CourseStopDto overseas = course.getStops().get(1);
        overseas.setName("난바역");
        overseas.setGooglePlaceId("ChIJ_namba_test");
        items.add(overseas); // 좌표가 없어도 Place ID로 정확한 장소를 연다.
        for (String type : List.of("PLACE", "STAY", "FOOD", "TRANSPORT", "ETC")) {
            CourseStopDto direct = stop(null, items.size() + 1, "<직접 " + type + ">", null);
            direct.setItemId(100L + items.size()); direct.setItemType(type);
            direct.setAddress("사용자 주소"); direct.setMemo("직접 일정 메모"); direct.setStartTime("09:00");
            if (type.equals("STAY")) {
                direct.setLatitude(java.math.BigDecimal.ZERO); direct.setLongitude(java.math.BigDecimal.ZERO);
                direct.setAddress("서울 중구 을지로 30");
            }
            items.add(direct);
        }
        day.setItems(items); course.setDays(List.of(day));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = org.jsoup.Jsoup.parse(body);
        assertThat(document.select(".course-day-route .course-stop")).hasSize(7);
        assertThat(document.select(".course-stop-direct")).hasSize(5);
        assertThat(document.select(".course-stop-direct .course-stop-image-wrap, .course-stop-direct a.course-stop-title-link")).isEmpty();
        assertThat(document.select(".course-stop-direct .course-item-type").eachText())
                .containsExactly("장소", "숙소", "식당·카페", "교통", "기타");
        var links = document.select("a.course-map-link");
        assertThat(links).hasSize(7);
        assertThat(links.eachText())
                .containsOnly("지도에서 보기 ↗");
        assertThat(links).allSatisfy(link -> {
            assertThat(link.attr("target")).isEqualTo("_blank");
            assertThat(link.attr("rel")).isEqualTo("noopener noreferrer");
            assertThat(link.attr("href")).startsWith("https://www.google.com/maps/search/?api=1&query=")
                    .doesNotContain("key=", "embed");
        });
        assertThat(java.net.URLDecoder.decode(links.get(0).attr("href"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=경복궁 종로구");
        assertThat(java.net.URLDecoder.decode(links.get(1).attr("href"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=난바역&query_place_id=ChIJ_namba_test");
        assertThat(java.net.URLDecoder.decode(links.get(3).attr("href"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=<직접 STAY> 서울 중구 을지로 30");
        assertThat(document.select(".course-inline-map, .course-location-canvas, [data-course-map-toggle]")).isEmpty();
        assertThat(body).doesNotContain("지도에서 크게 보기", "data-google-maps-key");
        assertThat(body).contains("&lt;직접 STAY&gt;").doesNotContain("/destinations/null");
    }

    @Test
    void mapsHideItemsWithoutSearchDataAndRetainCoordinateFallback() throws Exception {
        CourseDetailDto course = course();
        CourseDayDto day = new CourseDayDto(); day.setDayNumber(1);
        CourseStopDto empty = stop(null, 1, null, null);
        CourseStopDto fallback = stop(null, 2, null, null);
        fallback.setLatitude(new java.math.BigDecimal("37.5"));
        fallback.setLongitude(new java.math.BigDecimal("127.1"));
        day.setItems(List.of(empty, fallback)); course.setDays(List.of(day));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var links = org.jsoup.Jsoup.parse(body).select("a.course-map-link");
        assertThat(links).hasSize(1);
        assertThat(java.net.URLDecoder.decode(links.first().attr("href"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=37.5,127.1");
    }

    @Test
    void multipleCountriesKeepOrderedMetaAndSingleCountryRemainsCompact() throws Exception {
        CourseDetailDto course = course();
        course.setCountryNames(List.of("일본", "프랑스", "스위스"));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(body).select(".course-meta").text()).contains("일본 · 프랑스 · 스위스");
        course.setCountryNames(List.of("일본"));
        assertThat(course.getCountryLabel()).isEqualTo("일본");
        course.setCountryNames(List.of());
        assertThat(course.getCountryLabel()).isEmpty();
    }

    @Test
    void rendersTheCourseCtaAndModalAndExportsABrowserFixture() throws Exception {
        var course = course();
        course.setCourseKind("OFFICIAL");
        course.setSummary("오사카를 처음 방문하는 여행자를 위한 여행코스입니다.");
        course.setRepresentativeImageUrl("/images/about/travel-course.png");
        course.setCountryNames(List.of("일본"));
        var days = new java.util.ArrayList<CourseDayDto>();
        for (int i = 1; i <= 4; i++) {
            var day = new CourseDayDto(); day.setDayNumber(i); day.setTitle("여행 " + i + "일차");
            day.setItems(i == 1 ? course.getStops() : List.of()); days.add(day);
        }
        course.setDays(days);
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var html = org.jsoup.Jsoup.parse(body);
        assertThat(html.select(".course-detail-columns > .course-plan-action")).hasSize(1);
        assertThat(html.select("[data-course-plan-open]").text()).isEqualTo("이 일정으로 여행 만들기");
        assertThat(html.select(".course-plan-eyebrow").text()).isEqualTo("3박 4일");
        assertThat(html.select("#course-plan-dialog")).hasSize(1);
        assertThat(html.select("#course-plan-name").val()).isEqualTo(course.getTitle());
        assertThat(html.select("[data-course-plan-form]").attr("action")).isEqualTo("/travel-plans");
        var fixture = java.nio.file.Path.of("build/reports/course-plan-ui/detail-fixture.html");
        java.nio.file.Files.createDirectories(fixture.getParent());
        java.nio.file.Files.writeString(fixture, body, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void timelineUsesLocationLabelWhileKeepingMapQueryAndLayout() throws Exception {
        CourseDetailDto course = course();
        CourseStopDto palace = course.getStops().get(0);
        palace.setLocationLabel("서울 종로구");
        CourseStopDto paris = course.getStops().get(1);
        paris.setRegionName("파리"); paris.setLocationLabel("프랑스 파리");
        CourseDayDto day = new CourseDayDto(); day.setDayNumber(1); day.setItems(course.getStops());
        course.setDays(List.of(day));
        when(courseService.getCourseDetail(7L, null, SupportedLanguage.KOREAN)).thenReturn(course);
        String body = mockMvc.perform(get("/courses/7")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = org.jsoup.Jsoup.parse(body);
        assertThat(document.select(".course-stop-region strong").eachText())
                .containsExactly("서울 종로구", "프랑스 파리");
        assertThat(java.net.URLDecoder.decode(document.selectFirst("a.course-map-link").attr("href"),
                java.nio.charset.StandardCharsets.UTF_8)).endsWith("query=경복궁 종로구");
    }

    private CourseStopDto stop(Long destinationId, int visitOrder, String name, String regionName) {
        CourseStopDto stop = new CourseStopDto();
        stop.setDestinationId(destinationId);
        stop.setVisitOrder(visitOrder);
        stop.setName(name);
        stop.setRegionId(235L);
        stop.setRegionName(regionName);
        return stop;
    }
}
