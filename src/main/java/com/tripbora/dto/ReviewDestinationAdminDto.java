package com.tripbora.dto;

import lombok.Data;

/** 사진 상태는 공개 갤러리 노출 여부가 아닌 저장된 심사 상태 기준이다. */
@Data
public class ReviewDestinationAdminDto {
    private Long destinationId;
    private String destinationName;
    private String regionName;
    private String categoryNames;
    private long total;
    private long pending;
    private long approved;
    private long rejected;
    private long revoked;
    private long none;
    private long reviewCount;
    private long userCount;
}
