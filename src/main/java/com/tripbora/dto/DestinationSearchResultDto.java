package com.tripbora.dto;

import lombok.Data;

@Data
public class DestinationSearchResultDto {
    private Long id; // 여행지 PK (상세 페이지 이동용)
    private String name; // 여행지명
    private String type; // Course Item의 표시용 타입. 저장 시에는 서버가 다시 조회한다.
    private String shortDescription; // 간략설명
    private String regionName; // 소속 지역명 (예: 종로구)
    private Long regionId;
    private String locationLabel; // Course 일정 row와 같은 실제 계층 기반 표시명
    private String parentRegionName; // 상위 지역명 (예: 서울)
    private Long countryId; // 실제 국가 카테고리 ID
    private String countryName; // 실제 국가명
    private String thumbnailUrl; // 대표 이미지
    private boolean imageNoDerivatives; // 대표 이미지가 공공누리 제3유형(변경금지) → 잘리지 않게(contain) 그린다

    // 검색 확장시, 북마크 여부·댓글 수·후기 등 필요하면 나중에 추가

}
