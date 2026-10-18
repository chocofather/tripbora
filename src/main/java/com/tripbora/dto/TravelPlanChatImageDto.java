package com.tripbora.dto;

import com.tripbora.model.TravelPlanChatImage;

/** 저장 키/원본 파일명 대신 권한 검사가 있는 서버 URL만 외부에 전달한다. */
public record TravelPlanChatImageDto(Long id, String url, int width, int height) {
    public static TravelPlanChatImageDto of(Long planId, TravelPlanChatImage image) {
        return new TravelPlanChatImageDto(image.getId(),
                "/travel-plans/" + planId + "/chat/messages/" + image.getMessageId()
                        + "/images/" + image.getId(), image.getWidth(), image.getHeight());
    }
}
