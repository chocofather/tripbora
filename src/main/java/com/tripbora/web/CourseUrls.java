package com.tripbora.web;

/** Course canonical 경로. 댓글/북마크 API 및 관리자 경로와 구분한다. */
public final class CourseUrls {
    public static final String LIST = "/courses";
    public static final String NEW = LIST + "/new";

    private CourseUrls() { }

    public static String detail(Long id) { return LIST + "/" + id; }
    public static String edit(Long id) { return detail(id) + "/edit"; }
    public static String delete(Long id) { return detail(id) + "/delete"; }
    public static String translation(Long id) { return detail(id) + "/translation"; }
}
