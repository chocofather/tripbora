package com.tripbora.controller.destination;

import com.tripbora.config.*;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.*;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.*;
import com.tripbora.service.file.DestinationCardThumbnailService;
import org.jsoup.Jsoup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.tripbora.model.CountryCategory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 템플릿·리소스 체인을 렌더링하며 DB·Flyway는 시작하지 않는다. */
@WebMvcTest(DestinationController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class DestinationListRenderingTest {
    @Autowired MockMvc mvc;
    @MockitoBean DestinationService destinations;
    @MockitoBean DestinationImageService images;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean DestinationCommentService comments;
    @MockitoBean ReferenceNameLocalizationService names;
    @MockitoBean DestinationCardThumbnailService thumbnails;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;

    @ParameterizedTest
    @CsvSource({"1,1", "7,1", "7,4", "7,7", "23,1", "23,12", "23,23", "100,1", "100,50", "100,100", "120,60"})
    void boundsAndLinksPreserveFiltersAcrossAllThreeEndpoints(int total, int page) throws Exception {
        when(countries.getDomesticRootIds()).thenReturn(List.of(10L));
        when(countries.getAllRegionIdsUnder(10L)).thenReturn(List.of(11L));
        when(destinations.countDestinationsByRegionIds(anyList(), anyList())).thenReturn(total * 12);
        for (String endpoint : List.of("/destinations", "/destinations/fragment", "/destinations/list-fragment")) {
            String html = mvc.perform(get(endpoint).param("page", "" + page).param("sort", "latest")
                            .param("type", "domestic").header("Accept-Language", "ko"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var document = Jsoup.parse(html);
            assertThat(document.select(".pagination-desktop .page-number")).hasSize(Math.min(total, 7));
            assertThat(document.select(".pagination-mobile .page-number")).hasSize(Math.min(total, 3));
            assertThat(document.select(".pagination .is-current").eachText()).containsExactly("" + page, "" + page);
            assertThat(document.select(".pagination-desktop .is-disabled")).hasSize((page == 1 ? 2 : 0) + (page == total ? 2 : 0));
            document.select(".pagination a").forEach(link -> assertThat(link.attr("href"))
                    .startsWith("/destinations?").contains("type=domestic", "sort=latest", "size=12"));
            if (endpoint.equals("/destinations")) {
                Files.createDirectories(Path.of("build/reports/destination-ui"));
                Files.writeString(Path.of("build/reports/destination-ui/list-" + total + "-" + page + ".html"), html);
                for (String selector : List.of("link[href^=/css/]", "script[src^=/js/]")) {
                    String attr = selector.startsWith("link") ? "href" : "src";
                    for (var element : document.select(selector)) {
                        String url = element.attr(attr);
                        assertThat(url).matches("/(css|js)/.+-[0-9a-f]{32}\\.(css|js)");
                        var response = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
                        assertThat(response.getContentType()).startsWith(attr.equals("href") ? "text/css" : "text/javascript");
                    }
                }
            }
        }
        verify(destinations, times(3)).getDestinationsByRegionIdsPaged(anyList(), anyList(), eq((page - 1) * 12), eq(12), eq("latest"));
    }

    @ParameterizedTest
    @CsvSource({"-10,0,1,1", "2147483647,2147483647,100,2", "0,12,12,1"})
    void invalidBoundsAreClampedBeforeQuery(int requestedPage, int requestedSize, int size, int page) throws Exception {
        when(destinations.countDestinationsByRegionIds(anyList(), anyList())).thenReturn(120);
        mvc.perform(get("/destinations/list-fragment").param("page", "" + requestedPage)
                        .param("size", "" + requestedSize).param("sort", "invalid"))
                .andExpect(status().isOk());
        verify(destinations).getDestinationsByRegionIdsPaged(anyList(), anyList(), eq((page - 1) * size), eq(size), eq("default"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void overseasCountryAndValidatedCategorySurviveEveryNavigationLink(int depth) throws Exception {
        CountryCategory country = new CountryCategory();
        country.setId(100L); country.setDepth(depth); country.setParentId(1L); country.setRegionName("프랑스");
        when(countries.getById(100L)).thenReturn(country);
        when(countries.getAllRegionIdsUnder(100L)).thenReturn(List.of(100L, 101L));
        when(destinations.getCategoryFilterCounts(anyList(), anyList())).thenReturn(Map.of(42L, 276));
        when(names.localizeCategories(anyList(), any())).thenReturn(Map.of(42L, "랜드마크"));
        when(destinations.countDestinationsByRegionIds(anyList(), anyList())).thenReturn(276);
        for (String endpoint : List.of("/destinations", "/destinations/fragment", "/destinations/list-fragment")) {
            String html = mvc.perform(get(endpoint).param("type", "overseas").param("region", "100")
                            .param("category", "42").param("sort", "default").param("page", "12"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var document = Jsoup.parse(html);
            assertThat(document.select(".pagination a")).isNotEmpty().allSatisfy(link ->
                    assertThat(link.attr("href")).contains("type=overseas", "region=100", "category=42", "sort=default"));
        }
        verify(destinations, times(3)).getDestinationsByRegionIdsPaged(eq(List.of(100L, 101L)), eq(List.of(42L)), eq(132), eq(12), eq("default"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ja", "zh-CN", "zh-TW"})
    void exportsLocalizedSortControlsForBrowserVerification(String language) throws Exception {
        when(destinations.countDestinationsByRegionIds(anyList(), anyList())).thenReturn(276);
        String html = mvc.perform(get("/destinations").param("page", "12").param("lang", language))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.createDirectories(Path.of("build/reports/destination-ui"));
        Files.writeString(Path.of("build/reports/destination-ui/list-23-12-" + language + ".html"), html);
    }
}
