package com.tripbora.dto;

import lombok.Data;

/** 페이지의 linked stop 지역 경로를 일괄 조회한 표시 전용 metadata. */
@Data
public class CourseCatalogRegionDto {
    private Long courseId;
    private Long stopId;
    private Long regionId;
    private Long parentId;
    private String regionCode;
    private boolean countryNode;
    private String regionName;
}
