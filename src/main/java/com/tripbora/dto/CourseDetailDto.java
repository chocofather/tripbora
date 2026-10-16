package com.tripbora.dto;

import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

@Data
public class CourseDetailDto {
    private Long id;
    private Long userId;
    private String title;
    private String titleSourceLanguage;
    private String content;
    private String courseKind;
    private String summary;
    private String contentSourceLanguage;
    private String nickname;
    /** 최종 탈퇴(DEACTIVATED) 회원이면 true. 화면은 nickname 대신 공통 문구를 쓴다. */
    private boolean writerWithdrawn;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Integer views;
    private List<CourseStopDto> stops;
    private List<CourseDayDto> days;
    private List<String> countryNames;
    private List<CourseThemeDto> themes = List.of();
    private String coverImageUrl;
    private String representativeImageUrl;
    private boolean myCourse;
    private boolean bookmarked;
    private boolean translationAvailable;

    public String getCountryLabel() {
        return CourseCountryLabel.of(countryNames);
    }
}
