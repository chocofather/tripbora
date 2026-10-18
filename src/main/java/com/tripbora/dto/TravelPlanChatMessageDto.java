package com.tripbora.dto;

import com.tripbora.model.TravelPlanChatMessage;

/**
 * 화면으로 나가는 채팅 메시지 한 건.
 *
 * <p>계정 정보는 싣지 않는다. 방 안에서만 뜻이 있는 memberId 와 그 방의 표시 이름까지다.
 * 지워진 메시지는 내용을 아예 담지 않는다. 화면에서 가리는 것만으로는 원문이 그대로 나간다.
 *
 * @param createdAt 보낸 시각(epoch millis). 표시 형식은 보는 사람의 브라우저가 정한다.
 */
public record TravelPlanChatMessageDto(
        Long id,
        Long memberId,
        String displayName,
        String content,
        Long createdAt,
        boolean deleted,
        java.util.List<TravelPlanChatImageDto> images) {

    /** 이름을 알 수 없는 경우. 실제로는 멤버 행을 지우지 않아 거의 나오지 않는다. */
    private static final String UNKNOWN_SENDER = "알 수 없음";

    public static TravelPlanChatMessageDto of(TravelPlanChatMessage message) {
        boolean deleted = message.getDeletedAt() != null;
        String displayName = message.getSenderDisplayName();
        return new TravelPlanChatMessageDto(
                message.getId(),
                message.getSenderMemberId(),
                displayName == null || displayName.isBlank() ? UNKNOWN_SENDER : displayName,
                // 지워진 메시지의 원문은 여기서 끊는다.
                deleted ? null : message.getContent(),
                message.getCreatedAt() == null ? null : message.getCreatedAt().getTime(),
                deleted,
                deleted ? java.util.List.of() : message.getImages());
    }
}
