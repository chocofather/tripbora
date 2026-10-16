package com.tripbora.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Course 상세의 외부 지도 링크. 저장 snapshot과 표시 언어는 변경하지 않는다. */
final class CourseGoogleMapsUrl {
    private static final String SEARCH_URL = "https://www.google.com/maps/search/?api=1&query=";

    private CourseGoogleMapsUrl() {}

    static String from(CourseStopDto stop) {
        boolean linked = stop.getDestinationId() != null;
        String name = text(stop.getName());
        if ("이름 없는 여행지".equals(name)) name = null;
        String placeId = linked ? text(stop.getGooglePlaceId()) : null;
        String context = text(linked ? stop.getRegionName() : stop.getAddress());
        String query = name;
        if (placeId == null && context != null) {
            if (name != null) query = name + " " + context;
            else if (!linked) query = context;
        }
        if (query == null && stop.getLatitude() != null && stop.getLongitude() != null) {
            query = stop.getLatitude().toPlainString() + "," + stop.getLongitude().toPlainString();
        }
        // Place ID만 남은 오래된 항목도 ID를 버리고 다른 장소를 검색하지 않는다.
        if (query == null) query = placeId;
        if (query == null) return null;
        String url = SEARCH_URL + encode(query);
        return placeId == null ? url : url + "&query_place_id=" + encode(placeId);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
