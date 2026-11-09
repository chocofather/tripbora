package com.tripbora.dto;

/** 작성자 전용 사진 관리 응답. 공개 댓글 응답에는 심사 정보를 넣지 않는다. */
public record ReviewImageDto(Long id, String imageUrl, String galleryStatus, String reviewReason) {
}
