package com.tripbora.dto;

/** 이미 확인된 지역 표시명만 조합한다. 주소 추론이나 행정명 축약은 하지 않는다. */
public final class CourseLocationLabel {
    private CourseLocationLabel() {}

    public static String join(String... names) {
        String label = "";
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String part = name.strip();
            if (label.equals(part) || label.endsWith(" " + part)) continue;
            if (label.isEmpty() || part.startsWith(label + " ")) label = part;
            else label += " " + part;
        }
        return label.isEmpty() ? null : label;
    }
}
