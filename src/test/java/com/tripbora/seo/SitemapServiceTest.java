package com.tripbora.seo;

import com.tripbora.model.TravelInfoContentType;
import com.tripbora.repository.seo.SitemapMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SitemapServiceTest {

    @Test
    void canonicalPathsContainOnlyPublicContentReturnedByTheMapper() {
        SitemapMapper mapper = mock(SitemapMapper.class);
        when(mapper.findDestinationIds()).thenReturn(List.of(2L));
        when(mapper.findTravelInfoEntries()).thenReturn(List.of(
                new SitemapTravelInfo(3L, TravelInfoContentType.GENERAL),
                new SitemapTravelInfo(4L, TravelInfoContentType.FESTIVAL)));
        when(mapper.findEventIds()).thenReturn(List.of(5L));
        when(mapper.findPostIds()).thenReturn(List.of(6L));
        when(mapper.findCourseIds()).thenReturn(List.of(7L));

        SitemapService service = new SitemapService(mapper);

        assertThat(service.canonicalPaths()).containsExactly(
                "/", "/about", "/destinations", "/travel-info",
                "/travel-info?contentType=FESTIVAL", "/events", "/courses", "/board/list",
                "/destinations/2", "/travel-info/3", "/festivals/4",
                "/events/5", "/post/6", "/courses/7");
    }
}
