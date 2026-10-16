package com.tripbora.service.search;

import com.tripbora.repository.search.DestinationSearchMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DestinationSearchFilterTest {
    private final DestinationSearchMapper mapper = mock(DestinationSearchMapper.class);
    private final DestinationSearchService service = new DestinationSearchServiceImpl(mapper,
            mock(DestinationImageService.class), mock(CountryCategoryService.class), mock(ReferenceNameLocalizationService.class));

    @Test
    void emptyQueryWithoutFiltersDoesNotLoadDestinations() {
        assertThat(service.search("  ", 20, null, null, null)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidScopeAndNonpositiveIdsAreRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.search("성", 20, null, "invalid", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("성", 20, -1L, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("성", 20, null, null, 0L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void optionalFiltersAreIndependentAndLimitIsCapped() {
        for (String scope : new String[]{null, "domestic", "overseas"}) {
            when(mapper.searchFilteredDestinations("성", 20, null, scope, null)).thenReturn(List.of());
            assertThat(service.search(" 성 ", 999, null, scope, null)).isEmpty();
            verify(mapper).searchFilteredDestinations("성", 20, null, scope, null);
        }
        when(mapper.searchFilteredDestinations("", 20, 80L, "overseas", 90L)).thenReturn(List.of());
        assertThat(service.search("", 20, 80L, "overseas", 90L)).isEmpty();
        verify(mapper).searchFilteredDestinations("", 20, 80L, "overseas", 90L);
    }
}
