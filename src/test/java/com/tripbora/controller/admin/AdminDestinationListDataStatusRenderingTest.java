package com.tripbora.controller.admin;

import com.tripbora.dto.AdminDestinationListItemDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 여행지 목록의 데이터 상태 표시. 실제 list.html 본문을 렌더한다.
 * 문제가 있는 행에만 작은 상태 표시가 붙고, 바로가기는 지금 데이터 상태를 활성으로 보여 준다.
 */
class AdminDestinationListDataStatusRenderingTest {

    @Test
    void visibilityBadgesAndActionsMatchSavedStateAndFilterIsRetained() throws IOException {
        AdminDestinationListItemDto visible = row(1L, "경복궁");
        visible.setPublicVisible(true);
        AdminDestinationListItemDto hidden = row(2L, "남산공원");
        hidden.setPublicVisible(false);
        Document page = render(List.of(visible, hidden), null, "hidden");

        assertThat(page.select(".admin-destination-visibility-badge")).extracting(Element::text)
                .containsExactly("공개", "숨김");
        List<Element> buttons = page.select("[data-destination-visibility]");
        assertThat(buttons).extracting(Element::text).containsExactly("숨기기", "공개하기");
        assertThat(buttons.get(0).attr("data-public-visible")).isEqualTo("true");
        assertThat(buttons.get(1).attr("data-public-visible")).isEqualTo("false");
        assertThat(buttons.get(1).attr("data-visibility-url"))
                .isEqualTo("/admin/destinations/2/visibility");
        assertThat(buttons.get(1).attr("type")).isEqualTo("button");
        assertThat(page.select("#destination-visibility-filter option[selected]").attr("value"))
                .isEqualTo("hidden");
        assertThat(page.selectFirst("[data-destination-visibility-error]").hasAttr("hidden")).isTrue();
    }

    @Test
    void visibilityDefaultsToAllAndFiltersStartAtPageOne() throws IOException {
        Document page = render(List.of(row(1L, "경복궁")), null);
        assertThat(page.select("#destination-visibility-filter option[selected]")).hasSize(1);
        assertThat(page.select("#destination-visibility-filter option[selected]").attr("value")).isEmpty();
        assertThat(page.select("#admin-destination-filter-form [name=page]")).isEmpty();
        assertThat(page.select(".admin-filter-actions a").attr("href")).isEqualTo("/admin/destinations");
    }

    @Test
    void onlyRowsWithMissingDataGetBadgesAndTheActiveShortcutIsMarked() throws IOException {
        AdminDestinationListItemDto complete = row(1L, "경복궁");
        complete.setCompleteTranslationLanguages("en,ja,zh-CN,zh-TW");
        AdminDestinationListItemDto incomplete = row(2L, "남산공원");
        incomplete.setMissingImage(true);
        incomplete.setCompleteTranslationLanguages("en,zh-CN");
        AdminDestinationListItemDto untranslated = row(3L, "한옥호텔");
        untranslated.setMissingCategory(true);

        Document page = render(List.of(complete, incomplete, untranslated), "missing_image");

        List<Element> rows = page.select("tbody tr");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).select(".admin-data-issue")).isEmpty();
        assertThat(rows.get(1).select(".admin-data-issue")).extracting(Element::text)
                .containsExactly("이미지 없음", "번역 누락: ja, zh-TW");
        assertThat(rows.get(2).select(".admin-data-issue")).extracting(Element::text)
                .containsExactly("카테고리 없음", "번역 없음");

        List<Element> shortcuts = page.select(".admin-data-status-link");
        assertThat(shortcuts).extracting(Element::text)
                .containsExactly("전체 1,284", "이미지 없음 12", "대표 이미지 없음 4", "카테고리 없음 0",
                        "번역 미완료 19", "기본 설명 없음 7", "숨김 24");
        assertThat(shortcuts.get(1).hasClass("is-active")).isTrue();
        assertThat(shortcuts.get(1).attr("aria-current")).isEqualTo("true");
        assertThat(shortcuts.get(0).hasClass("is-active")).isFalse();
        assertThat(shortcuts.get(2).hasClass("has-issue")).isTrue();
        assertThat(shortcuts.get(3).hasClass("has-issue")).isFalse();
        assertThat(shortcuts.get(1).attr("href")).isEqualTo("/admin/destinations?dataStatus=missing_image");
        assertThat(page.text()).doesNotContain("문제 있음");

        assertThat(page.select("#destination-data-status-filter option[selected]").attr("value"))
                .isEqualTo("missing_image");
    }

    @Test
    void hiddenShortcutSharesTheSelectedStyleAndCombinesWithMissingImage() throws IOException {
        Document page = render(List.of(row(1L, "경복궁")), "missing_image", "hidden");
        Element hidden = page.selectFirst(".admin-data-status-link[href*='visibility=hidden']");
        assertThat(hidden).isNotNull();
        assertThat(hidden.text()).isEqualTo("숨김 24");
        assertThat(hidden.hasClass("is-active")).isTrue();
        assertThat(hidden.hasClass("has-issue")).isTrue();
        assertThat(hidden.attr("aria-current")).isEqualTo("true");
        assertThat(page.select(".admin-data-status-link.is-active").eachText())
                .containsExactly("이미지 없음 12", "숨김 24");
        assertThat(page.select("#destination-visibility-filter option[selected]").attr("value"))
                .isEqualTo("hidden");
        assertThat(page.select(".admin-filter-actions a").attr("href")).isEqualTo("/admin/destinations");
        assertThat(render(List.of(), null, "public").selectFirst(".admin-data-status-link[href*='visibility=hidden']")
                .hasClass("is-active")).isFalse();
    }

    /** 선택 삭제: 행마다 체크박스가 선택 삭제 폼에 묶이고, 폼은 지금 목록 조건을 담아 POST 한다. 단건 삭제도 그대로다. */
    @Test
    void rowsCanBeSelectedForBulkDeleteWithoutLosingTheSingleDelete() throws IOException {
        Document page = render(List.of(row(11L, "경복궁"), row(12L, "남산공원")), "missing_image");

        Element form = page.selectFirst("form#destination-bulk-delete-form");
        assertThat(form).isNotNull();
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("action")).isEqualTo("/admin/destinations/bulk-delete?dataStatus=missing_image");
        Element submit = form.selectFirst("[data-destination-bulk-delete-submit]");
        assertThat(submit.text()).isEqualTo("선택 삭제");
        assertThat(submit.hasAttr("disabled")).isTrue();
        assertThat(submit.hasClass("is-danger")).isTrue();

        assertThat(page.selectFirst("thead [data-destination-select-all]").attr("aria-label"))
                .isEqualTo("현재 페이지 여행지 전체 선택");
        List<Element> choices = page.select("tbody input[type=checkbox][data-destination-select]");
        assertThat(choices).extracting(choice -> choice.attr("value")).containsExactly("11", "12");
        assertThat(choices).allSatisfy(choice -> {
            assertThat(choice.attr("name")).isEqualTo("destinationIds");
            assertThat(choice.attr("form")).isEqualTo("destination-bulk-delete-form");
        });
        assertThat(choices.get(0).attr("aria-label")).isEqualTo("경복궁 선택");

        assertThat(page.select("tbody form[action$=/delete?dataStatus=missing_image]"))
                .extracting(single -> single.attr("action"))
                .containsExactly("/admin/destinations/11/delete?dataStatus=missing_image",
                        "/admin/destinations/12/delete?dataStatus=missing_image");
    }

    @Test
    void anEmptyListOffersNoBulkDelete() throws IOException {
        Document page = render(List.of(), "missing_image");

        assertThat(page.selectFirst("#destination-bulk-delete-form")).isNull();
        assertThat(page.selectFirst("[data-destination-select-all]")).isNull();
        assertThat(page.selectFirst(".admin-table-empty").attr("colspan")).isEqualTo("5");
    }

    private static AdminDestinationListItemDto row(Long id, String name) {
        AdminDestinationListItemDto row = new AdminDestinationListItemDto();
        row.setId(id);
        row.setName(name);
        row.setRegionName("종로구");
        return row;
    }

    private Document render(List<AdminDestinationListItemDto> rows, String dataStatus) throws IOException {
        return render(rows, dataStatus, null);
    }

    private Document render(List<AdminDestinationListItemDto> rows, String dataStatus, String visibility) throws IOException {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                Locale.KOREAN);
        Map<String, String> statusLabels = new LinkedHashMap<>();
        statusLabels.put("missing_image", "이미지 없음");
        statusLabels.put("missing_main_image", "대표 이미지 없음");
        context.setVariable("destinationList", rows);
        context.setVariable("dataStatus", dataStatus);
        context.setVariable("visibility", visibility);
        context.setVariable("dataStatusLabels", statusLabels);
        context.setVariable("destinationTypeLabels", Map.of());
        context.setVariable("sortLabels", Map.of("latest", "최신 등록순"));
        context.setVariable("sort", "latest");
        context.setVariable("statusShortcuts", List.of(
                new AdminDestinationController.DataStatusShortcut(null, "전체", 1284, "/admin/destinations"),
                new AdminDestinationController.DataStatusShortcut("missing_image", "이미지 없음", 12,
                        "/admin/destinations?dataStatus=missing_image"),
                new AdminDestinationController.DataStatusShortcut("missing_main_image", "대표 이미지 없음", 4,
                        "/admin/destinations?dataStatus=missing_main_image"),
                new AdminDestinationController.DataStatusShortcut("missing_category", "카테고리 없음", 0,
                        "/admin/destinations?dataStatus=missing_category"),
                new AdminDestinationController.DataStatusShortcut("missing_translation", "번역 미완료", 19,
                        "/admin/destinations?dataStatus=missing_translation"),
                new AdminDestinationController.DataStatusShortcut("missing_description", "기본 설명 없음", 7,
                        "/admin/destinations?dataStatus=missing_description"),
                new AdminDestinationController.DataStatusShortcut("hidden", "숨김", 24,
                        "/admin/destinations?visibility=hidden&dataStatus=missing_image")));
        context.setVariable("totalCount", rows.size());
        context.setVariable("pageOffset", 0L);
        context.setVariable("currentPage", 1);
        context.setVariable("totalPages", 1);
        context.setVariable("listUrl", "/admin/destinations?dataStatus=" + dataStatus);
        context.setVariable("listQuery", "?dataStatus=" + dataStatus);
        return Jsoup.parse(engine().process(listBody(), context));
    }

    /** list.html 의 본문 fragment(레이아웃 제외). */
    private String listBody() throws IOException {
        String list;
        try (InputStream input = getClass().getResourceAsStream("/templates/admin/destinations/list.html")) {
            assertThat(input).isNotNull();
            list = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = list.indexOf("<th:block th:fragment=\"body\">");
        int end = list.lastIndexOf("</th:block>");
        assertThat(start).isNotNegative();
        return list.substring(start, end + "</th:block>".length());
    }

    private static SpringTemplateEngine engine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
