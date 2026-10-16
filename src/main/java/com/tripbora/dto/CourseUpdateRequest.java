package com.tripbora.dto;

import lombok.Data;

import java.util.List;

@Data
public class CourseUpdateRequest {
    private org.springframework.web.multipart.MultipartFile coverImage;
    private boolean removeCoverImage;
    private Long countryId;
    private String title;
    private String content;
    private List<Long> destinationIds;
    private List<CourseDayForm> days;
    private List<Long> themeIds;
}
