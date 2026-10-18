package com.tripbora.dto;

import com.tripbora.model.TravelPlanChatMessage;
import com.tripbora.model.TravelPlanPoll;

import java.util.List;

/**
 * 채팅창에 시간 순서대로 놓이는 한 줄.
 *
 * <p>대화와 "새 투표를 만들었어요" 알림 두 가지가 섞여 온다.
 * 두 가지는 DB 에서 계속 따로 산다(travel_plan_chat_messages / travel_plan_polls).
 * 여기서만 읽기용으로 한 줄기로 합친다.
 *
 * <p>계정 정보는 싣지 않는다. 방 안에서만 뜻이 있는 memberId 와 그 방의 표시 이름까지다.
 *
 * @param createdAt 그 일이 있었던 시각(epoch millis)
 */
public record TravelPlanChatTimelineItemDto(
        String type,
        Long createdAt,

        // type = MESSAGE
        Long messageId,
        Long memberId,
        String displayName,
        String content,
        boolean deleted,
        /** 이 메시지에 달린 반응. 없으면 빈 목록이고 지워진 메시지에는 늘 비어 있다. */
        List<TravelPlanChatReactionDto> reactions,

        // type = POLL_CREATED
        Long pollId,
        String creatorDisplayName,
        String pollTitle,
        List<TravelPlanChatImageDto> images) {

    public static final String MESSAGE = "MESSAGE";
    public static final String POLL_CREATED = "POLL_CREATED";

    /** 이름을 알 수 없는 경우. 실제로는 멤버 행을 지우지 않아 거의 나오지 않는다. */
    private static final String UNKNOWN = "알 수 없음";

    public static TravelPlanChatTimelineItemDto ofMessage(TravelPlanChatMessage message) {
        return ofMessage(message, List.of());
    }

    /**
     * 대화 한 줄과 거기 달린 반응.
     *
     * <p>지워진 메시지에는 반응을 싣지 않는다. 남아 있는 행을 지우지는 않지만
     * (지움은 tombstone 이라 내용도 행도 그대로 둔다) 밖으로 내보내지 않는다.
     */
    public static TravelPlanChatTimelineItemDto ofMessage(
            TravelPlanChatMessage message, List<TravelPlanChatReactionDto> reactions) {
        boolean deleted = message.getDeletedAt() != null;
        return new TravelPlanChatTimelineItemDto(
                MESSAGE,
                message.getCreatedAt() == null ? null : message.getCreatedAt().getTime(),
                message.getId(),
                message.getSenderMemberId(),
                nameOr(message.getSenderDisplayName()),
                // 지워진 메시지의 원문은 여기서 끊는다.
                deleted ? null : message.getContent(),
                deleted,
                deleted || reactions == null ? List.of() : reactions,
                null, null, null,
                deleted ? List.of() : message.getImages());
    }

    /**
     * 투표가 만들어졌다는 알림.
     * 투표의 선택지나 표 수는 싣지 않는다. 그것은 투표 센터가 따로 읽는다.
     */
    public static TravelPlanChatTimelineItemDto ofPollCreated(TravelPlanPoll poll) {
        return new TravelPlanChatTimelineItemDto(
                POLL_CREATED,
                poll.getCreatedAt() == null ? null : poll.getCreatedAt().getTime(),
                null, null, null, null, false, List.of(),
                poll.getId(),
                nameOr(poll.getCreatedByDisplayName()),
                poll.getTitle(), List.of());
    }

    private static String nameOr(String displayName) {
        return displayName == null || displayName.isBlank() ? UNKNOWN : displayName;
    }
}
