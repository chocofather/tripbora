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
    private String regionSummary;
    private List<CourseThemeDto> themes = List.of();
    private String coverImageUrl;
    private String representativeImageUrl;
    private boolean myCourse;
    private boolean bookmarked;
    private boolean translationAvailable;

    public String getCountryLabel() {
        return CourseCountryLabel.of(countryNames);
    }

    public String getRegionSummary() {
        return regionSummary == null || regionSummary.isBlank() ? getCountryLabel() : regionSummary;
    }

    public int getDayCount() {
        return days == null ? 1 : days.stream().map(CourseDayDto::getDayNumber)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(1);
    }

    public String getDurationLabel() {
        int count = getDayCount();
        return count <= 1 ? "당일" : (count - 1) + "박 " + count + "일";
    }
}
