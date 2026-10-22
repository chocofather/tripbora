package com.tripbora.service.destination;

import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.info.AccommodationInfoService;
import com.tripbora.service.info.ActivityInfoService;
import com.tripbora.service.info.AttractionInfoService;
import com.tripbora.service.info.RestaurantInfoService;
import com.tripbora.service.info.ShopInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 목록 조회가 일반 이름 검색과 초성 검색을 각각 Mapper 조건으로 전달하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class DestinationServiceListSearchTest {

    private static final List<Long> REGION_IDS = List.of(1L, 10L);

    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private BookmarkMapper bookmarkMapper;
    @Mock
    private AmenityService amenityService;
    @Mock
    private DestinationCommentService destinationCommentService;
    @Mock
    private AccommodationInfoService accommodationInfoService;
    @Mock
    private AttractionInfoService attractionInfoService;
    @Mock
    private RestaurantInfoService restaurantInfoService;
    @Mock
    private ActivityInfoService activityInfoService;
    @Mock
    private ShopInfoService shopInfoService;
    @Mock
    private CourseService courseService;

    private DestinationService destinationService;

    @BeforeEach
    void setUp() {
        destinationService = new DestinationService(
                destinationMapper, destinationImageService, bookmarkMapper, amenityService,
                destinationCommentService, courseService,
                accommodationInfoService, attractionInfoService,
                restaurantInfoService, activityInfoService, shopInfoService,
                new DestinationLocalizationService(destinationMapper));
    }

    @Test
    void ordinaryKeywordIsSentAsAPartialNameCondition() {
        destinationService.getAdminDestinationPage(REGION_IDS, "ATTRACTION", "missing_image", "경복", "latest", 30, 30);

        verify(destinationMapper).findAdminDestinationPage(
                REGION_IDS, "ATTRACTION", "missing_image", "경복", null, "latest", 30, 30, null);
    }

    @Test
    void chosungKeywordIsSentAsAGeneratedPatternInsteadOfTheRawJamo() {
        destinationService.getAdminDestinationPage(REGION_IDS, null, null, "ㄱㅂㄱ", "latest", 0, 30);

        ArgumentCaptor<String> chosung = ArgumentCaptor.forClass(String.class);
        verify(destinationMapper)
                .findAdminDestinationPage(org.mockito.ArgumentMatchers.eq(REGION_IDS),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        chosung.capture(),
                        org.mockito.ArgumentMatchers.eq("latest"),
                        org.mockito.ArgumentMatchers.eq(0L),
                        org.mockito.ArgumentMatchers.eq(30), org.mockito.ArgumentMatchers.isNull());
        assertThat(Pattern.compile(chosung.getValue()).matcher("경복궁").find()).isTrue();
        assertThat(Pattern.compile(chosung.getValue()).matcher("창덕궁").find()).isFalse();
    }

    @Test
    void blankKeywordCarriesNoSearchCondition() {
        destinationService.countAdminDestinations(REGION_IDS, null, null, "   ");

        verify(destinationMapper).countAdminDestinations(REGION_IDS, null, null, null, null, null);
    }

    /** 상단 상태별 건수는 데이터 상태 조건 없이 같은 검색 조건으로 한 번만 집계한다. */
    @Test
    void dataStatusCountsUseTheSameSearchConditionWithoutAStatusFilter() {
        destinationService.getAdminDestinationDataStatusCounts(REGION_IDS, "ATTRACTION", "경복");

        verify(destinationMapper).countAdminDestinationDataStatus(REGION_IDS, "ATTRACTION", "경복", null, null);
    }

    /** 지역 범위가 비면 IN () 쿼리를 만들지 않고 빈 결과로 끝낸다. */
    @Test
    void emptyRegionScopeSkipsTheQuery() {
        assertThat(destinationService.getAdminDestinationPage(List.of(), null, null, null, "latest", 0, 30)).isEmpty();
        assertThat(destinationService.countAdminDestinations(List.of(), null, null, null)).isZero();
        assertThat(destinationService.getAdminDestinationDataStatusCounts(List.of(), null, null).getTotal()).isZero();

        org.mockito.Mockito.verifyNoInteractions(destinationMapper);
    }
}
