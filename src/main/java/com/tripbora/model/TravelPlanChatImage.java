package com.tripbora.model;

import lombok.Data;

@Data
public class TravelPlanChatImage {
    private Long id;
    private Long messageId;
    private String storageKey;
    private String contentType;
    private long byteSize;
    private int width;
    private int height;
    private int sortOrder;
}
