package com.tripbora.seo;

import java.util.List;
import java.util.regex.Pattern;

public final class SeoRobotsPolicy {

    public static final String INDEX = "index, follow";
    public static final String NOINDEX = "noindex, nofollow";

    private static final List<String> NOINDEX_PREFIXES = List.of(
            "/admin", "/mypage", "/diaries", "/travel-plans",
            "/account", "/api", "/bookmarks", "/comments", "/post-comments",
            "/course-comments", "/oauth2", "/login/oauth2", "/social-signup",
            "/social-link", "/users/profile", "/support/inquiries",
            "/uploads/diary-covers", "/uploads/diary-pages",
            "/uploads/diary-cover-designs");
    private static final List<String> NOINDEX_EXACT_PATHS = List.of(
            "/login", "/logout", "/register", "/social-signup", "/social-link",
            "/search", "/search.html", "/users/register", "/users/verify",
            "/users/find-password", "/post/write", "/course/write", "/courses/new");
    private static final Pattern EDIT_PATH = Pattern.compile("^/(?:post|courses?)/\\d+/edit$");

    private SeoRobotsPolicy() {
    }

    public static boolean isNoindex(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (NOINDEX_EXACT_PATHS.contains(path) || EDIT_PATH.matcher(path).matches()) {
            return true;
        }
        if (path.startsWith("/users/reset-password")
                || path.startsWith("/users/register/")
                || path.startsWith("/users/verification/")
                || path.startsWith("/users/recover-account/")) {
            return true;
        }
        if (path.contains("/fragment") || path.endsWith("-fragment")) {
            return true;
        }
        return NOINDEX_PREFIXES.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
