package com.tripbora.dto;

import com.tripbora.web.CourseUrls;
import lombok.Data;

import java.util.List;

/** 공개 코스 목록의 한 카드. 국가와 테마는 페이지 조회 뒤 일괄 조합한다. */
@Data
public class CourseCardDto {
    private Long id;
    private String title;
    private String courseKind;
    private Long userId;
    private String nickname;
    private boolean writerWithdrawn;
    private String representativeImageUrl;
    private boolean imageNoDerivatives;
    private List<String> countryNames = List.of();
    private List<CourseThemeDto> themes = List.of();
    private Integer dayCount;
    private String regionSummary;
    private String durationLabel = "";
    private int views;

    public String getCountryLabel() {
        return CourseCountryLabel.of(countryNames);
    }

    public String getRegionSummary() {
        return regionSummary == null || regionSummary.isBlank() ? getCountryLabel() : regionSummary;
    }

    public String getDetailUrl() {
        return CourseUrls.detail(id);
    }
}
