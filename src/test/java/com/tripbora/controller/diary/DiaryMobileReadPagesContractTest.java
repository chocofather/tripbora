package com.tripbora.controller.diary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좁은 화면 읽기는 펼침(두 장)이 아니라 다이어리의 모든 장을 한 줄로 넘긴다.
 * 장 HTML 은 PC 넘김·PDF 와 같은 서버 펼침 조각을 그대로 쓰고, PC 펼침 화면은 그대로 둔다.
 */
class DiaryMobileReadPagesContractTest {

    private static final Path DETAIL = Path.of("src/main/resources/templates/diary/detail.html");
    private static final Path MOBILE_JS = Path.of("src/main/resources/static/js/diary-mobile-pages.js");
    private static final Path FLIP_JS = Path.of("src/main/resources/static/js/diary-page-flip.js");
    private static final Path DIARY_CSS = Path.of("src/main/resources/static/css/diary.css");

    @Test
    void theMobileTrackReusesTheServerSpreadFragmentAndOnlyRealPages() throws IOException {
        String detail = read(DETAIL);
        String js = read(MOBILE_JS);

        // 전체 장 수는 읽기 조각이 함께 내려 준다(새 조회 없이 이미 있는 목록 크기)
        assertThat(detail).contains("th:data-page-count=\"${#lists.size(diaryPages)}\"");
        assertThat(detail).contains("<script src=\"/js/diary-mobile-pages.js\" defer></script>");

        // 옆 펼침은 PC 넘김과 같은 조각 주소에서 받아 그 장들을 그대로 옮겨 붙인다
        assertThat(js)
                .contains("board.dataset.spreadUrl")
                .contains("getElementById('diary-read-board')")
                .contains("window.diaryPageScale?.refresh(page)")
                .contains("window.diaryTape?.render(item)");
        // 아직 쓰지 않은 빈 종이는 장이 아니다
        assertThat(js).contains("hasAttribute('data-diary-page-order')");
        // 장 순번은 다이어리 안 위치다(왼쪽 장 = 홀수 번째, 오른쪽 장 = 짝수 번째)
        assertThat(js).contains("spread * 2 + side + 1");
        // 저장 좌표에는 손대지 않는다
        assertThat(js).doesNotContain("positionX").doesNotContain("style.left");
    }

    @Test
    void onlyTheNarrowReadScreenDropsTheSpreadControls() throws IOException {
        String css = read(DIARY_CSS);
        String flip = read(FLIP_JS);

        String narrow = between(css, "@media (max-width: 860px) {", "@media (max-width: 780px) {");
        // 연필은 지금 장 하나, 펼침 화살표 없음, 빈 오른쪽 종이 없음
        assertThat(narrow)
                .contains(".diary-read-tools-page.is-right {")
                .contains(".diary-spread-nav.is-read .diary-spread-arrow {")
                .contains(".diary-sheet-right:not([data-diary-page-order])");
        // PC 의 세 칸 줄(왼쪽 연필 / 가운데 / 오른쪽 연필)은 그대로다
        assertThat(between(css, "\n.diary-read-tools {", "}"))
                .contains("grid-template-columns: minmax(0, 1fr) 26px minmax(0, 1fr)");

        // PC 넘김은 좁은 화면에서 방향키를 가져가지 않고, 조각을 갈면 모바일 줄을 다시 붙인다
        assertThat(flip)
                .contains("if (compactLayout.matches) return;")
                .contains("window.diaryMobilePages?.attach(board);");
    }

    @Test
    void oneSwipeNeverShowsMoreThanTheNeighbourPage() throws IOException {
        String css = read(DIARY_CSS);
        String js = read(MOBILE_JS);

        /*
          스크립트가 붙으면 일반 carousel 이다. 보이는 창은 잘라 두고(가로 스크롤 없음),
          모든 장을 담은 줄(.diary-page-strip) 하나에만 transform 을 건다. 장마다 transform 을 걸지 않는다.
          세로 손짓과 확대는 브라우저에 맡긴다.
        */
        String narrow = between(css, "@media (max-width: 860px) {", "@media (max-width: 780px) {");
        assertThat(between(narrow, ".is-page-track .diary-book-spread {", "}"))
                .contains("overflow: hidden;")
                .contains("scroll-snap-type: none;")
                .contains("touch-action: pan-y pinch-zoom;");
        assertThat(between(narrow, ".diary-page-strip {", "}"))
                .contains("transform: translate3d(calc(var(--diary-page-index, 0) * -100% + var(--diary-page-drag, 0px)), 0, 0);")
                .contains("will-change: transform;");
        assertThat(between(narrow, ".diary-page-strip > .diary-sheet-viewport {", "}"))
                .contains("flex: 0 0 100%;")
                .doesNotContain("transform");
        assertThat(between(narrow, ".diary-page-strip.is-settling {", "}"))
                .contains("var(--diary-page-settle, 200ms)");

        // 끄는 거리는 지금 장 기준 한 장 폭 안으로 묶이고, 놓으면 지금 장 ±1 장에만 앉는다
        assertThat(between(js, "function clampOffset(raw) {", "\n    }"))
                .contains("Math.max(-width, Math.min(width, raw))");
        assertThat(between(js, "function onPointerUp(event) {", "\n    }"))
                .contains("settleTo(moved !== 0 && (farEnough || flicked) ? state.index + direction : state.index)");
        // 방향은 몇 px 만에 정하고, 세로가 더 크면 넘김을 시작하지 않는다
        assertThat(js).contains("const DIRECTION_LOCK_PX = 6;");
        assertThat(between(js, "function onPointerMove(event) {", "\n    }"))
                .contains("if (Math.abs(dy) >= Math.abs(dx)) {")
                // 가로로 정해지면 처음 눌렀던 자리부터의 거리를 그대로 따라온다
                .contains("state.offset = clampOffset(drag.baseOffset + dx);");
        // 앉는 중에도 다시 잡을 수 있다(입력을 막아 두지 않는다)
        assertThat(js).doesNotContain("animating");
        // 예전의 "관성으로 지나간 뒤 되돌리기" 는 남아 있지 않다
        assertThat(js).doesNotContain("scrollTo(").doesNotContain("scrollBy(").doesNotContain("scrollLeft")
                .doesNotContain("IntersectionObserver");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        assertThat(startIndex).as("start %s", start).isNotNegative();
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
