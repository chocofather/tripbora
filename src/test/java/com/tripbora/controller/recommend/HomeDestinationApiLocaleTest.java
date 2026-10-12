package com.tripbora.controller.recommend;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.RecommendDestinationDto;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.service.file.DestinationCardThumbnailService;
import com.tripbora.service.recommend.DestinationRecommendService;
import com.tripbora.service.recommend.PopularRecommendService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import com.tripbora.service.destination.DestinationImageService;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HomeDestinationApiLocaleTest {

    @Mock private PopularRecommendService popularRecommendService;
    @Mock private DestinationRecommendService destinationRecommendService;
    @Mock private DestinationImageService destinationImageService;
    /** 이 테스트의 카드에는 이미지가 없어 썸네일을 채우지 않는다. (업로드 폴더도 비어 있다) */
    private final DestinationCardThumbnailService cardThumbnailService =
            new DestinationCardThumbnailService("build/tmp/no-uploads",
                    org.mockito.Mockito.mock(DestinationImageService.class));

    @ParameterizedTest
    @EnumSource(SupportedLanguage.class)
    void homeDestinationApisUseTheLocaleCookieForTheirLocalizedJson(
            SupportedLanguage language) throws Exception {
        PopularRecommendController popularController =
                new PopularRecommendController(popularRecommendService, cardThumbnailService,
                        destinationImageService);
        DestinationRecommendController seasonController =
                new DestinationRecommendController(destinationRecommendService, cardThumbnailService,
                        destinationImageService);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(popularController, seasonController)
                .setLocaleResolver(new TripBoraLocaleResolver())
                .build();
        RecommendDestinationDto popular = new RecommendDestinationDto();
        popular.setId(15L);
        popular.setName(language.getLanguageTag() + " popular");
        SeasonDestinationDto seasonal = new SeasonDestinationDto();
        seasonal.setId(15L);
        seasonal.setName(language.getLanguageTag() + " seasonal");
        when(popularRecommendService.findDomesticPopular(5, language))
                .thenReturn(List.of(popular));
        when(destinationRecommendService.findByCurationCategoryName(
                "단풍 명소", 5, language)).thenReturn(List.of(seasonal));

        Cookie localeCookie = new Cookie(
                TripBoraLocaleResolver.COOKIE_NAME, language.getLanguageTag());
        mockMvc.perform(get("/api/popular-destinations/domestic")
                        .param("limit", "5")
                        .cookie(localeCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name")
                        .value(language.getLanguageTag() + " popular"));
        mockMvc.perform(get("/api/season-destinations")
                        .param("season", "FALL")
                        .param("categoryName", "단풍 명소")
                        .param("limit", "5")
                        .cookie(localeCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name")
                        .value(language.getLanguageTag() + " seasonal"));

        verify(popularRecommendService).findDomesticPopular(5, language);
        verify(destinationRecommendService).findByCurationCategoryName(
                "단풍 명소", 5, language);
    }
}
