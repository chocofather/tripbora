package com.tripbora.repository.travelplan;

import com.tripbora.model.TravelPlanItem;
import com.tripbora.model.TravelPlanItemDetails;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalTime;

@Mapper
public interface TravelPlanItemMapper {

    /** DAY 의 A 일정 목록. display_order, id 오름차순. */
    List<TravelPlanItem> findByDayId(@Param("travelPlanDayId") Long travelPlanDayId);

    /**
     * 방 전체의 A 일정. 전체 상세 화면에서 DAY 수만큼 조회가 나가지 않도록 한 번에 읽는다.
     * 호출자가 travel_plan_day_id 로 묶어서 쓴다.
     */
    List<TravelPlanItem> findByPlanId(@Param("travelPlanId") Long travelPlanId);

    /** DAY 의 마지막 순서. 일정이 없으면 0 을 돌려준다. */
    int findMaxDisplayOrder(@Param("travelPlanDayId") Long travelPlanDayId);

    /** A 일정 1건 등록. version 등 DB DEFAULT 는 그대로 둔다. */
    int insertItem(TravelPlanItem item);

    /** 일정 1건. DAY 소속 조건을 함께 걸어 다른 DAY 의 itemId 를 섞을 수 없게 한다. */
    TravelPlanItem findByIdAndDayId(@Param("id") Long id,
                                    @Param("travelPlanDayId") Long travelPlanDayId);

    /**
     * 내용 수정. 낙관적 잠금이라 넘겨받은 version 이 그대로일 때만 반영된다.
     *
     * @return 1 이면 반영, 0 이면 그 사이 다른 변경이 있었거나 소속이 맞지 않는다.
     */
    int updateContent(@Param("id") Long id,
                      @Param("travelPlanDayId") Long travelPlanDayId,
                      @Param("content") String content,
                      @Param("version") Integer version);

    int updateDetails(@Param("id") Long id,
                      @Param("travelPlanDayId") Long travelPlanDayId,
                      @Param("content") String content,
                      @Param("tag") String tag,
                      @Param("startTime") LocalTime startTime,
                      @Param("version") Integer version);

    int updateStructuredDetails(@Param("id") Long id,
                                @Param("travelPlanDayId") Long travelPlanDayId,
                                @Param("content") String content,
                                @Param("tag") String tag,
                                @Param("startTime") LocalTime startTime,
                                @Param("details") TravelPlanItemDetails details,
                                @Param("version") Integer version);

    /** 일정 1건 삭제. DAY 소속까지 조건으로 건다. */
    int deleteByIdAndDayId(@Param("id") Long id,
                           @Param("travelPlanDayId") Long travelPlanDayId);

    /** 한 DAY 의 display_order 를 1..N 으로 다시 매긴다. 다른 DAY 는 건드리지 않는다. */
    int resequenceDisplayOrder(@Param("travelPlanDayId") Long travelPlanDayId);

    /** 같은 DAY 에서 바로 위 일정. 첫 일정이면 null. */
    TravelPlanItem findPreviousItem(@Param("travelPlanDayId") Long travelPlanDayId,
                                    @Param("displayOrder") Integer displayOrder);

    /** 같은 DAY 에서 바로 아래 일정. 마지막 일정이면 null. */
    TravelPlanItem findNextItem(@Param("travelPlanDayId") Long travelPlanDayId,
                                @Param("displayOrder") Integer displayOrder);

    /**
     * 순서만 바꾼다. 넘겨받은 version 이 그대로일 때만 반영되고 성공하면 version 이 1 오른다.
     *
     * @return 1 이면 반영, 0 이면 그 사이 다른 변경이 있었다.
     */
    int updateDisplayOrderWithVersion(@Param("id") Long id,
                                      @Param("travelPlanDayId") Long travelPlanDayId,
                                      @Param("displayOrder") Integer displayOrder,
                                      @Param("version") Integer version);

    /** 자리를 비켜 주는 이웃 일정의 순서 변경. 사용자가 들고 있던 version 이 없으므로 조건을 걸지 않는다. */
    int updateDisplayOrderById(@Param("id") Long id,
                               @Param("travelPlanDayId") Long travelPlanDayId,
                               @Param("displayOrder") Integer displayOrder);

    /**
     * 대안(B)을 A 자리로 끌어올린다.
     * row 를 새로 만들지 않고 기존 A 행의 내용만 바꾸므로 id 와 display_order 는 그대로다.
     * 작성자도 승격된 대안의 작성자로 함께 바뀐다.
     *
     * @return 1 이면 반영, 0 이면 그 사이 일정이 사라졌거나 소속이 맞지 않는다.
     */
    int promoteAlternativeContent(@Param("id") Long id,
                                  @Param("travelPlanDayId") Long travelPlanDayId,
                                  @Param("content") String content,
                                  @Param("tag") String tag,
                                  @Param("createdByMemberId") Long createdByMemberId);

    /**
     * 다른 DAY 로 옮긴다. content / tag / created_by_member_id 는 건드리지 않는다.
     *
     * @return 1 이면 반영, 0 이면 그 사이 다른 변경이 있었다.
     */
    int moveToDayWithVersion(@Param("id") Long id,
                             @Param("sourceDayId") Long sourceDayId,
                             @Param("targetDayId") Long targetDayId,
                             @Param("displayOrder") Integer displayOrder,
                             @Param("version") Integer version);
}
