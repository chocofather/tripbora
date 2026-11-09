package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCommentUiContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void detailPagesLoadNamespacedCommonCommentStyles() throws IOException {
        for (String template : new String[]{
                "templates/destination/detail.html",
                "templates/post/detail.html",
                "templates/course/detail.html"
        }) {
            assertThat(resource(template))
                    .contains("/css/content-comment.css")
                    .contains("content-comments")
                    .contains("content-comment-list");
        }

        String css = resource("static/css/content-comment.css");
        assertThat(css)
                .contains(".content-comments .content-comment-item")
                .contains("grid-template-columns: 40px minmax(0, 1fr)")
                .contains("width: 40px")
                .contains("width: 36px")
                .contains("border-left: 1px solid")
                .contains("flex-wrap: wrap")
                .contains("@media (max-width: 768px)")
                .contains("@media (max-width: 480px)")
                .contains("margin-left: 20px")
                .contains("@media (max-width: 320px)")
                .contains("margin-left: 14px")
                .contains("focus-visible")
                .contains("content-comment-sr-only")
                .doesNotContain("\n.comment-item {")
                .doesNotContain("content: '↳'");
    }

    @Test
    void postAndCourseRenderersKeepApisAndAddUnifiedCommentContract() throws IOException {
        // 코스 댓글은 화면이 내려준 현재 언어 문구를 쓰고, 게시글 댓글은 아직 원문을 들고 있다.
        // 확인하는 것은 문구 자체가 아니라 같은 자리에 같은 순서로 놓이는지다.
        var labelsByScript = new java.util.LinkedHashMap<String, String[]>();
        labelsByScript.put("static/js/post-comments.js", new String[]{
                "삭제된 댓글입니다.",
                "actions.append(makeButton('답글'",
                "makeButton('수정'",
                "makeButton('삭제'"});
        labelsByScript.put("static/js/course-comments.js", new String[]{
                "detailMessage('commentDeleted')",
                "actions.append(makeButton(detailMessage('commentReply')",
                "makeButton(detailMessage('commentEdit')",
                "makeButton(detailMessage('commentDelete')"});

        for (var entry : labelsByScript.entrySet()) {
            String scriptPath = entry.getKey();
            String[] labels = entry.getValue();
            String script = resource(scriptPath);
            assertThat(script)
                    .contains("writerUserId")
                    .contains("writerProfileImage")
                    .contains("content-comment-card")
                    .contains("content-comment-avatar")
                    .contains("content-comment-actions")
                    .contains("content-comment-replies")
                    .contains("content-comment-mention")
                    .contains("/images/default.png")
                    .contains("fallbackApplied")
                    .contains("/uploads/icons/like.png")
                    .contains("/uploads/icons/like2.png")
                    .contains("aria-hidden")
                    .contains("aria-pressed")
                    .contains("date.getFullYear() % 100")
                    .contains("updatedAt > createdAt")
                    .contains("date.dateTime = comment.createdAt")
                    .contains("replyToCommentId")
                    .contains("replyToNickname")
                    .contains("replyToDeleted")
                    .contains(labels[0])
                    .doesNotContain("comment.updatedAt || comment.createdAt")
                    .doesNotContain("comment.updatedAt !== comment.createdAt");
            assertOrdered(script,
                    "actions.append(makeLikeControl(comment, loggedIn))",
                    labels[1], labels[2], labels[3]);
        }

        assertThat(resource("static/js/post-comments.js"))
                .contains("/post-comments")
                .contains("post-comment-like-button")
                .contains("post-comment-reply-button")
                .contains("post-comment-edit")
                .contains("post-comment-delete");
        assertThat(resource("static/js/course-comments.js"))
                .contains("/course-comments")
                .contains("course-comment-like-button")
                .contains("course-comment-reply-button")
                .contains("course-comment-edit")
                .contains("course-comment-delete");
    }

    @Test
    void destinationRendererKeepsFeaturesAndUsesUnifiedAccessibleLikeUi() throws IOException {
        String renderer = resource("static/js/comment/render.js");
        assertThat(renderer)
                .contains("comment.writer?.profileImage")
                .contains("comment.writer?.id")
                .contains("content-comment-card")
                .contains("content-comment-avatar")
                .contains("content-comment-image")
                .contains("content-comment-actions")
                .contains("content-comment-replies")
                .contains("<span class=\"mention content-comment-mention\">")
                .contains("/images/default.png")
                .contains("fallbackApplied")
                .contains("/uploads/icons/like.png")
                .contains("/uploads/icons/like2.png")
                .contains("aria-hidden=\"true\"")
                .contains("aria-pressed")
                .contains("date.getFullYear() % 100")
                .contains("updatedAt > createdAt")
                .contains("formatDate(comment.createdAt)")
                .doesNotContain("/profile/")
                .doesNotContain("comment.updatedAt !== comment.createdAt");
        assertOrdered(renderer,
                "${likeControl}",
                "class=\"reply-btn content-comment-action\"",
                "class=\"edit-btn content-comment-action\"",
                "class=\"delete-btn content-comment-action\"");

        assertThat(resource("static/js/comment/events.js"))
                .contains("toggleLikeApi(id)")
                .contains("onCommentsReload()")
                .contains("[data-comment-sort]")
                .contains("setupCommentPagingEvents(onLoadMore)");
        assertThat(resource("static/js/comment/api.js"))
                .contains("/comments/${commentId}/like-toggle")
                .contains("/comments/list/page")
                .doesNotContain("/comments/images");
    }

    @Test
    void postCommentsReuseTheOnDemandTranslationUiWithoutSendingSourceText() throws IOException {
        String template = resource("templates/post/detail.html");
        String script = resource("static/js/post-comments.js");

        assertThat(template)
                .contains("th:data-translation-show")
                .contains("th:data-translation-original")
                .contains("th:data-translation-loading");
        assertThat(script)
                .contains("if (comment.translationAvailable !== true) return null;")
                .contains("`/post-comments/${encodeURIComponent(commentId)}/translation`")
                .contains("method: 'GET'")
                .contains("content.textContent = response.translatedText || '';")
                .contains("content.dataset.originalContent || ''")
                .doesNotContain("body: JSON.stringify({sourceText");
    }

    @Test
    void courseCommentsReuseTheOnDemandTranslationUiWithoutSendingSourceText() throws IOException {
        String template = resource("templates/course/detail.html");
        String script = resource("static/js/course-comments.js");

        assertThat(template)
                .contains("th:data-translation-show")
                .contains("th:data-translation-original")
                .contains("th:data-translation-loading");
        assertThat(script)
                .contains("if (comment.translationAvailable !== true) return null;")
                .contains("`/course-comments/${encodeURIComponent(commentId)}/translation`")
                .contains("method: 'GET'")
                .contains("content.textContent = response.translatedText || '';")
                .contains("content.dataset.originalContent || ''")
                .doesNotContain("body: JSON.stringify({sourceText");
    }

    @Test
    void destinationCommentsFollowTheCommunityLayoutInsteadOfLegacyRules() throws IOException {
        String css = resource("static/css/comment.css");

        // 공통 디자인은 content-comment.css 가 담당하고 여기서 다시 정의하지 않는다
        assertThat(css)
                .doesNotContain("\n.comment-item.reply {")
                .doesNotContain("content: '↳'")
                .doesNotContain("\n.comment-profile {")
                .doesNotContain("\n.comment-nickname {")
                .doesNotContain("\n.comment-header {")
                .doesNotContain("\n.comment-content {")
                // .comment-item.editing .comment-actions (수정 중 액션 숨김)는 기능이라 남는다
                .doesNotContain("\n.comment-actions {")
                .doesNotContain("\n.likeicon {")
                .doesNotContain(".reply-list > .comment-item");
        // 대댓글이 중첩돼도 들여쓰기가 누적되지 않아야 본문 폭이 유지된다
        assertThat(css)
                .contains(".content-comments .content-comment-replies .content-comment-replies")
                // 사진 1~3장은 본문 폭 안에서 줄바꿈된다
                .contains(".content-comments .comment-images")
                .contains("flex-wrap: wrap")
                .contains("@media (max-width: 480px)");
    }

    @Test
    void destinationModeratedPlaceholderUsesFullBodyWidthLikeCommunity() throws IOException {
        String renderer = resource("static/js/comment/render.js");

        // 커뮤니티와 동일하게 카드 그리드 없이 안내 문구만 li 에 붙인다 (대댓글 폭 붕괴 방지)
        assertThat(renderer)
                .contains("li.classList.add('content-comment-deleted')")
                .contains("content-comment-deleted-text")
                .contains("li.append(moderatedText)")
                .doesNotContain("moderatedCard");
        // 다중 이미지 렌더링과 모달 연결은 그대로 유지된다
        assertThat(renderer)
                .contains("renderCommentImages(comment)")
                .contains("class=\"comment-image content-comment-image\"");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(RESOURCES.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private void assertOrdered(String value, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = value.indexOf(fragment, previous + 1);
            assertThat(current).as("%s 뒤에 %s가 있어야 함", previous, fragment).isGreaterThan(previous);
            previous = current;
        }
    }
}
