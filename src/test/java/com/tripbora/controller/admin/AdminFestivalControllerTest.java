package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.AdminTravelInfoListItemDto;
import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.dto.FestivalEditData;
import com.tripbora.dto.FestivalEditForm;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.InfoImage;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.InfoCategoryService;
import com.tripbora.service.travelinfo.TravelInfoService;
import com.tripbora.service.travelinfo.FestivalRegistrationService;
import com.tripbora.service.travelinfo.FestivalRegistrationResult;
import com.tripbora.service.travelinfo.FestivalValidationException;
import com.tripbora.service.travelinfo.FestivalAdminService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminFestivalController.class)
@Import(SecurityConfig.class)
class AdminFestivalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TravelInfoService travelInfoService;
    @MockitoBean
    private InfoCategoryService infoCategoryService;
    @MockitoBean
    private FestivalRegistrationService festivalRegistrationService;
    @MockitoBean
    private FestivalAdminService festivalAdminService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void adminCanOpenFestivalOnlyListWithPeriodAndCategory() throws Exception {
        AdminTravelInfoListItemDto festival = new AdminTravelInfoListItemDto();
        festival.setId(20L);
        festival.setTitle("서울 재즈 페스티벌");
        festival.setScope(TravelInfoScope.DOMESTIC);
        festival.setContentType(TravelInfoContentType.FESTIVAL);
        festival.setCategoryId(4L);
        festival.setCategoryName("공연");
        festival.setStartDate(LocalDate.of(2026, 5, 1));
        festival.setEndDate(LocalDate.of(2026, 5, 3));
        festival.setViews(35);
        festival.setCreatedAt(Timestamp.valueOf("2026-04-01 10:00:00"));
        when(travelInfoService.getAdminList(TravelInfoScope.DOMESTIC,
                TravelInfoContentType.FESTIVAL, 4L)).thenReturn(List.of(festival));
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(4L, "공연", TravelInfoContentType.FESTIVAL),
                category(5L, "계절여행", TravelInfoContentType.GENERAL)));

        mockMvc.perform(get("/admin/festivals")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "4")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/festivals/list"))
                .andExpect(model().attribute("scope", TravelInfoScope.DOMESTIC))
                .andExpect(model().attribute("categoryId", 4L))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("서울 재즈 페스티벌")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("공연")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-05-01 ~ 2026-05-03")));

        verify(travelInfoService).getAdminList(
                TravelInfoScope.DOMESTIC, TravelInfoContentType.FESTIVAL, 4L);
    }

    @Test
    void nonAdminCannotOpenFestivalList() throws Exception {
        mockMvc.perform(get("/admin/festivals").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanOpenFestivalCreateFormWithFestivalCategoriesAndDomesticDefault() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(4L, "공연", TravelInfoContentType.FESTIVAL),
                category(5L, "계절여행", TravelInfoContentType.GENERAL)));

        mockMvc.perform(get("/admin/festivals/create").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/festivals/form"))
                .andExpect(model().attribute("scope", TravelInfoScope.DOMESTIC))
                .andExpect(model().attributeExists("festivalForm"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("축제·행사 등록")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-festival-pagination")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-festival-unregistered-only")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("공연")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("계절여행"))))
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    var document = org.jsoup.Jsoup.parse(html);
                    assertThat(document.select("[data-festival-registration-mode]")).hasSize(2);
                    assertThat(document.selectFirst("[data-festival-bulk]").hasAttr("hidden")).isTrue();
                    assertThat(document.selectFirst("form#festival-create-form input[name=_csrf]")).isNotNull();
                    for (var resource : document.select("link[href*='admin-festival-bulk'], script[src*='admin-festival-bulk']")) {
                        String url = resource.hasAttr("href") ? resource.attr("href") : resource.attr("src");
                        assertThat(url).matches("/(css|js)/admin-festival-bulk-[a-f0-9]{32}\\.(css|js)");
                        var response = mockMvc.perform(get(url).with(user("admin").roles("ADMIN")))
                                .andExpect(status().isOk()).andReturn().getResponse();
                        assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
                    }
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/festival-bulk"));
                    java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/festival-bulk/form.html"), html);
                });

        verifyNoInteractions(travelInfoService);
    }

    @Test
    void validFestivalCreateUsesAdminIdAndRedirectsWithFlashMessage() throws Exception {
        when(festivalRegistrationService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(new FestivalRegistrationResult(10L, null));
        mockMvc.perform(post("/admin/festivals/create")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "서울 빛 축제")
                        .param("content", "<p>행사 소개</p>")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "5")
                        .param("startDate", "2026-12-01")
                        .param("endDate", "2026-12-31")
                        .param("eventPlace", "광화문광장")
                        .param("ktoFestivalContentId", "12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/festivals"))
                .andExpect(flash().attribute("festivalMessage", "축제·행사가 등록되었습니다."));

        ArgumentCaptor<FestivalCreateForm> captor = ArgumentCaptor.forClass(FestivalCreateForm.class);
        verify(festivalRegistrationService).create(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        assertThat(captor.getValue().getKtoFestivalContentId()).isEqualTo("12345");
    }

    @Test
    void festivalCreateShowsImageWarningWithoutCancellingRegistration() throws Exception {
        when(festivalRegistrationService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(new FestivalRegistrationResult(10L,
                        "TourAPI 대표이미지를 저장하지 못해 이미지 없이 등록했습니다."));

        mockMvc.perform(post("/admin/festivals/create")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "이미지 경고 축제")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "5")
                        .param("startDate", "2026-12-01")
                        .param("endDate", "2026-12-31"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/festivals"))
                .andExpect(flash().attribute("festivalImageWarning",
                        "TourAPI 대표이미지를 저장하지 못해 이미지 없이 등록했습니다."));
    }

    @Test
    void festivalValidationFailureRendersSameFormWithInputAndFestivalCategories() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(5L, "축제", TravelInfoContentType.FESTIVAL),
                category(8L, "여행추천", TravelInfoContentType.GENERAL)));
        doThrow(new FestivalValidationException("endDate", "행사 종료일은 시작일보다 빠를 수 없습니다."))
                .when(festivalRegistrationService).create(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L));

        mockMvc.perform(post("/admin/festivals/create")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "입력 유지 축제")
                        .param("content", "<p>소개</p>")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "5")
                        .param("startDate", "2026-12-31")
                        .param("endDate", "2026-12-01"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/festivals/form"))
                .andExpect(model().attributeHasFieldErrors("festivalForm", "endDate"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("입력 유지 축제")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("축제")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("여행추천"))));
    }

    @Test
    void adminCanOpenFestivalEditFormWithExistingValuesImagesAndFestivalCategories() throws Exception {
        FestivalEditForm form = editForm();
        InfoImage image = image(101L, true, false, "/uploads/travel-info/festivals/main.jpg");
        InfoImage thumbnail = image(105L, false, true, "/uploads/travel-info/festivals/poster.jpg");
        form.setThumbnailImageId(105L);
        when(festivalAdminService.getEditData(10L))
                .thenReturn(new FestivalEditData(form, List.of(image, thumbnail)));
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(5L, "문화축제", TravelInfoContentType.FESTIVAL),
                category(8L, "여행추천", TravelInfoContentType.GENERAL)));

        mockMvc.perform(get("/admin/festivals/10/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/festivals/form"))
                .andExpect(model().attribute("editMode", true))
                .andExpect(model().attribute("festivalImages", List.of(image, thumbnail)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("축제·행사 수정")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("경복궁 별빛야행")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("목록 썸네일 선택")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("공식 포스터")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("checked=\"checked\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("TourAPI에서 축제·행사 불러오기"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("여행추천"))));
    }

    @Test
    void generalIdCannotOpenFestivalEditRoute() throws Exception {
        when(festivalAdminService.getEditData(10L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "축제·행사 정보를 찾을 수 없습니다."));

        mockMvc.perform(get("/admin/festivals/10/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void validFestivalEditRedirectsWithFlashMessage() throws Exception {
        mockMvc.perform(post("/admin/festivals/10/edit")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("title", "수정 축제")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "5")
                        .param("startDate", "2026-09-02")
                        .param("endDate", "2026-10-24")
                        .param("thumbnailImageId", "105"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/festivals"))
                .andExpect(flash().attribute("festivalMessage", "축제·행사가 수정되었습니다."));

        ArgumentCaptor<FestivalEditForm> captor = ArgumentCaptor.forClass(FestivalEditForm.class);
        verify(festivalAdminService).update(org.mockito.ArgumentMatchers.eq(10L), captor.capture());
        assertThat(captor.getValue().getThumbnailImageId()).isEqualTo(105L);
    }

    @Test
    void festivalEditValidationFailureRestoresInputCategoriesAndImagePicker() throws Exception {
        FestivalEditForm persisted = editForm();
        InfoImage image = image(101L, true, false, "/uploads/travel-info/festivals/main.jpg");
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(5L, "문화축제", TravelInfoContentType.FESTIVAL),
                category(8L, "여행추천", TravelInfoContentType.GENERAL)));
        when(festivalAdminService.getEditData(10L))
                .thenReturn(new FestivalEditData(persisted, List.of(image)));
        doThrow(new FestivalValidationException("endDate", "행사 종료일은 시작일보다 빠를 수 없습니다."))
                .when(festivalAdminService).update(
                        org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/admin/festivals/10/edit")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("title", "입력 유지 수정 축제")
                        .param("scope", "DOMESTIC")
                        .param("categoryId", "5")
                        .param("startDate", "2026-10-24")
                        .param("endDate", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/festivals/form"))
                .andExpect(model().attributeHasFieldErrors("festivalForm", "endDate"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("입력 유지 수정 축제")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대표사진")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("여행추천"))));
    }

    @Test
    void adminCanDeleteFestivalThroughPostAndReceivesFlashMessage() throws Exception {
        mockMvc.perform(post("/admin/festivals/10/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/festivals"))
                .andExpect(flash().attribute("festivalMessage", "축제·행사가 삭제되었습니다."));

        verify(festivalAdminService).delete(10L);
    }

    private FestivalEditForm editForm() {
        FestivalEditForm form = new FestivalEditForm();
        form.setTitle("경복궁 별빛야행");
        form.setContent("<p>행사 소개</p>");
        form.setScope(TravelInfoScope.DOMESTIC);
        form.setCategoryId(5L);
        form.setStartDate(LocalDate.parse("2026-09-02"));
        form.setEndDate(LocalDate.parse("2026-10-24"));
        form.setEventPlace("경복궁");
        return form;
    }

    private InfoImage image(Long id, boolean main, boolean thumbnail, String url) {
        InfoImage image = new InfoImage();
        image.setId(id);
        image.setInfoId(10L);
        image.setImageUrl(url);
        image.setIsMain(main);
        image.setIsThumbnail(thumbnail);
        image.setSourceTitle(main ? "대표사진" : "공식 포스터");
        image.setLicenseType("KOGL_TYPE_1");
        return image;
    }

    private InfoCategory category(Long id, String name, TravelInfoContentType contentType) {
        InfoCategory category = new InfoCategory();
        category.setId(id);
        category.setName(name);
        category.setContentType(contentType);
        category.setIsVisible(true);
        return category;
    }

    private com.tripbora.security.CustomUserDetails adminDetails() {
        com.tripbora.model.User user = new com.tripbora.model.User();
        user.setId(7L);
        user.setUserPassword("password");
        user.setUserRole(com.tripbora.model.UserRole.ADMIN);
        return new com.tripbora.security.CustomUserDetails(user);
    }
}
