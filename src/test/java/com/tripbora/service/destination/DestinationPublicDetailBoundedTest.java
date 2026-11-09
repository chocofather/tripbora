package com.tripbora.service.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.*;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.info.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DestinationPublicDetailBoundedTest {
    @Test void publicDetailReadsOneRepresentativeAndNeverLoadsEitherUnboundedImagePath() {
        var mapper = mock(DestinationMapper.class);
        var service = new DestinationService(mapper, mock(DestinationImageService.class), mock(BookmarkMapper.class),
                mock(AmenityService.class), mock(DestinationCommentService.class), mock(CourseService.class),
                mock(AccommodationInfoService.class), mock(AttractionInfoService.class), mock(RestaurantInfoService.class),
                mock(ActivityInfoService.class), mock(ShopInfoService.class), new DestinationLocalizationService(mapper));
        var destination = new Destination(); destination.setId(10L); destination.setType(DestinationType.ATTRACTION);
        when(mapper.findPublicDestinationDetail(10L)).thenReturn(destination);
        var korean = new DestinationTranslation(); korean.setLanguageCode("ko"); korean.setName("여행지");
        when(mapper.findTranslationsByDestinationId(10L)).thenReturn(List.of(korean));
        var representative = new DestinationImage(); representative.setId(1L);
        when(mapper.findPublicRepresentativeImage(10L)).thenReturn(List.of(representative));
        var detail = service.getPublicDestinationDetailWithInfo(10L, SupportedLanguage.KOREAN);
        assertThat(detail.getImages()).containsExactly(representative);
        assertThat(detail.getDestination().getName()).isEqualTo("여행지");
        verify(mapper, never()).findDestinationDetail(anyLong());
        verify(mapper, never()).findImagesByDestinationId(anyLong());
    }
}
