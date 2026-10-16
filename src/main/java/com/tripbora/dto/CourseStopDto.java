package com.tripbora.dto;

import lombok.Data;
import java.math.BigDecimal;

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
    private String googlePlaceId;
    private Integer visitOrder;
    private String name;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String shortDescription;
    private Long regionId;
    private String regionName;
    private Long countryId;
    private String countryName;
    private String countryCode;
    private String locationLabel;
    private String imageUrl;
    // 대표 이미지가 공공누리 제3유형(변경금지)이면 true → 잘리지 않게(contain) 그린다
    private boolean imageNoDerivatives;

    public String getGoogleMapsUrl() {
        return CourseGoogleMapsUrl.from(this);
    }

    public String getLocationLabel() {
        if (locationLabel != null && !locationLabel.isBlank()) return locationLabel;
        if (destinationId == null && address != null && !address.isBlank()) return address.strip();
        return CourseLocationLabel.join(countryName, regionName);
    }
}
