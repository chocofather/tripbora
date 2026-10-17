package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanEditorLockDto;
import com.tripbora.dto.TravelPlanFinalizeCheckDto;
import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanFinalDay;
import com.tripbora.model.TravelPlanFinalItem;
import com.tripbora.model.TravelPlanFinalItemAlternative;
import com.tripbora.model.TravelPlanFinalMember;
import com.tripbora.model.TravelPlanFinalSnapshot;
import com.tripbora.model.TravelPlanItem;
import com.tripbora.model.TravelPlanItemAlternative;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.model.TravelPlanMemberStatus;
import com.tripbora.model.TravelPlanRole;
import com.tripbora.model.TravelPlanStatus;
import com.tripbora.repository.travelplan.TravelPlanAlternativeMapper;
import com.tripbora.repository.travelplan.TravelPlanFinalMapper;
import com.tripbora.repository.travelplan.TravelPlanItemMapper;
import com.tripbora.repository.travelplan.TravelPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여행 계획 완료.
 *
 * <p>이번 단계에서 하는 일은 "지금 완료할 수 있는가" 를 보는 것까지다.
 * 진행 상태를 바꾸거나 최종본을 만들지 않는다.
 *
 * <p>완료할 수 있는지 보는 자리는 {@link #requireFinalizable} 하나뿐이다.
 * 다음 단계에서 실제로 완료할 때도 같은 곳을 다시 지나가게 해,
 * 물어본 뒤 완료하기 전 사이에 누가 편집을 시작해도 걸러지게 한다.
 */
@Service
@RequiredArgsConstructor
public class TravelPlanFinalizeService {

    /** 누군가 일정을 쓰고 있는데 그냥 완료하려 할 때. */
    static final String EDITING_IN_PROGRESS =
            "현재 편집 중인 일정이 있습니다. 편집이 끝난 후 다시 시도해 주세요.";
    /** 그 사이 누가 먼저 완료했을 때. */
    static final String ALREADY_FINALIZED = "이미 완료된 여행 계획입니다.";

    private final TravelPlanRoomAccess travelPlanRoomAccess;
    /** 지금 누가 어느 자리를 붙잡고 있는지는 실시간 쪽이 들고 있다. */
    private final TravelPlanEditorRealtimeService travelPlanEditorRealtimeService;
    private final TravelPlanMapper travelPlanMapper;
    private final TravelPlanItemMapper travelPlanItemMapper;
    private final TravelPlanAlternativeMapper travelPlanAlternativeMapper;
    private final TravelPlanFinalMapper travelPlanFinalMapper;
    /** 완료 알림과 작성 중 내용 정리는 커밋 뒤에 한다. */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 완료하기 전에 한 번 물어본다.
     *
     * <p>누가 쓰고 있다고 해서 막지 않는다. 누구인지 알려 주고 판단은 방장에게 맡긴다.
     * 방장이 아니거나 방을 볼 자격이 없는 경우만 사유 없이 막는다.
     */
    @Transactional(readOnly = true)
    public TravelPlanFinalizeCheckDto checkFinalizable(Principal principal, Long travelPlanId) {
        TravelPlanMember member = requireOwner(principal, travelPlanId);

        List<String> editors = activeEditorDisplayNames(travelPlanId, member.getId());
        return editors.isEmpty()
                ? TravelPlanFinalizeCheckDto.ready()
                : TravelPlanFinalizeCheckDto.warnAbout(editors);
    }

    /**
     * 지금 이 사람이 이 방을 완료할 수 있는지.
     *
     * <p>완료할 수 없으면 예외로 끊는다. 다음 단계의 실제 완료도 이 문을 지난다.
     *
     * <ul>
     *   <li>살아 있는 방의 ACTIVE 참여자여야 한다(끝난 방·LEFT·REMOVED·비참여자는 여기서 걸린다)
     *   <li>그중에서도 방장이어야 한다
     *   <li>누가 일정을 쓰고 있다면, 방장이 그것을 알고도 하겠다고 한 경우에만 지나간다
     * </ul>
     *
     * <p>진행 중인 투표·대화·접속 인원은 완료를 막지 않는다.
     * 투표는 계획을 정하는 데 도우려는 것이지 완료의 조건이 아니다.
     *
     * @param force 쓰고 있는 사람이 있어도 그대로 하겠다고 방장이 정한 경우 true.
     *              경고를 보여 준 뒤에만 쓴다.
     * @return 완료하려는 방장의 참여 정보
     */
    @Transactional(readOnly = true)
    public TravelPlanMember requireFinalizable(Principal principal, Long travelPlanId,
                                               boolean force) {
        TravelPlanMember member = requireOwner(principal, travelPlanId);

        /*
          그냥 완료라면 쓰고 있는 사람이 없어야 한다.
          알고도 하겠다고 한 경우(force)에는 지나간다.
          그때 남아 있던 작성 중 내용을 어떻게 정리할지는 실제 완료 쪽에서 다룬다.
          여기서는 아무것도 건드리지 않아, 나중에 그 정리를 막지 않는다.
        */
        if (!force && !activeEditorDisplayNames(travelPlanId, member.getId()).isEmpty()) {
            throw new TravelPlanValidationException("editor", EDITING_IN_PROGRESS);
        }
        return member;
    }

    /**
     * 지금 이 방에서 일정을 쓰고 있는 다른 사람들의 이름.
     *
     * <p>화면이 비어 보인다고 믿지 않는다.
     * 누가 무엇을 붙잡고 있는지는 서버가 들고 있는 지금 상태로만 본다.
     * (A 일정과 B/C 대안이 같은 장부에 들어 있어 한 번에 확인된다)
     *
     * <p>같은 사람이 여러 자리를 잡고 있어도 이름은 한 번만 나온다.
     * 완료하려는 본인은 세지 않는다. 자기 편집기는 자기가 닫으면 된다.
     */
    private List<String> activeEditorDisplayNames(Long travelPlanId, Long viewerMemberId) {
        Map<Long, String> byMember = new LinkedHashMap<>();
        for (TravelPlanEditorLockDto lock : travelPlanEditorRealtimeService.locksOf(travelPlanId)) {
            if (lock.memberId() == null || lock.memberId().equals(viewerMemberId)) {
                continue;
            }
            byMember.putIfAbsent(lock.memberId(), lock.displayName());
        }
        return List.copyOf(byMember.values());
    }

    /**
     * 실제로 완료한다.
     *
     * <p>흐름은 ACTIVE → FINALIZING → 최종본 만들기 → COMPLETED 다.
     * 최종본을 뜨는 동안 FINALIZING 으로 두어, 그 사이 들어오는 일정 저장이
     * ACTIVE 조건에서 걸리게 한다.
     *
     * <p>방 row 를 잠그고 시작하므로 일정 저장과 한 줄로 선다.
     * 저장이 먼저 끝났으면 그 변경까지 최종본에 담기고,
     * 이쪽이 먼저면 저장은 상태가 바뀐 뒤라 거부된다.
     *
     * <p>중간에 하나라도 실패하면 전부 되돌아간다.
     * 최종본 조각만 남거나 COMPLETED 만 남는 일이 없다.
     *
     * @param force 쓰고 있는 사람이 있어도 그대로 하겠다고 방장이 정한 경우 true
     */
    @Transactional
    public void finalizePlan(Principal principal, Long travelPlanId, boolean force) {
        // preflight 결과를 믿지 않는다. 지금 자격과 편집 상태를 다시 본다.
        requireFinalizable(principal, travelPlanId, force);

        /*
          여기서 방 row 를 잠근다.
          이 줄을 지나면 일정 저장은 이 트랜잭션이 끝날 때까지 기다리고,
          끝난 뒤에는 ACTIVE 가 아니라 거부된다.
        */
        TravelPlan plan = travelPlanMapper.findPlanByIdAndStatusForUpdate(
                travelPlanId, TravelPlanStatus.ACTIVE.name());
        if (plan == null) {
            // 그 사이 누가 먼저 완료했거나 방이 사라졌다.
            throw new TravelPlanValidationException("status", ALREADY_FINALIZED);
        }
        if (travelPlanMapper.updatePlanStatus(travelPlanId,
                TravelPlanStatus.ACTIVE.name(), TravelPlanStatus.FINALIZING.name(), false) != 1) {
            throw new TravelPlanValidationException("status", ALREADY_FINALIZED);
        }

        writeSnapshot(plan);

        if (travelPlanMapper.updatePlanStatus(travelPlanId,
                TravelPlanStatus.FINALIZING.name(), TravelPlanStatus.COMPLETED.name(),
                true) != 1) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "여행 계획을 완료하지 못했습니다.");
        }

        /*
          작성 중이던 내용은 커밋이 끝난 뒤에 정리한다.
          여기서 먼저 지우면 뒤에서 실패했을 때 남의 편집 상태까지 사라진다.
        */
        eventPublisher.publishEvent(new TravelPlanCompletedEvent(travelPlanId));
    }

    /**
     * 지금 계획을 최종본으로 옮겨 적는다.
     *
     * <p>원본 번호를 그대로 쓰지 않는다. 최종본 안에서만 통하는 번호로 새로 잇는다.
     * 그래서 원본이 나중에 어떻게 되든 최종본은 그대로 남는다.
     */
    private void writeSnapshot(TravelPlan plan) {
        Long travelPlanId = plan.getId();
        // (travel_plan_id) UNIQUE 가 마지막 방어지만, 여기서 먼저 알아보기 쉽게 끊는다.
        if (travelPlanFinalMapper.existsByPlanId(travelPlanId)) {
            throw new TravelPlanValidationException("status", ALREADY_FINALIZED);
        }

        TravelPlanFinalSnapshot snapshot = new TravelPlanFinalSnapshot();
        snapshot.setTravelPlanId(travelPlanId);
        snapshot.setTitle(plan.getTitle());
        snapshot.setStartDate(plan.getStartDate());
        snapshot.setEndDate(plan.getEndDate());
        snapshot.setRepresentativeImageUrl(plan.getRepresentativeImageUrl());
        requireOneRow(travelPlanFinalMapper.insertSnapshot(snapshot));

        copyMembers(travelPlanId, snapshot.getId());
        copyDaysWithItems(travelPlanId, snapshot.getId());
    }

    /**
     * 완료 시점에 방에 남아 있던 사람만 옮겨 적는다.
     *
     * <p>화면용 조회가 아니라 계정 번호까지 읽는 조회를 쓴다.
     * 최종 명단의 user_id 로 "내 완료된 여행" 을 찾기 때문이다.
     */
    private void copyMembers(Long travelPlanId, Long snapshotId) {
        List<TravelPlanMember> members = travelPlanMapper.findActiveMembersForSnapshot(
                travelPlanId, TravelPlanMemberStatus.ACTIVE.name());
        for (TravelPlanMember member : members == null ? List.<TravelPlanMember>of() : members) {
            TravelPlanFinalMember finalMember = new TravelPlanFinalMember();
            finalMember.setSnapshotId(snapshotId);
            finalMember.setUserId(member.getUserId());
            finalMember.setDisplayName(member.getDisplayName());
            finalMember.setRole(member.getRole());
            requireOneRow(travelPlanFinalMapper.insertMember(finalMember));
        }
    }

    /**
     * 날짜와 그 안의 일정, 각 일정의 대안까지 순서 그대로 옮겨 적는다.
     * 일정 수만큼 조회가 나가지 않도록 방 단위로 한 번에 읽어 묶는다.
     */
    private void copyDaysWithItems(Long travelPlanId, Long snapshotId) {
        Map<Long, List<TravelPlanItem>> itemsByDayId = groupBy(
                travelPlanItemMapper.findByPlanId(travelPlanId),
                TravelPlanItem::getTravelPlanDayId);
        Map<Long, List<TravelPlanItemAlternative>> alternativesByItemId = groupBy(
                travelPlanAlternativeMapper.findByPlanId(travelPlanId),
                TravelPlanItemAlternative::getTravelPlanItemId);

        List<TravelPlanDay> days = travelPlanMapper.findDaysByPlanId(travelPlanId);
        for (TravelPlanDay day : days == null ? List.<TravelPlanDay>of() : days) {
            TravelPlanFinalDay finalDay = new TravelPlanFinalDay();
            finalDay.setSnapshotId(snapshotId);
            finalDay.setDayNumber(day.getDayNumber());
            finalDay.setPlanDate(day.getPlanDate());
            requireOneRow(travelPlanFinalMapper.insertDay(finalDay));

            for (TravelPlanItem item : itemsByDayId.getOrDefault(day.getId(), List.of())) {
                TravelPlanFinalItem finalItem = new TravelPlanFinalItem();
                // 원본 day 가 아니라 방금 만든 최종본 day 에 붙인다.
                finalItem.setFinalDayId(finalDay.getId());
                finalItem.setContent(item.getContent());
                finalItem.setTag(item.getTag());
                finalItem.setStartTime(item.getStartTime());
                finalItem.setDetails(item.getDetails());
                finalItem.setDisplayOrder(item.getDisplayOrder());
                requireOneRow(travelPlanFinalMapper.insertItem(finalItem));

                copyAlternatives(alternativesByItemId.getOrDefault(item.getId(), List.of()),
                        finalItem.getId());
            }
        }
    }

    private void copyAlternatives(List<TravelPlanItemAlternative> alternatives, Long finalItemId) {
        for (TravelPlanItemAlternative alternative : alternatives) {
            TravelPlanFinalItemAlternative finalAlternative =
                    new TravelPlanFinalItemAlternative();
            finalAlternative.setFinalItemId(finalItemId);
            finalAlternative.setAlternativeOrder(alternative.getAlternativeOrder());
            finalAlternative.setConditionLabel(alternative.getConditionLabel());
            finalAlternative.setContent(alternative.getContent());
            finalAlternative.setTag(alternative.getTag());
            requireOneRow(travelPlanFinalMapper.insertAlternative(finalAlternative));
        }
    }

    private <T> Map<Long, List<T>> groupBy(List<T> rows, Function<T, Long> key) {
        return rows == null
                ? Map.of()
                : rows.stream().collect(Collectors.groupingBy(
                        key, LinkedHashMap::new, Collectors.toList()));
    }

    /** 한 줄도 들어가지 않았다면 최종본이 어긋난 것이다. 여기서 끊어 전부 되돌린다. */
    private void requireOneRow(int affected) {
        if (affected != 1) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "여행 계획을 완료하지 못했습니다.");
        }
    }

    /** 방장만 완료할 수 있다. 자격 문제는 사유를 알리지 않고 막는다. */
    private TravelPlanMember requireOwner(Principal principal, Long travelPlanId) {
        TravelPlanMember member = travelPlanRoomAccess
                .findActiveMember(principal, travelPlanId)
                .orElseThrow(() -> new AccessDeniedException("여행계획에 참여 중이 아닙니다."));

        if (member.getRole() != TravelPlanRole.OWNER) {
            throw new AccessDeniedException("방장만 여행 계획을 완료할 수 있습니다.");
        }
        return member;
    }
}
