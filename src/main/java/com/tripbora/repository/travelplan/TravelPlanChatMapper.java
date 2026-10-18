package com.tripbora.repository.travelplan;

import com.tripbora.model.TravelPlanChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 방 채팅 전용 매퍼.
 * travel_plan_chat_messages 와 travel_plan_chat_read_positions 두 테이블만 다룬다.
 *
 * <p>목록 조회는 (travel_plan_id, id) 인덱스를 그대로 타도록 id 기준으로만 자른다.
 * OFFSET 으로 뒤 페이지를 세지 않는다.
 *
 * <p>보는 사람이 그 방에 들어오기 전의 대화는 여기서 아예 읽지 않는다.
 * 읽어 온 뒤 화면에서 가리는 것이 아니라 SQL 조건으로 자른다.
 */
@Mapper
public interface TravelPlanChatMapper {

    /**
     * 가장 최근 메시지부터 limit 건. 화면에 그릴 때는 호출한 쪽에서 순서를 뒤집는다.
     *
     * @param joinedAt 보는 사람이 이 방에 들어온 시각. 그 앞의 대화는 오지 않는다.
     */
    List<TravelPlanChatMessage> findRecentMessages(@Param("travelPlanId") Long travelPlanId,
                                                   @Param("joinedAt") Timestamp joinedAt,
                                                   @Param("limit") int limit);

    /**
     * 어떤 메시지보다 앞선 것들 중 최근 limit 건. [이전 메시지 보기] 가 쓴다.
     * 위로 아무리 올려도 들어오기 전의 대화까지는 올라가지 않는다.
     */
    List<TravelPlanChatMessage> findMessagesBefore(@Param("travelPlanId") Long travelPlanId,
                                                   @Param("beforeMessageId") Long beforeMessageId,
                                                   @Param("joinedAt") Timestamp joinedAt,
                                                   @Param("limit") int limit);

    /** 메시지 1건. 방 소속 조건을 함께 걸어 다른 방의 메시지를 집을 수 없게 한다. */
    TravelPlanChatMessage findByIdAndPlanId(@Param("id") Long id,
                                            @Param("travelPlanId") Long travelPlanId);

    /** 이 방의 마지막 메시지 id. 없으면 null. 읽음 위치를 최신으로 옮길 때 쓴다. */
    Long findLatestMessageId(@Param("travelPlanId") Long travelPlanId);

    /** 메시지 1건 등록. created_at 등 DB DEFAULT 는 그대로 둔다. */
    int insertMessage(TravelPlanChatMessage message);

    TravelPlanChatMessage findByRequestId(@Param("travelPlanId") Long travelPlanId,
            @Param("senderMemberId") Long senderMemberId, @Param("clientRequestId") String clientRequestId);

    /**
     * 지움 표시. 행을 지우지 않고 deleted_at 만 채운다.
     * 보낸 사람 조건을 SQL 에 함께 걸어, 남의 메시지는 조건이 맞지 않아 한 건도 바뀌지 않는다.
     * 이미 지워진 메시지도 조건에서 빠져 0 이 돌아온다.
     *
     * @return 1 이면 방금 지워졌고, 0 이면 내 것이 아니거나 이미 지워져 있었다.
     */
    int markMessageDeleted(@Param("id") Long id,
                           @Param("travelPlanId") Long travelPlanId,
                           @Param("senderMemberId") Long senderMemberId);

    /** 이 사람이 마지막으로 읽은 메시지 id. 읽은 적이 없으면 null. */
    Long findLastReadMessageId(@Param("travelPlanId") Long travelPlanId,
                               @Param("memberId") Long memberId);

    /**
     * 읽음 위치 저장. (travel_plan_id, member_id) UNIQUE 를 그대로 쓴다.
     * 뒤로 되돌아가지 않도록 이미 더 앞을 읽은 경우에는 그대로 둔다.
     */
    int upsertReadPosition(@Param("travelPlanId") Long travelPlanId,
                           @Param("memberId") Long memberId,
                           @Param("lastReadMessageId") Long lastReadMessageId);

    /**
     * 아직 읽지 않은 메시지 수.
     * 내가 보낸 것과 지워진 것은 세지 않는다.
     * 볼 수 없는 대화는 세지도 않으므로 목록과 같은 기준으로 자른다.
     *
     * @param joinedAt          보는 사람이 이 방에 들어온 시각
     * @param lastReadMessageId 읽은 적이 없으면 null
     */
    int countUnread(@Param("travelPlanId") Long travelPlanId,
                    @Param("memberId") Long memberId,
                    @Param("joinedAt") Timestamp joinedAt,
                    @Param("lastReadMessageId") Long lastReadMessageId);
}
