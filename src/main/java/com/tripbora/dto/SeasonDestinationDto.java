package com.tripbora.dto;

import lombok.Data;

@Data
public class SeasonDestinationDto {
    private Long id;
    private String name;
    private String imageUrl;
    private Long regionId;
    private String regionName;
    // 직계 상위 지역 (country_categories.parent_id). 최상위 지역이면 null 이다.
    private Long parentRegionId;
    private String parentRegionName;
    private String season;
    // 메인 랜드마크 후보의 국내/해외 균형 선정용. 지역 번호 대신 KR 지역 트리로 판별한다.
    private boolean domestic;

    // 태그/카테고리 정보
    private Long categoryId;
    private String categoryName;

    // 카드 썸네일 (여행지 업로드 사진이 아니면 null → imageUrl 을 쓴다), 후보 srcset (폭은 실제 픽셀)
    private String cardImageUrl;
    private String cardImageSrcset;
    // 대표 이미지가 공공누리 제3유형(변경금지)이면 true → 카드 썸네일 대신 원본을 잘리지 않게(contain) 그린다
    private boolean imageNoDerivatives;
    // 메인 랜드마크의 3:4 아치 칸을 cover 로 채울 때 사진 폭 배율 (img sizes 에 곱한다)
    private double cardImageCoverScale = 1;


}
