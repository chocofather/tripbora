package com.tripbora.service.travelinfo;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.HomeFestivalDto;
import com.tripbora.dto.HomeFestivalCandidate;
import com.tripbora.model.FestivalInfo;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HomeFestivalServiceTest {
    private final TravelInfoMapper mapper = mock(TravelInfoMapper.class);
    private final TravelInfoService common = mock(TravelInfoService.class);
    private final FestivalInfoMapper festivalMapper = mock(FestivalInfoMapper.class);
    private final HomeFestivalRegionService regions = mock(HomeFestivalRegionService.class);
    private final FestivalDetailService service = new FestivalDetailService(common, mapper,
            festivalMapper, new FestivalInfoLocalizationService(festivalMapper), regions);

    @Test
    void applicationDateControlsSelectionAndBadgesAndBatchLocalizationPreservesRecommendationOrder() {
        LocalDate today = LocalDate.of(2035, 1, 1);
        var ongoing = festival(9L, today, today);
        var upcoming = festival(2L, today.plusDays(1), today.plusDays(4));
        when(mapper.findHomeFestivalCandidates(today)).thenReturn(List.of(
                new HomeFestivalCandidate(2L, 20L, upcoming.getStartDate(), upcoming.getEndDate()),
                new HomeFestivalCandidate(9L, 90L, ongoing.getStartDate(), ongoing.getEndDate())));
        when(mapper.findHomeFestivals(List.of(90L, 20L))).thenReturn(List.of(upcoming, ongoing));
        when(regions.resolveLocations(List.of(ongoing, upcoming), SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(9L, "Seoul Jongno-gu", 2L, "Seoul Jongno-gu"));

        var result = service.getHomeFestivals(today, SupportedLanguage.ENGLISH);

        assertThat(result).containsExactly(ongoing, upcoming);
        assertThat(result).extracting(HomeFestivalDto::getEventStatus).containsExactly("ongoing", "upcoming");
        assertThat(result).extracting(HomeFestivalDto::getLocation).containsExactly("Seoul Jongno-gu", "Seoul Jongno-gu");
        verify(common).localizePublicList(any(), eq(SupportedLanguage.ENGLISH));
        verify(regions).resolveLocations(List.of(ongoing, upcoming), SupportedLanguage.ENGLISH);
        verify(mapper, never()).incrementPublicViews(any());
    }

    @Test
    void emptySelectionSkipsTranslationAndDetailQueries() {
        LocalDate today = LocalDate.of(2026, 10, 2);
        when(mapper.findHomeFestivalCandidates(today)).thenReturn(List.of());
        assertThat(service.getHomeFestivals(today, SupportedLanguage.KOREAN)).isEmpty();
        verifyNoInteractions(common, festivalMapper, regions);
        verify(mapper, never()).findHomeFestivals(any());
    }

    private HomeFestivalDto festival(long id, LocalDate start, LocalDate end) {
        var dto = new HomeFestivalDto();
        dto.setId(id);
        dto.setStartDate(start);
        dto.setEndDate(end);
        var info = new FestivalInfo();
        info.setInfoId(id);
        info.setAddress("서울 종로구");
        dto.setFestivalInfo(info);
        return dto;
    }
}
