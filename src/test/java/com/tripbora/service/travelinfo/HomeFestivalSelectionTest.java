package com.tripbora.service.travelinfo;

import com.tripbora.dto.HomeFestivalCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class HomeFestivalSelectionTest {
    private final LocalDate today = LocalDate.of(2026, 12, 31);

    @Test
    void prioritizesSeasonalOngoingThenNearUpcomingThenMediumThenLongAndDistant() {
        var ending = candidate(1, today.minusDays(5), today);
        var recent = candidate(2, today.minusDays(1), today.plusDays(3));
        var older = candidate(3, today.minusDays(10), today.plusDays(3));
        var tomorrow = candidate(4, today.plusDays(1), today.plusDays(4));
        var later = candidate(5, today.plusDays(30), today.plusDays(32));
        var medium = candidate(6, today.minusDays(100), today.plusDays(10));
        var annual = candidate(7, today.minusDays(300), today.plusDays(64));
        var distant = candidate(8, today.plusDays(31), today.plusDays(35));
        var ended = candidate(9, today.minusDays(10), today.minusDays(1));
        assertThat(HomeFestivalSelection.select(List.of(annual, distant, medium, later, ended,
                        older, tomorrow, recent, ending), today, 8))
                .containsExactly(ending, recent, older, tomorrow, later, medium, annual, distant);
    }

    @Test
    void durationBoundariesAndMultiplePeriodsAreHandledBeforeLimiting() {
        var seasonal = candidate(1, today.minusDays(89), today); // 90일
        var medium = candidate(2, today.minusDays(90), today); // 91일
        var mediumEnd = candidate(3, today.minusDays(178), today); // 179일
        var longEvent = candidate(4, today.minusDays(179), today); // 180일
        var duplicateUpcoming = new HomeFestivalCandidate(1L, 99L, today.plusDays(1), today.plusDays(2));
        var invalid = candidate(5, today.plusDays(2), today.plusDays(1));
        assertThat(HomeFestivalSelection.select(List.of(longEvent, mediumEnd, medium, duplicateUpcoming,
                        seasonal, invalid), today, 8))
                .containsExactly(seasonal, medium, mediumEnd, longEvent);
        assertThat(HomeFestivalSelection.select(List.of(duplicateUpcoming, seasonal, medium), today, 2))
                .containsExactly(seasonal, medium);
        assertThat(HomeFestivalSelection.select(List.of(), today, 8)).isEmpty();
    }

    @Test
    void lowerPrioritiesFillOnlyRemainingSlotsAndKstNewYearControlsTheBoundary() {
        LocalDate newYear = new com.tripbora.service.destination.DestinationViewClock(
                java.time.Clock.fixed(java.time.Instant.parse("2026-12-31T15:00:00Z"),
                        java.time.ZoneOffset.UTC)).today();
        var startsToday = candidate(1, newYear, newYear.plusDays(2));
        var endedYesterday = candidate(2, newYear.minusDays(3), newYear.minusDays(1));
        var startsTomorrow = candidate(3, newYear.plusDays(1), newYear.plusDays(2));
        var candidates = new ArrayList<>(List.of(startsTomorrow, endedYesterday, startsToday));
        for (long id = 10; id < 20; id++) {
            candidates.add(candidate(id, newYear.minusDays(300), newYear.plusDays(64)));
        }
        var result = HomeFestivalSelection.select(candidates, newYear, 8);
        assertThat(newYear).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(result).hasSize(8).doesNotContain(endedYesterday);
        assertThat(result.subList(0, 2)).containsExactly(startsToday, startsTomorrow);
        assertThat(result.stream().map(HomeFestivalCandidate::infoId)).doesNotHaveDuplicates();
    }

    @Test
    void upcomingYearRoundEventAlsoRemainsBehindCurrentMediumAndNearSeasonalEvents() {
        var annual = candidate(1, today.plusDays(1), today.plusDays(365));
        var medium = candidate(2, today.minusDays(100), today.plusDays(5));
        var seasonal = candidate(3, today.plusDays(2), today.plusDays(7));
        assertThat(HomeFestivalSelection.select(List.of(annual, medium, seasonal), today, 8))
                .containsExactly(seasonal, medium, annual);
    }

    private HomeFestivalCandidate candidate(long id, LocalDate start, LocalDate end) {
        return new HomeFestivalCandidate(id, id, start, end);
    }
}
