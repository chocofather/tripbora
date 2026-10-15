package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublicProfileContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void publicUserQuerySelectsOnlyPublicFieldsAndActiveMember() throws IOException {
        String mapper = resource("mapper/UserMapper.xml");
        String query = between(mapper, "<select id=\"findPublicProfileById\"", "</select>");

        assertThat(query)
                .contains("id,", "nickname,", "profile_image AS profileImage")
                .contains("WHERE id = #{id}")
                .contains("status = 'ACTIVE'")
                .contains("deleted_at IS NULL")
                .doesNotContain("SELECT *")
                .doesNotContain("user_email", "username", "full_name", "user_phone", "user_birth",
                        "user_role", "verification_token", "last_login");
    }

    @Test
    void authorBoardQueryFiltersBothUnionBranchesAndDoesNotReadBookmarks() throws IOException {
        String mapper = resource("mapper/BoardMapper.xml");
        String union = between(mapper, "<sql id=\"unifiedBoardSelectByUserId\"", "</sql>");
        String list = between(mapper, "<sql id=\"userBoardList\"", "</sql>");

        assertThat(union)
                .contains("WHERE p.user_id = #{userId}")
                .contains("p.post_type IN ('QUESTION', 'TIP')")
                .contains("p.deleted = 0")
                .contains("p.deleted_at IS NULL")
                .contains("WHERE c.user_id = #{userId}")
                .contains("c.deleted = 0")
                .contains("c.deleted_at IS NULL");
        assertThat(list)
                .contains("ORDER BY board.createdAt DESC, board.boardType ASC, board.id DESC")
                .contains("LIMIT #{offset}, #{limit}")
                .doesNotContain("bookmarks", "bookmarked", "bookmarkCount");
    }

    @Test
    void profileAndAllAuthorSurfacesUseNumericUserLinks() throws IOException {
        assertThat(resource("templates/board/fragment.html")).contains("@{/users/{id}(id=${item.userId})}");
        assertThat(resource("templates/post/detail.html")).contains("@{/users/{id}(id=${post.userId})}");
        assertThat(resource("templates/course/detail.html")).contains("@{/users/{id}(id=${course.userId})}");

        for (String script : new String[]{
                "static/js/post-comments.js",
                "static/js/course-comments.js",
                "static/js/comment/render.js"
        }) {
            assertThat(resource(script))
                    .contains("`/users/${encodeURIComponent(String(")
                    .contains("textContent")
                    .contains("content-comment-profile-link")
                    .contains("content-comment-writer-link");
        }
    }

    @Test
    void publicProfileIsSsrPagedResponsiveAndHasLoadFailureFallback() throws IOException {
        String template = resource("templates/user/public-profile.html");
        String css = resource("static/css/public-profile.css");

        assertThat(template)
                .contains("profile.profileImage")
                .contains("this.src='/images/default.png'")
                .contains("type='all'", "type='question'", "type='tip'", "type='course'")
                .contains("currentPage - 1", "currentPage + 1")
                .doesNotContain("bookmark", "email", "username", "userPhone", "userBirth", "userRole");
        assertThat(css)
                .contains("width: 82px", "height: 82px")
                .contains("@media (max-width: 768px)")
                .contains("@media (max-width: 480px)")
                .contains("@media (max-width: 320px)");
    }

    @Test
    void deletedPostAndCourseCommentsReturnBeforeAnyAuthorLinkIsBuilt() throws IOException {
        for (String scriptPath : new String[]{"static/js/post-comments.js", "static/js/course-comments.js"}) {
            String script = resource(scriptPath);
            int renderStart = script.indexOf("function renderComment(comment");
            int deletedBranch = script.indexOf("if (comment.deleted)", renderStart);
            int deletedReturn = script.indexOf("return item;", deletedBranch);
            int authorLink = script.indexOf("makeProfileLink(comment, writer", deletedReturn);
            int avatarLink = script.indexOf("makeProfileLink(comment, makeProfileImage", deletedReturn);

            assertThat(renderStart).isGreaterThanOrEqualTo(0);
            assertThat(deletedBranch).isGreaterThan(renderStart);
            assertThat(deletedReturn).isGreaterThan(deletedBranch);
            assertThat(authorLink).isGreaterThan(deletedReturn);
            assertThat(avatarLink).isGreaterThan(deletedReturn);
            // 작성자 링크는 회원 번호가 없을 때와 최종 탈퇴 회원일 때 모두 만들지 않는다.
            assertThat(script).contains(
                    "if (comment.writerUserId == null || comment.writerWithdrawn === true) return child;");
        }
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(RESOURCES.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
