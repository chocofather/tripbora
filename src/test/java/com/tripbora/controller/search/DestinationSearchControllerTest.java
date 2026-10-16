package com.tripbora.controller.search;

import com.tripbora.dto.DestinationSearchResultDto;
import com.tripbora.service.search.DestinationSearchService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DestinationSearchControllerTest {

    @Test
    void searchWithoutCountryKeepsExistingUnfilteredBehavior() {
        DestinationSearchService service = mock(DestinationSearchService.class);
        DestinationSearchController controller = new DestinationSearchController(service, mock(CountryCategoryService.class), mock(ReferenceNameLocalizationService.class));
        List<DestinationSearchResultDto> results = List.of(new DestinationSearchResultDto());
        when(service.search("서울", 20, null, null, null)).thenReturn(results);

        assertThat(controller.searchDestinations("서울", 20, null, null, null)).isSameAs(results);
        verify(service).search("서울", 20, null, null, null);
    }

    @Test
    void searchPassesSelectedCountryToServerFilter() {
        DestinationSearchService service = mock(DestinationSearchService.class);
        DestinationSearchController controller = new DestinationSearchController(service, mock(CountryCategoryService.class), mock(ReferenceNameLocalizationService.class));
        List<DestinationSearchResultDto> results = List.of(new DestinationSearchResultDto());
        when(service.search("도쿄", 20, 8L, "overseas", 9L)).thenReturn(results);

        assertThat(controller.searchDestinations("도쿄", 20, 8L, "overseas", 9L)).isSameAs(results);
        verify(service).search("도쿄", 20, 8L, "overseas", 9L);
    }
}
