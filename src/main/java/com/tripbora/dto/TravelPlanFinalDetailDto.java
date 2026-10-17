package com.tripbora.dto;

import com.tripbora.model.TravelPlanFinalDay;
import com.tripbora.model.TravelPlanFinalItem;
import com.tripbora.model.TravelPlanFinalItemAlternative;
import com.tripbora.model.TravelPlanFinalMember;
import com.tripbora.model.TravelPlanFinalSnapshot;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
}
