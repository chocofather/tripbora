package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공개 여행지 목록 카테고리 필터.
 * 대표 카테고리만이 아니라 모든 등록 카테고리로 찾고(OR, 중복 없음), 지역·정렬·쪽과 함께 주소로 이어진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationListCategoryFilterTest {

    private static final List<Long> SEOUL_REGION_IDS = List.of(38L, 235L);

    @Mock private DestinationService destinationService;
    @Mock private DestinationImageService destinationImageService;
    @Mock private CountryCategoryService countryCategoryService;
    @Mock private DestinationCommentService destinationCommentService;
    @Mock private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock private HttpServletRequest request;

    private DestinationController controller;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.KOREAN.getLocale());
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService, referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)));
        when(countryCategoryService.getById(38L)).thenReturn(region(38L, "서울", 3, 7L));
        when(countryCategoryService.getSubregions(38L, 4)).thenReturn(List.of());
        when(countryCategoryService.getAllRegionIdsUnder(38L)).thenReturn(SEOUL_REGION_IDS);
        when(destinationService.getDestinationsByRegionIdsPaged(anyList(), anyList(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of());
        when(destinationService.convertToLocalizedDtoWithBookmark(anyList(), any(), any(), any()))
                .thenReturn(List.of());
        when(referenceNameLocalizationService.localizeCountryCategoryNames(any(), any())).thenReturn(Map.of());
        // 서울 범위: 고궁(5) 2곳, 랜드마크(3) 3곳, 사진명소(8) 1곳. 12 는 있는 카테고리지만 서울에는 없다.
        when(referenceNameLocalizationService.localizeCategories(any(), eq(SupportedLanguage.KOREAN)))
                .thenAnswer(invocation -> {
                    Map<Long, String> names = new HashMap<>();
                    for (Object id : (java.util.Collection<?>) invocation.getArgument(0)) {
                        names.put((Long) id, Map.of(3L, "랜드마크", 5L, "고궁", 8L, "사진명소", 12L, "야경")
                                .get((Long) id));
                    }
                    return names;
                });
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void eightItemPagesUseTheSameLimitOffsetAndPaginationForAllListEndpoints() {
        when(destinationService.countDestinationsByRegionIds(SEOUL_REGION_IDS, List.of()))
                .thenReturn(25);
        for (int endpoint = 0; endpoint < 3; endpoint++) {
            Model model = new ExtendedModelMap();
            if (endpoint == 0) {
                controller.destinationList("domestic", 38L, 2, 8, "views", null, null, request, model);
            } else if (endpoint == 1) {
                controller.regionFragment("domestic", 38L, 2, 8, "views", null, null, model);
            } else {
                controller.destinationListFragment("domestic", 38L, 2, 8, "views", null, null, model);
            }
            assertThat(model.getAttribute("pageSize")).isEqualTo(8);
            assertThat(model.getAttribute("currentPage")).isEqualTo(2);
            assertThat(model.getAttribute("totalPages")).isEqualTo(4);
        }
        verify(destinationService, org.mockito.Mockito.times(3))
                .getDestinationsByRegionIdsPaged(SEOUL_REGION_IDS, List.of(), 8, 8, "views");
        verify(destinationService, org.mockito.Mockito.never()).getCategoryFilterCounts(anyList(), anyList());
    }

    @Test
    void oneCategoryIsSelectedEvenWhenAnOldMultiValueAddressIsUsed() {
        // 예전 다중 선택 주소(category=abc&category=12&category=5)는 스프링이 "abc,12,5" 로 이어 준다.
        // 잘못된 값은 버리고 첫 번째 올바른 값 하나만 쓴다. 야경(12)은 서울에 0곳이어도 있는 카테고리라 고른다.
        when(destinationService.getCategoryFilterCounts(SEOUL_REGION_IDS, List.of(12L)))
                .thenReturn(Map.of(3L, 3, 5L, 2, 8L, 1, 12L, 0));
        Model model = new ExtendedModelMap();

        controller.destinationListFragment("domestic", 38L, 2, 12, "views", "abc,12,5", null, model);

        verify(destinationService).getDestinationsByRegionIdsPaged(SEOUL_REGION_IDS, List.of(12L), 12, 12, "views");
        verify(destinationService).countDestinationsByRegionIds(SEOUL_REGION_IDS, List.of(12L));
        assertThat(model.getAttribute("selectedCategoryId")).isEqualTo(12L);
        assertThat(model.containsAttribute("categoryFilterOptions")).isFalse();
        verify(referenceNameLocalizationService).localizeCategories(List.of(12L), SupportedLanguage.KOREAN);
    }

    @Test
    void anUnknownOrInvalidCategoryShowsTheWholeList() {
        when(destinationService.getCategoryFilterCounts(SEOUL_REGION_IDS, List.of(999L))).thenReturn(Map.of(5L, 2));
        Model unknown = new ExtendedModelMap();

        controller.destinationListFragment("domestic", 38L, 1, 12, "default", "999", null, unknown);
        controller.destinationListFragment("domestic", 38L, 1, 12, "default", "-3", null, new ExtendedModelMap());

        verify(destinationService, org.mockito.Mockito.times(2))
                .getDestinationsByRegionIdsPaged(SEOUL_REGION_IDS, List.of(), 0, 12, "default");
        assertThat(unknown.getAttribute("selectedCategoryId")).isNull();
        assertThat(unknown.getAttribute("selectedCategoryName")).isNull();
    }

    @Test
    void theListFilterMatchesEveryRegisteredCategoryOnceNotOnlyTheMainOne() throws IOException {
        Configuration configuration = mapperConfiguration();
        Map<String, Object> filtered = new HashMap<>(Map.of("regionIds", List.of(38L, 235L),
                "categoryIds", List.of(3L, 5L), "offset", 0, "size", 12, "sort", "views"));

        String list = sql(configuration, "findByRegionIdsPaged", filtered);
        String count = sql(configuration, "countByRegionIds", filtered);
        for (String query : List.of(list, count)) {
            // 대표 여부(is_main)를 보지 않는 모든 연결, EXISTS 라 두 카테고리에 모두 걸려도 한 번만 나온다.
            assertThat(query)
                    .contains("AND EXISTS (SELECT 1 FROM destination_categories dc WHERE dc.destination_id = d.id "
                            + "AND dc.category_id IN (? , ?))")
                    .doesNotContain("dc.is_main")
                    .doesNotContain("JOIN destination_categories");
        }
        // 같은 조회수면 id 순으로 고정해 쪽을 넘겨도 겹치거나 빠지지 않는다.
        assertThat(list).contains("ORDER BY d.views DESC, d.id ASC");

        Map<String, Object> unfiltered = new HashMap<>(filtered);
        unfiltered.put("categoryIds", List.of());
        assertThat(sql(configuration, "findByRegionIdsPaged", unfiltered)).doesNotContain("destination_categories");
        assertThat(sql(configuration, "countByRegionIds", unfiltered)).doesNotContain("destination_categories");
    }

    @Test
    void theFragmentKeepsTheSelectionInPagingLinksAndShowsAnEmptyState() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("destinations", List.<DestinationDto>of());
        variables.put("regionDisplayNames", Map.of());
        variables.put("selectedCityId", 38L);
        variables.put("selectedCityName", "서울");
        variables.put("totalPages", 3);
        variables.put("currentPage", 2);
        variables.put("pageSize", 12);
        variables.put("sort", "views");
        variables.put("type", "domestic");
        variables.put("selectedCategoryId", 5L);

        Document page = renderList(variables, Locale.KOREAN);

        assertThat(page.selectFirst("#destination-list").attr("data-category")).isEqualTo("5");
        Map<String, Object> headingVariables = new HashMap<>(variables);
        headingVariables.put("selectedCityName", "종로구");
        Document heading = renderList(headingVariables, Locale.KOREAN);
        assertThat(heading.select(".destination-title-context")).isEmpty();
        assertThat(heading.selectFirst("#page-title").text()).isEqualTo("종로구 여행지");
        assertThat(page.select("[data-category-filter], [data-category-filter-toggle], [data-category-filter-panel]"))
                .isEmpty();
        assertThat(page.select(".list-sort-buttons .sort-btn")).hasSize(3);
        // 쪽 이동에도 지역·정렬·카테고리가 그대로 실린다.
        assertThat(page.select(".pagination a.page-number").first().attr("href"))
                .isEqualTo("/destinations/fragment?type=domestic&region=38&page=1&size=12&sort=views&category=5");
        assertThat(page.selectFirst(".destination-list-empty").text())
                .isEqualTo("조건에 맞는 여행지가 없습니다.");

        // 기본 목록과 다른 언어에서도 드롭다운 없이 정렬과 목록을 유지한다.
        variables.put("selectedCategoryId", null);
        Document none = renderList(variables, Locale.KOREAN);
        assertThat(none.select("[data-category-filter]")).isEmpty();
        assertThat(none.selectFirst("#destination-list").hasAttr("data-category")).isFalse();
        Document english = renderList(variables, Locale.ENGLISH);
        assertThat(english.select("[data-category-filter]")).isEmpty();
        assertThat(english.select(".list-sort-buttons .sort-btn")).hasSize(3);
        assertThat(renderList(variables, Locale.JAPANESE).select("[data-category-filter]")).isEmpty();
    }

    @Test
    void overseasRegionBackIsTheFirstRailItemAndKeepsFiltersWithoutRegionOrPage() {
        Map<String, Object> variables = new HashMap<>();
        CountryCategory country = region(8L, "일본", 2, 1L);
        country.setIconPath("/images/japan.png");
        variables.put("cities", List.of(country));
        variables.put("destinations", List.of());
        variables.put("regionDisplayNames", Map.of(8L, "일본"));
        variables.put("selectedCityId", 1L);
        variables.put("selectedSubregionId", null);
        variables.put("subregions", List.of());
        variables.put("totalPages", 0);
        variables.put("currentPage", 3);
        variables.put("pageSize", 8);
        variables.put("sort", "views");
        variables.put("type", "overseas");
        variables.put("selectedCategoryId", 5L);
        Document continent = renderFragment(variables, Locale.KOREAN, "regionFragment");
        var back = continent.selectFirst("[data-region-back]");
        assertThat(back).isNotNull();
        assertThat(back.text()).isEqualTo("대륙");
        assertThat(back.select(".region-back-visual[aria-hidden=true] svg path")).hasSize(1);
        assertThat(back.hasClass("region-btn")).isFalse();
        assertThat(back.attr("href")).isEqualTo("/destinations?type=overseas&sort=views&size=8&category=5");
        // 뒤로가기도 같은 rail(.region-buttons)의 첫 항목이라 지역 아이콘과 함께 스크롤된다.
        assertThat(continent.selectFirst(".region-buttons > :first-child")).isEqualTo(back);
        variables.put("selectedCityId", 8L);
        assertThat(renderFragment(variables, Locale.ENGLISH, "regionFragment")
                .selectFirst("[data-region-back]").text()).isEqualTo("Continents");
        variables.put("selectedCityId", null);
        assertThat(renderFragment(variables, Locale.KOREAN, "regionFragment").select("[data-region-back]")).isEmpty();
        variables.put("type", "domestic");
        variables.put("selectedCityId", 38L);
        assertThat(renderFragment(variables, Locale.KOREAN, "regionFragment").select("[data-region-back]")).isEmpty();
    }

    /**
     * 지역 rail 은 서버 목록 한 벌만 그리고, 화살표는 개수와 상관없이 숨긴 채로 둔다.
     * 실제로 넘칠 때만 스크립트(destination-region-rail.js)가 보인다.
     */
    @Test
    void regionRailsRenderEachRegionOnceWithArrowsLeftToTheOverflowCheck() {
        Map<String, Object> variables = new HashMap<>();
        List<CountryCategory> continents = List.of(region(1L, "아시아", 1, null), region(2L, "유럽", 1, null),
                region(3L, "북미", 1, null));
        continents.forEach(continent -> continent.setIconPath("/images/continent.png"));
        List<CountryCategory> cities = List.of(region(30L, "카이로", 3, 6L), region(31L, "룩소르", 3, 6L));
        variables.put("cities", continents);
        variables.put("destinations", List.of());
        variables.put("regionDisplayNames", Map.of(1L, "아시아", 2L, "유럽", 3L, "북미", 30L, "카이로", 31L, "룩소르"));
        variables.put("selectedCityId", 2L);
        variables.put("selectedSubregionId", 31L);
        variables.put("subregions", cities);
        variables.put("totalPages", 0);
        variables.put("currentPage", 1);
        variables.put("pageSize", 12);
        variables.put("sort", "default");
        variables.put("type", "overseas");

        Document page = renderFragment(variables, Locale.KOREAN, "regionFragment");

        assertThat(page.select(".region-buttons .region-btn")).extracting(e -> e.attr("data-region-id"))
                .containsExactly("1", "2", "3");
        assertThat(page.select(".region-btn.selected")).hasSize(1);
        assertThat(page.select(".subregion-list .subregion-btn")).extracting(e -> e.attr("data-city-id"))
                .containsExactly("30", "31");
        assertThat(page.select("[data-rail-clone]")).isEmpty();
        // 항목이 적어도 화살표 자리는 있고, 처음에는 숨겨 둔다
        assertThat(page.select(".region-selector > .arrow.prev[hidden], .region-selector > .arrow.next[hidden]"))
                .hasSize(2);
        assertThat(page.select(".subregion-arrow.prev[hidden], .subregion-arrow.next[hidden]")).hasSize(2);
    }

    private static Document renderList(Map<String, Object> variables, Locale locale) {
        return renderFragment(variables, locale, "destinationList");
    }

    private static Document renderFragment(Map<String, Object> variables, Locale locale, String fragment) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);

        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()), locale);
        context.setVariables(variables);
        return Jsoup.parse(engine.process("destination/fragment", Set.of(fragment), context));
    }

    private Configuration mapperConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/DestinationMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "mapper/DestinationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Configuration configuration, String id, Map<String, Object> parameters) {
        return configuration.getMappedStatement("com.tripbora.repository.destination.DestinationMapper." + id)
                .getBoundSql(parameters).getSql()
                .replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")").trim();
    }

    private static CountryCategory region(Long id, String name, int depth, Long parentId) {
        CountryCategory region = new CountryCategory();
        region.setId(id);
        region.setRegionName(name);
        region.setDepth(depth);
        region.setParentId(parentId);
        return region;
    }
}
