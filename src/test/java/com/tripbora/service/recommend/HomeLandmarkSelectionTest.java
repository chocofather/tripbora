package com.tripbora.service.recommend;

import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.repository.recommend.DestinationRecommendMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static com.tripbora.config.i18n.SupportedLanguage.KOREAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeLandmarkSelectionTest {
    private final DestinationRecommendMapper mapper = mock(DestinationRecommendMapper.class);
    private final DestinationRecommendService service = new DestinationRecommendService(mapper,
            mock(DestinationService.class), mock(ReferenceNameLocalizationService.class));

    @Test
    void repeatedLoadsBalanceBothScopesWithoutDuplicatesAndVarySelectionAndFirstCard() {
        var candidates = new ArrayList<SeasonDestinationDto>();
        LongStream.rangeClosed(1, 60).forEach(id -> candidates.add(candidate(id, id <= 30)));
        when(mapper.findHomeLandmarkCandidates("랜드마크")).thenReturn(candidates);
        when(mapper.findByCategoryName(eq("랜드마크"), eq(18), anyList()))
                .thenAnswer(call -> {
                    List<Long> ids = call.getArgument(2);
                    return ids.stream().map(id -> candidate(id, id <= 30)).toList();
                });
        Set<List<Long>> combinations = new HashSet<>();
        Set<Long> firstCards = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            var result = service.findHomeLandmarks(KOREAN);
            assertThat(result).hasSize(18);
            assertThat(result.stream().map(SeasonDestinationDto::getId)).doesNotHaveDuplicates();
            assertThat(result.stream().filter(SeasonDestinationDto::isDomestic)).hasSize(9);
            combinations.add(result.stream().map(SeasonDestinationDto::getId).toList());
            firstCards.add(result.get(0).getId());
        }
        assertThat(combinations).hasSizeGreaterThan(1);
        assertThat(firstCards).hasSizeGreaterThan(1);
        verify(mapper, times(30)).findByCategoryName(eq("랜드마크"), eq(18), anyList());
    }

    @Test
    void sparseScopeIsFilledFromTheOtherAndEmptyCandidatesSkipCardQuery() {
        var candidates = new ArrayList<SeasonDestinationDto>();
        candidates.add(candidate(1L, true));
        candidates.add(candidate(1L, true));
        LongStream.rangeClosed(2, 5).forEach(id -> candidates.add(candidate(id, false)));
        when(mapper.findHomeLandmarkCandidates("랜드마크")).thenReturn(candidates);
        when(mapper.findByCategoryName(eq("랜드마크"), eq(18), anyList()))
                .thenAnswer(call -> {
                    List<Long> ids = call.getArgument(2);
                    return ids.stream().map(id -> candidate(id, id == 1)).toList();
                });
        assertThat(service.findHomeLandmarks(KOREAN)).extracting(SeasonDestinationDto::getId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        when(mapper.findHomeLandmarkCandidates("랜드마크")).thenReturn(List.of());
        assertThat(service.findHomeLandmarks(KOREAN)).isEmpty();
        verify(mapper, times(1)).findByCategoryName(anyString(), anyInt(), anyList());
    }

    private static SeasonDestinationDto candidate(long id, boolean domestic) {
        var dto = new SeasonDestinationDto();
        dto.setId(id);
        dto.setDomestic(domestic);
        return dto;
    }
}
