package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class Destination {
    private Long id; // 여행지번호
    private BigDecimal latitude; // 위도
    private BigDecimal longitude; // 경도
    private String googlePlaceId; // 해외 지도(Maps Embed API) 장소 식별자. 선택 값
    private Timestamp createdAt; // 생성일
    private Integer views; // 조회수
    private DestinationSeason season; // 시즌
    private Long userID; // 회원번호
    private Long regionId; // 지역 카테고리 번호
    private DestinationType type;
    /** 일반 공개 조회에 노출할지 여부. 등록/정보 수정과 별도로 관리한다. */
    private boolean publicVisible = true;

    /** 등록 출처: ADMIN(관리자 직접 입력), KTO_TOURAPI(TourAPI 가져오기). */
    private String sourceType;
    /** TourAPI contentId. 같은 출처 안에서 여행지 중복 판정의 유일한 기준이다. */
    private String externalContentId;

    // ✅ 추가: 다국어 표시용
    private String name; // destinationTranslation join용
    private String description; // destinationTranslation join용
    private String shortDescription; // destinationTranslation join용
    private String regionName; // country_categories.region_name 조인 결과 담기용

    private String thumbnailPath; // 메인 이미지 URL
    // Destination.java
    private List<DestinationImage> images = new ArrayList<>();




}
