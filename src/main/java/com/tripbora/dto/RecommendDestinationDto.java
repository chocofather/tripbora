package com.tripbora.dto;

import lombok.Data;

@Data
public class RecommendDestinationDto {
    private Long id;            // 여행지 id
    private String name;        // 여행지명 (destination_translations.name)
    private String imageUrl;    // 대표 이미지 (destination_images)
    private Long regionId;      // 지역 id (locale-independent)
    private String regionName;  // 지역명 (country_categories.region_name)
    private Long categoryId;    // 카테고리 id
    private int views;          // 조회수 (destinations.views)
    private int bookmarkCount;  // 북마크 수
    private String cardImageUrl;    // 카드 썸네일 (여행지 업로드 사진이 아니면 null → imageUrl 을 쓴다)
    private String cardImageSrcset; // 카드 썸네일 후보 (img srcset, 폭은 실제 픽셀)
    private boolean imageNoDerivatives; // 대표 이미지가 공공누리 제3유형(변경금지) → 카드 썸네일 대신 원본을 contain 으로
    private int cardPositionX = 50;
    private int cardPositionY = 50;
    private Integer sourceImageWidth;
    private Integer sourceImageHeight;
}
