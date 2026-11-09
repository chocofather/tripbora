package com.tripbora.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CommentDto {
    private Long id;
    private String content;
    private String sourceLanguage;
    private boolean translationAvailable;
    /** 댓글 첨부 사진 (destination_comment_images, display_order 오름차순, 최대 5장) */
    private List<String> imageUrls = List.of();
    private List<CommentImageDto> photos = List.of(); // 공개 사진 ID·URL. 심사/동의 내부 정보는 포함하지 않는다.
    private String createdAt;
    private String updatedAt;
    private boolean likedByMe; // 현재 유저가 좋아요 눌렀는지
    private int likes;
    private Long parentCommentId;
    private Integer rating;
    private WriterDto writer;

    private boolean myComment; //  현재 로그인한 사용자가 이 댓글의 작성자인가?
    private boolean admin;    // 관리자 여부
    private boolean moderated; // 관리자 조치로 숨겨진 댓글이면 true

    @JsonProperty("isLoggedIn")
    private Boolean isLoggedIn;

}
