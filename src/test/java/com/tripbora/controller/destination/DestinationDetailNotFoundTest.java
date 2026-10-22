package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 삭제됐거나 존재하지 않는 여행지 상세 요청은 NPE 500 이 아니라 404 여야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationDetailNotFoundTest {

    @Mock
    private DestinationService destinationService;
    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private CountryCategoryService countryCategoryService;
    @Mock
    private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock
    private DestinationCommentService destinationCommentService;

    private MockMvc mockMvc;
    private CustomUserDetails currentPrincipal;

    @BeforeEach
    void setUp() {
        DestinationController controller = new DestinationController(
                destinationService,
                destinationImageService,
                countryCategoryService,
                destinationCommentService,
                referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(anonymousPrincipalResolver())
                .build();
    }

    /** 비로그인 방문자: @AuthenticationPrincipal 은 null 로 들어온다. */
    private HandlerMethodArgumentResolver anonymousPrincipalResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return currentPrincipal;
            }
        };
    }

    @Test
    void deletedOrUnknownDestinationAnswersNotFound() throws Exception {
        when(destinationService.getDestinationDetailWithInfo(eq(404L), any(SupportedLanguage.class)))
                .thenReturn(null);

        mockMvc.perform(get("/destinations/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownDestinationIsNotCountedAsAView() throws Exception {
        when(destinationService.getDestinationDetailWithInfo(eq(404L), any(SupportedLanguage.class)))
                .thenReturn(null);

        mockMvc.perform(get("/destinations/404"))
                .andExpect(status().isNotFound());

        verify(destinationService, never()).recordDetailView(any(), any());
        verify(destinationService, never()).incrementViewCount(404L);
    }

    @Test
    void hiddenDestinationAnswersNotFoundForAnonymousWithoutCountingViews() throws Exception {
        stubHiddenDestination();

        mockMvc.perform(get("/destinations/7"))
                .andExpect(status().isNotFound());

        verify(destinationService, never()).recordDetailView(any(), any());
        verify(destinationService, never()).incrementViewCount(7L);
    }

    @Test
    void hiddenDestinationAnswersNotFoundForOrdinaryUser() throws Exception {
        currentPrincipal = principal(UserRole.USER);
        stubHiddenDestination();

        mockMvc.perform(get("/destinations/7"))
                .andExpect(status().isNotFound());

        verify(destinationService, never()).recordDetailView(any(), any());
    }

    @Test
    void administratorCanStillViewHiddenDestination() throws Exception {
        currentPrincipal = principal(UserRole.ADMIN);
        stubExistingDestination();
        stubHiddenDestination();

        mockMvc.perform(get("/destinations/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("destination/detail"));
    }

    private void stubHiddenDestination() {
        DestinationDetailDto hidden = detailDto();
        hidden.getDestination().setPublicVisible(false);
        when(destinationService.getDestinationDetailWithInfo(eq(7L), any(SupportedLanguage.class)))
                .thenReturn(hidden);
    }

    private CustomUserDetails principal(UserRole role) {
        User user = new User();
        user.setId(25L);
        user.setUserRole(role);
        return new CustomUserDetails(user);
    }

    @Test
    void theSameSessionIsCountedOncePerKstDayWhileEveryViewIsRecorded() throws Exception {
        stubExistingDestination();
        MockHttpSession session = new MockHttpSession();
        LocalDate today = LocalDate.of(2026, 9, 30);

        mockMvc.perform(get("/destinations/7").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/destinations/7").session(session)).andExpect(status().isOk());

        // 두 번 모두 기록(누적 조회수)을 부르고, 일별 집계 여부는 세션 판정이 정한다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<LocalDate>> firstViewOn = ArgumentCaptor.forClass(Predicate.class);
        verify(destinationService, times(2)).recordDetailView(eq(7L), firstViewOn.capture());
        assertThat(firstViewOn.getAllValues().get(0).test(today)).isTrue();
        assertThat(firstViewOn.getAllValues().get(1).test(today)).isFalse();
        // 날짜가 바뀌면 같은 세션도 다시 집계된다.
        assertThat(firstViewOn.getAllValues().get(1).test(today.plusDays(1))).isTrue();
    }

    @Test
    void existingDestinationStillRendersTheDetailPageAndCountsTheView() throws Exception {
        stubExistingDestination();

        var result = mockMvc.perform(get("/destinations/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("destination/detail"))
                .andReturn();

        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getModel().get("regionName")).isEqualTo("종로구");
        verify(destinationService).recordDetailView(eq(7L), any());
    }

    private void stubExistingDestination() {
        when(destinationService.getDestinationDetailWithInfo(eq(7L), any(SupportedLanguage.class)))
                .thenReturn(detailDto());
        when(countryCategoryService.getById(101L)).thenReturn(region(101L, "종로구", 10L));
        when(countryCategoryService.getById(10L)).thenReturn(region(10L, "서울", null));
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(10L));
        when(destinationService.getSimilarDestinations(7L, 4)).thenReturn(List.of());
        when(destinationService.convertToDtoWithBookmark(List.of(), null)).thenReturn(List.of());
    }

    private DestinationDetailDto detailDto() {
        Destination destination = new Destination();
        destination.setId(7L);
        destination.setRegionId(101L);
        destination.setDescription("설명");
        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);
        dto.setImages(List.of());
        return dto;
    }

    private CountryCategory region(Long id, String name, Long parentId) {
        CountryCategory region = new CountryCategory();
        region.setId(id);
        region.setRegionName(name);
        region.setParentId(parentId);
        region.setCode("KR-11");
        return region;
    }
}
