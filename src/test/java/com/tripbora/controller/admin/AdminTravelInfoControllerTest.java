package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.AdminTravelInfoDetailDto;
import com.tripbora.dto.AdminTravelInfoListItemDto;
import com.tripbora.dto.InfoPeriodForm;
import com.tripbora.dto.TravelInfoForm;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.InfoPeriod;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.InfoCategoryService;
import com.tripbora.service.travelinfo.TravelInfoService;
import com.tripbora.service.travelinfo.TravelInfoValidationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminTravelInfoController.class)
@Import(SecurityConfig.class)
class AdminTravelInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TravelInfoService travelInfoService;
    @MockitoBean
    private InfoCategoryService infoCategoryService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void adminListAlwaysQueriesGeneralTravelInfo() throws Exception {
        AdminTravelInfoListItemDto item = new AdminTravelInfoListItemDto();
        item.setId(10L);
        item.setTitle("벚꽃 여행 가이드");
        item.setScope(TravelInfoScope.DOMESTIC);
        item.setContentType(TravelInfoContentType.GENERAL);
        item.setCategoryName("계절여행");
        item.setViews(12);
        item.setCreatedAt(Timestamp.valueOf("2026-04-01 10:00:00"));
        when(travelInfoService.getAdminList(TravelInfoScope.DOMESTIC,
                TravelInfoContentType.GENERAL, 3L)).thenReturn(List.of(item));
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(3L, "계절여행", true, TravelInfoContentType.GENERAL),
                category(4L, "축제", true, TravelInfoContentType.FESTIVAL)));

        mockMvc.perform(get("/admin/travel-info")
                        .param("scope", "DOMESTIC")
                        .param("contentType", "FESTIVAL")
                        .param("categoryId", "3")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/travel-info/list"))
                .andExpect(model().attribute("scope", TravelInfoScope.DOMESTIC))
                .andExpect(model().attribute("categoryId", 3L))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("벚꽃 여행 가이드")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("12")));

        verify(travelInfoService).getAdminList(
                TravelInfoScope.DOMESTIC, TravelInfoContentType.GENERAL, 3L);
    }

    @Test
    void adminGuideListIgnoresScopeAndShowsGuideBadge() throws Exception {
        AdminTravelInfoListItemDto item = new AdminTravelInfoListItemDto();
        item.setId(21L);
        item.setTitle("환전 가이드");
        item.setContentType(TravelInfoContentType.GUIDE);
        item.setCategoryName("여행준비");
        item.setViews(3);
        item.setCreatedAt(Timestamp.valueOf("2026-04-01 10:00:00"));
        when(travelInfoService.getAdminList(null, TravelInfoContentType.GUIDE, null))
                .thenReturn(List.of(item));
        when(infoCategoryService.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/admin/travel-info")
                        .param("contentType", "GUIDE")
                        .param("scope", "DOMESTIC")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scope", (Object) null))
                .andExpect(model().attribute("contentType", TravelInfoContentType.GUIDE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("환전 가이드")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("여행가이드")));

        verify(travelInfoService).getAdminList(null, TravelInfoContentType.GUIDE, null);
    }

    @Test
    void adminCanOpenFestivalDetailWithMultiplePeriodsAndHtmlContent() throws Exception {
        AdminTravelInfoDetailDto detail = detail(TravelInfoContentType.FESTIVAL, List.of(
                infoPeriod("2026-04-01", "2026-04-03"),
                infoPeriod("2026-05-10", "2026-05-12")));
        detail.setContent("<p><span class=\"ql-font-noto-serif-kr\">축제 본문</span></p>"
                + "<img src=\"/uploads/editor/festival.png\" width=\"600\" alt=\"축제\">");
        when(travelInfoService.getAdminDetail(10L)).thenReturn(detail);

        mockMvc.perform(get("/admin/travel-info/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/travel-info/detail"))
                .andExpect(model().attribute("travelInfo", detail))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("벚꽃 축제")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("계절여행")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-04-01")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-04-03")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-05-10")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-05-12")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"admin-travel-info-content rich-text-content rich-text-image-layout\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<p><span class=\"ql-font-noto-serif-kr\">축제 본문</span></p>"
                                + "<img src=\"/uploads/editor/festival.png\" width=\"600\" alt=\"축제\">")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("&lt;p&gt;축제 본문&lt;/p&gt;"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/admin/travel-info\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/admin/travel-info/edit/10\"")));
    }

    @Test
    void generalDetailDoesNotRenderPeriodSection() throws Exception {
        AdminTravelInfoDetailDto detail = detail(TravelInfoContentType.GENERAL, List.of());
        when(travelInfoService.getAdminDetail(10L)).thenReturn(detail);

        mockMvc.perform(get("/admin/travel-info/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("축제 기간"))));
    }

    @Test
    void missingDetailReturnsNotFound() throws Exception {
        when(travelInfoService.getAdminDetail(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/admin/travel-info/99").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createFormShowsOnlyVisibleCategoriesAndGeneralDefault() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(1L, "계절여행", true),
                category(2L, "숨김 분류", false)));

        mockMvc.perform(get("/admin/travel-info/create").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/travel-info/form"))
                .andExpect(model().attribute("editMode", false))
                .andExpect(model().attribute("formAction", "/admin/travel-info"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-kto-festival-autofill")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/js/admin-travel-info-festival-autofill.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("계절여행")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("숨김 분류"))))
                .andExpect(result -> {
                    TravelInfoForm form = (TravelInfoForm) result.getModelAndView().getModel()
                            .get("travelInfoForm");
                    assertThat(form.getContentType()).isEqualTo(TravelInfoContentType.GENERAL);
                });
    }

    @Test
    void createFormMarksOnlyMatchingContentTypeCategoriesAsSelectable() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(1L, "일반 분류", true, TravelInfoContentType.GENERAL),
                category(2L, "축제 분류", true, TravelInfoContentType.FESTIVAL)));

        mockMvc.perform(get("/admin/travel-info/create").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = org.jsoup.Jsoup.parse(result.getResponse().getContentAsString());
                    var generalOption = document.selectFirst("#travel-info-category option[value='1']");
                    var festivalOption = document.selectFirst("#travel-info-category option[value='2']");

                    assertThat(generalOption).isNotNull();
                    assertThat(generalOption.attr("data-content-type")).isEqualTo("GENERAL");
                    assertThat(generalOption.hasAttr("disabled")).isFalse();
                    assertThat(festivalOption).isNotNull();
                    assertThat(festivalOption.attr("data-content-type")).isEqualTo("FESTIVAL");
                    assertThat(festivalOption.hasAttr("hidden")).isTrue();
                    assertThat(festivalOption.hasAttr("disabled")).isTrue();
                });
    }

    @Test
    void editFormKeepsCurrentlySelectedHiddenCategory() throws Exception {
        TravelInfoForm form = validForm();
        form.setCategoryId(2L);
        form.setContentType(TravelInfoContentType.FESTIVAL);
        InfoPeriodForm period = new InfoPeriodForm();
        period.setStartDate(LocalDate.parse("2026-04-01"));
        period.setEndDate(LocalDate.parse("2026-04-03"));
        form.setPeriods(List.of(period));
        when(travelInfoService.getForm(10L)).thenReturn(form);
        when(travelInfoService.getThumbnailUrl(10L))
                .thenReturn("/uploads/travel-info/thumbnails/current.jpg");
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(1L, "계절여행", true),
                category(2L, "기존 숨김 분류", false, TravelInfoContentType.FESTIVAL),
                category(3L, "다른 숨김 분류", false)));

        mockMvc.perform(get("/admin/travel-info/edit/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("editMode", true))
                .andExpect(model().attribute("formAction", "/admin/travel-info/edit/10"))
                .andExpect(model().attribute("currentThumbnailUrl",
                        "/uploads/travel-info/thumbnails/current.jpg"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "src=\"/uploads/travel-info/thumbnails/current.jpg\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-kto-festival-autofill"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                                "/js/admin-travel-info-festival-autofill.js"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("기존 숨김 분류 (숨김)")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("다른 숨김 분류"))));
    }

    @Test
    void validCreateUsesAuthenticatedAdminId() throws Exception {
        mockMvc.perform(post("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "벚꽃 여행")
                        .param("content", "<p>본문</p>")
                        .param("scope", "DOMESTIC")
                        .param("contentType", "GENERAL")
                        .param("categoryId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/travel-info"));

        verify(travelInfoService).create(any(TravelInfoForm.class), org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void contentSectionFromTheFormBindsToExistingContentTypeAndScope() throws Exception {
        // 화면은 콘텐츠 구분 하나만 보낸다. 범위 select 는 축제·행사가 아니면 막혀 있어 오지 않는다.
        mockMvc.perform(multipart("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "해외 여행")
                        .param("content", "<p>본문</p>")
                        .param("contentSection", "GENERAL_INTERNATIONAL")
                        .param("categoryId", "3")
                        .param("homeFeaturedOrder", "3"))
                .andExpect(status().is3xxRedirection());

        // 축제·행사는 따로 고른 국내/해외를 그대로 쓴다.
        mockMvc.perform(multipart("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "축제")
                        .param("content", "<p>본문</p>")
                        .param("contentSection", "FESTIVAL")
                        .param("scope", "INTERNATIONAL")
                        .param("categoryId", "3"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<TravelInfoForm> captor = ArgumentCaptor.forClass(TravelInfoForm.class);
        verify(travelInfoService, org.mockito.Mockito.times(2))
                .create(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        TravelInfoForm general = captor.getAllValues().get(0);
        assertThat(general.getContentType()).isEqualTo(TravelInfoContentType.GENERAL);
        assertThat(general.getScope()).isEqualTo(TravelInfoScope.INTERNATIONAL);
        assertThat(general.isHomeFeatured()).isFalse();
        assertThat(general.getHomeFeaturedOrder()).isEqualTo(3);
        TravelInfoForm festival = captor.getAllValues().get(1);
        assertThat(festival.getContentType()).isEqualTo(TravelInfoContentType.FESTIVAL);
        assertThat(festival.getScope()).isEqualTo(TravelInfoScope.INTERNATIONAL);
    }

    @Test
    void multipartCreateBindsThumbnailToExistingTravelInfoForm() throws Exception {
        MockMultipartFile thumbnail = new MockMultipartFile(
                "thumbnailFile", "thumbnail.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        mockMvc.perform(multipart("/admin/travel-info")
                        .file(thumbnail)
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "썸네일 여행")
                        .param("content", "<p>본문</p>")
                        .param("scope", "DOMESTIC")
                        .param("contentType", "GENERAL")
                        .param("categoryId", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/travel-info"));

        ArgumentCaptor<TravelInfoForm> captor = ArgumentCaptor.forClass(TravelInfoForm.class);
        verify(travelInfoService).create(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        assertThat(captor.getValue().getThumbnailFile()).isNotNull();
        assertThat(captor.getValue().getThumbnailFile().getOriginalFilename()).isEqualTo("thumbnail.jpg");
    }

    @Test
    void bindingErrorKeepsInputAndDoesNotCallService() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(category(3L, "계절여행", true)));

        mockMvc.perform(post("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "입력 유지 제목")
                        .param("content", "<p>본문</p>")
                        .param("scope", "UNKNOWN")
                        .param("contentType", "GENERAL")
                        .param("categoryId", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/travel-info/form"))
                .andExpect(model().attributeHasFieldErrors("travelInfoForm", "scope"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("입력 유지 제목")));

        verify(travelInfoService, never()).create(any(), any());
    }

    @Test
    void createFormOffersBothWritingModesWithQuillByDefault() throws Exception {
        String html = mockMvc.perform(get("/admin/travel-info/create").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        assertThat(document.select("input[type=radio][name=contentFormat]").eachAttr("value"))
                .containsExactly("QUILL", "STRUCTURED");
        assertThat(document.selectFirst("input[name=contentFormat][value=QUILL]").hasAttr("checked")).isTrue();
        assertThat(document.selectFirst("#travel-info-quill-body").hasAttr("hidden")).isFalse();
        assertThat(document.selectFirst("[data-structured-editor]").hasAttr("hidden")).isTrue();
        assertThat(document.selectFirst("#travel-info-structured-content").attr("name")).isEqualTo("structuredContent");
        assertThat(document.selectFirst("#travel-info-structured-translation-notice").hasAttr("hidden")).isTrue();
        assertThat(document.select("input[name=translations[1].structuredText]")).hasSize(1);
    }

    @Test
    void structuredEditFormFixesTheWritingModeAndCarriesTheBlocks() throws Exception {
        TravelInfoForm form = new TravelInfoForm();
        form.setTitle("서울 궁 투어");
        form.setContentSection("GENERAL_DOMESTIC");
        form.setCategoryId(3L);
        form.setContentFormat(com.tripbora.model.TravelInfoContentFormat.STRUCTURED);
        form.setStructuredContent("{\"version\":1,\"blocks\":[{\"type\":\"CALLOUT\",\"id\":\"b-1\",\"text\":\"<b>휴궁</b>\"}]}");
        when(travelInfoService.getForm(10L)).thenReturn(form);

        String html = mockMvc.perform(get("/admin/travel-info/edit/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        assertThat(document.select("input[type=radio][name=contentFormat]")).isEmpty();
        assertThat(document.selectFirst("#travel-info-content-format-fixed").val()).isEqualTo("STRUCTURED");
        assertThat(document.selectFirst(".admin-content-format.is-fixed").text())
                .contains("구조화 에디터", "등록한 뒤에는 작성 방식을 바꿀 수 없습니다.");
        assertThat(document.selectFirst("#travel-info-quill-body").hasAttr("hidden")).isTrue();
        assertThat(document.selectFirst("[data-structured-editor]").hasAttr("hidden")).isFalse();
        // JSON 은 속성 값으로 escape 되어 실리고, 에디터가 그대로 다시 읽는다.
        assertThat(document.selectFirst("#travel-info-structured-content").val()).isEqualTo(form.getStructuredContent());
        assertThat(html).doesNotContain("value=\"{\"version\"");
        assertThat(document.selectFirst("#travel-info-structured-translation-notice").hasAttr("hidden")).isFalse();
    }

    @Test
    void rejectedStructuredCreateComesBackWithTheSubmittedBlocks() throws Exception {
        String submitted = "{\"version\":1,\"blocks\":[{\"type\":\"RICH_TEXT\",\"id\":\"b-1\",\"text\":\"긴 글\"}]}";
        doThrow(new com.tripbora.service.travelinfo.structured.StructuredContentValidationException(
                "1번째 블록(본문): 본문은 5000자 이하로 입력해 주세요."))
                .when(travelInfoService).create(any(), org.mockito.ArgumentMatchers.eq(7L));

        String html = mockMvc.perform(post("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "서울 궁 투어")
                        .param("contentSection", "GENERAL_DOMESTIC")
                        .param("categoryId", "3")
                        .param("contentFormat", "STRUCTURED")
                        .param("structuredContent", submitted))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/travel-info/form"))
                .andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        // 칸별 자리가 없는 오류라 위쪽 전체 오류로 보이고, 작성한 블록과 작성 방식이 그대로 돌아온다.
        assertThat(document.selectFirst(".admin-alert").text()).contains("본문은 5000자 이하로 입력해 주세요.");
        assertThat(document.selectFirst("input[name=contentFormat][value=STRUCTURED]").hasAttr("checked")).isTrue();
        assertThat(document.selectFirst("#travel-info-structured-content").val()).isEqualTo(submitted);
        assertThat(document.selectFirst("[data-structured-editor]").hasAttr("hidden")).isFalse();
    }

    @Test
    void serviceValidationReturnsFieldErrorOnSameForm() throws Exception {
        doThrow(new TravelInfoValidationException("content", "본문을 입력해 주세요."))
                .when(travelInfoService).create(any(), org.mockito.ArgumentMatchers.eq(7L));

        mockMvc.perform(post("/admin/travel-info")
                        .with(user(adminDetails()))
                        .with(csrf())
                        .param("title", "제목")
                        .param("content", "<p><br></p>")
                        .param("scope", "DOMESTIC")
                        .param("contentType", "GENERAL")
                        .param("categoryId", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("travelInfoForm", "content"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("본문을 입력해 주세요.")));
    }

    @Test
    void validUpdateAndDeleteRedirectToList() throws Exception {
        when(travelInfoService.getById(10L)).thenReturn(new com.tripbora.model.TravelInfo());

        mockMvc.perform(post("/admin/travel-info/edit/10")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("title", "수정 제목")
                        .param("content", "<p>수정 본문</p>")
                        .param("scope", "INTERNATIONAL")
                        .param("contentType", "FESTIVAL")
                        .param("categoryId", "3")
                        .param("periods[0].startDate", "2026-02-01")
                        .param("periods[0].endDate", "2026-02-10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/travel-info"));
        verify(travelInfoService).update(org.mockito.ArgumentMatchers.eq(10L), any());

        mockMvc.perform(post("/admin/travel-info/10/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/travel-info"));
        verify(travelInfoService).delete(10L);
    }

    @Test
    void updateBindsThumbnailRemovalWithoutTrustingAClientImageUrl() throws Exception {
        when(travelInfoService.getById(10L)).thenReturn(new com.tripbora.model.TravelInfo());

        mockMvc.perform(post("/admin/travel-info/edit/10")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("title", "수정 제목")
                        .param("content", "<p>수정 본문</p>")
                        .param("scope", "DOMESTIC")
                        .param("contentType", "GENERAL")
                        .param("categoryId", "3")
                        .param("removeThumbnail", "true")
                        .param("currentThumbnailUrl", "/uploads/travel-info/thumbnails/forged.jpg"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/travel-info"));

        ArgumentCaptor<TravelInfoForm> captor = ArgumentCaptor.forClass(TravelInfoForm.class);
        verify(travelInfoService).update(org.mockito.ArgumentMatchers.eq(10L), captor.capture());
        assertThat(captor.getValue().isRemoveThumbnail()).isTrue();
    }

    @Test
    void missingEditAndDeleteReturnNotFound() throws Exception {
        when(travelInfoService.getForm(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(travelInfoService.getById(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(travelInfoService).delete(99L);

        mockMvc.perform(get("/admin/travel-info/edit/99").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/travel-info/edit/99")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/travel-info/99/delete")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteIsNotAvailableByGet() throws Exception {
        mockMvc.perform(get("/admin/travel-info/10/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());
        verify(travelInfoService, never()).delete(10L);
    }

    @Test
    void guestIsRedirectedAndRegularUserIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/travel-info"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/travel-info").with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
        // 토큰을 갖춰도 권한에서 막힌다
        mockMvc.perform(post("/admin/travel-info/10/delete")
                        .with(user("member").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        // 관리자라도 토큰이 없으면 막힌다
        mockMvc.perform(post("/admin/travel-info/10/delete")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verify(travelInfoService, never()).delete(10L);
    }

    private TravelInfoForm validForm() {
        TravelInfoForm form = new TravelInfoForm();
        form.setTitle("벚꽃 여행");
        form.setContent("<p>본문</p>");
        form.setScope(TravelInfoScope.DOMESTIC);
        form.setContentType(TravelInfoContentType.GENERAL);
        form.setCategoryId(3L);
        return form;
    }

    @Test
    void structuredAdminDetailReusesThePublicBlockFragment() throws Exception {
        AdminTravelInfoDetailDto detail = detail(TravelInfoContentType.GENERAL, List.of());
        detail.setContent("<p>DERIVED-SEARCH-HTML</p>");
        detail.setContentFormat(com.tripbora.model.TravelInfoContentFormat.STRUCTURED);
        detail.setStructuredContent(
                com.tripbora.service.travelinfo.structured.StructuredContentSamples.palaceTour());
        when(travelInfoService.getAdminDetail(10L)).thenReturn(detail);

        String html = mockMvc.perform(get("/admin/travel-info/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        assertThat(document.select(".admin-travel-info-content.travel-info-structured .structured-content"))
                .hasSize(1);
        assertThat(document.select("[data-structured-slider]")).hasSize(3);
        // 이미지 2장 / 3장 배치도 공개 상세와 같은 fragment 로 그린다.
        assertThat(document.select(".structured-grid").eachAttr("class"))
                .containsExactly("structured-grid is-two", "structured-grid is-three");
        assertThat(document.select(".structured-grid.is-three .structured-grid-media img")).hasSize(3);
        assertThat(document.select(".structured-section-title h2").eachText()).containsExactly("경복궁", "창덕궁");
        assertThat(document.select("link[href^=/css/travel-info-structured.css]")).hasSize(1);
        assertThat(document.select("script[src^=/js/structured-slider.js]")).hasSize(1);
        assertThat(document.select(".rich-text-content")).isEmpty();
        assertThat(document.text()).doesNotContain("DERIVED-SEARCH-HTML");
    }

    @Test
    void structuredAdminDetailPreviewShowsImageCreditsThroughThePublicFragment() throws Exception {
        var image = com.tripbora.service.travelinfo.structured.StructuredContentSamples.image(1, 1600, 1000);
        var credited = new com.tripbora.service.travelinfo.structured.StructuredImage(
                image.url(), image.width(), image.height(),
                new com.tripbora.service.travelinfo.structured.StructuredImageCredit("John Doe", "Wikimedia Commons",
                        "https://commons.wikimedia.org/wiki/File:A.jpg", "CC BY-SA 4.0", null));
        AdminTravelInfoDetailDto detail = detail(TravelInfoContentType.GENERAL, List.of());
        detail.setContentFormat(com.tripbora.model.TravelInfoContentFormat.STRUCTURED);
        detail.setStructuredContent(new com.tripbora.service.travelinfo.structured.StructuredContent(1, List.of(
                new com.tripbora.service.travelinfo.structured.StructuredBlock.FullImage("full", credited, null, "가을의 경복궁"),
                new com.tripbora.service.travelinfo.structured.StructuredBlock.FullImage("plain", image, null, "광화문"))));
        when(travelInfoService.getAdminDetail(10L)).thenReturn(detail);

        String html = mockMvc.perform(get("/admin/travel-info/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        // 관리자 미리보기도 공개 상세와 같은 fragment 라 같은 자리(사진 틀 안 오버레이)에 같은 출처가 나온다.
        assertThat(document.select(".structured-figure figcaption").eachText()).containsExactly("가을의 경복궁", "광화문");
        assertThat(document.select(".structured-figure > img + .structured-media-credit").eachText())
                .containsExactly("사진: John Doe · Wikimedia Commons · CC BY-SA 4.0");
        assertThat(document.select(".structured-media-credit a").eachAttr("href"))
                .containsExactly("https://commons.wikimedia.org/wiki/File:A.jpg");
        assertThat(document.select(".structured-media-credit a").attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(document.select("link[href='/css/travel-info-structured.css?v=20261014-rich-text-2']")).hasSize(1);
        // 확대 모달은 공개 상세 전용이다. 관리자 미리보기에는 모달·스크립트를 두지 않는다.
        assertThat(document.select("[data-structured-gallery]")).isEmpty();
        assertThat(document.select("script[src^=/js/structured-gallery.js]")).isEmpty();
    }

    @Test
    void quillAdminDetailDoesNotLoadStructuredAssets() throws Exception {
        when(travelInfoService.getAdminDetail(10L)).thenReturn(detail(TravelInfoContentType.GENERAL, List.of()));

        String html = mockMvc.perform(get("/admin/travel-info/10").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("class=\"admin-travel-info-content rich-text-content rich-text-image-layout\"")
                .doesNotContain("travel-info-structured", "structured-slider.js");
    }

    private AdminTravelInfoDetailDto detail(TravelInfoContentType contentType, List<InfoPeriod> periods) {
        AdminTravelInfoDetailDto detail = new AdminTravelInfoDetailDto();
        detail.setId(10L);
        detail.setTitle(contentType == TravelInfoContentType.FESTIVAL ? "벚꽃 축제" : "봄 여행 정보");
        detail.setContent("<p>본문</p>");
        detail.setScope(TravelInfoScope.DOMESTIC);
        detail.setContentType(contentType);
        detail.setCategoryId(3L);
        detail.setCategoryName("계절여행");
        detail.setViews(12);
        detail.setCreatedAt(Timestamp.valueOf("2026-04-01 10:00:00"));
        detail.setUpdatedAt(Timestamp.valueOf("2026-04-02 11:30:00"));
        detail.setPeriods(periods);
        return detail;
    }

    /**
     * 축제 기간 입력은 {@code input type="date"} 라 ISO(yyyy-MM-dd) 값만 읽는다.
     * 화면 언어에 맞춘 짧은 날짜로 찍히면 브라우저가 값을 버려 빈 칸으로 보인다.
     */
    @Test
    void festivalPeriodInputsKeepIsoDateValuesOnTheEditScreen() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(
                category(3L, "문화축제", true, TravelInfoContentType.FESTIVAL)));

        TravelInfoForm form = new TravelInfoForm();
        form.setTitle("벚꽃 축제");
        form.setContent("<p>본문</p>");
        form.setScope(TravelInfoScope.DOMESTIC);
        form.setContentType(TravelInfoContentType.FESTIVAL);
        form.setCategoryId(3L);
        InfoPeriodForm period = new InfoPeriodForm();
        period.setStartDate(LocalDate.parse("2026-09-02"));
        period.setEndDate(LocalDate.parse("2026-10-24"));
        form.setPeriods(new java.util.ArrayList<>(List.of(period)));
        when(travelInfoService.getForm(10L)).thenReturn(form);

        String html = mockMvc.perform(get("/admin/travel-info/edit/10")
                        .with(user(adminDetails())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);

        assertThat(document.select("#period-start-0").attr("value")).isEqualTo("2026-09-02");
        assertThat(document.select("#period-end-0").attr("value")).isEqualTo("2026-10-24");
    }

    @Test
    void editScreenRestoresContentSectionAndKeepsOrderEditableWhenNotFeatured() throws Exception {
        when(infoCategoryService.getAll()).thenReturn(List.of(category(3L, "현지정보", true)));
        TravelInfoForm general = new TravelInfoForm();
        general.setTitle("해외 여행");
        general.setContent("<p>본문</p>");
        general.setContentType(TravelInfoContentType.GENERAL);
        general.setScope(TravelInfoScope.INTERNATIONAL);
        general.setCategoryId(3L);
        general.setHomeFeaturedOrder(3);
        when(travelInfoService.getForm(10L)).thenReturn(general);

        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(mockMvc.perform(
                        get("/admin/travel-info/edit/10").with(user(adminDetails())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.select("#travel-info-content-type option[selected]").attr("value"))
                .isEqualTo("GENERAL_INTERNATIONAL");
        // 국내/해외 여행정보의 범위는 콘텐츠 구분이 정하므로 행사 범위는 숨기고 보내지 않는다.
        assertThat(document.select("#travel-info-scope-field").hasAttr("hidden")).isTrue();
        assertThat(document.select("#travel-info-scope").hasAttr("disabled")).isTrue();
        assertThat(document.select("#travel-info-home-featured").hasAttr("hidden")).isFalse();
        assertThat(document.select("#travel-info-home-featured-check").hasAttr("checked")).isFalse();
        assertThat(document.select("#travel-info-home-featured-order").hasAttr("disabled")).isFalse();
        assertThat(document.select("#travel-info-home-featured-order").attr("value")).isEqualTo("3");

        TravelInfoForm festival = new TravelInfoForm();
        festival.setTitle("벚꽃 축제");
        festival.setContent("<p>본문</p>");
        festival.setContentType(TravelInfoContentType.FESTIVAL);
        festival.setScope(TravelInfoScope.INTERNATIONAL);
        festival.setCategoryId(3L);
        when(travelInfoService.getForm(11L)).thenReturn(festival);

        document = org.jsoup.Jsoup.parse(mockMvc.perform(
                        get("/admin/travel-info/edit/11").with(user(adminDetails())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.select("#travel-info-content-type option[selected]").attr("value"))
                .isEqualTo("FESTIVAL");
        assertThat(document.select("#travel-info-scope-field").hasAttr("hidden")).isFalse();
        assertThat(document.select("#travel-info-scope option[selected]").attr("value"))
                .isEqualTo("INTERNATIONAL");
        assertThat(document.select("#travel-info-home-featured").hasAttr("hidden")).isTrue();
    }

    private InfoPeriod infoPeriod(String startDate, String endDate) {
        InfoPeriod period = new InfoPeriod();
        period.setInfoId(10L);
        period.setStartDate(LocalDate.parse(startDate));
        period.setEndDate(LocalDate.parse(endDate));
        return period;
    }

    private InfoCategory category(Long id, String name, boolean visible) {
        return category(id, name, visible, TravelInfoContentType.GENERAL);
    }

    private InfoCategory category(Long id,
                                  String name,
                                  boolean visible,
                                  TravelInfoContentType contentType) {
        InfoCategory category = new InfoCategory();
        category.setId(id);
        category.setName(name);
        category.setContentType(contentType);
        category.setDisplayOrder(id.intValue());
        category.setIsVisible(visible);
        return category;
    }

    private CustomUserDetails adminDetails() {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.ADMIN);
        return new CustomUserDetails(user);
    }
}
