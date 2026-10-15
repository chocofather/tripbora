package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;


@Data
@NoArgsConstructor
public class Course {
    private String coverImageUrl;
    private Long id; // 코스번호
    private Long userId; // 작성자 id
    private String courseKind;
    private String status;
    private Long countryId; // 코스 국가 id
    private String countryName; // 코스 국가명 (조회용)
    private String title; // 코스제목
    private String titleSourceLanguage = "und"; // 제목 원문 언어
    private String content; // 코스설명
    private String contentSourceLanguage = "und"; // 본문 원문 언어
    private Integer likes; // 좋아요 수 기본값 0
    private Timestamp createdAt; // 생성일
    private Timestamp updatedAt; // 수정일
    private Timestamp deletedAt; // 삭제일
    private Boolean deleted; // 삭제여부

}
