package com.tripbora.service.travelinfo;

import com.tripbora.dto.HomeFestivalCandidate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 전체 축제 목록과 분리된 메인 전용 추천 기준. 날짜 양 끝을 행사 기간에 포함한다. */
final class HomeFestivalSelection {
    // 2026-10-15 로컬 백업의 8개 기간은 2~24일. 현재 장기 행사 분포는 별도 확인이 필요하다.
    // 한 계절(90일)까지 우선하며, 반년(180일) 이상은 상시·장기 행사로 후순위에 둔다.
    private static final int SEASONAL_MAX_DAYS = 90;
    private static final int LONG_EVENT_MIN_DAYS = 180;
    private static final int UPCOMING_DAYS = 30;

    private HomeFestivalSelection() {
    }

    static List<HomeFestivalCandidate> select(List<HomeFestivalCandidate> candidates, LocalDate today, int limit) {
        Comparator<HomeFestivalCandidate> order = Comparator
                .comparingInt((HomeFestivalCandidate candidate) -> priority(candidate, today))
                // 진행 행사는 종료 임박순, 예정 행사는 시작일순.
                .thenComparing(candidate -> candidate.startDate().isAfter(today)
                        ? candidate.startDate() : candidate.endDate())
                .thenComparing(HomeFestivalCandidate::startDate, Comparator.reverseOrder())
                .thenComparing(HomeFestivalCandidate::infoId)
                .thenComparing(HomeFestivalCandidate::periodId);
        Map<Long, HomeFestivalCandidate> unique = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> !candidate.endDate().isBefore(today)
                        && !candidate.endDate().isBefore(candidate.startDate()))
                .sorted(order)
                .forEach(candidate -> unique.putIfAbsent(candidate.infoId(), candidate));
        return unique.values().stream().limit(limit).toList();
    }

    private static int priority(HomeFestivalCandidate candidate, LocalDate today) {
        long days = ChronoUnit.DAYS.between(candidate.startDate(), candidate.endDate()) + 1;
        if (candidate.startDate().isAfter(today.plusDays(UPCOMING_DAYS))) {
            return 4;
        }
        // 시작이 임박해도 연중·장기 행사는 계절·중장기 행사보다 앞세우지 않는다.
        if (days >= LONG_EVENT_MIN_DAYS) {
            return 3;
        }
        if (candidate.startDate().isAfter(today)) {
            return 1;
        }
        return days <= SEASONAL_MAX_DAYS ? 0 : 2;
    }
}
