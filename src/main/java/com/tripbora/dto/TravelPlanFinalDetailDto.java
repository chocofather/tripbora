package com.tripbora.dto;

import com.tripbora.model.TravelPlanFinalDay;
import com.tripbora.model.TravelPlanFinalItem;
import com.tripbora.model.TravelPlanFinalItemAlternative;
import com.tripbora.model.TravelPlanFinalMember;
import com.tripbora.model.TravelPlanFinalSnapshot;
import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanItem;
import com.tripbora.model.TravelPlanItemAlternative;
import com.tripbora.model.TravelPlanStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 완료된 여행 한 벌.
 *
 * <p>일정과 참여자는 최종본에서 읽는다. 원본 일정을 다시 조합하지 않는다.
 * 공용 준비물·메모·경비는 별도 읽기 전용 dashboard API가 제공한다.
 * 완료된 뒤에는 바뀌지 않으므로 version 이나 편집에 쓰던 값은 담지 않는다.
 */
@Getter
@RequiredArgsConstructor
public class TravelPlanFinalDetailDto {

    private final TravelPlanFinalSnapshot snapshot;
    private final List<TravelPlanFinalMember> members;
    private final List<TravelPlanFinalDay> days;
    /** final_day_id -> 그 날의 일정. 완료 시점의 순서 그대로다. */
    private final Map<Long, List<TravelPlanFinalItem>> itemsByDayId;
    /** final_item_id -> 그 일정의 대안(B/C). 없으면 비어 있다. */
    private final Map<Long, List<TravelPlanFinalItemAlternative>> alternativesByItemId;

    /** 저장 모델은 유지하고 공용 상세 화면에 필요한 형태만 맞춘다. 편집 권한은 부여하지 않는다. */
    public TravelPlanDetailDto toPlannerDetail() {
        TravelPlan plan = new TravelPlan();
        BeanUtils.copyProperties(snapshot, plan);
        plan.setId(snapshot.getTravelPlanId());
        plan.setStatus(TravelPlanStatus.COMPLETED);
        List<TravelPlanDay> plannerDays = days.stream().map(source -> {
            TravelPlanDay day = new TravelPlanDay();
            BeanUtils.copyProperties(source, day);
            day.setTravelPlanId(plan.getId());
            return day;
        }).toList();
        Map<Long, List<TravelPlanItem>> items = new LinkedHashMap<>();
        itemsByDayId.forEach((dayId, rows) -> items.put(dayId, rows.stream().map(source -> {
            TravelPlanItem item = new TravelPlanItem();
            BeanUtils.copyProperties(source, item);
            item.setTravelPlanDayId(dayId);
            return item;
        }).toList()));
        Map<Long, List<TravelPlanItemAlternative>> alternatives = new LinkedHashMap<>();
        alternativesByItemId.forEach((itemId, rows) -> alternatives.put(itemId, rows.stream().map(source -> {
            TravelPlanItemAlternative alternative = new TravelPlanItemAlternative();
            BeanUtils.copyProperties(source, alternative);
            alternative.setTravelPlanItemId(itemId);
            return alternative;
        }).toList()));
        return new TravelPlanDetailDto(plan, null, plannerDays, items, alternatives,
                members.stream().map(member -> new TravelPlanMemberDto(
                        member.getId(), member.getDisplayName(), member.getRole(), false)).toList(),
                List.of(), 8);
    }
}
