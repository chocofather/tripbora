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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 여행지 목록의 선택 삭제 요청. ADMIN 과 CSRF 토큰이 있는 POST 만 받고,
 * 결과·오류는 삭제 전 목록 조건(검색·필터·정렬·쪽)으로 돌아가 한 번만 보여 준다.
 */
@WebMvcTest(AdminDestinationController.class)
@Import(SecurityConfig.class)
class AdminDestinationBulkDeleteControllerTest {

    private static final String URL = "/admin/destinations/bulk-delete";

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
    void onlyAdministratorsWithTheCsrfTokenCanBulkDelete() throws Exception {
        mockMvc.perform(post(URL).param("destinationIds", "1", "2")
                        .with(user("user").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).param("destinationIds", "1", "2")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        // GET 으로는 지울 수 없다
        mockMvc.perform(get(URL).param("destinationIds", "1", "2")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());

        verify(destinationService, never()).deleteByIds(any());
    }

    @Test
    void selectedIdsAreDeletedInOneRequestAndTheFilteredListPageIsKept() throws Exception {
        when(destinationService.deleteByIds(List.of(3L, 5L, 8L))).thenReturn(3);

        // 목록 조건은 폼 주소(query string)로, 선택한 ID는 본문으로 온다
        mockMvc.perform(post(URL)
                        .queryParam("scope", "domestic").queryParam("destinationType", "CAFE")
                        .queryParam("dataStatus", "missing_image").queryParam("regionId", "10")
                        .queryParam("keyword", " 카페 ").queryParam("sort", "oldest").queryParam("page", "2")
                        .param("destinationIds", "3", "5", "8")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations?scope=domestic&destinationType=CAFE"
                        + "&dataStatus=missing_image&regionId=10&keyword=%EC%B9%B4%ED%8E%98&sort=oldest&page=2"))
                .andExpect(flash().attribute("bulkDeleteResult", "여행지 3건을 삭제했습니다."));

        verify(destinationService).deleteByIds(List.of(3L, 5L, 8L));
        verify(destinationService, never()).deleteById(any());
    }

    @Test
    void rejectedSelectionShowsTheReasonOnTheSameList() throws Exception {
        when(destinationService.deleteByIds(null))
                .thenThrow(new IllegalArgumentException("삭제할 여행지를 선택해 주세요."));

        mockMvc.perform(post(URL + "?page=3")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations?page=3"))
                .andExpect(flash().attribute("error", "삭제할 여행지를 선택해 주세요."))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void nonNumericIdsAreRejectedBeforeAnythingIsDeleted() throws Exception {
        mockMvc.perform(post(URL).param("destinationIds", "3", "abc")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());

        verify(destinationService, never()).deleteByIds(any());
    }

    @Test
    void singleDeleteStillWorksAndKeepsTheListConditions() throws Exception {
        mockMvc.perform(post("/admin/destinations/7/delete?scope=overseas&sort=name&page=2")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations?scope=overseas&sort=name&page=2"));

        verify(destinationService).deleteById(7L);
        verify(destinationService, never()).deleteByIds(any());
    }
}
