package com.tripbora.model;

import lombok.Data;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalTime;

@Data
public class CourseItem {
    private Long id;
    private Long courseDayId;
    private Integer displayOrder;
    private String itemType;
    private Long destinationId;
    private Long countryId;
    private String placeName;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalTime startTime;
    private LocalTime endTime;
    private String memo;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
