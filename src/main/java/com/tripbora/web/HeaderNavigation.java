package com.tripbora.web;

/** 공통 헤더의 현재 메뉴. 상세 화면의 지역 분류는 컨트롤러가 확정한 type을 사용한다. */
public final class HeaderNavigation {
    private final String path;
    private final String requestedType;

    public HeaderNavigation(String path, String requestedType) {
        this.path = path == null ? "" : path;
        this.requestedType = requestedType;
    }

    public String resolve(String destinationType, Boolean adminPreview) {
        if ("/destinations".equals(path)) {
            return geography(requestedType == null || requestedType.isEmpty()
                    ? "domestic" : requestedType);
        }
        if (within("/destinations")) {
            // 상세의 query type은 신뢰하지 않는다. 지역을 확인하지 못했으면 표시하지 않는다.
            return geography(destinationType);
        }
        if (within(CourseUrls.LIST) || (Boolean.TRUE.equals(adminPreview)
                && within("/admin/courses") && path.endsWith("/preview"))) {
            return "courses";
        }
        if (within("/board") || within("/post")) {
            return "community";
        }
        if (within("/travel-info") || within("/festivals")) {
            return "travelInfo";
        }
        if (within("/diaries") || within("/travel-plans") || "/random-travel".equals(path)) {
            return "diaries";
        }
        if (within("/support")) {
            return "support";
        }
        return within("/events") ? "events" : "";
    }

    private String geography(String type) {
        return "domestic".equals(type) || "overseas".equals(type) ? type : "";
    }

    private boolean within(String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
