package com.tripbora.dto;

import lombok.Data;

/**
 * 관리자 여행지 목록 상단의 데이터 상태별 건수. 지금 범위·분류·지역·검색 조건 안에서 한 번의 집계 쿼리로 센다.
 */
@Data
public class AdminDestinationDataStatusCounts {
    private int total;
    private int hidden;
    private int missingImage;
    private int missingMainImage;
    private int missingCategory;
    private int missingTranslation;
    private int missingDescription;
}
