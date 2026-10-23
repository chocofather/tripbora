package com.tripbora.dto;

import lombok.Data;

@Data
public class DestinationDto {

    private Long id;
    private String name;
    private String thumbnailPath;   // 대표 이미지 원본 주소
    private int cardPositionX = 50;
    private int cardPositionY = 50;
    private String cardImageUrl;    // 목록 카드 썸네일 (여행지 업로드 사진이 아니면 null → thumbnailPath 를 쓴다)
    private String cardImageSrcset; // 목록 카드 썸네일 후보 (img srcset, 폭은 실제 픽셀)
    private double cardImageCoverScale = 1; // 4:3 카드를 cover 로 채울 때 사진 폭 배율 (img sizes 에 곱한다)
    private boolean imageNoDerivatives;     // 대표 이미지가 공공누리 제3유형(변경금지) → 카드 썸네일 대신 원본을 contain 으로
    private String regionName;
    private boolean bookmarked; //  북마크 여부
    private int commentCount; // 댓글 수


    private String shortDescription;
}
