package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.DigestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON 일괄 등록 화면 계약: 입력 두 가지, 상단 요약, 상태 필터, 마스터 데이터 내보내기, 여행지 관리에서 진입. */
@WebMvcTest(AdminDestinationController.class)
@Import(SecurityConfig.class)
class AdminDestinationJsonImportPageRenderingTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DestinationService destinationService;
    @MockitoBean private CategoryService categoryService;
    @MockitoBean private AmenityService amenityService;
    @MockitoBean private CountryCategoryService countryCategoryService;
    @MockitoBean private KtoSelectedPhotoRequestParser ktoSelectedPhotoRequestParser;
    @MockitoBean private DestinationSaveOrchestrationService destinationSaveOrchestrationService;
    @MockitoBean private RestaurantInfoService restaurantInfoService;
    @MockitoBean private AttractionInfoService attractionInfoService;
    @MockitoBean private AccommodationInfoService accommodationInfoService;
    @MockitoBean private ActivityInfoService activityInfoService;
    @MockitoBean private ShopInfoService shopInfoService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void thePageOffersPasteAndFileInputSummaryFiltersAndMasterExport() throws Exception {
        var document = Jsoup.parse(mockMvc.perform(get("/admin/destinations/json-import")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.selectFirst("textarea[data-json-import-text]")).isNotNull();
        assertThat(document.selectFirst("input[type=file][data-json-import-file]").attr("accept"))
                .contains(".json");
        assertThat(document.selectFirst("[data-json-import-preview]").text()).isEqualTo("미리보기");
        // 상단 요약: 총 · 등록 가능 · 중복 확인 · 등록됨 · 오류
        assertThat(document.select("[data-json-import-summary-count]").eachAttr("data-json-import-summary-count"))
                .containsExactly("ALL", "NOT_REGISTERED", "POSSIBLE_DUPLICATE", "REGISTERED", "INVALID");
        assertThat(document.select("[data-json-import-summary] dt").eachText())
                .containsExactly("총", "등록 가능", "중복 확인", "등록됨", "오류");
        var filters = document.select("[data-json-import-filter]");
        assertThat(filters.eachAttr("data-json-import-filter"))
                .containsExactly("ALL", "NOT_REGISTERED", "POSSIBLE_DUPLICATE", "REGISTERED", "INVALID");
        assertThat(filters.stream().filter(filter -> filter.hasClass("active")).toList()).singleElement()
                .satisfies(filter -> assertThat(filter.attr("data-json-import-filter")).isEqualTo("ALL"));
        // 고르기 전에는 등록할 수 없다.
        assertThat(document.selectFirst("[data-json-import-register]").hasAttr("disabled")).isTrue();
        assertThat(document.select(".admin-json-import-table thead th").eachText())
                .contains("여행지", "국가·지역", "유형", "카테고리", "상태", "검수");
        var master = document.selectFirst("a[href='/admin/api/destinations/import-json/master']");
        assertThat(master).isNotNull();
        assertThat(master.attr("download")).isEqualTo("destination-import-master.json");
        assertThat(document.select("script[src^='/js/admin-destination-json-import-']")).hasSize(1);
        assertThat(document.selectFirst("meta[name=_csrf]").attr("content")).isNotBlank();
    }

    @Test
    void updateModeRendersAlongsideCreateAndRequestsHashedAssetsOverHttp() throws Exception {
        String html = mockMvc.perform(get("/admin/destinations/json-import").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertThat(document.select("input[name=json-mode][value=import][checked]")).hasSize(1);
        assertThat(document.select("input[name=json-mode][value=update]")).hasSize(1);
        assertThat(document.select("[data-json-update][hidden]")).hasSize(1);
        assertThat(document.select("[data-update-step]").eachText()).containsExactly(
                "① JSON 파일 선택 및 자동 분석", "② 여행지 확인 및 변경 내용 선택", "③ 선택한 여행지 업데이트");
        assertThat(document.select(".admin-json-update-source:not([open]) [data-update-text]")).hasSize(1);
        assertThat(document.select("[data-update-preview]").text()).isEqualTo("JSON 다시 분석");
        assertThat(document.text()).contains("한 줄 소개", "상세 설명", "한국어");
        var directory = Path.of("build/reports/destination-json-update");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("page.html"), html);
        for (var asset : document.select("link[href^=/css/],script[src^=/js/]")) {
            String url = asset.hasAttr("href") ? asset.attr("href") : asset.attr("src");
            assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
            String original = url.replaceFirst("-[a-f0-9]{32}(?=\\.(css|js)$)", "");
            byte[] source = new ClassPathResource("static" + original).getContentAsByteArray();
            assertThat(url).contains("-" + DigestUtils.md5DigestAsHex(source) + ".");
            var response = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            assertThat(response.getContentAsString()).doesNotContain("<!DOCTYPE html>");
            var file = directory.resolve("assets" + url);
            Files.createDirectories(file.getParent());
            Files.write(file, response.getContentAsByteArray());
        }
    }

    @Test
    void theDestinationListLinksToTheJsonImportPage() throws Exception {
        try (InputStream template = getClass().getResourceAsStream("/templates/admin/destinations/list.html")) {
            String listPage = new String(template.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(listPage).contains("@{/admin/destinations/json-import}").contains("JSON 일괄 등록");
        }
    }
}
