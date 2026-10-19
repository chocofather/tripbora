package com.tripbora.controller.travelplan;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.TravelPlanDayDetailDto;
import com.tripbora.dto.TravelPlanDetailDto;
import com.tripbora.dto.TravelPlanListItemDto;
import com.tripbora.dto.TravelPlanListPageDto;
import com.tripbora.dto.TravelPlanMemberDto;
import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.model.TravelPlanRole;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanAccessNotice;
import com.tripbora.service.travelplan.TravelPlanConflictException;
import com.tripbora.service.travelplan.TravelPlanInvitationService;
import com.tripbora.service.travelplan.TravelPlanService;
import com.tripbora.service.travelplan.TravelPlanValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 방 생성 폼과 처리. 검증은 Service 가 하고 Controller 는 바인딩과 오류 표시만 맡는다.
 */
@WebMvcTest(TravelPlanController.class)
@Import(SecurityConfig.class)
class TravelPlanControllerTest {

    private static final String START = "2026-09-01";
    private static final String END = "2026-09-03";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TravelPlanService travelPlanService;
    @MockitoBean
    private TravelPlanInvitationService travelPlanInvitationService;
    /** 완료된 여행은 최종본에서만 읽는다. */
    @MockitoBean
    private com.tripbora.service.travelplan.TravelPlanFinalReadService
            travelPlanFinalReadService;
    /** 완료된 여행 지우기. 마지막 한 사람이 지우면 그 여행 자체가 사라진다. */
    @MockitoBean
    private com.tripbora.service.travelplan.TravelPlanFinalDeleteService
            travelPlanFinalDeleteService;
    /** 진행 중인 방 통째로 지우기. 방장만 할 수 있다. */
    @MockitoBean
    private com.tripbora.service.travelplan.TravelPlanDeleteService
            travelPlanDeleteService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void modalTemplateRequiresLoginAndRejectsUnavailableCourses() throws Exception {
        mockMvc.perform(get("/travel-plans/course-template?sourceCourseId=9").accept("application/json"))
                .andExpect(status().isUnauthorized());
        when(travelPlanService.getCourseTemplate(9L)).thenThrow(new TravelPlanValidationException("sourceCourseId", "없는 코스"));
        mockMvc.perform(get("/travel-plans/course-template?sourceCourseId=9").with(user(member())).accept("application/json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void modalSubmissionRejectsForgedTokensAndStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/travel-plans").with(user(member()))
                        .header("X-Requested-With", "XMLHttpRequest").accept("application/json").param("sourceCourseId", "9"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest").accept("application/json")
                        .param("sourceCourseId", "9").param("creationToken", "forged"))
                .andExpect(status().isBadRequest());
        verify(travelPlanService, never()).createPlan(any(), any(), any(), any(), any(), any());
    }

    @Test
    void modalTemplateAndAjaxCreationReuseTheExistingCourseFlow() throws Exception {
        var course = new com.tripbora.dto.CourseDetailDto(); course.setTitle("가을 코스");
        var day = new com.tripbora.dto.CourseDayDto(); day.setDayNumber(2);
        course.setDays(List.of(day)); course.setStops(List.of()); course.setCountryNames(List.of("일본"));
        when(travelPlanService.getCourseTemplate(9L)).thenReturn(course);
        var session = new org.springframework.mock.web.MockHttpSession();
        var template = mockMvc.perform(get("/travel-plans/course-template?sourceCourseId=9")
                        .session(session).with(user(member())).accept("application/json"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("가을 코스"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.dayCount").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.durationLabel").value("1박 2일"))
                .andReturn();
        var token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(template.getResponse().getContentAsString()).get("creationToken").asText();
        when(travelPlanService.createPlan(7L, "새 이름", LocalDate.parse(START), LocalDate.parse(END), "나", 9L)).thenReturn(42L);
        for (int i = 0; i < 2; i++) mockMvc.perform(post("/travel-plans").session(session).with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest").accept("application/json")
                        .param("sourceCourseId", "9").param("creationToken", token).param("title", "새 이름")
                        .param("startDate", START).param("endDate", END).param("displayName", "나"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.redirectUrl").value("/travel-plans/42"));
        verify(travelPlanService).createPlan(7L, "새 이름", LocalDate.parse(START), LocalDate.parse(END), "나", 9L);
    }

    @Test
    void courseEntryPrefillsTitleAndRepeatedSubmitReturnsTheSamePlan() throws Exception {
        var course = new com.tripbora.dto.CourseDetailDto(); course.setTitle("가을 코스");
        var day = new com.tripbora.dto.CourseDayDto(); day.setDayNumber(1);
        course.setDays(List.of(day)); course.setStops(List.of());
        when(travelPlanService.getCourseTemplate(9L)).thenReturn(course);
        var session = new org.springframework.mock.web.MockHttpSession();
        var entry = mockMvc.perform(get("/travel-plans").param("sourceCourseId", "9")
                        .session(session).with(user(member())))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("가져올 여행코스")))
                .andReturn();
        var form = (com.tripbora.dto.TravelPlanCreateForm) entry.getModelAndView().getModel().get("travelPlanCreateForm");
        assertThat(form.getTitle()).isEqualTo("가을 코스");
        assertThat(form.getStartDate()).isNull(); assertThat(form.getDisplayName()).isNull();
        when(travelPlanService.createPlan(7L, "수정 제목", LocalDate.parse(START), LocalDate.parse(END), "내 이름", 9L)).thenReturn(42L);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/travel-plans").session(session).with(user(member())).with(csrf())
                            .param("sourceCourseId", "9").param("creationToken", form.getCreationToken())
                            .param("title", "수정 제목").param("startDate", START).param("endDate", END).param("displayName", "내 이름"))
                    .andExpect(redirectedUrl("/travel-plans/42"));
        }
        verify(travelPlanService).createPlan(7L, "수정 제목", LocalDate.parse(START), LocalDate.parse(END), "내 이름", 9L);
    }

    @Test
    void periodErrorKeepsTheEditedFormCoursePreviewAndRetryToken() throws Exception {
        var course = new com.tripbora.dto.CourseDetailDto(); course.setTitle("원본 코스");
        course.setDays(List.of()); course.setStops(List.of());
        when(travelPlanService.getCourseTemplate(9L)).thenReturn(course);
        var session = new org.springframework.mock.web.MockHttpSession();
        var entry = mockMvc.perform(get("/travel-plans?sourceCourseId=9").session(session).with(user(member()))).andReturn();
        var form = (com.tripbora.dto.TravelPlanCreateForm) entry.getModelAndView().getModel().get("travelPlanCreateForm");
        when(travelPlanService.createPlan(7L, "바꾼 제목", LocalDate.parse(START), LocalDate.parse(START), "이름", 9L))
                .thenThrow(new TravelPlanValidationException("endDate", "2일 이상으로 조정해 주세요."));
        var result = mockMvc.perform(post("/travel-plans").session(session).with(user(member())).with(csrf())
                        .param("sourceCourseId", "9").param("creationToken", form.getCreationToken()).param("title", "바꾼 제목")
                        .param("startDate", START).param("endDate", START).param("displayName", "이름"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("sourceCourse"))
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "endDate")).andReturn();
        var retry = (com.tripbora.dto.TravelPlanCreateForm) result.getModelAndView().getModel().get("travelPlanCreateForm");
        assertThat(retry.getTitle()).isEqualTo("바꾼 제목");
        assertThat(retry.getCreationToken()).isEqualTo(form.getCreationToken());
        assertThat(retry.getStartDate()).isEqualTo(LocalDate.parse(START));
    }

    @Test
    void invalidCourseEntryIsAnOrdinaryFormWithANotice() throws Exception {
        mockMvc.perform(get("/travel-plans").param("sourceCourseId", "not-an-id").with(user(member())))
                .andExpect(status().isOk()).andExpect(model().attributeExists("courseImportNotice"));
        verify(travelPlanService, never()).getCourseTemplate(any());
    }

    @Test
    void forgedCourseSubmissionCannotCreateAPlan() throws Exception {
        when(travelPlanService.getCourseTemplate(9L)).thenThrow(new TravelPlanValidationException("sourceCourseId", "없는 코스"));
        mockMvc.perform(post("/travel-plans").with(user(member())).with(csrf())
                        .param("sourceCourseId", "9").param("creationToken", "forged").param("title", "이름")
                        .param("startDate", START).param("endDate", END).param("displayName", "나"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "sourceCourseId"));
        verify(travelPlanService, never()).createPlan(any(), any(), any(), any(), any(), any());
    }

    @Test
    void courseEntryRequiresAuthenticationAndCachesTheCourseQuery() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(get("/travel-plans?sourceCourseId=9").session(session))
                .andExpect(status().is3xxRedirection());
        var saved = (org.springframework.security.web.savedrequest.SavedRequest) session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved.getRedirectUrl()).contains("sourceCourseId=9");
        verify(travelPlanService, never()).getCourseTemplate(any());
    }

    @Test
    void ajaxEmptyTypedCardReturnsThePersistedItemId() throws Exception {
        when(travelPlanService.addEmptyTypedItem(7L, 42L, 100L, "MOVE")).thenReturn(500L);
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "MOVE"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"itemId\":500}"));
        verify(travelPlanService).addEmptyTypedItem(7L, 42L, 100L, "MOVE");
        verify(travelPlanService, never()).addItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void ajaxEmptyTypedCardPassesTheInsertionAnchor() throws Exception {
        when(travelPlanService.addEmptyTypedItem(7L, 42L, 100L, "MOVE", 501L)).thenReturn(500L);
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "MOVE").param("beforeItemId", "501"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"itemId\":500}"));
        verify(travelPlanService).addEmptyTypedItem(7L, 42L, 100L, "MOVE", 501L);
        verify(travelPlanService, never()).addEmptyTypedItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void staleInsertionAnchorReturnsConflict() throws Exception {
        when(travelPlanService.addEmptyTypedItem(7L, 42L, 100L, "MOVE", 501L))
                .thenThrow(new com.tripbora.service.travelplan.TravelPlanConflictException());
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "MOVE").param("beforeItemId", "501"))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedInsertionAnchorCannotSilentlyAppend() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "MOVE").param("beforeItemId", "invalid"))
                .andExpect(status().isConflict());
        verify(travelPlanService, never()).addEmptyTypedItem(anyLong(), anyLong(), anyLong(), any());
        verify(travelPlanService, never()).addEmptyTypedItem(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void normalEmptyTypedCardRequestRedirectsToTheDay() throws Exception {
        when(travelPlanService.addEmptyTypedItem(7L, 42L, 100L, "STAY")).thenReturn(500L);
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .param("emptyCard", "true").param("itemType", "STAY"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
    }

    @Test
    void emptyTypedCardRequestStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member()))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "FLIGHT"))
                .andExpect(status().isForbidden());
        verify(travelPlanService, never()).addEmptyTypedItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void invalidAjaxEmptyCardReturnsTheExistingValidationResponse() throws Exception {
        when(travelPlanService.addEmptyTypedItem(7L, 42L, 100L, "NOTE"))
                .thenThrow(new TravelPlanValidationException("content", "Unsupported card type"));
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("emptyCard", "true").param("itemType", "NOTE"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Unsupported card type"));
    }

    @Test
    void legacyCreateUrlRedirectsToTheInlineListForm() throws Exception {
        mockMvc.perform(get("/travel-plans/new").with(user(member())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans#travel-plan-create"));
    }

    @Test
    void anonymousAccessIsSentToLogin() throws Exception {
        // 별도 인가 규칙 없이 기존 anyRequest().authenticated() 정책을 그대로 탄다
        mockMvc.perform(get("/travel-plans/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans/new"));
    }

    @Test
    void aValidSubmissionCreatesOnePlanWithoutACreationSuccessBanner() throws Exception {
        when(travelPlanService.createPlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(42L);

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().is3xxRedirection())
                // 상세 화면이 생겼으므로 생성된 방으로 바로 보낸다
                .andExpect(redirectedUrl("/travel-plans/42"))
                .andExpect(result -> assertThat(result.getFlashMap()
                        .containsKey("travelPlanMessage")).isFalse());

        // 로그인 사용자 id 와 폼 값이 그대로 전달된다
        verify(travelPlanService).createPlan(
                eq(7L), eq("제주 여행"),
                eq(LocalDate.parse(START)), eq(LocalDate.parse(END)),
                eq("민준"));
    }

    @Test
    void aTitleValidationFailureIsShownOnTheTitleField() throws Exception {
        doThrow(new TravelPlanValidationException("title", "여행계획 이름을 입력해 주세요."))
                .when(travelPlanService).createPlan(anyLong(), any(), any(), any(), any());

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "   ")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "title"))
                // 사용자가 입력한 나머지 값은 그대로 남는다
                .andExpect(model().attribute("travelPlanCreateForm",
                        org.hamcrest.Matchers.hasProperty("displayName",
                                org.hamcrest.Matchers.equalTo("민준"))))
                .andExpect(model().attribute("travelPlanCreateForm",
                        org.hamcrest.Matchers.hasProperty("startDate",
                                org.hamcrest.Matchers.equalTo(LocalDate.parse(START)))));
    }

    @Test
    void aYearLongerThanFourDigitsNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", "12026-09-01")
                        .param("endDate", "12026-09-03")
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attributeHasFieldErrors(
                        "travelPlanCreateForm", "startDate", "endDate"));

        verify(travelPlanService, never()).createPlan(anyLong(), any(), any(), any(), any());
    }

    @Test
    void anUnreadableDateIsExplainedInThisScreensOwnWords() throws Exception {
        // 기본 변환 오류 문구가 화면에 그대로 나가지 않는다
        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", "12026-09-01")
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(
                                "여행 시작일을 올바른 날짜로 선택해 주세요."),
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Failed to convert")))));
    }

    @Test
    void anOrdinaryFourDigitYearGoesStraightThrough() throws Exception {
        when(travelPlanService.createPlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(42L);

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42"));

        verify(travelPlanService).createPlan(
                7L, "제주 여행", LocalDate.parse(START), LocalDate.parse(END), "민준");
    }

    @Test
    void aYearOutOfRangeIsShownOnTheDateFieldLikeAnyOtherRule() throws Exception {
        // Service 가 막은 것도 같은 자리에 같은 모양으로 나온다
        doThrow(new TravelPlanValidationException(
                "startDate", "여행 시작일의 연도는 4자리(1000 ~ 9999)로 입력해 주세요."))
                .when(travelPlanService).createPlan(anyLong(), any(), any(), any(), any());

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "startDate"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("연도는 4자리")));
    }

    @Test
    void aDisplayNameValidationFailureIsShownOnItsOwnField() throws Exception {
        doThrow(new TravelPlanValidationException(
                "displayName", "이 방에서 사용할 표시 이름을 입력해 주세요."))
                .when(travelPlanService).createPlan(anyLong(), any(), any(), any(), any());

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "   "))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "displayName"));
    }

    @Test
    void periodValidationFailuresAreShownOnTheEndDateField() throws Exception {
        doThrow(new TravelPlanValidationException(
                "endDate", "여행 기간은 최대 90일까지 설정할 수 있습니다."))
                .when(travelPlanService).createPlan(anyLong(), any(), any(), any(), any());

        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "너무 긴 여행")
                        .param("startDate", START)
                        .param("endDate", "2027-09-01")
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "endDate"));
    }

    @Test
    void aBrokenDateNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/travel-plans")
                        .with(user(member())).with(csrf())
                        .param("title", "제주 여행")
                        .param("startDate", "날짜아님")
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attributeHasFieldErrors("travelPlanCreateForm", "startDate"));

        verify(travelPlanService, never()).createPlan(anyLong(), any(), any(), any(), any());
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/travel-plans")
                        .with(user(member()))
                        .param("title", "제주 여행")
                        .param("startDate", START)
                        .param("endDate", END)
                        .param("displayName", "민준"))
                .andExpect(status().isForbidden());

        verify(travelPlanService, never()).createPlan(anyLong(), any(), any(), any(), any());
    }

    @Test
    void theListShowsOnlyTheCurrentUsersRooms() throws Exception {
        TravelPlanListItemDto row = new TravelPlanListItemDto();
        row.setTravelPlanId(42L);
        row.setTitle("제주 여행");
        row.setStartDate(LocalDate.parse(START));
        row.setEndDate(LocalDate.parse(END));
        row.setRole(TravelPlanRole.OWNER);
        row.setMemberCount(1);
        // 최근 활동까지 그대로 그려진다
        row.setLastActivityAt(java.sql.Timestamp.valueOf("2026-09-01 10:00:00"));
        when(travelPlanService.getActivePlans(7L)).thenReturn(List.of(row));

        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/list"))
                .andExpect(model().attribute("travelPlans", List.of(row)));

        verify(travelPlanService).getActivePlans(7L);
    }

    @Test
    void anonymousListAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans"));
    }

    @Test
    void theDetailPageAsksTheServiceWithTheCurrentUser() throws Exception {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("제주 여행");
        plan.setStartDate(LocalDate.parse(START));
        plan.setEndDate(LocalDate.parse(END));
        TravelPlanDetailDto detail = new TravelPlanDetailDto(
                plan, new TravelPlanMember(), List.of(), Map.of(), Map.of(),
                List.of(), List.of(), 8);
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(detail);

        mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/detail"))
                .andExpect(model().attribute("travelPlan", detail));

        // planId 만으로 조회하지 않고 항상 로그인 사용자와 함께 넘긴다
        verify(travelPlanService).getActivePlanDetail(7L, 42L);
    }

    @Test
    void theHeaderShowsHowLongTheTripIsBesideItsTitle() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetailWithDays(3));

        mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("제주 여행"),
                        org.hamcrest.Matchers.containsString(
                                "<span class=\"travel-plan-detail-length\">2박 3일</span>"))));
    }

    @Test
    void aOneDayTripIsNotCalledZeroNights() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetailWithDays(1));

        mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<span class=\"travel-plan-detail-length\">당일</span>")));
    }

    @Test
    void aMemberSeesTheCommonActionsButNoManagementArea() throws Exception {
        // planDetail 의 currentMember 는 역할이 없어 방장이 아니다
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());

        mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        // 참여자 / 채팅은 누구나 쓴다
                        org.hamcrest.Matchers.containsString("data-travel-plan-members-toggle"),
                        org.hamcrest.Matchers.containsString("data-travel-plan-chat-toggle"),
                        // 방장 자리는 남아 있지만 안이 비어 있다
                        org.hamcrest.Matchers.containsString("data-travel-plan-owner-actions"),
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "travel-plan-finalize-toggle")),
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "data-travel-plan-invite-toggle")))));
    }

    @Test
    void theOwnersActionsAreDrawnAheadOfTheCommonOnes() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(ownerPlanDetail());

        String body = mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // [확정][초대] | [참여자][채팅]
        int finalize = body.indexOf("data-travel-plan-finalize-open");
        int invite = body.indexOf("data-travel-plan-invite-toggle");
        int divider = body.indexOf("travel-plan-action-divider");
        int members = body.indexOf("data-travel-plan-members-toggle");
        int chat = body.indexOf("data-travel-plan-chat-toggle");
        assertThat(finalize).isGreaterThan(0);
        assertThat(invite).isGreaterThan(finalize);
        assertThat(divider).isGreaterThan(invite);
        assertThat(members).isGreaterThan(divider);
        assertThat(chat).isGreaterThan(members);
    }

    @Test
    void thePlannerOnlyLearnsWhetherAnInviteLinkIsOnNotItsUrl() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());
        when(travelPlanInvitationService.hasActiveInvitation(7L, 42L)).thenReturn(true);

        mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlanInviteActive", true))
                // raw token 은 DB 에서 되살릴 수 없으므로 링크 문자열은 실리지 않는다
                .andExpect(model().attributeDoesNotExist("travelPlanInviteUrl"));

        verify(travelPlanInvitationService).hasActiveInvitation(7L, 42L);
    }

    @Test
    void theDayFragmentGivesBackJustThatDayForAnActiveMember() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());

        mockMvc.perform(get("/travel-plans/42/days/100/fragment").with(user(member())))
                .andExpect(status().isOk())
                // 처음 그릴 때와 같은 fragment 를 쓴다
                .andExpect(view().name("travelplan/fragments/schedule-day :: scheduleDay("
                        + "plan=${plan}, days=${days}, day=${day}, dayItems=${dayItems},"
                        + " alternativesByItemId=${alternativesByItemId}, dayOpen=${dayOpen})"))
                .andExpect(model().attributeExists("plan", "days", "day", "alternativesByItemId"))
                // 실시간 갱신에서는 입력칸을 열어 두지 않는다
                .andExpect(model().attribute("dayOpen", false));

        // 접근 권한은 상세 화면과 똑같이 Service 가 확인한다
        verify(travelPlanService).getActivePlanDetail(7L, 42L);
    }

    @Test
    void aDayFragmentFromAnotherRoomComesBackAsNotFound() throws Exception {
        // 그 방의 DAY 목록에 없는 dayId 는 통과하지 못한다
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());

        mockMvc.perform(get("/travel-plans/42/days/999/fragment").with(user(member())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDayFragmentOfARoomTheUserCannotSeeComesBackAsNotFound() throws Exception {
        // 비참여자 / LEFT / REMOVED 는 상세 조회에서 이미 막힌다
        when(travelPlanService.getActivePlanDetail(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));

        mockMvc.perform(get("/travel-plans/42/days/100/fragment").with(user(member())))
                .andExpect(status().isNotFound());
    }

    @Test
    void theFragmentEndpointsAreNotPublic() throws Exception {
        mockMvc.perform(get("/travel-plans/42/days/100/fragment"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/travel-plans/42/schedule/fragment"))
                .andExpect(status().is3xxRedirection());

        verify(travelPlanService, never()).getActivePlanDetail(anyLong(), anyLong());
    }

    @Test
    void theWholeScheduleFragmentIsOneRequestForReconnects() throws Exception {
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());

        mockMvc.perform(get("/travel-plans/42/schedule/fragment").with(user(member())))
                .andExpect(status().isOk())
                // DAY 수만큼 요청이 나가지 않도록 통째로 준다
                .andExpect(view().name("travelplan/fragments/schedule-day :: scheduleDays("
                        + "plan=${plan}, days=${days}, itemsByDayId=${itemsByDayId},"
                        + " alternativesByItemId=${alternativesByItemId})"))
                .andExpect(model().attributeExists("plan", "days", "itemsByDayId"));

        verify(travelPlanService).getActivePlanDetail(7L, 42L);
    }

    @Test
    void theOldEditingUrlLeadsStraightToTheFinishedTrip() throws Exception {
        /*
          완료된 여행에 함께했던 사람이라면 볼 것이 있다.
          목록에서 다시 찾게 하지 않고 최종본으로 바로 보낸다.
        */
        when(travelPlanService.getActivePlanDetail(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));
        when(travelPlanService.explainInaccessiblePlan(anyLong(), anyLong()))
                .thenReturn(TravelPlanAccessNotice.COMPLETED_PARTICIPANT);

        mockMvc.perform(get("/travel-plans/999").with(user(member())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/999/final"));
    }

    @Test
    void someoneWhoLeftBeforeTheEndOnlyHearsThatItIsOver() throws Exception {
        when(travelPlanService.getActivePlanDetail(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));
        when(travelPlanService.explainInaccessiblePlan(anyLong(), anyLong()))
                .thenReturn(TravelPlanAccessNotice.COMPLETED_PAST);

        mockMvc.perform(get("/travel-plans/999").with(user(member())))
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice", "이미 종료된 여행 계획입니다."));
    }

    @Test
    void aStrangerIsNotToldWhetherTheRoomEvenExists() throws Exception {
        when(travelPlanService.getActivePlanDetail(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));
        when(travelPlanService.explainInaccessiblePlan(anyLong(), anyLong()))
                .thenReturn(TravelPlanAccessNotice.NO_ACCESS);

        mockMvc.perform(get("/travel-plans/999").with(user(member())))
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice", "접근할 수 없는 여행 계획입니다."));
    }

    @Test
    void anonymousDetailAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans/42"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans/42"));
    }

    @Test
    void theDayPageAsksTheServiceWithTheCurrentUserAndBothIds() throws Exception {
        when(travelPlanService.getActiveDayDetail(7L, 42L, 100L)).thenReturn(dayDetail());

        mockMvc.perform(get("/travel-plans/42/days/100").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/day-detail"))
                .andExpect(model().attributeExists("travelPlanDay"))
                .andExpect(model().attributeExists("travelPlanItemCreateForm"));

        verify(travelPlanService).getActiveDayDetail(7L, 42L, 100L);
    }

    @Test
    void aDayTheUserCannotOpenSendsThemBackTheSameWay() throws Exception {
        when(travelPlanService.getActiveDayDetail(anyLong(), anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));
        when(travelPlanService.explainInaccessiblePlan(anyLong(), anyLong()))
                .thenReturn(TravelPlanAccessNotice.COMPLETED_PAST);

        mockMvc.perform(get("/travel-plans/42/days/999").with(user(member())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice", "이미 종료된 여행 계획입니다."));
    }

    // ── 완료된 여행 ─────────────────────────────────────────

    @Test
    void theFinishedTripIsShownReadOnly() throws Exception {
        when(travelPlanFinalReadService.getCompletedPlanDetail(7L, 42L))
                .thenReturn(finalDetail());

        mockMvc.perform(get("/travel-plans/42/final").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(view().name("travelplan/detail"))
                .andExpect(model().attributeExists("travelPlan"))
                .andExpect(model().attribute("planReadonly", true));
    }

    @Test
    void theFinishedTripReadsLikeTheSamePaperItWasWrittenOn() throws Exception {
        when(travelPlanFinalReadService.getCompletedPlanDetail(7L, 42L))
                .thenReturn(finalDetailWithMembers("민준", "소희", "초코"));

        String body = mockMvc.perform(get("/travel-plans/42/final").with(user(member())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 작성 화면과 같은 종이·머리글·DAY 머리글을 그대로 쓴다
        assertThat(body)
                .contains("travel-plan-paper")
                .contains("travel-plan-detail-heading")
                .contains("travel-plan-day-heading")
                .contains("tp-detail-day-tile")
                // 읽기 전용이라는 것은 작은 표시 하나로만 알린다
                .contains("확정된 여행계획")
                .contains("is-planning")
                .contains("data-planner-calendar")
                .contains("data-dashboard-readonly")
                // 함께한 사람은 큰 상자가 아니라 한 줄이다
                .contains("함께한 친구", "민준", "소희", "초코");
        // 고칠 수 있는 것은 하나도 없다
        assertThat(body)
                .doesNotContain("data-travel-plan-slot")
                .doesNotContain("data-travel-plan-item-form")
                .doesNotContain("data-travel-plan-chat")
                .doesNotContain("data-travel-plan-poll-modal");
    }

    @Test
    void someoneWhoWasNotOnTheTripIsSentAwayWithoutDetail() throws Exception {
        when(travelPlanFinalReadService.getCompletedPlanDetail(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "완료된 여행을 찾을 수 없습니다."));

        mockMvc.perform(get("/travel-plans/42/final").with(user(member())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice", "접근할 수 없는 여행 계획입니다."));
    }

    @Test
    void completedPlannerRendersEveryTypeWithoutActiveRoomScriptsOrMutationControls() throws Exception {
        var base = finalDetailWithMembers("민준", "소희");
        base.getDays().get(0).setTitle("확정된 첫날 제목");
        var items = new java.util.ArrayList<com.tripbora.model.TravelPlanFinalItem>();
        for (var type : com.tripbora.model.TravelPlanItemType.values()) {
            var item = new com.tripbora.model.TravelPlanFinalItem();
            item.setId(920L + items.size());
            item.setFinalDayId(910L);
            item.setDisplayOrder(items.size() + 1);
            item.setTag(type.name());
            item.setContent(type.getLabel() + " 저장된 제목\n예약 확인 메모");
            item.setStartTime(java.time.LocalTime.of(9, 30));
            var details = new com.tripbora.model.TravelPlanItemDetails();
            details.setDeparturePlace("인천"); details.setArrivalPlace("오사카");
            details.setStayName("오사카 호텔"); details.setFlightNumber("KE721");
            details.setDepartureDate("2026-09-13"); details.setDepartureTime("09:30");
            details.setBookingMemo("예약 확인 정보");
            item.setDetails(details);
            items.add(item);
        }
        var alternative = new com.tripbora.model.TravelPlanFinalItemAlternative();
        alternative.setId(950L); alternative.setFinalItemId(920L);
        alternative.setAlternativeOrder(1); alternative.setContent("비가 오면 실내 관광");
        var finalPlan = new com.tripbora.dto.TravelPlanFinalDetailDto(base.getSnapshot(), base.getMembers(),
                base.getDays(), Map.of(910L, items), Map.of(920L, List.of(alternative)));
        when(travelPlanFinalReadService.getCompletedPlanDetail(7L, 42L)).thenReturn(finalPlan);
        String html = mockMvc.perform(get("/travel-plans/42/final").with(user(member())))
                .andExpect(status().isOk()).andExpect(view().name("travelplan/detail"))
                .andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        assertThat(doc.select("[data-travel-plan-item]")).hasSize(items.size());
        assertThat(doc.select("[data-item-readonly-fields]")).hasSize(items.size());
        assertThat(doc.select("[data-plan-readonly][data-dashboard-readonly]")).hasSize(1);
        assertThat(doc.select("[data-day-title-edit], [data-item-edit-button], [data-item-drag-handle],"
                + "[data-overview-insert-bar], [data-travel-plan-slot], [data-travel-plan-item-form],"
                + "[data-travel-plan-alt-form], .travel-plan-alt-remove-form, [data-item-editor-delete]")).isEmpty();
        assertThat(doc.select("script[src^='/js/travel-plan-realtime'], script[src^='/js/travel-plan-scheduler'],"
                + "script[src^='/js/travel-plan-finalize'], script[src^='/js/travel-plan-item-autosave']")).isEmpty();
        assertThat(doc.select("script[src^='/js/travel-plan-readonly-'][src$='.js']")).hasSize(1);
        assertThat(doc.select(".tp-detail-day-title").text()).isEqualTo("확정된 첫날 제목");
        assertThat(doc.select(".travel-plan-alt-content").text()).isEqualTo("비가 오면 실내 관광");
        assertThat(doc.select("form[action='/travel-plans/42/final/delete']")).hasSize(1);
        // 브라우저 회귀 테스트가 실제 Thymeleaf 결과를 사용한다. DB나 DEV 서버는 필요 없다.
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/browser-fixtures"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/browser-fixtures/travel-plan-final.html"), html);
    }

    @Test
    void anonymousFinalAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans/42/final"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans/42/final"));
    }

    // ── 완료된 여행 개인 삭제 ────────────────────────────────

    @Test
    void clearingAFinishedTripSendsMeBackToTheList() throws Exception {
        mockMvc.perform(post("/travel-plans/42/final/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice",
                        "완료된 여행을 내 목록에서 삭제했습니다."));

        // 지우는 것은 언제나 부른 사람 자신의 것이다
        verify(travelPlanFinalDeleteService).deleteForMe(7L, 42L);
    }

    @Test
    void theScreenSaysTheSameThingWhetherOrNotItWasTheLastCopy() throws Exception {
        // 마지막 한 사람이어서 여행 자체가 사라져도 하는 말은 같다.
        // 남이 아직 보관 중인지는 알릴 일이 아니다
        when(travelPlanFinalDeleteService.deleteForMe(7L, 42L)).thenReturn(true);

        mockMvc.perform(post("/travel-plans/42/final/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanNotice",
                        "완료된 여행을 내 목록에서 삭제했습니다."));
    }

    @Test
    void clearingSomeoneElsesFinishedTripIsRefusedWithoutRevealingIt() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "완료된 여행을 찾을 수 없습니다."))
                .when(travelPlanFinalDeleteService).deleteForMe(anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/final/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans"))
                // 그 최종본이 있는지조차 알리지 않는다
                .andExpect(flash().attribute("travelPlanNotice", "접근할 수 없는 여행 계획입니다."));
    }

    @Test
    void clearingIsNeverDoneByFollowingALink() throws Exception {
        // GET 으로는 지워지지 않는다 (주소를 눌러 보거나 미리 읽는 것만으로 사라지면 안 된다)
        mockMvc.perform(get("/travel-plans/42/final/delete").with(user(member())))
                .andExpect(status().isMethodNotAllowed());
        verify(travelPlanFinalDeleteService, never()).deleteForMe(anyLong(), anyLong());
    }

    @Test
    void clearingWithoutATokenIsRefused() throws Exception {
        mockMvc.perform(post("/travel-plans/42/final/delete").with(user(member())))
                .andExpect(status().isForbidden());
        verify(travelPlanFinalDeleteService, never()).deleteForMe(anyLong(), anyLong());
    }

    @Test
    void anonymousClearingIsSentToLogin() throws Exception {
        mockMvc.perform(post("/travel-plans/42/final/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(travelPlanFinalDeleteService, never()).deleteForMe(anyLong(), anyLong());
    }

    // ── 진행 중인 방 통째로 지우기 ──────────────────────────

    @Test
    void closingTheRoomSendsTheOwnerBackToTheList() throws Exception {
        mockMvc.perform(post("/travel-plans/42/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanMessage", "여행 계획을 삭제했습니다."));

        // 지울 수 있는지는 Service 가 본다. 화면은 부탁만 한다
        verify(travelPlanDeleteService).deletePlan(7L, 42L);
    }

    @Test
    void closingSomeoneElsesRoomIsRefusedWithoutRevealingIt() throws Exception {
        // 방장이 아닌 사람의 직접 요청. 그 방이 있는지조차 알리지 않는다
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."))
                .when(travelPlanDeleteService).deletePlan(anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRoomIsNeverClosedByFollowingALink() throws Exception {
        // GET 으로는 지워지지 않는다
        mockMvc.perform(get("/travel-plans/42/delete").with(user(member())))
                .andExpect(status().isMethodNotAllowed());
        verify(travelPlanDeleteService, never()).deletePlan(anyLong(), anyLong());
    }

    @Test
    void closingARoomWithoutATokenIsRefused() throws Exception {
        mockMvc.perform(post("/travel-plans/42/delete").with(user(member())))
                .andExpect(status().isForbidden());
        verify(travelPlanDeleteService, never()).deletePlan(anyLong(), anyLong());
    }

    @Test
    void anonymousRoomClosingIsSentToLogin() throws Exception {
        mockMvc.perform(post("/travel-plans/42/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(travelPlanDeleteService, never()).deletePlan(anyLong(), anyLong());
    }

    @Test
    void theListShowsFinishedTripsAlongsideTheRunningOnes() throws Exception {
        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("travelPlans"))
                .andExpect(model().attributeExists("completedTravelPlans"));
    }

    @Test
    void theListAsksForTheFinishedTripsOfWhoeverIsLookingAtIt() throws Exception {
        // 로그인한 사람의 계정으로 찾는다. 이 번호가 최종 명단의 user_id 와 맞아야 목록이 나온다
        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk());

        verify(travelPlanFinalReadService).getCompletedPlans(7L, 0, 8);
    }

    // ── 한 화면에 놓인 두 구역 ──────────────────────────────

    @Test
    void bothListsUseTheSameTabbedDashboard() throws Exception {
        TravelPlanListItemDto running = new TravelPlanListItemDto();
        running.setTravelPlanId(42L);
        running.setTitle("제주 여행");
        running.setStartDate(LocalDate.parse(START));
        running.setEndDate(LocalDate.parse(END));
        running.setRole(TravelPlanRole.OWNER);
        running.setMemberCount(2);
        com.tripbora.dto.TravelPlanFinalListItemDto finished =
                new com.tripbora.dto.TravelPlanFinalListItemDto();
        finished.setTravelPlanId(21L);
        finished.setSnapshotId(900L);
        finished.setTitle("부산 여행");
        finished.setStartDate(LocalDate.parse(START));
        finished.setEndDate(LocalDate.parse(END));
        when(travelPlanService.getActivePlans(7L)).thenReturn(List.of(running));
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(1);
        when(travelPlanFinalReadService.getCompletedPlans(7L, 0, 8)).thenReturn(List.of(finished));

        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlans", List.of(running)))
                .andExpect(model().attribute("completedTravelPlans", List.of(finished)))
                // 둘 다 한 화면에 그려지고, 탭으로 가려지지 않는다
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("제주 여행"),
                        org.hamcrest.Matchers.containsString("부산 여행"),
                        org.hamcrest.Matchers.containsString("data-plan-filter=\"active\""),
                        org.hamcrest.Matchers.containsString("data-plan-filter=\"completed\""))));
    }

    @Test
    void eachSectionCarriesItsOwnTotal() throws Exception {
        TravelPlanListItemDto running = new TravelPlanListItemDto();
        running.setTravelPlanId(42L);
        running.setStartDate(LocalDate.parse(START));
        running.setEndDate(LocalDate.parse(END));
        when(travelPlanService.getActivePlans(7L)).thenReturn(List.of(running));
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(12);

        // 진행 중은 전부 보여 주므로 화면에 그려진 수가 곧 전체 수다
        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlanPage", listPage(1, 12, 1, 2)));
    }

    @Test
    void theRunningSectionIsNeverCutIntoPages() throws Exception {
        // 진행 중이 몇 건이든 한 번에 다 보여 준다
        java.util.List<TravelPlanListItemDto> many = new java.util.ArrayList<>();
        for (int index = 0; index < 12; index++) {
            TravelPlanListItemDto row = new TravelPlanListItemDto();
            row.setTravelPlanId((long) index);
            row.setStartDate(LocalDate.parse(START));
            row.setEndDate(LocalDate.parse(END));
            many.add(row);
        }
        when(travelPlanService.getActivePlans(7L)).thenReturn(many);

        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlans", org.hamcrest.Matchers.hasSize(12)))
                // 쪽수는 완료된 여행만 보고 정해진다
                .andExpect(model().attribute("travelPlanPage", listPage(12, 0, 1, 1)));
    }

    // ── 완료된 여행 쪽 나누기 ───────────────────────────────

    @Test
    void onlyTheFinishedListIsPaged() throws Exception {
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(20);

        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlanPage", listPage(0, 20, 1, 3)));

        verify(travelPlanFinalReadService).getCompletedPlans(7L, 0, 8);
    }

    @Test
    void aSecondPageSkipsTheFirstPageWorthOfFinishedTrips() throws Exception {
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(20);

        mockMvc.perform(get("/travel-plans?completedPage=2").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("travelPlanPage", listPage(0, 20, 2, 3)))
                // 쪽 이동 주소에는 완료된 여행의 쪽 번호만 붙는다
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/travel-plans?completedPage=3#tp-trips-panel")));

        // 전부 읽어 와서 자르지 않는다. 건너뛸 만큼을 DB 에 알려 준다
        verify(travelPlanFinalReadService).getCompletedPlans(7L, 8, 8);
        // 진행 중 목록은 쪽과 상관없이 그대로다
        verify(travelPlanService).getActivePlans(7L);
    }

    @Test
    void aBrokenCompletedPageComesBackAsASafeOne() throws Exception {
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(9);

        // 숫자가 아니거나 0 이하이면 첫 쪽으로 본다
        for (String page : new String[]{"abc", "0", "-3", ""}) {
            mockMvc.perform(get("/travel-plans?completedPage=" + page).with(user(member())))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("travelPlanPage", listPage(0, 9, 1, 2)));
        }
        // 있지도 않은 쪽을 달라고 하면 마지막 쪽을 준다
        mockMvc.perform(get("/travel-plans?completedPage=99").with(user(member())))
                .andExpect(model().attribute("travelPlanPage", listPage(0, 9, 2, 2)));
    }

    @Test
    void theOldTabAddressStillOpensTheList() throws Exception {
        // 탭이 있던 시절의 주소가 들어와도 오류 없이 그냥 목록이 나온다
        for (String query : new String[]{"?status=completed", "?status=active&page=2"}) {
            mockMvc.perform(get("/travel-plans" + query).with(user(member())))
                    .andExpect(status().isOk())
                    .andExpect(view().name("travelplan/list"))
                    .andExpect(model().attribute("travelPlanPage", listPage(0, 0, 1, 1)));
        }
    }

    @Test
    void anEmptyFinishedListStillHasOnePage() throws Exception {
        // 쪽이 하나뿐이면 화면이 쪽 이동을 감춘다
        mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(model().attribute("travelPlanPage", listPage(0, 0, 1, 1)));
    }

    /** 목록 상태. 완료된 여행의 쪽 크기는 화면과 같은 8건이다. */
    private TravelPlanListPageDto listPage(int activeCount, int completedCount,
                                           int completedPage, int completedTotalPages) {
        return new TravelPlanListPageDto(
                activeCount, completedCount, completedPage, completedTotalPages, 8);
    }

    private com.tripbora.dto.TravelPlanFinalDetailDto finalDetail() {
        com.tripbora.model.TravelPlanFinalSnapshot snapshot =
                new com.tripbora.model.TravelPlanFinalSnapshot();
        snapshot.setId(900L);
        snapshot.setTravelPlanId(42L);
        snapshot.setTitle("제주도 여행");
        snapshot.setStartDate(java.time.LocalDate.of(2026, 9, 13));
        snapshot.setEndDate(java.time.LocalDate.of(2026, 9, 15));
        return new com.tripbora.dto.TravelPlanFinalDetailDto(
                snapshot, java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), java.util.Map.of());
    }

    @Test
    void listMenusFollowActiveAndSnapshotRolesWithoutNestedControls() throws Exception {
        var owner = new TravelPlanListItemDto(); owner.setTravelPlanId(42L); owner.setTitle("오사카 여행"); owner.setRole(TravelPlanRole.OWNER);
        owner.setStartDate(LocalDate.parse(START)); owner.setEndDate(LocalDate.parse(END)); owner.setMemberCount(3);
        var participant = new TravelPlanListItemDto(); participant.setTravelPlanId(43L); participant.setTitle("아주 긴 여행 제목으로 함께 떠나는 가을 여행 ".repeat(3)); participant.setRole(TravelPlanRole.MEMBER);
        participant.setStartDate(LocalDate.parse(START)); participant.setEndDate(LocalDate.parse(END)); participant.setMemberCount(2);
        var completed = new com.tripbora.dto.TravelPlanFinalListItemDto(); completed.setTravelPlanId(44L); completed.setTitle("완료된 여행"); completed.setRole(TravelPlanRole.MEMBER);
        completed.setStartDate(LocalDate.parse(START)); completed.setEndDate(LocalDate.parse(END)); completed.setMemberCount(2);
        when(travelPlanService.getActivePlans(7L)).thenReturn(List.of(owner, participant));
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(1);
        when(travelPlanFinalReadService.getCompletedPlans(7L, 0, 8)).thenReturn(List.of(completed));
        var html = mockMvc.perform(get("/travel-plans").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        assertThat(doc.select(".tp-card-menu form").eachAttr("action")).containsExactly(
                "/travel-plans/42/delete", "/travel-plans/43/members/leave", "/travel-plans/44/final/delete");
        assertThat(doc.select(".tp-card-menu button").eachText()).containsExactly("여행 삭제", "여행에서 나가기", "내 목록에서 삭제");
        assertThat(doc.select(".travel-plan-card-link button, .travel-plan-card-link form")).isEmpty();
        assertThat(doc.select(".is-completed .travel-plan-card-role").text()).isEqualTo("참여자");
        assertThat(doc.select("[data-plan-filter]").eachText()).containsExactly("진행 중 2", "완료 1", "전체 3");
        assertThat(doc.select("[data-plan-grid]")).hasSize(1);
        assertThat(doc.select("[data-plan-status=completed][hidden]")).hasSize(1);
        var dir = java.nio.file.Path.of("build/reports/travel-plan-management-ui");
        java.nio.file.Files.createDirectories(dir); java.nio.file.Files.writeString(dir.resolve("list-fixture.html"), html);
        var completedPage = new java.util.ArrayList<com.tripbora.dto.TravelPlanFinalListItemDto>();
        for (int index = 0; index < 9; index++) {
            var row = new com.tripbora.dto.TravelPlanFinalListItemDto();
            org.springframework.beans.BeanUtils.copyProperties(completed, row);
            row.setTravelPlanId(44L + index); row.setTitle("완료 여행 " + (index + 1)); completedPage.add(row);
        }
        when(travelPlanFinalReadService.countCompletedPlans(7L)).thenReturn(9);
        when(travelPlanFinalReadService.getCompletedPlans(7L, 0, 8)).thenReturn(completedPage.subList(0, 8));
        when(travelPlanFinalReadService.getCompletedPlans(7L, 8, 8)).thenReturn(completedPage.subList(8, 9));
        for (int page = 1; page <= 2; page++) {
            var pagedHtml = mockMvc.perform(get("/travel-plans?completedPage=" + page).with(user(member())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            java.nio.file.Files.writeString(dir.resolve("paged-" + page + "-fixture.html"), pagedHtml);
        }
    }

    @Test
    void finishedToolbarUsesSnapshotRolesAndReadOnlyFriends() throws Exception {
        var completed = finalDetailWithMembers("방장", "이름이아주긴참여자이름이아주긴참여자");
        when(travelPlanFinalReadService.getCompletedPlanDetail(7L, 42L)).thenReturn(completed);
        when(travelPlanFinalReadService.getCompletedPlanDetail(8L, 42L)).thenReturn(completed);
        var ownerHtml = mockMvc.perform(get("/travel-plans/42/final").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var ownerDoc = org.jsoup.Jsoup.parse(ownerHtml);
        assertThat(ownerDoc.select(".travel-plan-back-link")).isEmpty();
        assertThat(ownerDoc.select(".tp-final-friends summary").text()).contains("함께한 친구", "2명");
        assertThat(ownerDoc.select(".tp-final-friends-panel li")).hasSize(2);
        assertThat(ownerDoc.select("[data-plan-manage-open]").text()).isEqualTo("여행 삭제");
        var memberHtml = mockMvc.perform(get("/travel-plans/42/final").with(user(plainMember())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(memberHtml).select("[data-plan-manage-open]")).isEmpty();
        var dir = java.nio.file.Path.of("build/reports/travel-plan-management-ui");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("final-fixture.html"), ownerHtml);
    }

    /** 함께한 사람과 하루치 일정이 들어 있는 최종본. 화면이 실제로 그려지는지 본다. */
    private com.tripbora.dto.TravelPlanFinalDetailDto finalDetailWithMembers(
            String... displayNames) {
        com.tripbora.model.TravelPlanFinalSnapshot snapshot =
                new com.tripbora.model.TravelPlanFinalSnapshot();
        snapshot.setId(900L);
        snapshot.setTravelPlanId(42L);
        snapshot.setTitle("제주도 여행");
        snapshot.setStartDate(java.time.LocalDate.of(2026, 9, 13));
        snapshot.setEndDate(java.time.LocalDate.of(2026, 9, 15));

        java.util.List<com.tripbora.model.TravelPlanFinalMember> members =
                new java.util.ArrayList<>();
        for (String displayName : displayNames) {
            com.tripbora.model.TravelPlanFinalMember member =
                    new com.tripbora.model.TravelPlanFinalMember();
            member.setDisplayName(displayName);
            member.setUserId(7L + members.size());
            member.setRole(members.isEmpty() ? TravelPlanRole.OWNER : TravelPlanRole.MEMBER);
            members.add(member);
        }

        com.tripbora.model.TravelPlanFinalDay day =
                new com.tripbora.model.TravelPlanFinalDay();
        day.setId(910L);
        day.setDayNumber(1);
        day.setPlanDate(java.time.LocalDate.of(2026, 9, 13));

        return new com.tripbora.dto.TravelPlanFinalDetailDto(
                snapshot, members, java.util.List.of(day),
                java.util.Map.of(), java.util.Map.of());
    }

    @Test
    void anonymousDayAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans/42/days/100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans/42/days/100"));
    }

    @Test
    void addingAnItemComesBackToThatDayOnTheMainPlanner() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items")
                        .with(user(member())).with(csrf())
                        .param("content", "오전 10시 경복궁 도착\n한복 빌리고 천천히 둘러보기"))
                .andExpect(status().is3xxRedirection())
                // DAY 상세가 아니라 메인 편집 화면의 그 DAY 자리로 돌아온다
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).addItem(
                7L, 42L, 100L, "오전 10시 경복궁 도착\n한복 빌리고 천천히 둘러보기");
    }

    @Test
    void aBlankItemRedisplaysThePlannerWithThatDaysEditorOpen() throws Exception {
        doThrow(new TravelPlanValidationException("content", "일정 내용을 입력해 주세요."))
                .when(travelPlanService).addItem(anyLong(), anyLong(), anyLong(), any());
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(planDetail());

        mockMvc.perform(post("/travel-plans/42/days/100/items")
                        .with(user(member())).with(csrf())
                        .param("content", "   "))
                .andExpect(status().isOk())
                // 오류 페이지가 아니라 같은 편집 화면을 다시 그린다
                .andExpect(view().name("travelplan/detail"))
                .andExpect(model().attributeHasFieldErrors("travelPlanItemCreateForm", "content"))
                // 작성 중이던 내용이 남고, 어느 DAY 입력칸을 열지 알려 준다
                .andExpect(model().attribute("travelPlanItemCreateForm",
                        org.hamcrest.Matchers.hasProperty("content",
                                org.hamcrest.Matchers.equalTo("   "))))
                .andExpect(model().attribute("openDayId", 100L))
                .andExpect(model().attributeExists("travelPlan"));
    }

    @Test
    void addingAnItemWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items")
                        .with(user(member()))
                        .param("content", "일정"))
                .andExpect(status().isForbidden());

        verify(travelPlanService, never()).addItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void typedItemCreationAndUpdateKeepTheExistingRoutesAndVersion() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .param("content", "공항\n터미널 확인").param("itemType", "FLIGHT").param("startTime", "09:30"))
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
        verify(travelPlanService).addTypedItem(7L, 42L, 100L, "공항\n터미널 확인", "FLIGHT", "09:30");
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update").with(user(member())).with(csrf())
                        .param("content", "숙소").param("version", "3").param("itemType", "ACCOMMODATION").param("startTime", "15:00"))
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
        verify(travelPlanService).updateTypedItem(7L, 42L, 100L, 500L, "숙소", 3, "ACCOMMODATION", "15:00");
    }

    @Test
    void flightAndStayNestedFieldsUseStructuredOverloadsWhileLegacyRequestsStayUnchanged() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items").with(user(member())).with(csrf())
                        .param("content", "항공\n메모").param("itemType", "FLIGHT").param("startTime", "09:30")
                        .param("details.flightNumber", "KE123").param("details.departureDate", "2026-10-18")
                        .param("details.departureTime", "09:30"))
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
        var created = org.mockito.ArgumentCaptor.forClass(com.tripbora.model.TravelPlanItemDetails.class);
        verify(travelPlanService).addTypedItem(eq(7L), eq(42L), eq(100L), eq("항공\n메모"), eq("FLIGHT"), eq("09:30"), created.capture());
        assertThat(created.getValue().getFlightNumber()).isEqualTo("KE123");
        assertThat(created.getValue().getDepartureDate()).isEqualTo("2026-10-18");
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update").with(user(member())).with(csrf())
                        .param("content", "숙소").param("version", "3").param("itemType", "ACCOMMODATION").param("startTime", "15:00")
                        .param("details.stayName", "서울 호텔").param("details.bookingMemo", "조식 포함"))
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
        var updated = org.mockito.ArgumentCaptor.forClass(com.tripbora.model.TravelPlanItemDetails.class);
        verify(travelPlanService).updateTypedItem(eq(7L), eq(42L), eq(100L), eq(500L), eq("숙소"), eq(3),
                eq("ACCOMMODATION"), eq("15:00"), updated.capture());
        assertThat(updated.getValue().getStayName()).isEqualTo("서울 호텔");
        assertThat(updated.getValue().getBookingMemo()).isEqualTo("조식 포함");
    }

    @Test
    void typedItemsRenderEscapedContentAndKeepTheirDayMapping() throws Exception {
        var detail = planDetail();
        var item = new com.tripbora.model.TravelPlanItem();
        item.setId(500L);
        item.setTravelPlanDayId(100L);
        item.setDisplayOrder(1);
        item.setVersion(0);
        item.setContent("<script>alert(1)</script>\n터미널 확인");
        item.setTag("FLIGHT");
        item.setStartTime(java.time.LocalTime.of(9, 30));
        detail.setItemsByDayId(Map.of(100L, List.of(item)));
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(detail);
        String html = mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        var rendered = doc.selectFirst("#day-100 [data-travel-plan-item]");
        assertThat(doc.select("[data-plan-readonly], [data-dashboard-readonly], .travel-plan-back-link")).isEmpty();
        assertThat(doc.select("script[src^='/js/travel-plan-realtime-'][src$='.js']")).hasSize(1);
        assertThat(doc.select("script[src^='/js/travel-plan-readonly']")).isEmpty();
        assertThat(rendered).isNotNull();
        assertThat(rendered.attr("data-item-type")).isEqualTo("FLIGHT");
        assertThat(rendered.attr("data-start-time")).isEqualTo("09:30");
        assertThat(rendered.selectFirst("[data-travel-plan-item-form]").hasAttr("hidden")).isTrue();
        assertThat(rendered.select("select")).isEmpty();
        assertThat(rendered.selectFirst("[name='details.departureTime']").val()).isEqualTo("09:30");
        assertThat(rendered.select("[data-travel-plan-item-content] script")).isEmpty();
        assertThat(rendered.selectFirst("[data-travel-plan-item-content]").wholeText())
                .isEqualTo("<script>alert(1)</script>\n터미널 확인");
        assertThat(doc.selectFirst("[data-planner-summary='100'] .tp-detail-item-title").wholeText())
                .isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void oneOverviewInsertionBarFollowsTheLastDayIncludingEmptyDays() throws Exception {
        var detail = planDetailWithDays(3);
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(detail);
        String html = mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        assertThat(doc.select("[data-overview-insert-bar]")).hasSize(1);
        var summaries = doc.selectFirst("[data-planner-summaries]");
        assertThat(summaries.children().last().select("[data-planner-summary-items] > [data-overview-insert-bar]")).hasSize(1);
        assertThat(summaries.select("[data-planner-summary]")).hasSize(3);
        assertThat(summaries.select("[data-planner-summary] [data-overview-insert-bar]")).hasSize(1);
        assertThat(summaries.select("[data-overview-insert-shift]")).isEmpty();
        assertThat(summaries.select("[data-overview-insert-type]")).hasSize(3);
        assertThat(summaries.selectFirst("[data-overview-insert-bar]").attr("data-day-id"))
                .isEqualTo(detail.getDays().get(detail.getDays().size() - 1).getId().toString());
        assertThat(summaries.selectFirst("[data-overview-insert-bar]").attr("data-insert-placement")).isEqualTo("end");
        assertThat(summaries.select("[data-planner-summary-content], .tp-planner-summary-action")).isEmpty();
        assertThat(summaries.select("[data-planner-summary-count]")).isEmpty();
        assertThat(doc.select(".tp-planner-overview a[href='#tp-schedule-title']")).isEmpty();
    }

    @Test
    void blankRowsRenderInsideOneBarAndDayTitleIsAnInlineControl() throws Exception {
        var detail = planDetail();
        detail.getDays().get(0).setTitle("난바 체크인 뒤 도톤보리");
        detail.getDays().get(0).setTitleVersion(3);
        var items = new java.util.ArrayList<com.tripbora.model.TravelPlanItem>();
        String[] types = {"SCHEDULE", "FLIGHT", "ACCOMMODATION", "TRANSPORT"};
        for (int i = 0; i < types.length; i++) {
            var item = new com.tripbora.model.TravelPlanItem();
            item.setId(700L + i); item.setTravelPlanDayId(100L); item.setDisplayOrder(i + 1);
            item.setVersion(0); item.setTag(types[i]); item.setContent("");
            items.add(item);
        }
        detail.setItemsByDayId(Map.of(100L, items));
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(detail);
        String html = mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        assertThat(doc.selectFirst("#day-100 .tp-detail-day-tile").attr("aria-label")).isEqualTo("DAY 1");
        assertThat(doc.selectFirst("#day-100 .tp-detail-day-tile strong").text()).isEqualTo("1");
        assertThat(doc.selectFirst("#day-100 [data-day-title-edit]").text()).isEqualTo("난바 체크인 뒤 도톤보리");
        assertThat(doc.selectFirst("#day-100 [data-day-title-form][hidden] [name=titleVersion]").val()).isEqualTo("3");
        for (int i = 0; i < types.length; i++) {
            var row = doc.selectFirst("#day-100 [data-item-id='" + (700 + i) + "']");
            var bar = row.selectFirst(".tp-detail-item-shell");
            assertThat(bar.attr("data-type")).isEqualTo(types[i]);
            assertThat(bar.select("[data-item-drag-handle], .travel-plan-line-order, .tp-detail-item-view, [data-travel-plan-menu-button]"))
                    .hasSize(3);
            assertThat(bar.select("[data-travel-plan-menu-button], [data-travel-plan-menu-list]")).isEmpty();
            assertThat(bar.select("[data-travel-plan-item-form][hidden]")).hasSize(1);
            var editor = bar.selectFirst("[data-travel-plan-item-form][hidden]");
            assertThat(editor.selectFirst("[data-item-editor-delete]").attr("formaction")).endsWith("/delete");
            assertThat(editor.select("input[name=_csrf]")).hasSize(1);
            assertThat(bar.select("[data-travel-plan-reorder-forms][hidden] form")).hasSize(2);
            assertThat(bar.selectFirst(".tp-detail-item-title").text()).isNotBlank();
            if (i > 0) assertThat(bar.text()).contains("날짜 미정");
        }
        assertThat(doc.select("[data-planner-summary='100'] .tp-overview-item .tp-item-card")).hasSize(3);
        assertThat(doc.select("[data-planner-summary='100'] .tp-detail-item-shell")).hasSize(3);
        assertThat(doc.select("[data-planner-summary='100'] .tp-detail-item-shell [data-overview-card-drag-handle]")).hasSize(3);
        assertThat(doc.select("[data-planner-summary='100'] [data-item-drag-handle], [data-planner-summary='100'] .travel-plan-line-order")).isEmpty();
    }

    @Test
    void persistedFlightAndStayDetailsRenderInsideClosedEditorsAndPastelCards() throws Exception {
        var detail = planDetail();
        var flight = new com.tripbora.model.TravelPlanItem();
        flight.setId(501L); flight.setTravelPlanDayId(100L); flight.setDisplayOrder(1); flight.setVersion(2);
        flight.setTag("FLIGHT"); flight.setContent("대한항공 KE123\n예약 확인");
        var air = new com.tripbora.model.TravelPlanItemDetails();
        air.setFlightNumber("대한항공 KE123"); air.setDeparturePlace("인천"); air.setArrivalPlace("간사이");
        air.setDepartureDate("2026-12-31"); air.setDepartureTime("09:30"); air.setArrivalTime("11:20");
        flight.setDetails(air);
        var stay = new com.tripbora.model.TravelPlanItem();
        stay.setId(502L); stay.setTravelPlanDayId(100L); stay.setDisplayOrder(2); stay.setVersion(0);
        stay.setTag("ACCOMMODATION"); stay.setContent("오사카 호텔\n체크인 안내");
        stay.setStartTime(java.time.LocalTime.of(15, 0));
        var hotel = new com.tripbora.model.TravelPlanItemDetails();
        hotel.setStayName("오사카 호텔"); hotel.setCheckInDate("2026-12-31"); hotel.setCheckOutDate("2027-01-02");
        hotel.setAddress("오사카"); hotel.setBookingMemo("조식 포함");
        stay.setDetails(hotel);
        detail.setItemsByDayId(Map.of(100L, List.of(flight, stay)));
        when(travelPlanService.getActivePlanDetail(7L, 42L)).thenReturn(detail);
        String html = mockMvc.perform(get("/travel-plans/42").with(user(member())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(html);
        var card = doc.selectFirst("#day-100 [data-item-id='501'] [data-item-view]");
        assertThat(card.attr("data-type")).isEqualTo("FLIGHT");
        assertThat(card.text()).contains("인천", "간사이", "09:30");
        assertThat(doc.selectFirst("#day-100 [data-item-id='501'] [name='details.arrivalTime']").val()).isEqualTo("11:20");
        assertThat(doc.selectFirst("#day-100 [data-item-id='502'] [name='details.checkInTime']").val()).isEqualTo("15:00");
        assertThat(doc.selectFirst("#day-100 [data-item-id='502'] [data-item-view]").text())
                .contains("오사카 호텔", "2026-12-31");
        assertThat(doc.selectFirst("#day-100 [data-item-id='502'] [name='details.checkOutDate']").val()).isEqualTo("2027-01-02");
        assertThat(doc.selectFirst("#day-100 [data-item-id='502'] input[type=hidden][name='details.bookingMemo']").val())
                .isEqualTo("조식 포함");
        assertThat(doc.selectFirst("#day-100 [data-item-id='502'] input[type=hidden][name='details.address']").val())
                .isEqualTo("오사카");
        assertThat(doc.selectFirst("#day-100 [data-item-id='501'] [data-item-type-fields=ROUTE] input[name='details.departureDate']"))
                .isNotNull();
        assertThat(doc.select("#day-100 [data-travel-plan-item-form][hidden]")).hasSize(2);
        assertThat(doc.select("[data-planner-summary='100'] .tp-overview-item")).hasSize(2);
        for (String type : List.of("FLIGHT", "ACCOMMODATION")) {
            var summary = doc.selectFirst("[data-planner-summary='100'] .tp-detail-item-shell[data-type='" + type + "'] .tp-detail-item-view");
            var timeline = doc.selectFirst("#day-100 .tp-detail-item-shell[data-type='" + type + "'] .tp-detail-item-view");
            assertThat(summary.html()).isEqualTo(timeline.html());
        }
    }

    @Test
    void editingAnItemPassesTheVersionAndComesBackToThatDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update")
                        .with(user(member())).with(csrf())
                        .param("content", "고친 일정")
                        .param("version", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"))
                .andExpect(flash().attributeCount(0));

        verify(travelPlanService).updateItem(7L, 42L, 100L, 500L, "고친 일정", 3);
    }

    @Test
    void aConflictOnEditIsShownAsAMessageInsteadOfAnErrorPage() throws Exception {
        doThrow(new TravelPlanConflictException())
                .when(travelPlanService).updateItem(anyLong(), anyLong(), anyLong(), anyLong(),
                        any(), any());

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update")
                        .with(user(member())).with(csrf())
                        .param("content", "고친 일정")
                        .param("version", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"))
                .andExpect(flash().attribute("travelPlanError",
                        org.hamcrest.Matchers.containsString("다른 변경이 먼저 반영")));
    }

    @Test
    void aBlankEditIsShownAsAMessageInsteadOfAnErrorPage() throws Exception {
        doThrow(new TravelPlanValidationException("content", "일정 내용을 입력해 주세요."))
                .when(travelPlanService).updateItem(anyLong(), anyLong(), anyLong(), anyLong(),
                        any(), any());

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update")
                        .with(user(member())).with(csrf())
                        .param("content", "   ")
                        .param("version", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("travelPlanError", "일정 내용을 입력해 주세요."));
    }

    @Test
    void deletingAnItemComesBackToThatDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).deleteItem(7L, 42L, 100L, 500L);
    }

    @Test
    void editingOrDeletingWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/update")
                        .with(user(member()))
                        .param("content", "고친 일정").param("version", "3"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/delete")
                        .with(user(member())))
                .andExpect(status().isForbidden());

        verify(travelPlanService, never())
                .updateItem(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(travelPlanService, never()).deleteItem(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void addingAnAlternativePassesBothFieldsAndComesBackToThatDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives")
                        .with(user(member())).with(csrf())
                        .param("conditionLabel", "비가 많이 올 때")
                        .param("content", "아쿠아플라넷 방문"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).addAlternative(
                7L, 42L, 100L, 500L, "비가 많이 올 때", "아쿠아플라넷 방문");
    }

    @Test
    void aRejectedAlternativeIsShownAsAMessageInsteadOfAnErrorPage() throws Exception {
        doThrow(new TravelPlanValidationException("content", "대안은 일정마다 2개까지 추가할 수 있습니다."))
                .when(travelPlanService).addAlternative(
                        anyLong(), anyLong(), anyLong(), anyLong(), any(), any());

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives")
                        .with(user(member())).with(csrf())
                        .param("content", "세 번째"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"))
                .andExpect(flash().attribute("travelPlanError",
                        "대안은 일정마다 2개까지 추가할 수 있습니다."));
    }

    @Test
    void editingAnAlternativePassesItsOwnVersion() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives/900/update")
                        .with(user(member())).with(csrf())
                        .param("conditionLabel", "눈 올 때")
                        .param("content", "실내 박물관")
                        .param("version", "4"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).updateAlternative(
                7L, 42L, 100L, 500L, 900L, "눈 올 때", "실내 박물관", 4);
    }

    @Test
    void aConflictOnAnAlternativeEditIsShownAsAMessage() throws Exception {
        doThrow(new TravelPlanConflictException())
                .when(travelPlanService).updateAlternative(anyLong(), anyLong(), anyLong(),
                        anyLong(), anyLong(), any(), any(), any());

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives/900/update")
                        .with(user(member())).with(csrf())
                        .param("content", "실내 박물관").param("version", "4"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("travelPlanError"));
    }

    @Test
    void deletingAnAlternativeComesBackToThatDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives/900/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).deleteAlternative(7L, 42L, 100L, 500L, 900L);
    }

    @Test
    void theGroupDeleteIsItsOwnEndpoint() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/delete-group")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        // "일정만 삭제" 와 뜻이 갈리므로 서로 다른 서비스 호출이다
        verify(travelPlanService).deleteItemGroup(7L, 42L, 100L, 500L);
        verify(travelPlanService, never()).deleteItem(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void theAlternativeEndpointsAreRejectedWithoutCsrf() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives")
                        .with(user(member())).param("content", "아쿠아플라넷 방문"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives/900/update")
                        .with(user(member())).param("content", "실내 박물관").param("version", "4"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/alternatives/900/delete")
                        .with(user(member())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/delete-group")
                        .with(user(member())))
                .andExpect(status().isForbidden());

        verify(travelPlanService, never()).addAlternative(
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(travelPlanService, never()).updateAlternative(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any());
        verify(travelPlanService, never()).deleteAlternative(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(travelPlanService, never()).deleteItemGroup(
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void anItemFromAnotherRoomComesBackAsNotFound() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."))
                .when(travelPlanService).deleteItem(anyLong(), anyLong(), anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/days/100/items/999/delete")
                        .with(user(member())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void movingAnItemUpOrDownComesBackToTheSameDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move-up")
                        .with(user(member())).with(csrf()).param("version", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move-down")
                        .with(user(member())).with(csrf()).param("version", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42#day-100"));

        verify(travelPlanService).moveItemUp(7L, 42L, 100L, 500L, 3);
        verify(travelPlanService).moveItemDown(7L, 42L, 100L, 500L, 3);
    }

    @Test
    void movingToAnotherDayFollowsTheItemToItsNewDay() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move")
                        .with(user(member())).with(csrf())
                        .param("targetDayId", "200").param("version", "3"))
                .andExpect(status().is3xxRedirection())
                // 옮긴 일정이 보이도록 대상 DAY 로 돌아간다
                .andExpect(redirectedUrl("/travel-plans/42#day-200"));

        verify(travelPlanService).moveItemToDay(7L, 42L, 100L, 500L, 200L, 3);
    }

    @Test
    void aRejectedMoveStaysOnTheOriginalDayWithAMessage() throws Exception {
        doThrow(new TravelPlanValidationException("itemId", "이미 첫 번째 일정입니다."))
                .when(travelPlanService).moveItemUp(anyLong(), anyLong(), anyLong(), anyLong(), any());
        doThrow(new TravelPlanConflictException())
                .when(travelPlanService).moveItemToDay(anyLong(), anyLong(), anyLong(), anyLong(),
                        anyLong(), any());

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move-up")
                        .with(user(member())).with(csrf()).param("version", "3"))
                .andExpect(redirectedUrl("/travel-plans/42#day-100"))
                .andExpect(flash().attribute("travelPlanError", "이미 첫 번째 일정입니다."));

        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move")
                        .with(user(member())).with(csrf())
                        .param("targetDayId", "200").param("version", "3"))
                // 이동이 실패했으니 원래 DAY 에 남는다
                .andExpect(redirectedUrl("/travel-plans/42#day-100"))
                .andExpect(flash().attributeExists("travelPlanError"));
    }

    @Test
    void movingWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move-up")
                        .with(user(member())).param("version", "3"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move-down")
                        .with(user(member())).param("version", "3"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/items/500/move")
                        .with(user(member()))
                        .param("targetDayId", "200").param("version", "3"))
                .andExpect(status().isForbidden());

        verify(travelPlanService, never())
                .moveItemUp(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(travelPlanService, never())
                .moveItemDown(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(travelPlanService, never())
                .moveItemToDay(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    private TravelPlanDetailDto planDetail() {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("제주 여행");
        plan.setStartDate(LocalDate.parse(START));
        plan.setEndDate(LocalDate.parse(END));
        TravelPlanDay day = new TravelPlanDay();
        day.setId(100L);
        day.setTravelPlanId(42L);
        day.setDayNumber(1);
        day.setPlanDate(LocalDate.parse(START));
        return new TravelPlanDetailDto(
                plan, new TravelPlanMember(), List.of(day), Map.of(), Map.of(),
                List.of(new TravelPlanMemberDto(11L, "민준", TravelPlanRole.OWNER, true)),
                List.of(), 8);
    }

    /** 보고 있는 사람이 방장인 상세. 방장 전용 액션이 그려지는지 보는 데 쓴다. */
    private TravelPlanDetailDto ownerPlanDetail() {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("제주 여행");
        plan.setStartDate(LocalDate.parse(START));
        plan.setEndDate(LocalDate.parse(END));
        TravelPlanMember owner = new TravelPlanMember();
        owner.setId(11L);
        owner.setTravelPlanId(42L);
        owner.setUserId(7L);
        owner.setDisplayName("민준");
        owner.setRole(TravelPlanRole.OWNER);
        return new TravelPlanDetailDto(
                plan, owner, List.of(), Map.of(), Map.of(),
                List.of(new TravelPlanMemberDto(11L, "민준", TravelPlanRole.OWNER, true)),
                List.of(), 8);
    }

    /** DAY 수만 다른 상세. 머리글이 여행 길이를 어떻게 적는지 보는 데 쓴다. */
    private TravelPlanDetailDto planDetailWithDays(int dayCount) {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("제주 여행");
        plan.setStartDate(LocalDate.parse(START));
        plan.setEndDate(LocalDate.parse(START).plusDays(dayCount - 1L));
        java.util.List<TravelPlanDay> days = new java.util.ArrayList<>();
        for (int number = 1; number <= dayCount; number++) {
            TravelPlanDay day = new TravelPlanDay();
            day.setId(100L + number);
            day.setTravelPlanId(42L);
            day.setDayNumber(number);
            day.setPlanDate(LocalDate.parse(START).plusDays(number - 1L));
            days.add(day);
        }
        return new TravelPlanDetailDto(
                plan, new TravelPlanMember(), days, Map.of(), Map.of(),
                List.of(new TravelPlanMemberDto(11L, "민준", TravelPlanRole.OWNER, true)),
                List.of(), 8);
    }

    private TravelPlanDayDetailDto dayDetail() {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("제주 여행");
        TravelPlanDay day = new TravelPlanDay();
        day.setId(100L);
        day.setTravelPlanId(42L);
        day.setDayNumber(1);
        day.setPlanDate(LocalDate.parse(START));
        return new TravelPlanDayDetailDto(plan, new TravelPlanMember(), day, List.of());
    }

    // ── 참여자 명단 조각 ─────────────────────────────────────

    @Test
    void theHeadcountComesFromTheServerNotFromTheScreen() throws Exception {
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(membersOf(
                new com.tripbora.dto.TravelPlanMemberDto(
                        11L, "민준", com.tripbora.model.TravelPlanRole.OWNER, true),
                new com.tripbora.dto.TravelPlanMemberDto(
                        12L, "쭈니", com.tripbora.model.TravelPlanRole.MEMBER, false)));

        mockMvc.perform(get("/travel-plans/42/members/fragment").with(user(member())))
                .andExpect(status().isOk())
                // 화면이 더하거나 빼지 않도록 토글 글자까지 서버가 만들어 보낸다
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-members-label=\"참여자 2/8\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("민준")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("쭈니")));
    }

    @Test
    void theRowsSayOnlyWhatIsWorthSaying() throws Exception {
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(membersOf(
                new com.tripbora.dto.TravelPlanMemberDto(
                        11L, "민준", com.tripbora.model.TravelPlanRole.OWNER, true),
                new com.tripbora.dto.TravelPlanMemberDto(
                        12L, "쭈니", com.tripbora.model.TravelPlanRole.MEMBER, false)));

        String body = mockMvc.perform(
                        get("/travel-plans/42/members/fragment").with(user(member())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                // 방장에게만 적는다. 나머지가 멤버라는 것은 되풀이하지 않는다
                .contains("방장")
                .doesNotContain("멤버</span>")
                // 나라는 표시는 이름에 괄호로 붙이지 않는다
                .contains(">나</span>")
                .doesNotContain("(나)")
                // 참여 인원과 접속 인원이 한 줄에 온다
                .contains("2/8명 참여")
                // 접속 인원 자리는 연결되기 전까지 숨어 있다
                .contains("class=\"travel-plan-online-count\" hidden");
    }

    @Test
    void theParticipantListSaysNothingAboutClosingTheRoom() throws Exception {
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(membersOf(
                new com.tripbora.dto.TravelPlanMemberDto(
                        11L, "민준", com.tripbora.model.TravelPlanRole.OWNER, true),
                new com.tripbora.dto.TravelPlanMemberDto(
                        12L, "쭈니", com.tripbora.model.TravelPlanRole.MEMBER, false)));

        String body = mockMvc.perform(
                        get("/travel-plans/42/members/fragment").with(user(member())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 방을 지우는 것은 참여자 관리가 아니라 방 관리다. 여기에는 오지 않는다
        assertThat(body)
                .doesNotContain("data-travel-plan-plan-delete-open")
                .doesNotContain("여행 계획 삭제")
                // 방장은 먼저 방장을 넘겨야 나갈 수 있다. 나가기도 여기 없다
                .doesNotContain("여행에서 나가기");
    }

    @Test
    void aPlainMemberStillOnlyGetsTheWayOut() throws Exception {
        when(travelPlanService.getActivePlanMembers(8L, 42L)).thenReturn(membersSeenByMember(
                new com.tripbora.dto.TravelPlanMemberDto(
                        11L, "민준", com.tripbora.model.TravelPlanRole.OWNER, false),
                new com.tripbora.dto.TravelPlanMemberDto(
                        12L, "쭈니", com.tripbora.model.TravelPlanRole.MEMBER, true)));

        String body = mockMvc.perform(
                        get("/travel-plans/42/members/fragment").with(user(plainMember())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("여행 계획 삭제")
                .contains("여행에서 나가기");
    }

    @Test
    void theMemberListFragmentIsNotAWholePage() throws Exception {
        // 패널 속만 갈아 끼우므로 껍데기가 딸려 오면 안 된다
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(membersOf(
                new com.tripbora.dto.TravelPlanMemberDto(
                        11L, "민준", com.tripbora.model.TravelPlanRole.OWNER, true)));

        mockMvc.perform(get("/travel-plans/42/members/fragment").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<html"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("travel-plan-members-panel"))));
    }

    @Test
    void someoneWhoIsNotInTheRoomCannotReadItsMemberList() throws Exception {
        when(travelPlanService.getActivePlanMembers(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));

        mockMvc.perform(get("/travel-plans/42/members/fragment").with(user(member())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousMemberListAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans/42/members/fragment"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/travel-plans/42/members/fragment"));
    }

    // ── 방장 전용 자리 ──────────────────────────────────────

    @Test
    void whoeverIsOwnerNowGetsTheOwnerActionsBack() throws Exception {
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(ownerViewer());

        mockMvc.perform(get("/travel-plans/42/owner-actions/fragment").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-travel-plan-finalize")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-travel-plan-invite")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-travel-plan-finalize-modal")))
                /*
                  방 삭제도 방장의 방 관리다. 넘겨받은 사람에게 진입점과 확인 창이
                  함께 따라와야 그 자리에서 바로 눌린다.
                */
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-travel-plan-plan-delete-open")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-travel-plan-plan-delete-modal")));
    }

    @Test
    void whoeverIsNoLongerOwnerGetsAnEmptyPlace() throws Exception {
        /*
          넘겨준 쪽에서는 이 조각이 비어서 오고, 화면은 그것으로 그 자리를 비운다.
          방장이 누구인지는 언제나 여기서 다시 읽는다.
        */
        when(travelPlanService.getActivePlanMembers(7L, 42L)).thenReturn(plainViewer());

        mockMvc.perform(get("/travel-plans/42/owner-actions/fragment").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-travel-plan-finalize"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-travel-plan-invite"))))
                // 넘겨준 사람에게는 방 삭제도 진입점과 창이 함께 사라진다
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("travel-plan-plan-delete"))));
    }

    @Test
    void someoneWhoIsNotInTheRoomCannotReadTheOwnerActions() throws Exception {
        when(travelPlanService.getActivePlanMembers(anyLong(), anyLong()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));

        mockMvc.perform(get("/travel-plans/42/owner-actions/fragment").with(user(member())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousOwnerActionsAccessIsSentToLogin() throws Exception {
        mockMvc.perform(get("/travel-plans/42/owner-actions/fragment"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/login?redirect=/travel-plans/42/owner-actions/fragment"));
    }

    /** 지금 이 방의 방장으로 보고 있는 사람. */
    private com.tripbora.dto.TravelPlanMembersDto ownerViewer() {
        return viewerWithRole(com.tripbora.model.TravelPlanRole.OWNER);
    }

    /** 방장이 아닌 참여자로 보고 있는 사람. */
    private com.tripbora.dto.TravelPlanMembersDto plainViewer() {
        return viewerWithRole(com.tripbora.model.TravelPlanRole.MEMBER);
    }

    private com.tripbora.dto.TravelPlanMembersDto viewerWithRole(
            com.tripbora.model.TravelPlanRole role) {
        com.tripbora.model.TravelPlan plan =
                new com.tripbora.model.TravelPlan();
        plan.setId(42L);
        com.tripbora.model.TravelPlanMember current =
                new com.tripbora.model.TravelPlanMember();
        current.setId(11L);
        current.setRole(role);
        return new com.tripbora.dto.TravelPlanMembersDto(
                plan, current, List.of(), List.of(), 8);
    }

    private com.tripbora.dto.TravelPlanMembersDto membersOf(
            com.tripbora.dto.TravelPlanMemberDto... members) {
        com.tripbora.model.TravelPlan plan =
                new com.tripbora.model.TravelPlan();
        plan.setId(42L);
        com.tripbora.model.TravelPlanMember current =
                new com.tripbora.model.TravelPlanMember();
        current.setId(11L);
        current.setRole(com.tripbora.model.TravelPlanRole.OWNER);
        return new com.tripbora.dto.TravelPlanMembersDto(
                plan, current, List.of(members), List.of(), 8);
    }

    /** 같은 명단을 방장이 아닌 사람이 볼 때. 보는 사람의 역할만 다르다. */
    private com.tripbora.dto.TravelPlanMembersDto membersSeenByMember(
            com.tripbora.dto.TravelPlanMemberDto... members) {
        com.tripbora.model.TravelPlan plan =
                new com.tripbora.model.TravelPlan();
        plan.setId(42L);
        com.tripbora.model.TravelPlanMember current =
                new com.tripbora.model.TravelPlanMember();
        current.setId(12L);
        current.setRole(com.tripbora.model.TravelPlanRole.MEMBER);
        return new com.tripbora.dto.TravelPlanMembersDto(
                plan, current, List.of(members), List.of(), 8);
    }

    /** 방장이 아닌 로그인 사용자. */
    private CustomUserDetails plainMember() {
        User user = new User();
        user.setId(8L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        return new CustomUserDetails(user);
    }

    private CustomUserDetails member() {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        return new CustomUserDetails(user);
    }
}
