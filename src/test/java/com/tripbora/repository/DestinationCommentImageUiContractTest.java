package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 댓글 사진 업로드 필드 계약.
 * 서버는 List&lt;MultipartFile&gt; images 를 받으므로 화면도 같은 이름으로 보내야 한다.
 */
class DestinationCommentImageUiContractTest {

    @Test
    void previewIsIndependentAndControlsKeepCountPhotoSubmitOrder() throws IOException {
        var document = org.jsoup.Jsoup.parse(readFile("src/main/resources/templates/destination/detail.html"));
        var form = document.selectFirst("#comment-form");
        assertThat(form.selectFirst(".textarea-wrapper").select(".comment-image-preview")).isEmpty();
        var preview = form.selectFirst("#comment-image-preview");
        assertThat(preview.parent()).isSameAs(form);
        assertThat(preview.hasAttr("hidden")).isTrue();
        assertThat(preview.previousElementSibling().hasClass("textarea-wrapper")).isTrue();
        var controls = form.selectFirst(".comment-controls");
        assertThat(controls.select(".comment-length, .image-upload-label, .submit-btn")
                .stream().map(element -> element.className()).toList())
                .containsExactly("comment-length", "image-upload-label", "submit-btn");
        assertThat(resource("/static/js/comment/events.js")).doesNotContain("ensureDecision", "tripboraGalleryConsent");
    }

    @Test
    void commentFormSendsUpToFiveFilesUnderTheImagesName() throws IOException {
        String detail = readFile("src/main/resources/templates/destination/detail.html");
        String input = between(detail, "id=\"comment-image-input\"", ">");

        assertThat(input)
                .contains("name=\"images\"")
                .contains("multiple");
    }

    @Test
    void replyFormUsesTheSameFieldNameAsTheCommentForm() throws IOException {
        String events = resource("/static/js/comment/events.js");
        String input = between(events, "id=\"${uniqueId}\"", ">");

        assertThat(input)
                .contains("name=\"images\"")
                .contains("multiple");
    }

    @Test
    void submitIsBlockedBeforeSendingMoreThanFiveImages() throws IOException {
        String events = resource("/static/js/comment/events.js");

        assertThat(events)
                .contains("const MAX_COMMENT_IMAGES = 5")
                .contains("detailMessage('commentImageLimit', MAX_COMMENT_IMAGES)")
                .contains("input[type=\"file\"][name=\"images\"]");
        // 댓글/답글 두 경로 모두 전송 전에 확인한다
        assertThat(events.split("if \\(exceedsImageLimit\\(form\\)\\) return;", -1))
                .hasSize(3);
    }

    @Test
    void reopeningTheFilePickerAccumulatesInsteadOfReplacingTheSelection() throws IOException {
        String events = resource("/static/js/comment/events.js");

        assertThat(events)
                // 선택 목록을 따로 들고 DataTransfer 로 input.files 와 동기화한다
                .contains("let selected = []")
                .contains("new DataTransfer()")
                .contains("input.files = transfer.files")
                .contains("addFiles(input.files)")
                // 같은 파일은 다시 담지 않는다
                .contains("${file.name}|${file.size}|${file.lastModified}")
                .contains("keys.has(fileKey(file))")
                // 수정 중 유지하는 기존 사진까지 합쳐 5장으로 제한한다
                .contains("const available = MAX_COMMENT_IMAGES - retained.length")
                .contains("selected = merged.slice(0, available)")
                .contains("alert(detailMessage('commentImageLimit', MAX_COMMENT_IMAGES))")
                // 개별 삭제 / 등록 후 초기화
                .contains("selected.splice(index, 1)")
                .contains("form.addEventListener('reset', () => setTimeout(clearFiles, 0))")
                // 미리보기는 선택 목록을 그대로 그린다
                .contains("selected.forEach((file, index)");
    }

    @Test
    void commentBodyRendersEveryImageUrlAndKeepsTheModalHook() throws IOException {
        String render = resource("/static/js/comment/render.js");

        assertThat(render)
                .contains("comment.imageUrls")
                // 모달은 .comment-image 를 기준으로 잡히므로 클래스를 유지한다
                .contains("class=\"comment-image content-comment-image\"")
                .contains("${renderCommentImages(comment)}")
                // 단일 imageUrl 렌더링은 남기지 않는다
                .doesNotContain("comment.imageUrl}");
    }

    @Test
    void commentImageModalNavigatesOnlyInsideTheClickedComment() throws IOException {
        String detail = readFile("src/main/resources/templates/destination/detail.html");
        String modal = between(detail, "id=\"image-modal\"", "</div>");

        // 대표 이미지 모달(.image-modal-nav)과 클래스가 겹치면 그쪽 핸들러가 가로채므로 분리한다
        assertThat(modal)
                .contains("class=\"comment-image-nav prev\"")
                .contains("class=\"comment-image-nav next\"")
                .contains("th:aria-label=\"#{destination.detail.gallery.previousPhoto}\"")
                .contains("th:aria-label=\"#{destination.detail.gallery.nextPhoto}\"")
                .doesNotContain("image-modal-nav");

        String events = resource("/static/js/comment/events.js");
        assertThat(events)
                // 클릭한 댓글의 사진들만 목록으로 삼는다
                .contains("target.closest('.comment-images')")
                .contains("group.querySelectorAll('.comment-image')")
                // 양끝에서 순환
                .contains("index = (nextIndex + images.length) % images.length")
                // 1장이면 좌우 버튼 숨김
                .contains("modal.classList.toggle('is-single', images.length <= 1)")
                // 버튼 클릭이 이미지 클릭(닫기)으로 전파되지 않게 한다
                .contains("e.stopPropagation()")
                // 확대 이미지 클릭 닫기 유지
                .contains("if (e.target === modalImg)")
                // 모달이 닫혀 있으면 키 이벤트에 간섭하지 않는다
                .contains("if (!isOpen()) return;")
                .contains("e.key === 'ArrowLeft'")
                .contains("e.key === 'ArrowRight'")
                .contains("e.key === 'Escape'");
    }

    @Test
    void commentImageModalNavReusesTheMainModalButtonDesign() throws IOException {
        String css = resource("/static/css/comment.css");
        String nav = between(css, ".image-modal .comment-image-nav {", "}");

        assertThat(nav)
                .contains("border-radius: 50%")
                .contains("background: rgba(0, 0, 0, 0.45)")
                .contains("border: 1px solid rgba(255, 255, 255, 0.22)")
                .contains("color: #fff");
        assertThat(css)
                .contains(".image-modal .comment-image-nav:hover")
                .contains("background: rgba(0, 0, 0, 0.72)")
                .contains(".image-modal.is-single .comment-image-nav");
    }

    @Test
    void removedPhotoGalleryHasNoRemainingEventHandlers() throws IOException {
        String events = resource("/static/js/comment/events.js");

        assertThat(events)
                .doesNotContain(".view-all", "photo-modal", "fetchThumbnails");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readFile(String path) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)),
                StandardCharsets.UTF_8);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
