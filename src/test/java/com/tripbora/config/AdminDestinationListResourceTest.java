package com.tripbora.config;

import com.tripbora.controller.admin.AdminDestinationController;
import com.tripbora.dto.AdminDestinationDataStatusCounts;
import com.tripbora.dto.AdminDestinationListItemDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.security.LoginThrottle;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.category.CategoryService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.destination.DestinationSaveOrchestrationService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.info.AccommodationInfoService;
import com.tripbora.service.info.ActivityInfoService;
import com.tripbora.service.info.AttractionInfoService;
import com.tripbora.service.info.RestaurantInfoService;
import com.tripbora.service.info.ShopInfoService;
import com.tripbora.service.kto.KtoSelectedPhotoRequestParser;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 관리자 Controller·Thymeleaf·보안 필터·리소스 체인 검증. DB 연결 및 앱 기동 없음. */
@WebMvcTest(AdminDestinationController.class)
@Import(SecurityConfig.class)
class AdminDestinationListResourceTest {
    private static final List<Long> REGION_IDS = List.of(101L, 102L);
    private static final Path REPORT = Path.of("build/reports/static-resources");

    @Autowired MockMvc mvc;
    @MockitoBean DestinationService destinations;
    @MockitoBean CategoryService categories;
    @MockitoBean AmenityService amenities;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean KtoSelectedPhotoRequestParser selectedPhotos;
    @MockitoBean DestinationSaveOrchestrationService saves;
    @MockitoBean RestaurantInfoService restaurants;
    @MockitoBean AttractionInfoService attractions;
    @MockitoBean AccommodationInfoService accommodations;
    @MockitoBean ActivityInfoService activities;
    @MockitoBean ShopInfoService shops;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean LoginThrottle loginThrottle;

    @BeforeEach
    void regionsAndRows() {
        when(countries.getKoreaRootId()).thenReturn(REGION_IDS.get(0));
        when(countries.getAllRegionIdsUnder(REGION_IDS.get(0))).thenReturn(REGION_IDS);
        when(countries.getOverseasRootIds()).thenReturn(List.of());
        when(countries.getRegionsByDepth(1)).thenReturn(List.of());
        when(destinations.countAdminDestinations(REGION_IDS, null, null, null)).thenReturn(2);
        when(destinations.getAdminDestinationPage(REGION_IDS, null, null, null, "latest", 0, 30))
                .thenReturn(List.of(row(17L, "경복궁", true), row(18L, "남산공원", false)));
        when(destinations.getAdminDestinationDataStatusCounts(REGION_IDS, null, null))
                .thenReturn(new AdminDestinationDataStatusCounts());
    }

    @Test
    void adminListRendersBothVisibilityStatesWithCurrentHashedResources() throws Exception {
        String html = render("/admin/destinations", "admin-destinations.html");
        var document = Jsoup.parse(html);
        assertThat(document.select(".admin-destination-visibility-badge").eachText())
                .containsExactly("공개", "숨김");
        assertThat(document.select("[data-destination-visibility]").eachText())
                .containsExactly("숨기기", "공개하기");
        assertThat(document.select("#destination-visibility-filter option[selected]").attr("value")).isEmpty();
        assertThat(document.selectFirst("meta[name=_csrf]").attr("content")).isNotBlank();
        assertThat(document.selectFirst("meta[name=_csrf_header]").attr("content")).isNotBlank();
        assertResources(html);
    }

    @Test
    void hiddenFilterIsRenderedAndRetainedInPaginationDeletionAndStatusLinks() throws Exception {
        when(destinations.countAdminDestinations(REGION_IDS, null, null, null, false)).thenReturn(31);
        when(destinations.getAdminDestinationPage(REGION_IDS, null, null, null, "latest", 0, 30, false))
                .thenReturn(List.of(row(18L, "남산공원", false)));
        when(destinations.getAdminDestinationDataStatusCounts(REGION_IDS, null, null, false))
                .thenReturn(new AdminDestinationDataStatusCounts());
        String html = render("/admin/destinations?visibility=hidden", "admin-destinations-hidden.html");
        var document = Jsoup.parse(html);
        assertThat(document.select("#destination-visibility-filter option[selected]").attr("value"))
                .isEqualTo("hidden");
        assertThat(document.select(".admin-destination-visibility-badge").eachText()).containsExactly("숨김");
        assertThat(document.select(".admin-pagination a").eachAttr("href"))
                .isNotEmpty().allSatisfy(url -> assertThat(url).contains("visibility=hidden"));
        assertThat(document.select("tbody form[action], #destination-bulk-delete-form").eachAttr("action"))
                .hasSize(2).allSatisfy(url -> assertThat(url).contains("visibility=hidden"));
        assertThat(document.select(".admin-data-status-link").eachAttr("href"))
                .isNotEmpty().allSatisfy(url -> assertThat(url).contains("visibility=hidden"));
        assertResources(html);
    }

    private String render(String url, String fixture) throws Exception {
        var admin = new User();
        admin.setId(7L);
        admin.setUserRole(UserRole.ADMIN);
        String html = mvc.perform(get(url).with(user(new CustomUserDetails(admin))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Files.createDirectories(REPORT);
        Files.writeString(REPORT.resolve(fixture), html, StandardCharsets.UTF_8);
        return html;
    }

    private void assertResources(String html) throws Exception {
        var assets = Jsoup.parse(html).select("link[href^=/css/], script[src^=/js/]");
        assertThat(assets).isNotEmpty();
        assertThat(assets.toString()).contains("/css/admin-compact-lists-", "/js/admin-destination-filter-",
                "/js/admin-destination-bulk-delete-", "/js/admin-destination-visibility-");
        for (var asset : assets) {
            String url = asset.hasAttr("href") ? asset.attr("href") : asset.attr("src");
            assertThat(url).doesNotContain("?v=").matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
            String original = url.replaceFirst("-[a-f0-9]{32}(?=\\.(css|js)$)", "");
            byte[] bytes = new ClassPathResource("static" + original).getContentAsByteArray();
            assertThat(url).contains("-" + DigestUtils.md5DigestAsHex(bytes) + ".");
            var response = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            assertThat(response.getHeader("Cache-Control")).contains("public", "max-age=");
            assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
            assertThat(response.getContentAsString()).doesNotContain("<!DOCTYPE html>");
            var file = REPORT.resolve("assets" + url);
            Files.createDirectories(file.getParent());
            Files.write(file, response.getContentAsByteArray());
        }
    }

    private static AdminDestinationListItemDto row(Long id, String name, boolean publicVisible) {
        var row = new AdminDestinationListItemDto();
        row.setId(id);
        row.setName(name);
        row.setRegionName("서울");
        row.setPublicVisible(publicVisible);
        row.setCompleteTranslationLanguages("en,ja,zh-CN,zh-TW");
        return row;
    }
}
