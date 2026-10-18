package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * travel_plan_chat_messages 한 행.
 *
 * <p>지운 메시지도 행을 지우지 않고 deleted_at 만 채운다.
 * 행을 지우면 그 자리에 있던 대화가 통째로 당겨져 앞뒤 문맥이 어긋난다.
 */
@Data
@NoArgsConstructor
public class TravelPlanChatMessage {
    private Long id;
    private Long travelPlanId;
    /** 보낸 사람의 방 안 id. 계정 id 가 아니다. */
    private Long senderMemberId;
    private TravelPlanChatMessageType messageType;
    private String content;
    private String clientRequestId;
    /** 영속 컬럼이 아닌 일괄 조회 결과. 기존 텍스트 메시지는 빈 목록이다. */
    private java.util.List<com.tripbora.dto.TravelPlanChatImageDto> images = java.util.List.of();
    private String systemEventType;
    /** 채워져 있으면 지워진 메시지다. */
    private Timestamp deletedAt;
    private Timestamp createdAt;

    /**
     * travel_plan_members 에서 함께 읽어 오는 방 안 표시 이름.
     * 이 테이블의 컬럼이 아니라 조회 결과에만 실린다.
     */
    private String senderDisplayName;
}
