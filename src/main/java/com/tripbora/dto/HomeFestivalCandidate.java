package com.tripbora.dto;

import java.time.LocalDate;

/** 메인 추천의 기간 비교에 필요한 값만 조회한다. 이미지·번역·장소는 선정 후 일괄 조회한다. */
public record HomeFestivalCandidate(Long infoId, Long periodId, LocalDate startDate, LocalDate endDate) {
}
