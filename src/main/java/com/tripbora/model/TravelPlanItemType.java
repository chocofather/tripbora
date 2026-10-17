package com.tripbora.model;

/** tag에 저장하는 일정 역할. DB enum 대신 문자열 코드로 향후 복사/타입 확장을 지원한다. */
public enum TravelPlanItemType {
    SCHEDULE("일정"), ACCOMMODATION("숙소"), TRANSPORT("이동"), FLIGHT("항공"),
    MEAL("식사"), SIGHTSEEING("관광"), NOTE("메모");

    private final String label;

    TravelPlanItemType(String label) { this.label = label; }

    public String getLabel() { return label; }

    public static TravelPlanItemType parse(String code) {
        return valueOf(switch (code) {
            case "STAY" -> "ACCOMMODATION";
            case "MOVE" -> "TRANSPORT";
            case "FOOD" -> "MEAL";
            default -> code;
        });
    }

    /** 기존 null/알 수 없는 과거 태그도 원문을 잃지 않고 일반 일정으로 표시한다. */
    public static TravelPlanItemType fromTag(String tag) {
        if (tag == null) return SCHEDULE;
        try { return parse(tag); }
        catch (IllegalArgumentException exception) { return SCHEDULE; }
    }
}
