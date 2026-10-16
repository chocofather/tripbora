package com.tripbora.dto;

import com.tripbora.model.DestinationType;
import lombok.Data;
import java.math.BigDecimal;

/** Phase 1과 같은 국가/이름 fallback으로 읽은 linked destination의 snapshot 원본. */
@Data
public class CourseDestinationSnapshotDto {
    private Long destinationId;
    private DestinationType type;
    private Long countryId;
    private Long regionId;
    private String regionName;
    private String placeName;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
