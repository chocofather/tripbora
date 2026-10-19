package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 함께 계획하기 목록 화면.
 *
 * <p>진행 중·완료·전체를 같은 그리드에서 전환한다.
 * 쪽을 나누는 것은 계속 쌓이는 완료된 여행뿐이다.
 */
class TravelPlanListUiContractTest {

    // ── 두 구역 ─────────────────────────────────────────────

    @Test
    void bothListsLiveOnTheSameScreen() throws IOException {
        String list = listHtml();

        assertThat(list)
                .contains("data-plan-filter=\"active\"")
                .contains("data-plan-filter=\"completed\"")
                .contains("data-plan-filter=\"all\"")
                .contains("th:each=\"plan : ${travelPlans}\"")
                .contains("th:each=\"plan : ${completedTravelPlans}\"");
        // 어느 쪽도 다른 쪽에 가려지지 않는다
        assertThat(list).contains("data-plan-grid").doesNotContain("travel-plan-section-title");
    }

    @Test
    void eachSectionWearsItsOwnTotal() throws IOException {
        String list = listHtml();

        assertThat(list)
                .contains("th:text=\"${travelPlanPage.activeCount}\"")
                .contains("th:text=\"${travelPlanPage.completedCount}\"");
    }

    @Test
    void anEmptySectionKeepsItsPlaceWithOneShortLine() throws IOException {
        String list = listHtml();

        // 목록이 비어도 구역과 제목은 그대로 있고 한 줄만 적힌다
        assertThat(list)
                .contains("th:if=\"${#lists.isEmpty(travelPlans)}\"")
                .contains("진행 중인 여행이 없어요.");
        assertThat(resource("/static/js/travel-plan-list.js")).contains("아직 완료된 여행이 없어요.");
    }

    // ── 쪽 이동 ─────────────────────────────────────────────

    @Test
    void onlyTheFinishedListHasAPager() throws IOException {
        String list = listHtml();

        assertThat(list).contains(
                "class=\"travel-plan-pagination\""
                        + " th:if=\"${travelPlanPage.completedTotalPages > 1}\"");
        // 쪽 이동은 완료된 여행 구역 안에만 있다
        assertThat(list.indexOf("data-plan-grid"))
                .isLessThan(list.indexOf("class=\"travel-plan-pagination\""));
        assertThat(list).contains("data-completed-pagination hidden");
    }

    @Test
    void thePagerCarriesOnlyTheCompletedPage() throws IOException {
        String pager = between(listHtml(), "class=\"travel-plan-pagination\"", "</nav>");

        assertThat(pager)
                .contains("@{/travel-plans(completedPage=${i})}")
                .contains("completedPage=${travelPlanPage.completedPage - 1}")
                .contains("completedPage=${travelPlanPage.completedPage + 1}");
        // 쪽을 옮긴 뒤 완료된 여행 구역으로 바로 돌아온다
        assertThat(pager).contains("'#tp-trips-panel'");
    }

    // ── 상단 / 카드 ─────────────────────────────────────────

    @Test
    void makingANewPlanUsesTheExistingInlineForm() throws IOException {
        String header = between(listHtml(), "class=\"travel-plan-list-header\"", "</header>");

        assertThat(header)
                .contains("함께 계획하기")
                .contains("함께 떠날 순간을, 함께 계획해요.");
        assertThat(listHtml())
                .contains("~{travelplan/fragments/create-inline :: createInline}")
                .doesNotContain("th:href=\"@{/travel-plans/new}\"");
    }

    @Test
    void theNewPlanButtonStaysQuieterThanTheListItself() throws IOException {
        String button = between(resource("/static/css/travel-plan.css"),
                ".travel-plan-new-link {", "}");

        // 눌리는 자리는 남기되(32px) 나머지는 한 단계씩 낮춘다
        assertThat(button)
                .contains("min-height: 32px")
                .contains("padding: 4px 10px")
                .contains("font-size: 12px")
                .contains("font-weight: 600");
    }

    @Test
    void aCardStillLeadsWhereItAlwaysDid() throws IOException {
        String list = listHtml();

        // 진행 중은 편집방으로, 완료는 읽기 전용 최종본으로
        assertThat(list)
                .contains("@{|/travel-plans/${plan.travelPlanId}|}")
                .contains("@{|/travel-plans/${plan.travelPlanId}/final|}");
    }

    @Test
    void thereIsNoEmptyPictureFrameOnACard() throws IOException {
        String list = listHtml();

        // 대표 이미지 데이터가 없다. 빈 자리를 만들어 두지 않는다
        assertThat(list)
                .doesNotContain("travel-plan-card-thumb")
                .doesNotContain("representativeImageUrl")
                .doesNotContain("<img");
        assertThat(resource("/static/css/travel-plan.css"))
                .doesNotContain(".travel-plan-card-thumb");
    }

    @Test
    void aRunningCardShowsWhatIsWorthKnowingAtAGlance() throws IOException {
        String card = between(listHtml(), "class=\"travel-plan-card-link\"", "</li>");

        assertThat(card)
                .contains("${plan.title}")
                .contains("${plan.dayCount}")
                .contains("'참여 ' + ${plan.memberCount} + '/8명'")
                // 내 자리는 둘 다 적되 약하게 둔다
                .contains("? '방장' : '참여자'")
                // 최근 활동은 있을 때만 자리를 차지한다
                .contains("th:if=\"${plan.lastActivityAt != null}\"")
                .contains("최근 활동 ");
    }

    @Test
    void theTwoSectionsAreSeparatedBySpaceRatherThanABox() throws IOException {
        String css = resource("/static/css/travel-plan.css");
        String gap = between(css, ".travel-plan-section + .travel-plan-section {", "}");

        assertThat(gap).contains("margin-top").contains("border-top: 1px solid");
        // 큰 상자나 그림자를 두지 않는다
        assertThat(between(css, ".travel-plan-card-link {", "}"))
                .doesNotContain("box-shadow")
                .contains("border-radius: 6px");
    }

    // ── 색 ──────────────────────────────────────────────────

    @Test
    void aCardIsNearlyWhitePaperRatherThanMoreBeige() throws IOException {
        String css = resource("/static/css/travel-plan.css");
        String card = between(css, ".travel-plan-card-link {", "}");

        // 따뜻한 바탕 위에 놓이는 카드만 중립으로 둔다
        assertThat(card)
                .contains("background: var(--tp-card-surface)")
                .contains("border: 1px solid var(--tp-card-line)");
        assertThat(between(css, "--tp-card-surface:", ";")).contains("#fdfdfd");
        // 갈색 선을 그대로 쓰지 않는다
        assertThat(card).doesNotContain("#e3dcd1").doesNotContain("#fffdf8");
    }

    @Test
    void aFinishedTripIsAResultNotGreyedOutData() throws IOException {
        String css = resource("/static/css/travel-plan.css");
        String completed = between(css, ".travel-plan-card-link.is-completed {", "}");

        /*
          끝난 여행도 사용자가 만들어 낸 결과물이다.
          흐리게 덮어 "지난 데이터" 로 보이게 하지 않는다.
          다른 것은 왼쪽 선의 색과 "완료" 표시뿐이다.
        */
        assertThat(between(css, ".travel-plan-card-link {", "}"))
                .contains("border-left: 3px solid var(--tp-live-line)")
                .contains("background: var(--tp-card-surface)");
        assertThat(completed).contains("border-left-color: var(--tp-card-line-strong)");
        // 종이도 글자도 진행 중인 것과 같다
        assertThat(completed)
                .doesNotContain("background:")
                .doesNotContain("opacity");
        assertThat(css).doesNotContain(
                ".travel-plan-card-link.is-completed .travel-plan-card-title");
    }

    @Test
    void theBadgesTellTheStateApartWithoutShouting() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // OWNER 일 때만 진행 중 색이 배고, MEMBER 는 중립이다
        assertThat(between(css, ".travel-plan-card-role {", "}"))
                .contains("color: var(--tp-card-ink-faint)");
        assertThat(between(css, ".travel-plan-card-role.is-owner {", "}"))
                .contains("color: var(--tp-live-accent)");
        // 완료 배지는 갈색도 파랑도 아닌 회색이고, 읽을 만큼의 대비는 있다
        assertThat(between(css, ".travel-plan-card-completed {", "}"))
                .contains("color: var(--tp-card-ink-soft)")
                .doesNotContain("--tp-poll-");
        assertThat(listHtml()).contains("? ' is-owner' : ''");
    }

    @Test
    void theSectionHeadingsUseTheSameInkAsTheRowsBelowThem() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 제목·숫자·구분선이 목록 줄과 같은 계열을 쓴다
        assertThat(between(css, ".travel-plan-list-header h1 {", "}"))
                .contains("color: var(--tp-card-ink)");
        assertThat(between(css, ".travel-plan-section-title {", "}"))
                .contains("color: var(--tp-card-ink)");
        assertThat(between(css, ".travel-plan-section-count {", "}"))
                .contains("color: var(--tp-card-ink-faint)");
        assertThat(between(css, ".travel-plan-section + .travel-plan-section {", "}"))
                .contains("border-top: 1px solid var(--tp-card-line)");
        /*
          끝난 여행 제목은 한 톤 차분하되 읽기 어려울 만큼 흐리지 않다.
          가장 연한 잉크(--tp-card-ink-faint)까지 내리지 않는다.
        */
        assertThat(between(css,
                ".travel-plan-section.is-completed .travel-plan-section-title {", "}"))
                .contains("color: var(--tp-card-ink-soft)");

        // 목록 화면 규칙에는 갈색·베이지가 남아 있지 않다
        String list = between(css, "/* ───── 함께 계획하기 목록 ───── */",
                "/* ───── 메인 협업 플래너");
        assertThat(list)
                .doesNotContain("#3f3426")
                .doesNotContain("#e8e1d5")
                .doesNotContain("#a1968a")
                .doesNotContain("#6b6259");
    }

    @Test
    void thePageBackgroundUsesTheNeutralTripboraSurface() throws IOException {
        // 기본 바탕은 따뜻한 베이지 대신 중립색을 쓴다
        assertThat(between(resource("/static/css/travel-plan.css"), ":root {", "}"))
                .contains("--tp-page: #fafafc");
    }

    @Test
    void aLongTitleEndsInAnEllipsisInsteadOfPushingThePageWider() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        /*
          flex 칸의 기본 최소 폭은 내용 크기다. 줄바꿈을 막아 둔 글자는
          그 폭이 곧 제목 전체 길이라, min-width 를 0 으로 내려 두지 않으면
          칸이 줄지 못해 제목이 카드 밖으로 나가고 가로 스크롤이 생긴다.
        */
        assertThat(between(css, ".travel-plan-card-title {", "}"))
                .contains("min-width: 0")
                .contains("overflow: hidden")
                .contains("text-overflow: ellipsis")
                .contains("white-space: nowrap");
        // 제목을 담은 줄도 줄어들 수 있어야 한다
        assertThat(between(css, ".travel-plan-card-title-row {", "}")).contains("min-width: 0");
        // 상태 표시는 줄어들지 않고 제 크기를 지킨다
        assertThat(between(css, ".travel-plan-card-role {", "}")).contains("flex: 0 0 auto");
        assertThat(between(css, ".travel-plan-card-completed {", "}")).contains("flex: 0 0 auto");
    }

    @Test
    void nothingInTheListForcesTheScreenWider() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 기간·일수·인원은 좁으면 다음 줄로 접힌다
        assertThat(between(css, ".travel-plan-card-meta {", "}")).contains("flex-wrap: wrap");
        // 제목과 만들기 버튼도 한 줄에서 눌리지 않고 접힌다
        assertThat(between(css, ".travel-plan-list-header {", "}")).contains("flex-wrap: wrap");
        assertThat(between(css, ".travel-plan-list-heading {", "}")).contains("min-width: 0");
        // 쪽 이동도 한 줄을 넘기면 접힌다
        assertThat(between(css, ".travel-plan-pagination {", "}")).contains("flex-wrap: wrap");
    }

    @Test
    void theEmptyStateStaysCompact() throws IOException {
        // 큰 점선 박스로 화면을 차지하지 않는다
        assertThat(between(resource("/static/css/travel-plan.css"), ".travel-plan-empty {", "}"))
                .contains("padding: 14px 12px");
    }

    private String listHtml() throws IOException {
        return resource("/templates/travelplan/list.html");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertThat(from).as("start marker %s", start).isNotNegative();
        int to = source.indexOf(end, from + start.length());
        assertThat(to).as("end marker %s", end).isNotNegative();
        return source.substring(from, to);
    }
}
