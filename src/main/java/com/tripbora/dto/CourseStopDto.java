package com.tripbora.dto;

import lombok.Data;

@Data
public class CourseStopDto {
    private Long courseDestinationId;
    private Long itemId;
    private Long courseDayId;
    private String itemType;
    private String startTime;
    private String endTime;
    private String memo;
    private Long destinationId;
    private Integer visitOrder;
    private String name;
    private String shortDescription;
    private Long regionId;
    private String regionName;
    private Long countryId;
    private String countryName;
    private String imageUrl;
    // 대표 이미지가 공공누리 제3유형(변경금지)이면 true → 잘리지 않게(contain) 그린다
    private boolean imageNoDerivatives;
}
