package com.tripbora.service.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDto;
import com.tripbora.model.Destination;
import com.tripbora.model.DestinationTranslation;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationServiceLocalizedListTest {

    @Mock private DestinationMapper destinationMapper;
    @Mock private DestinationImageService destinationImageService;
    @Mock private BookmarkMapper bookmarkMapper;
    @Mock private AmenityService amenityService;
    @Mock private DestinationCommentService destinationCommentService;
    @Mock private CourseService courseService;
    @Mock private AccommodationInfoService accommodationInfoService;
    @Mock private AttractionInfoService attractionInfoService;
    @Mock private RestaurantInfoService restaurantInfoService;
    @Mock private ActivityInfoService activityInfoService;
    @Mock private ShopInfoService shopInfoService;

    private DestinationService service;

    @BeforeEach
    void setUp() {
        service = new DestinationService(destinationMapper, destinationImageService, bookmarkMapper,
                amenityService, destinationCommentService, courseService,
                accommodationInfoService, attractionInfoService, restaurantInfoService,
                activityInfoService, shopInfoService,
                new DestinationLocalizationService(destinationMapper));
    }

    @Test
    void localizesCurrentPageCardsWithOneTranslationBatchAndFieldLevelFallback() {
        Destination palace = destination(15L, 235L, "경복궁", "한국어 소개");
        palace.setCardPositionX(25);
        palace.setCardPositionY(80);
        Destination village = destination(16L, 236L, "마을", "마을 소개");
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "ko", "경복궁", "한국어 소개"),
                        translation(2L, 15L, "en", "Gyeongbokgung Palace", " "),
                        translation(3L, 16L, "ko", "마을", "마을 소개")));
        when(bookmarkMapper.findBookmarkedTargetIds(
                9L, "DESTINATION", List.of(15L, 16L)))
                .thenReturn(Set.of(15L));
        when(destinationCommentService.countCommentsByDestinationIds(List.of(15L, 16L)))
                .thenReturn(Map.of(15L, 4));

        List<DestinationDto> cards = service.convertToLocalizedDtoWithBookmark(
                List.of(palace, village), 9L, SupportedLanguage.ENGLISH,
                Map.of(235L, "Jongno-gu", 236L, "Jung-gu"));

        assertThat(cards).extracting(DestinationDto::getName)
                .containsExactly("Gyeongbokgung Palace", "마을");
        assertThat(cards).extracting(DestinationDto::getShortDescription)
                .containsExactly("한국어 소개", "마을 소개");
        assertThat(cards).extracting(DestinationDto::getRegionName)
                .containsExactly("Jongno-gu", "Jung-gu");
        assertThat(cards.get(0).isBookmarked()).isTrue();
        assertThat(cards.get(0).getCardPositionX()).isEqualTo(25);
        assertThat(cards.get(0).getCardPositionY()).isEqualTo(80);
        assertThat(cards.get(1).getCardPositionX()).isEqualTo(50);
        assertThat(cards.get(1).getCardPositionY()).isEqualTo(50);
        assertThat(cards.get(0).getCommentCount()).isEqualTo(4);
        verify(destinationMapper, times(1))
                .findTranslationsByDestinationIds(List.of(15L, 16L));
        verify(bookmarkMapper).findBookmarkedTargetIds(
                9L, "DESTINATION", List.of(15L, 16L));
    }

    @Test
    void loggedInEmptyPageSkipsBookmarkLookup() {
        assertThat(service.convertToLocalizedDtoWithBookmark(
                List.of(), 9L, SupportedLanguage.KOREAN, Map.of())).isEmpty();

        verifyNoInteractions(bookmarkMapper);
    }

    @Test
    void missingRequestedAndKoreanCardFieldsUseDeterministicOtherThenBase() {
        Destination palace = destination(15L, 235L, "기존 이름", "기존 소개");
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L)))
                .thenReturn(List.of(
                        translation(9L, 15L, "ja", "日本語名", null),
                        translation(5L, 15L, "en", "English name", "English summary")));
        when(destinationCommentService.countCommentsByDestinationIds(List.of(15L)))
                .thenReturn(Map.of());

        DestinationDto card = service.convertToLocalizedDtoWithBookmark(
                List.of(palace), null, SupportedLanguage.CHINESE_TRADITIONAL,
                Map.of()).get(0);

        assertThat(card.getName()).isEqualTo("English name");
        assertThat(card.getShortDescription()).isEqualTo("English summary");
        assertThat(card.getRegionName()).isEqualTo("종로구");
    }

    @Test
    void resolvesHomeDestinationContentForMultipleIdsWithOneBatchAndFieldFallback() {
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "ko", "경복궁", "한국어 소개"),
                        translation(2L, 15L, "en", "Gyeongbokgung Palace", " "),
                        translation(3L, 16L, "ko", "창덕궁", "창덕궁 소개")));

        Map<Long, DestinationTranslation> localized =
                service.resolveLocalizedContentByDestinationIds(
                        List.of(15L, 16L), SupportedLanguage.ENGLISH);

        assertThat(localized.get(15L).getName()).isEqualTo("Gyeongbokgung Palace");
        assertThat(localized.get(15L).getShortDescription()).isEqualTo("한국어 소개");
        assertThat(localized.get(16L).getName()).isEqualTo("창덕궁");
        verify(destinationMapper, times(1))
                .findTranslationsByDestinationIds(List.of(15L, 16L));
    }

    private Destination destination(Long id, Long regionId, String name, String shortDescription) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setRegionId(regionId);
        destination.setRegionName("종로구");
        destination.setName(name);
        destination.setShortDescription(shortDescription);
        destination.setThumbnailPath("/image.jpg");
        return destination;
    }

    private DestinationTranslation translation(Long id, Long destinationId, String languageCode,
                                               String name, String shortDescription) {
        DestinationTranslation translation = new DestinationTranslation();
        translation.setId(id);
        translation.setDestinationId(destinationId);
        translation.setLanguageCode(languageCode);
        translation.setName(name);
        translation.setShortDescription(shortDescription);
        return translation;
    }
}
