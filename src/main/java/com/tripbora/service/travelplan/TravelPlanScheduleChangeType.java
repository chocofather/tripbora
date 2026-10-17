package com.tripbora.service.travelplan;

/**
 * 어떤 동작으로 DAY 가 바뀌었는지.
 *
 * <p>클라이언트는 이 값을 보고 화면을 다르게 조작하지 않는다.
 * 어떤 DAY 를 다시 읽어야 하는지만 알면 되고, 종류는 확인과 확장을 위해 붙여 둔다.
 */
public enum TravelPlanScheduleChangeType {
    DAY_UPDATED,
    ITEM_ADDED,
    ITEM_UPDATED,
    /** 일정만 삭제와 대안까지 통째로 삭제 모두 화면에서는 같은 뜻이다. */
    ITEM_DELETED,
    /** 같은 DAY 안에서 위/아래로 자리를 바꿨다. */
    ITEM_REORDERED,
    /** 다른 DAY 로 옮겼다. 두 DAY 가 함께 바뀐다. */
    ITEM_MOVED,
    ALTERNATIVE_ADDED,
    ALTERNATIVE_UPDATED,
    /** B 를 지워 C 가 B 자리로 올라온 경우도 화면에서는 같은 뜻이다. */
    ALTERNATIVE_DELETED,
    /** 공용 준비물/메모/경비 갱신. DAY를 교체하지 않고 대시보드만 다시 읽는다. */
    DASHBOARD_UPDATED
}
