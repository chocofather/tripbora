package com.tripbora.controller;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.controller.recommend.RandomTravelController;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.web.HeaderNavigation;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = RandomTravelController.class,
        properties = {
                "app.contact-email=contact@tripbora.test",
                "app.operator-name=운영자테스트"
        })
@Import({SecurityConfig.class, I18nConfig.class})
class HeaderProfileMenuTest {

    @Test
    void commonAssetsUseTheirContentHashAndResolveToTheBundledBytes() throws Exception {
        var document = page();
        for (String asset : List.of("css/style.css", "css/course-themes.css",
                "js/language-menu.js", "js/main.js", "js/menu.js", "js/admin-content-moderation.js")) {
            byte[] expected;
            try (var input = getClass().getResourceAsStream("/static/" + asset)) {
                expected = input.readAllBytes();
            }
            String hash = org.springframework.util.DigestUtils.md5DigestAsHex(expected);
            int dot = asset.lastIndexOf('.');
            String versioned = "/" + asset.substring(0, dot) + "-" + hash + asset.substring(dot);
            assertThat(document.select("[href], [src]").stream()
                    .anyMatch(element -> versioned.equals(element.attr("href"))
                            || versioned.equals(element.attr("src"))))
                    .as("content-versioned %s", asset).isTrue();
            var response = mockMvc.perform(get(versioned)).andExpect(status().isOk())
                    .andReturn().getResponse();
            assertThat(response.getContentAsByteArray()).isEqualTo(expected);
            assertThat(response.getContentType()).startsWith(asset.endsWith(".css") ? "text/css" : "text/javascript");
        }
        mockMvc.perform(get("/css/style-00000000000000000000000000000000.css"))
                .andExpect(status().isNotFound());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Test
    void guestDoesNotReceiveProfileOrAdminControls() throws Exception {
        var document = page();

        assertThat(document.select("#profile-menu-toggle, #profile-menu")).isEmpty();
        assertThat(document.select("a[href='/admin'], .admin-link")).isEmpty();
    }

    @Test
    void regularUserReceivesMypageAndPostLogoutWithoutAdminEntry() throws Exception {
        var document = page("USER");

        assertThat(document.select(
                "#profile-menu-toggle[aria-controls=profile-menu][aria-expanded=false]"))
                .hasSize(1);
        assertThat(document.select("#profile-menu[hidden]")).hasSize(1);
        assertThat(document.select("#profile-menu a[href='/mypage']").text())
                .isEqualTo("마이페이지");
        assertThat(document.select("#profile-menu a[href='/admin']")).isEmpty();
        assertThat(document.select("#profile-menu form[action='/logout'][method=post]"))
                .hasSize(1);
        assertThat(document.select(
                "#profile-menu form[action='/logout'] input[name=redirect]"))
                .hasSize(1);
        assertThat(document.select("#auth-area > .logout-form, .admin-link")).isEmpty();
    }

    @Test
    void adminReceivesServerRenderedAdminEntryInsideTheProfileMenu() throws Exception {
        var document = page("ADMIN");

        assertThat(document.select("#profile-menu a.profile-menu-admin[href='/admin']").text())
                .isEqualTo("관리자 페이지");
        assertThat(document.select("#auth-area > a[href='/admin']")).isEmpty();
    }

    /**
     * /admin/** 은 한국어 고정 영역이라, 들어가는 문 이름도 언어를 따라가지 않는다.
     * 같은 메뉴의 다른 항목은 그대로 현재 언어로 보인다.
     */
    @ParameterizedTest
    @CsvSource({
            "ko, 프로필, 마이페이지, 로그아웃",
            "en, Profile, My Page, Log out",
            "ja, プロフィール, マイページ, ログアウト",
            "zh-CN, 个人资料, 我的页面, 退出登录",
            "zh-TW, 個人資料, 我的頁面, 登出"
    })
    void theAdminEntryStaysKoreanWhileTheRestOfTheProfileMenuFollowsTheLocale(
            String tag, String profile, String mypage, String logout) throws Exception {
        var document = page("ADMIN", tag);

        assertThat(document.select("#profile-menu a.profile-menu-admin[href='/admin']").text())
                .isEqualTo("관리자 페이지");
        assertThat(document.select("#profile-menu .profile-menu-title").text()).isEqualTo(profile);
        assertThat(document.select("#profile-menu a[href='/mypage']").text()).isEqualTo(mypage);
        assertThat(document.select("#profile-menu .profile-menu-logout").text()).isEqualTo(logout);
    }

    @ParameterizedTest
    @CsvSource({"ko", "en", "ja", "zh-CN", "zh-TW"})
    void aRegularUserNeverSeesTheAdminEntryInAnyLanguage(String tag) throws Exception {
        var document = page("USER", tag);

        assertThat(document.select("#profile-menu a[href='/admin']")).isEmpty();
        assertThat(document.select("#profile-menu")).hasSize(1);
    }

    @Test
    void headerNavigationRemainsUnchanged() throws Exception {
        assertThat(page().select(".main-menu > .menu-item > a").eachText())
                .containsExactlyElementsOf(List.of(
                        "국내", "해외", "여행코스", "여행 커뮤니티", "여행정보",
                        "여행기록", "고객센터", "이벤트"));
    }

    @ParameterizedTest
    @CsvSource({
            "/courses, /courses",
            "/courses?kind=OFFICIAL, /courses",
            "/courses/40, /courses",
            "/board/list, /board/list",
            "/board/list?postType=tip, /board/list",
            "/post/40, /board/list",
            "/post/write, /board/list"
    })
    void courseAndCommunityContextsSelectOnlyTheirOwnPrimaryLink(String currentUri, String selectedHref)
            throws Exception {
        // 기존 공개 view에 메뉴가 받는 model만 지정해 desktop/mobile fragment를 함께 렌더링한다.
        var document = Jsoup.parse(mockMvc.perform(get("/random-travel")
                        .flashAttr("headerNavigation", new HeaderNavigation(currentUri.split("\\?", 2)[0], null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(document.select(".main-menu a[aria-current=page]").eachAttr("href"))
                .containsExactly(selectedHref);
        assertThat(document.select("#site-menu [aria-current=page]")).hasSize(1);
        if ("/courses".equals(selectedHref)) {
            assertThat(document.select("#site-menu .site-menu-entry > a[aria-current=page]").eachAttr("href"))
                    .containsExactly("/courses");
        } else {
            assertThat(document.select("#site-menu .site-menu-group > summary[aria-current=page]").text())
                    .isEqualTo("여행 커뮤니티");
        }
    }

    @Test
    void anAdminCoursePreviewKeepsTheCourseSectionSelected() throws Exception {
        var document = Jsoup.parse(mockMvc.perform(get("/random-travel")
                        .flashAttr("headerNavigation", new HeaderNavigation("/admin/courses/40/preview", null))
                        .flashAttr("adminPreview", true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(document.select(".main-menu a[aria-current=page]").eachAttr("href"))
                .containsExactly("/courses");
    }

    @Test
    void logoIsANavigationLinkRatherThanAPageHeading() throws Exception {
        var document = page();

        assertThat(document.select("header .logo")).hasSize(1);
        assertThat(document.select("header h1.logo")).isEmpty();
        assertThat(document.selectFirst("header .logo").tagName()).isEqualTo("div");
        assertThat(document.select("header .logo > a[href='/'] img")).hasSize(1);
    }

    @Test
    void selectedLocaleChangesThePublicLangHeaderAndFiveOptionSelector() throws Exception {
        var document = pageWithLocale("zh-TW");

        assertThat(document.selectFirst("html").attr("lang")).isEqualTo("zh-TW");
        assertThat(document.select(".main-menu > .menu-item > a").eachText())
                .containsExactly("國內", "海外", "旅遊路線", "旅遊社群", "旅遊資訊",
                        "旅遊記錄", "客服中心", "活動");
        assertThat(document.select(
                ".search-box .locale-option-form input[name=languageTag]").eachAttr("value"))
                .containsExactly("ko", "en", "ja", "zh-CN", "zh-TW");
        assertThat(document.select(".language-menu-option[aria-current=true]").text())
                .contains("繁體中文");
        assertThat(document.select(
                ".search-box .locale-option-form input[name=_csrf]")).hasSize(5);
        assertThat(document.select(".site-menu-utilities .locale-option-form input[name=languageTag]")
                .eachAttr("value")).containsExactly("ko", "en", "ja", "zh-CN", "zh-TW");
        assertThat(document.select(".site-menu-utilities .locale-option-form input[name=_csrf]"))
                .hasSize(5);
        assertThat(document.select(".site-menu-language .site-menu-language-back")).hasSize(1);
        assertThat(document.select(".site-menu-language .language-menu-option[aria-current=true]").text())
                .contains("繁體中文");
        assertThat(document.select(".site-menu-utilities > a.site-menu-about[href='/about']"))
                .hasSize(1);
        assertThat(document.select(".main-menu > .menu-item > .submenu-col a").eachText())
                .containsExactly(
                        "旅遊問答", "旅遊攻略",
                        // 여행정보 하위는 국내정보 / 해외정보 / 축제·행사 셋뿐이다
                        "國內資訊", "海外資訊", "節慶資訊",
                        "我的旅遊日記", "一起規劃", "隨機旅行",
                        "公告", "常見問題", "1:1 諮詢",
                        "進行中活動", "即將開始", "已結束活動");
        assertThat(document.selectFirst("#header-search-input").attr("placeholder"))
                .isEqualTo("想去哪裡旅行？");
        assertThat(document.selectFirst("#search-toggle").attr("aria-label"))
                .isEqualTo("開啟搜尋");
        assertThat(document.selectFirst("#site-menu-toggle").attr("data-open-label"))
                .isEqualTo("開啟主選單");
        assertThat(document.selectFirst("#site-menu-toggle").attr("data-close-label"))
                .isEqualTo("關閉主選單");
        assertThat(document.select(".footer-main > .footer-brand, "
                + ".footer-main > .footer-nav, .footer-main > .footer-contact"))
                .hasSize(3);
        assertThat(document.select(".footer-nav .footer-links a").eachText())
                .containsExactly(
                        "關於 TripBora", "使用條款", "隱私權政策");
        assertThat(document.select(".footer-nav .footer-links a").eachAttr("href"))
                .containsExactly(
                        "/about", "/terms", "/privacy");
        assertThat(document.select(".footer-contact .footer-links a").eachText())
                .containsExactly("客服中心", "1:1 諮詢");
        assertThat(document.select(".footer-contact .footer-links a").eachAttr("href"))
                .containsExactly("/support/notices", "/support/inquiries");
        assertThat(document.select(".footer-operations .footer-operator-label").text())
                .isEqualTo("營運者");
        assertThat(document.select(".footer-operations .footer-operator-name").text())
                .isEqualTo("운영자테스트");
        assertThat(document.select(".footer-operations .footer-contact-label").text())
                .isEqualTo("聯絡我們");
        assertThat(document.select(".footer-operations a.footer-contact-email").eachAttr("href"))
                .containsExactly("mailto:contact@tripbora.test");
        assertThat(document.select(".footer-operations a.footer-contact-email").text())
                .isEqualTo("contact@tripbora.test");
        assertThat(document.select(".footer-inquiry-link, .footer-contact-description")).isEmpty();
        assertThat(document.select(".footer-description").text())
                .isEqualTo("看見旅程，留下回憶。");
        assertThat(document.select(".footer-info").text())
                .contains(String.valueOf(Year.now().getValue()), "Tripbora")
                .doesNotContain("travel@diary.com");
        assertThat(document.select("footer a[href='#']")).isEmpty();
    }

    @Test
    void aNarrowScreenGetsAMenuButtonBesideSearchAndProfile() throws Exception {
        org.jsoup.nodes.Document page = page();

        // 실제 button 이고 열림 상태를 알린다
        assertThat(page.select("button#site-menu-toggle")).hasSize(1);
        assertThat(page.selectFirst("button#site-menu-toggle").attr("aria-expanded"))
                .isEqualTo("false");
        assertThat(page.selectFirst("button#site-menu-toggle").attr("aria-controls"))
                .isEqualTo("site-menu");
        assertThat(page.selectFirst("button#site-menu-toggle").attr("aria-label")).isNotBlank();

        // 검색·프로필과 같은 줄에 있어 좁은 화면에서도 셋 다 닿는다
        assertThat(page.select(".search-box > #site-menu-toggle")).hasSize(1);
        assertThat(page.select(".search-box #search-toggle")).hasSize(1);
        assertThat(page.select(".search-box #auth-area")).hasSize(1);

        // 메뉴 판은 닫힌 채로 온다
        assertThat(page.selectFirst("#site-menu").hasAttr("hidden")).isTrue();
    }

    @Test
    void theNarrowMenuLeadsToExactlyTheSamePlacesAsTheWideOne() throws Exception {
        org.jsoup.nodes.Document page = page();

        // 국내/해외/코스는 직접 링크, 나머지는 같은 다국어 제목의 접힌 그룹이다.
        assertThat(page.select("#site-menu .site-menu-entry > a").eachAttr("href"))
                .containsExactlyElementsOf(page.select(".main-menu > .menu-item > a")
                        .stream().limit(3).map(link -> link.attr("href")).toList());
        assertThat(page.select("#site-menu .site-menu-entry > a, #site-menu .site-menu-group > summary").eachText())
                .containsExactlyElementsOf(List.of(
                        "국내", "해외", "여행코스", "여행 커뮤니티", "여행정보",
                        "여행기록", "고객센터", "이벤트"));
        assertThat(page.select("#site-menu .site-menu-group")).hasSize(5);
        assertThat(page.select("#site-menu .site-menu-group[open]")).isEmpty();
        assertThat(page.select("#site-menu .site-menu-group").eachAttr("name"))
                .containsOnly("site-menu-group");

        // 넓은 화면에서 hover 로만 닿던 하위 링크도 빠지지 않는다
        List<String> submenuLinks = page.select(".main-menu > .menu-item > .submenu-col a").eachAttr("href");
        List<String> narrowLinks = page.select("#site-menu .site-menu-entry ul a").eachAttr("href");
        assertThat(narrowLinks).containsExactlyElementsOf(submenuLinks);
        assertThat(narrowLinks).contains("/travel-plans");
    }

    @Test
    void everyDesktopSubmenuBelongsToTheSameParentAsItsMobileGroup() throws Exception {
        var document = page();
        var parents = document.select(".main-menu > .menu-item");
        var mobileGroups = document.select("#site-menu .site-menu-group");
        assertThat(parents).hasSize(8);
        assertThat(parents.subList(0, 3)).allSatisfy(parent -> assertThat(parent.select(".submenu-col")).isEmpty());
        for (int index = 3; index < parents.size(); index++) {
            var parent = parents.get(index);
            var group = parent.children().select(".submenu-col").first();
            assertThat(group).isNotNull();
            assertThat(group.parent()).isSameAs(parent);
            assertThat(parent.selectFirst("a").text()).isEqualTo(mobileGroups.get(index - 3).selectFirst("summary").text());
            assertThat(group.select("a").eachAttr("href"))
                    .containsExactlyElementsOf(mobileGroups.get(index - 3).select("ul a").eachAttr("href"));
        }
        assertThat(document.select(".global-submenu a, .submenu-grid")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "/destinations,domestic,국내",
            "/destinations,overseas,해외",
            "/courses/12,,여행코스",
            "/post/12,,여행 커뮤니티",
            "/festivals/12,,여행정보",
            "/diaries/demo,,여행기록",
            "/support/inquiries/12,,고객센터",
            "/events/12,,이벤트"
    })
    void wideAndNarrowNavigationMarkTheSameOwningSection(
            String path, String type, String label) throws Exception {
        var document = Jsoup.parse(mockMvc.perform(get("/random-travel")
                        .flashAttr("headerNavigation", new HeaderNavigation(path, type)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.select(".main-menu a[aria-current=page]").eachText())
                .containsExactly(label);
        assertThat(document.select("#site-menu .site-menu-entry > a[aria-current=page], "
                + "#site-menu summary[aria-current=page]").eachText()).containsExactly(label);
    }

    @Test
    void theNarrowMenuReadsInOneDirectionWithoutRepeatingItself() throws Exception {
        org.jsoup.nodes.Document page = page();

        // 1차 메뉴가 제목 자리를 겸한다. 같은 이름이 두 번 나오지 않는다
        List<String> everyLabel = page.select("#site-menu a, #site-menu summary").eachText();
        assertThat(everyLabel).doesNotHaveDuplicates();

        // 하위 링크는 자기 1차 메뉴 안에 들어 있다
        org.jsoup.nodes.Element community = page.select("#site-menu .site-menu-entry").stream()
                .filter(entry -> entry.select("summary").text().equals("여행 커뮤니티"))
                .findFirst().orElseThrow();
        assertThat(community.select("ul a").eachText())
                .containsExactly("여행 질문", "여행 팁");

        // 하위 링크가 없는 항목은 목록만 있고 빈 껍데기가 붙지 않는다
        org.jsoup.nodes.Element domestic = page.selectFirst("#site-menu .site-menu-entry");
        assertThat(domestic.selectFirst("a").text()).isEqualTo("국내");
        assertThat(domestic.select("ul, details")).isEmpty();
    }

    @Test
    void theNarrowMenuStacksVerticallyAtEveryWidthItIsUsed() throws IOException {
        String css = resource("/static/css/style.css");

        // 1차 메뉴를 가로로 늘어놓지 않는다. 늘어놓으면 좁은 폭에서 다시 접힌다
        assertThat(between(css, ".site-menu-list {", "}"))
                .doesNotContain("display: flex")
                .doesNotContain("flex-wrap");
        assertThat(between(css, ".site-menu-entry > a {", "}"))
                .contains("display: block")
                .contains("font-weight: 600");

        // 하위 링크는 들여쓰기와 크기로 위계를 낮춘다
        String sublist = between(css, ".site-menu-entry ul {", "}");
        assertThat(sublist).contains("padding: 0 0 10px 12px");
        assertThat(between(css, ".site-menu-entry ul li a {", "}"))
                .contains("font-size: 14px")
                .contains("font-weight: 400");

        // 접힌 메뉴에서만 accordion 제목과 하위 링크의 밀도를 조정한다.
        String narrow = between(css, "/* 접힌 메뉴 전용.", "/* 모바일 로고 크기");
        assertThat(narrow).contains("@media (max-width: 1199px)",
                ".site-menu-group > summary", "min-height: 42px", "min-height: 34px");
    }

    @Test
    void theNarrowMenuScrollsInsideItselfInsteadOfStretchingThePage() throws IOException {
        String css = resource("/static/css/style.css");

        String panel = between(css, ".site-menu {", "}");
        assertThat(panel)
                .contains("max-height: calc(100vh - 100px)")
                .contains("overflow-y: auto")
                // 판이 화면 너비 안에 들어가 가로 스크롤이 생기지 않는다
                .contains("width: 100%")
                .contains("box-sizing: border-box");
    }

    @Test
    void theHeaderStaysOneRowUntilTheMenuPanelIsOpened() throws IOException {
        String css = resource("/static/css/style.css");

        // 메뉴 글자가 중간에서 끊기지 않는다
        assertThat(css).contains("white-space: nowrap");

        // 펼쳐 둔 메뉴가 들어가지 않는 폭부터는 통째로 감추고 ☰ 로 바꾼다.
        // (기존 좁은 화면 규칙은 열 수를 줄여 메뉴를 여러 줄로 쌓았다)
        String compact = between(css, "@media (max-width: 1199px) {", "@media (max-width: 767px)");
        assertThat(compact)
                .contains(".nav-grid")
                .contains(".global-submenu")
                .contains("display: none !important")
                .contains(".site-menu-toggle {\n        display: flex;");

        // 모바일 구간도 있다
        assertThat(css).contains("@media (max-width: 767px)");

        // 메뉴 판은 헤더 아래로 펼쳐진다. 헤더 자체 높이는 그대로다
        assertThat(between(css, ".site-menu {", "}"))
                .contains("position: absolute")
                .contains("top: 100px");
        assertThat(css).contains(".site-menu[hidden]");
    }

    @Test
    void theMenuButtonOpensClosesAndRestoresFocus() throws IOException {
        String script = resource("/static/js/main.js");

        assertThat(script)
                .contains("site-menu-toggle", "site-menu")
                .contains("setSiteMenuOpen")
                .contains("siteMenu.hidden")
                .contains("aria-expanded")
                // 바깥 클릭과 Esc 로 닫힌다
                .contains("siteMenu.contains(e.target)")
                .contains("e.key === 'Escape' && !siteMenu.hidden")
                .contains("siteMenuToggle.focus()")
                // 넓은 화면으로 돌아가면 판을 닫는다
                .contains("window.innerWidth <= 1199", "mobileHeader !== wasMobileHeader");
        // 새 UI 프레임워크를 들이지 않는다
        assertThat(script).doesNotContain("bootstrap").doesNotContain("import ");
    }

    @Test
    void thisChangeDoesNotTouchTheTravelPlanner() throws IOException {
        String css = resource("/static/css/style.css");

        // 공동 여행계획 화면은 자기 CSS 를 쓰고 여기서는 건드리지 않는다
        assertThat(css)
                .doesNotContain("travel-plan-paper")
                .doesNotContain("travel-plan-day")
                .doesNotContain("travel-plan-line");
    }

    @Test
    void profileMenuScriptCoversToggleOutsideClickAndEscapeFocusRestoration()
            throws IOException {
        String script = resource("/static/js/main.js");
        String css = resource("/static/css/style.css");

        assertThat(script)
                .contains("profile-menu-toggle", "profile-menu")
                .contains("setProfileMenuOpen")
                .contains("profileMenu.hidden")
                .contains("aria-expanded")
                .contains("!profileMenu.hidden")
                .contains("!profileMenuContainer.contains(e.target)")
                .contains("e.key === 'Escape'")
                .contains("profileToggle.focus()")
                .doesNotContain("location.href = '/mypage'");
        assertThat(css)
                .contains(".profile-menu")
                .contains("max-width: calc(100vw - 24px)")
                .contains(".profile-menu-item:hover")
                .contains(".profile-menu-item:focus-visible");
    }

    @Test
    void headerActionsKeepTheirPaddingWhileOnlyThePrimaryMenuGetsTheWiderGap()
            throws IOException {
        String css = resource("/static/css/style.css");

        assertThat(css)
                .contains("padding-right: clamp(8px, 2vw, 24px)")
                .contains(".main-nav > .nav-grid > .main-menu {")
                .contains("--header-menu-gap: 60px;")
                .doesNotContain("grid-template-columns: subgrid");
    }

    private org.jsoup.nodes.Document page() throws Exception {
        return Jsoup.parse(mockMvc.perform(get("/random-travel"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.jsoup.nodes.Document page(String role) throws Exception {
        return page(role, null);
    }

    private org.jsoup.nodes.Document page(String role, String languageTag)
            throws Exception {
        User user = new User();
        user.setId("ADMIN".equals(role) ? 2L : 1L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.valueOf(role));
        when(userMapper.findById(user.getId())).thenReturn(user);

        CustomUserDetails userDetails = new CustomUserDetails(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                userDetails, userDetails.getPassword(), userDetails.getAuthorities());

        var request = get("/random-travel").with(authentication(authentication));
        if (languageTag != null) {
            request = request.cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag));
        }
        return Jsoup.parse(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @CsvSource({"ko", "en", "ja", "zh-CN", "zh-TW"})
    void everyLanguageKeepsTheSearchFormBesideTheLanguageSelector(String languageTag)
            throws Exception {
        var document = pageWithLocale(languageTag);
        var searchBox = document.selectFirst(".search-box");

        assertThat(searchBox).isNotNull();
        // 검색 토글·검색 폼·언어 선택·로그인 영역이 모두 오른쪽 묶음 안에 그대로 있다.
        assertThat(searchBox.selectFirst("#search-toggle")).isNotNull();
        assertThat(searchBox.selectFirst("form#search-form[action=/search]")).isNotNull();
        assertThat(searchBox.selectFirst("form#search-form input[name=q]")).isNotNull();
        assertThat(searchBox.selectFirst(".language-menu")).isNotNull();
        assertThat(searchBox.selectFirst("#auth-area")).isNotNull();
        // 언어 선택은 다섯 언어를 그대로 내준다.
        assertThat(searchBox.select(".language-menu-option")).hasSize(5);
        // 검색창은 닫힌 상태로 시작하고, 지우기 버튼은 지울 값이 없어 숨어 있다.
        assertThat(document.selectFirst("#search-form").hasClass("hidden")).isTrue();
        assertThat(document.selectFirst("#search-toggle").attr("aria-expanded")).isEqualTo("false");
        var clear = searchBox.selectFirst("#header-search-clear");
        assertThat(clear).isNotNull();
        assertThat(clear.attr("type")).isEqualTo("button");
        assertThat(clear.hasAttr("hidden")).isTrue();
        assertThat(clear.attr("aria-label")).isNotBlank();
    }

    private org.jsoup.nodes.Document pageWithLocale(String languageTag) throws Exception {
        var html = mockMvc.perform(get("/random-travel")
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String fixtureDirectory = System.getenv("TRIPBORA_HEADER_FIXTURE_DIR");
        if (fixtureDirectory != null) {
            var directory = java.nio.file.Path.of(fixtureDirectory);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("header-" + languageTag + ".html"), html);
        }
        return Jsoup.parse(html);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
