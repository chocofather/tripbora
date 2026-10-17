package com.tripbora.repository.travelplan;

import com.tripbora.dto.TravelPlanListItemDto;
import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TravelPlanMapper {

    /** 방 1건 등록. useGeneratedKeys 로 plan.id 가 채워진다. */
    int insertPlan(TravelPlan plan);

    /** 참여자 1건 등록. 방 생성 시에는 OWNER 를 넣는다. */
    int insertMember(TravelPlanMember member);

    /**
     * 여행 기간의 DAY 를 한 번에 등록한다.
     *
     * @param days day_number / plan_date 를 담은 목록. travel_plan_id 는 별도 파라미터를 쓴다.
     */
    int insertDays(@Param("travelPlanId") Long travelPlanId,
                   @Param("days") List<TravelPlanDay> days);

    /**
     * 현재 사용자가 참여 중인 방 목록. 정렬은 최근 활동 순.
     * 상태 값은 SQL 에 박지 않고 enum 이름을 그대로 넘긴다.
     *
     * <p>진행 중인 방은 보통 몇 건뿐이라 쪽을 나누지 않고 전부 읽는다.
     * (계속 쌓이는 완료된 여행만 {@link TravelPlanFinalMapper} 에서 쪽으로 끊는다)
     */
    List<TravelPlanListItemDto> findActivePlansByUserId(@Param("userId") Long userId,
                                                        @Param("planStatus") String planStatus,
                                                        @Param("memberStatus") String memberStatus);

    /** 방 1건. 상태 조건을 함께 걸어 ACTIVE 가 아닌 방이 새어 나가지 않게 한다. */
    TravelPlan findPlanByIdAndStatus(@Param("travelPlanId") Long travelPlanId,
                                     @Param("planStatus") String planStatus);

    /** 이 방에서의 현재 사용자 참여 정보. 없으면 접근 권한이 없다는 뜻이다. */
    TravelPlanMember findMemberByPlanAndUser(@Param("travelPlanId") Long travelPlanId,
                                             @Param("userId") Long userId,
                                             @Param("memberStatus") String memberStatus);

    /**
     * 참여 처리용 방 조회. 그 방 row 에 잠금을 걸어 동시 참여를 한 줄로 세운다.
     * 정원 계산과 INSERT 가 이 잠금 안에서 일어나야 8명을 넘길 수 없다.
     * 반드시 트랜잭션 안에서만 부른다.
     */
    TravelPlan findPlanByIdAndStatusForUpdate(@Param("travelPlanId") Long travelPlanId,
                                              @Param("planStatus") String planStatus);

    /**
     * 상태를 가리지 않는 참여 기록 1건.
     * LEFT / REMOVED 로 남아 있는 사람이 초대로 새 row 를 만들지 못하게 확인할 때 쓴다.
     */
    TravelPlanMember findAnyMemberByPlanAndUser(@Param("travelPlanId") Long travelPlanId,
                                                @Param("userId") Long userId);

    /**
     * 방 안에서 그 표시 이름을 이미 쓰고 있는 참여 기록 수.
     * 나갔던 사람이 쓰던 이름도 예약된 것으로 보아 새 참여자가 가져가지 못하게 한다
     * (uk_travel_plan_members_plan_display_name 과 같은 기준).
     */
    int countMembersByPlanAndDisplayName(@Param("travelPlanId") Long travelPlanId,
                                         @Param("displayName") String displayName);

    /**
     * 방에 지금 참여 중인 사람 수.
     * 투표의 "2 / 3명 투표" 에서 뒤의 숫자가 이 값이다. 나갔거나 내보내진 사람은 빠진다.
     */
    int countActiveMembers(@Param("travelPlanId") Long travelPlanId);

    /**
     * 방의 진행 상태를 옮긴다.
     *
     * <p>지금 상태가 기대한 그대로일 때만 반영된다.
     * 같은 방을 두 사람이 동시에 완료하려 해도 한 번만 성공한다.
     *
     * @param completed true 면 완료 시각(finalized_at)도 함께 남긴다
     * @return 1 이면 방금 이 호출이 옮겼고, 0 이면 그 사이 다른 변화가 있었다.
     */
    int updatePlanStatus(@Param("travelPlanId") Long travelPlanId,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus,
                         @Param("completed") boolean completed);

    /**
     * 방 하나를 실제로 지운다. 아무도 그 여행을 더 보지 않게 되었을 때만 부른다.
     *
     * <p>딸린 데이터(참여자·설정·초대·일정·대안·채팅·투표·최종본)는
     * 모두 travel_plans 를 향한 ON DELETE CASCADE 로 함께 사라진다.
     * 작성자 표시(created_by_member_id)처럼 SET NULL 인 자리도 참여자와 함께 정리된다.
     *
     * <p>상태 조건이 안전장치다. 부르는 쪽이 어느 상태의 방을 지우려는지 분명히 적어야 하고,
     * 그 사이 상태가 달라졌으면 0 이 돌아온다. 지금 이 문을 지나는 길은 둘이다 —
     * 아무도 보관하지 않게 된 완료본(COMPLETED)과, 방장이 접는 진행 중인 방(ACTIVE).
     *
     * @return 1 이면 방금 이 호출이 지웠고, 0 이면 그 사이 이미 사라졌거나 상태가 다르다.
     */
    int deletePlanByIdAndStatus(@Param("travelPlanId") Long travelPlanId,
                                @Param("planStatus") String planStatus);

    /**
     * 방에 지금 참여 중인 사람들. OWNER 가 먼저 오고 그 뒤는 참여한 순서다.
     * 화면에 필요한 컬럼만 읽고 users 는 건드리지 않는다.
     */
    List<TravelPlanMember> findActiveMembersByPlanId(@Param("travelPlanId") Long travelPlanId,
                                                     @Param("memberStatus") String memberStatus);

    /**
     * 최종본에 옮겨 적을 참여자. 화면용 조회와 달리 계정 번호까지 읽는다.
     *
     * <p>최종 명단의 user_id 로 "내 완료된 여행" 을 찾는다.
     * 여기서 읽지 않으면 NULL 로 저장되어 그 여행이 누구의 목록에도 나오지 않는다.
     */
    List<TravelPlanMember> findActiveMembersForSnapshot(
            @Param("travelPlanId") Long travelPlanId,
            @Param("memberStatus") String memberStatus);

    /**
     * 방 소속 참여자 1건. 다른 방의 memberId 를 섞어 넣어도 통과하지 못하게
     * 방 조건을 함께 건다. 상태는 가리지 않는다(이미 나간 사람도 읽힌다).
     */
    TravelPlanMember findMemberByPlanAndId(@Param("travelPlanId") Long travelPlanId,
                                           @Param("memberId") Long memberId);

    /**
     * 스스로 나가기. row 를 지우지 않고 상태만 바꾼다.
     * 나간 시각을 남기고 재참여 여지는 열어 둔다(rejoin_allowed = 1).
     * ACTIVE 인 MEMBER 일 때만 반영되므로 두 번 눌러도 한 번만 처리된다.
     *
     * @return 1 이면 반영, 0 이면 이미 나갔거나 조건이 맞지 않는다.
     */
    int markMemberLeft(@Param("id") Long id,
                       @Param("travelPlanId") Long travelPlanId,
                       @Param("fromStatus") String fromStatus,
                       @Param("toStatus") String toStatus,
                       @Param("role") String role);

    /**
     * OWNER 가 내보내기. 역시 row 를 지우지 않고 상태만 바꾼다.
     * 지금 가진 초대 링크만으로 되돌아오지 못하게 rejoin_allowed = 0 으로 둔다.
     * 재참여 허용은 다음 단계의 기능이다.
     *
     * @return 1 이면 반영, 0 이면 이미 빠졌거나 조건이 맞지 않는다.
     */
    int markMemberRemoved(@Param("id") Long id,
                          @Param("travelPlanId") Long travelPlanId,
                          @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus,
                          @Param("role") String role);

    /**
     * 방을 떠났던 사람을 원래 자리로 되돌린다.
     * 스스로 나간 사람(LEFT)과 OWNER 가 다시 받아 준 사람(REMOVED)이 같은 문장을 쓰며,
     * 어느 쪽이든 rejoin_allowed 가 살아 있어야 반영된다.
     * row 를 새로 만들지 않으므로 id / display_name / role 이 그대로 유지되고,
     * 그 사람이 쓴 일정·대안의 created_by_member_id 연결도 끊기지 않는다.
     *
     * @return 1 이면 반영, 0 이면 이미 돌아왔거나 재참여가 막힌 사람이다.
     */
    int reactivateMember(@Param("id") Long id,
                         @Param("travelPlanId") Long travelPlanId,
                         @Param("userId") Long userId,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus);

    /**
     * OWNER 가 내보낸 사람의 재참여를 다시 허용한다.
     * 상태는 REMOVED 그대로 두고 rejoin_allowed 만 올린다.
     * 실제 복귀는 본인이 유효한 초대 링크로 들어올 때 일어난다.
     *
     * @return 1 이면 반영, 0 이면 이미 허용됐거나 대상이 아니다.
     */
    int allowMemberRejoin(@Param("id") Long id,
                          @Param("travelPlanId") Long travelPlanId,
                          @Param("memberStatus") String memberStatus,
                          @Param("role") String role);

    /**
     * 방을 떠난 사람들의 기록. OWNER 의 "이전 참여자" 목록에 쓴다.
     * 화면에 필요한 컬럼만 읽고 users 는 건드리지 않는다.
     */
    List<TravelPlanMember> findMembersByPlanAndStatus(@Param("travelPlanId") Long travelPlanId,
                                                      @Param("memberStatus") String memberStatus);

    /**
     * 방 안에서의 역할만 바꾼다. 방장 이전에서 양쪽 모두 이 문장을 쓴다.
     * 지금 역할이 fromRole 인 ACTIVE 참여자일 때만 반영되므로,
     * 상태가 그 사이 바뀌었으면 영향 행이 0 이 되어 호출자가 되돌릴 수 있다.
     * 사이트 전체 권한이 아니라 travel_plan_members.role 만 건드린다.
     *
     * @return 1 이면 반영, 0 이면 이미 바뀌었거나 조건이 맞지 않는다.
     */
    int changeMemberRole(@Param("id") Long id,
                         @Param("travelPlanId") Long travelPlanId,
                         @Param("memberStatus") String memberStatus,
                         @Param("fromRole") String fromRole,
                         @Param("toRole") String toRole);

    /** 방의 참여자 수. 초대 미리보기의 "N/8" 과 정원 검사에 쓴다. */
    int countMembersByPlanAndStatus(@Param("travelPlanId") Long travelPlanId,
                                    @Param("memberStatus") String memberStatus);

    /**
     * 방에서 그 역할을 맡고 있는 참여자 1건.
     * 초대 미리보기에서 OWNER 의 방 표시 이름을 읽을 때 쓴다(회원 개인정보는 읽지 않는다).
     */
    TravelPlanMember findMemberByPlanAndRole(@Param("travelPlanId") Long travelPlanId,
                                             @Param("role") String role,
                                             @Param("memberStatus") String memberStatus);

    /** 방의 DAY 목록. day_number 오름차순. */
    List<TravelPlanDay> findDaysByPlanId(@Param("travelPlanId") Long travelPlanId);

    /**
     * DAY 1건. 방 소속 조건을 함께 걸어 다른 방의 dayId 를 섞어 넣을 수 없게 한다.
     */
    TravelPlanDay findDayByPlanAndId(@Param("travelPlanId") Long travelPlanId,
                                     @Param("dayId") Long dayId);

    int updateDayTitle(@Param("travelPlanId") Long travelPlanId,
                       @Param("dayId") Long dayId,
                       @Param("title") String title,
                       @Param("titleVersion") Integer titleVersion);

    /**
     * 마지막 활동 시각 갱신. 목록 정렬 기준이라 실제 변경이 있을 때만 부른다.
     * (조회에서는 부르지 않는다)
     */
    int touchLastActivity(@Param("travelPlanId") Long travelPlanId);
}
