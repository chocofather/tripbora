package com.tripbora.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HomePopularCourseDto {

    private Long courseId;
    private String title;
    private String nickname;
    /** 최종 탈퇴(DEACTIVATED) 회원이면 true. 화면은 nickname 대신 공통 문구를 쓴다. */
    private boolean writerWithdrawn;
    private int views;
    private int totalDestinationCount;
    /** 표지 또는 Day 1의 첫 연결 여행지 사진. 없으면 서비스의 기본 이미지를 사용한다. */
    private String representativeImageUrl;
    private List<String> previewDestinationNames = new ArrayList<>();
    private List<String> previewImageUrls = new ArrayList<>();

    public String getDetailUrl() {
        return "/course/" + courseId;
    }

    public String getRoutePreview() {
        return String.join(" → ", previewDestinationNames);
    }

    public int getRemainingDestinationCount() {
        return Math.max(totalDestinationCount - previewDestinationNames.size(), 0);
    }
}
