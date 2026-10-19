package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플래너의 참여자 목록 계약.
 * ACTIVE 인 사람만, 방 표시 이름과 역할만 보여 주는 조회 전용 화면이다.
 */
class TravelPlanMemberListContractTest {

    @Test
    void theListOnlyReadsActiveMembersAndNeverTouchesAccounts() throws IOException {
        String select = between(resource("/mapper/TravelPlanMapper.xml"),
                "<select id=\"findActiveMembersByPlanId\"", "</select>");

        assertThat(select)
                .contains("FROM travel_plan_members")
                .contains("WHERE travel_plan_id = #{travelPlanId}")
                // LEFT / REMOVED 는 여기서 걸러진다
                .contains("AND status = #{memberStatus}")
                // 화면에 쓰는 컬럼만 읽는다
                .contains("SELECT id, display_name, role")
                .doesNotContain("user_id")
                .doesNotContain("JOIN users")
                .doesNotContain("email")
                .doesNotContain("nickname")
                .doesNotContain("${");
    }

    @Test
    void theOwnerComesFirstAndTheRestKeepTheirJoinOrder() throws IOException {
        String select = between(resource("/mapper/TravelPlanMapper.xml"),
                "<select id=\"findActiveMembersByPlanId\"", "</select>");

        // 실제 스키마에 있는 joined_at 을 참여 순서로 쓴다
        assertThat(select)
                .contains("ORDER BY CASE WHEN role = 'OWNER' THEN 0 ELSE 1 END")
                .contains("joined_at ASC")
                .contains("id ASC");

        String schema = Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
        assertThat(between(schema, "CREATE TABLE `travel_plan_members`", ") ENGINE=InnoDB"))
                .contains("`joined_at`");
    }

    @Test
    void theViewOnlyEverSeesADisplayNameAndARole() throws IOException {
        String dto = Files.readString(
                Path.of("src/main/java/com/tripbora/dto/TravelPlanMemberDto.java"),
                StandardCharsets.UTF_8);

        // 모델을 그대로 넘기지 않고 필요한 값만 옮겨 담는다
        assertThat(dto)
                .contains("private String displayName")
                .contains("private TravelPlanRole role")
                .contains("private boolean currentUser")
                // 내보내기 대상만 가리키는 방 참여 id 다. users.id 가 아니다
                .contains("private Long memberId")
                .doesNotContain("private Long userId")
                .doesNotContain("email")
                .doesNotContain("nickname");
    }

    @Test
    void theCountComesFromTheListItselfNotASecondQuery() throws IOException {
        String detailDto = Files.readString(
                Path.of("src/main/java/com/tripbora/dto/TravelPlanDetailDto.java"),
                StandardCharsets.UTF_8);

        assertThat(detailDto)
                .contains("private List<TravelPlanMemberDto> members")
                .contains("private int memberLimit")
                .contains("return members == null ? 0 : members.size()");
    }

    @Test
    void thePlannerShowsTheCountAsAnEntryPointForEveryMember() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("data-travel-plan-members")
                .contains("'참여자 ' + ${travelPlan.memberCount} + '/'")
                .contains("${travelPlan.memberLimit}");

        // 참여자 진입점에는 OWNER 조건이 걸려 있지 않다
        String membersBlock = between(detail,
                "<div class=\"travel-plan-members\"", "data-travel-plan-members-panel");
        assertThat(membersBlock).doesNotContain("OWNER");
    }

    @Test
    void onlyTheOwnerKeepsTheInviteEntryPointBesideIt() throws IOException {
        String detail = detailHtml();

        // 초대는 그대로 OWNER 전용이고, 같은 줄에서 참여자보다 앞에 온다
        int members = detail.indexOf("data-travel-plan-members");
        // 방장이 바뀔 수 있어 초대는 갈아 끼우는 자리에 있다
        int ownerActions = detail.indexOf("data-travel-plan-owner-actions");
        assertThat(ownerActions).isGreaterThan(0);
        assertThat(members).isGreaterThan(ownerActions);
        assertThat(ownerActionsHtml())
                .contains("class=\"travel-plan-invite\"")
                .contains("travelPlan.currentMember.role.name() == 'OWNER'");
        assertThat(detail).contains("class=\"travel-plan-top-actions\"");
    }

    @Test
    void thePanelSeparatesTheOwnerWithSmallTextRatherThanAColouredCard() throws IOException {
        String detail = detailHtml();
        String members = membersHtml();
        String css = resource("/static/css/travel-plan.css");

        assertThat(members)
                .contains("함께 계획하는 사람들")
                .contains("th:each=\"member : ${members}\"")
                .contains("th:text=\"${member.displayName}\"")
                // 방장일 때만 적는다. 나머지가 멤버라는 것은 되풀이할 말이 아니다
                .contains("th:if=\"${member.role.name() == 'OWNER'}\">방장</span>")
                .doesNotContain("'멤버'")
                // 나라는 표시는 이름에 괄호로 붙이지 않고 따로 떼어 둔다
                .contains("th:if=\"${member.currentUser}\"")
                .contains(">나</span>")
                .doesNotContain("(나)")
                .contains("'명 참여'");

        // 역할은 작은 글자로만 구분한다
        assertThat(between(css, ".travel-plan-member-role {", "}")).contains("font-size: 11px");
        // 이름 / 역할 / 메뉴 세 칸을 모든 행이 똑같이 쓴다.
        // 자식 수에 따라 자리가 달라지는 space-between 은 쓰지 않는다
        String row = between(css, "\n.travel-plan-member {", "}");
        assertThat(row)
                .contains("display: grid")
                .contains("grid-template-columns: minmax(0, 1fr) auto 28px")
                .doesNotContain("space-between");
        // 메뉴가 없는 행도 세 번째 칸을 비워 둔다
        assertThat(between(css, ".travel-plan-member-menu {", "}")).contains("justify-self");
        // 이름이 길어져도 역할 칸이 밀리지 않는다
        assertThat(between(css, ".travel-plan-member-role {", "}"))
                .contains("justify-self: end")
                .contains("white-space: nowrap");
        // 방장만 조금 진해진다. 색 있는 배지를 두르지 않는다
        assertThat(between(css, ".travel-plan-member-role.is-owner {", "}"))
                .contains("font-weight: 600")
                .contains("color: var(--tp-plan-accent)")
                .doesNotContain("background");
        assertThat(outsideThePollModal(detail))
                .doesNotContain("modal").doesNotContain("dialog");
    }

    @Test
    void bothPopoversFitTheScreenOnNarrowWidths() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        /*
          좁은 화면에서는 누른 버튼이 아니라 버튼 줄 전체를 기준으로 편다.
          버튼이 줄 가운데에 있으면 그 버튼 기준으로는 어느 쪽으로 펴도
          한쪽이 화면 밖으로 나간다.
        */
        String narrow = between(css, ".travel-plan-top-actions {\n        position: relative;",
                "\n}");
        assertThat(narrow)
                .contains("position: static")
                .contains("left: 0")
                .contains("right: 0")
                .contains("max-width: none");

        /*
          이 규칙은 두 패널의 기본 규칙보다 뒤에 있어야 한다.
          미디어 쿼리는 우선순위를 더해 주지 않아, 앞에 두면 그대로 덮인다.
        */
        int membersBase = css.indexOf(".travel-plan-members-panel {");
        int inviteBase = css.indexOf(".travel-plan-invite-panel {");
        int override = css.indexOf(".travel-plan-top-actions {\n        position: relative;");
        assertThat(override).as("좁은 화면 규칙이 기본 규칙보다 뒤에 온다")
                .isGreaterThan(membersBase)
                .isGreaterThan(inviteBase);
    }

    @Test
    void aLongNameYieldsInsteadOfCoveringTheBadges() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 이름 칸이 줄어들고 긴 이름은 줄바꿈된다. "나" / "방장" / ⋯ 는 제자리다
        assertThat(between(css, ".travel-plan-member-name {", "}"))
                .contains("min-width: 0")
                .contains("overflow-wrap: break-word");
        assertThat(between(css, ".travel-plan-member {", "}"))
                .contains("grid-template-columns: minmax(0, 1fr) auto 28px");
        // 접속 점은 찌그러지지 않는다
        assertThat(between(css, ".travel-plan-presence-dot {", "}"))
                .contains("width: 6px")
                .contains("height: 6px");
        // 초대 링크 칸도 복사 버튼을 밀어내지 않는다
        assertThat(between(css, ".travel-plan-invite-url {", "}"))
                .contains("min-width: 0");
        assertThat(between(css, ".travel-plan-invite-copy {", "}")).contains("flex: 0 0 auto");
    }

    @Test
    void thePanelNeverPutsAUserIdOnThePage() throws IOException {
        // 팝오버의 속이 통째로 옮겨 다니므로 조각 전체를 본다
        String panel = membersHtml();

        for (String personal : new String[]{
                "userId", "user_id", "username", "email", "nickname"}) {
            assertThat(panel).as("개인정보 노출: %s", personal).doesNotContain(personal);
        }
        // 사용자 식별은 방 참여 id 로만 한다
        assertThat(panel).contains("data-member-id=${member.memberId}");
    }

    @Test
    void theOwnerGetsARemoveActionOnMemberRowsOnly() throws IOException {
        String members = membersHtml();

        // ⋯ 는 OWNER 가 볼 때, MEMBER 줄에만 붙는다 (OWNER 자신의 줄은 role 로 걸러진다)
        assertThat(members)
                .contains("th:if=\"${viewerIsOwner and member.role.name() == 'MEMBER'}\"")
                .contains("data-travel-plan-member-menu")
                .contains(">⋯</button>")
                .contains("/members/${member.memberId}/remove|}")
                .contains("내보내기");
        // 확인 문구를 거쳐야 POST 된다
        assertThat(members)
                .contains("님을 이 여행에서 내보낼까요?")
                .contains("내보낸 뒤에는 현재 초대 링크만으로 다시 참여할 수 없습니다.");

        // 이번 단계에 없는 관리 액션
        for (String notYet : new String[]{"강퇴", "이름 변경", "기록 삭제"}) {
            assertThat(members).as("아직 없는 기능: %s", notYet).doesNotContain(notYet);
        }
        // ⋯ 메뉴 안에는 방장 넘기기와 내보내기 둘뿐이다
        assertThat(countOf(members, "data-travel-plan-member-menu-list")).isEqualTo(1);
        assertThat(countOf(members, "class=\"travel-plan-member-remove\"")).isEqualTo(1);
        // 방장 넘기기 + 이전 참여자의 다시 참여 허용
        assertThat(countOf(members, "class=\"travel-plan-member-action\"")).isEqualTo(2);
        // 관리 항목이 상세 화면에 따로 남아 있지 않다 (조각 하나가 유일한 출처다)
        assertThat(detailHtml()).doesNotContain("data-travel-plan-member-menu");
    }

    @Test
    void theOwnerCanHandTheRoomOverFromTheSameMenu() throws IOException {
        String members = membersHtml();

        // 방장 넘기기도 MEMBER 줄에만 붙는다 (OWNER 자신의 줄에는 메뉴 자체가 없다)
        int menu = members.indexOf("th:if=\"${viewerIsOwner and member.role.name() == 'MEMBER'}\"");
        int transfer = members.indexOf("/members/${member.memberId}/transfer-owner|}");
        int remove = members.indexOf("/members/${member.memberId}/remove|}");
        assertThat(menu).isGreaterThan(0);
        assertThat(transfer).isGreaterThan(menu);
        // 메뉴 안에서 방장 넘기기가 내보내기보다 먼저 온다
        assertThat(transfer).isLessThan(remove);
        assertThat(members).contains("방장 넘기기");

        // 바로 POST 하지 않고 확인을 거친다
        assertThat(members)
                .contains("님에게 방장을 넘길까요?")
                .contains("초대와 멤버 관리 권한을 갖게 됩니다")
                .contains("나는 일반 멤버가 됩니다");
        // 새 UI framework 를 만들지 않는다
        assertThat(members).doesNotContain("modal").doesNotContain("dialog");
        assertThat(outsideThePollModal(detailHtml()))
                .doesNotContain("modal").doesNotContain("dialog");
    }

    @Test
    void theOwnerOnlyUiFollowsTheMembershipRoleWithNoSecondSourceOfTruth() throws IOException {
        String detail = detailHtml();

        /*
          방장에게만 보이는 것들이 모두 membership role 하나만 본다.
          이전이 끝나면 그 조각을 다시 받아 자연스럽게 뒤바뀐다.

          상세 화면에는 방장 조건이 남아 있지 않다.
          초대·확정·확정 확인 창은 갈아 끼우는 조각으로 옮겼고,
          참여자 관리 메뉴는 참여자 조각 안에 있다.
        */
        assertThat(countOf(detail, "currentMember.role.name() == 'OWNER'")).isZero();
        // 방장 전용 조각은 조건 하나로 통째로 갈린다
        assertThat(countOf(ownerActionsHtml(), "currentMember.role.name() == 'OWNER'"))
                .isEqualTo(1);
        assertThat(countOf(membersHtml(), "currentMember.role.name() == 'OWNER'")).isEqualTo(1);
        for (String source : new String[]{detail, membersHtml(), ownerActionsHtml()}) {
            assertThat(source)
                    .doesNotContain("createdByUserId").doesNotContain("created_by_user_id");
        }
    }

    @Test
    void onlyAPlainMemberSeesTheLeaveAction() throws IOException {
        String members = membersHtml();

        // 방장에게는 나가기를 노출하지 않는다
        assertThat(members)
                .contains("th:unless=\"${viewerIsOwner}\"")
                .contains("/members/leave|}")
                .contains(">\n      여행에서 나가기\n    </button>");
        // 최소한의 확인을 거친다
        assertThat(members)
                .contains("이 여행 계획에서 나갈까요?")
                .contains("작성했던 일정은 여행 계획에 그대로 남습니다.");
        // 새 modal framework 를 만들지 않는다
        assertThat(members).doesNotContain("modal").doesNotContain("dialog");
        assertThat(outsideThePollModal(detailHtml()))
                .doesNotContain("modal").doesNotContain("dialog");
    }

    @Test
    void theMemberActionsStayLowKeyRatherThanBigRedButtons() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 완료된 여행의 삭제도 같은 규칙을 함께 쓴다
        String actions = between(css,
                ".travel-plan-member-remove,\n.travel-plan-member-leave,", "}");
        assertThat(actions)
                .contains("background: none")
                .contains("font-size: 12px")
                .contains("border: 0");
        /*
          ⋯ 는 조용히 두되 손은 닿아야 한다.
          글자는 작아도 눌리는 자리는 28px 을 채운다.
        */
        String menuButton = between(css, ".travel-plan-member-menu-button {", "}");
        assertThat(menuButton)
                .contains("color: var(--tp-plan-ink-faint)")
                .contains("width: 28px")
                .contains("height: 28px")
                // 테두리를 두른 큰 원형 버튼으로 만들지 않는다
                .contains("border: 0")
                .contains("background: none");
        assertThat(css).contains(".travel-plan-member:hover .travel-plan-member-menu-button");
    }

    @Test
    void bothTopPopoversShareOneOpenCloseRuleWithoutANewFramework() throws IOException {
        String script = resource("/static/js/travel-plan-scheduler.js");

        assertThat(script)
                .contains("registerPopover(")
                .contains("data-travel-plan-members-toggle")
                .contains("data-travel-plan-invite-toggle")
                // 하나를 열면 다른 하나는 닫힌다
                .contains("closePopovers(popover)")
                // 바깥 클릭 / Esc 로 닫힌다
                .contains("closePopovers(null)")
                .contains("if (event.key !== \"Escape\") return")
                // 참여자 줄의 ⋯ 도 같은 방식으로 하나만 열어 둔다
                .contains("closeMemberMenus(menu)")
                .contains("closeMemberMenus(null)");
        assertThat(script)
                .doesNotContain("WebSocket")
                .doesNotContain("StompJs")
                .doesNotContain("setInterval");
    }

    @Test
    void neitherActionEverDeletesAMemberRow() throws IOException {
        String mapper = resource("/mapper/TravelPlanMapper.xml");

        // 과거 참여 기록과 일정/대안의 created_by_member_id 가 남아야 한다
        assertThat(mapper).doesNotContain("DELETE FROM travel_plan_members");

        String left = between(mapper, "<update id=\"markMemberLeft\"", "</update>");
        assertThat(left)
                .contains("UPDATE travel_plan_members")
                .contains("SET status = #{toStatus}")
                .contains("left_at = CURRENT_TIMESTAMP")
                // 나중에 다시 들어올 여지는 남긴다
                .contains("rejoin_allowed = 1")
                .contains("updated_at = CURRENT_TIMESTAMP")
                .doesNotContain("${");

        String removed = between(mapper, "<update id=\"markMemberRemoved\"", "</update>");
        assertThat(removed)
                .contains("SET status = #{toStatus}")
                .contains("removed_at = CURRENT_TIMESTAMP")
                // 지금 가진 초대 링크만으로 되돌아오지 못한다
                .contains("rejoin_allowed = 0")
                .doesNotContain("${");

        // 작성자 기록은 어느 쪽에서도 건드리지 않는다
        for (String update : new String[]{left, removed}) {
            assertThat(update)
                    .doesNotContain("display_name")
                    .doesNotContain("created_by_member_id");
        }
    }

    @Test
    void theStatusChangesOnlyLandOnAnActiveMemberOfThatRoom() throws IOException {
        String mapper = resource("/mapper/TravelPlanMapper.xml");

        // 조건부 UPDATE 라 두 번 눌러도 한 번만 반영되고 OWNER 는 절대 걸리지 않는다
        for (String id : new String[]{"markMemberLeft", "markMemberRemoved"}) {
            assertThat(between(mapper, "<update id=\"" + id + "\"", "</update>"))
                    .as("%s", id)
                    .contains("WHERE id = #{id}")
                    .contains("AND travel_plan_id = #{travelPlanId}")
                    .contains("AND status = #{fromStatus}")
                    .contains("AND role = #{role}");
        }

        // 대상 조회도 방 소속을 함께 확인한다
        assertThat(between(mapper, "<select id=\"findMemberByPlanAndId\"", "</select>"))
                .contains("WHERE travel_plan_id = #{travelPlanId}")
                .contains("AND id = #{memberId}")
                .doesNotContain("${");
    }

    @Test
    void everyMemberPostIsCsrfProtectedAndAuthenticated() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 나가기/내보내기/방장 넘기기/재참여 허용 POST 는 기본 CSRF 정책이 보호한다
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
        // 인가는 그대로 anyRequest().authenticated() 를 쓴다
        assertThat(securityConfig).doesNotContain("/travel-plans/**");
    }

    @Test
    void thePastMemberSectionIsOwnerOnlyAndOffersNothingButLettingThemBackIn()
            throws IOException {
        String members = membersHtml();

        assertThat(members)
                .contains("th:if=\"${viewerIsOwner and !#lists.isEmpty(pastMembers)}\"")
                .contains(">이전 참여자</p>")
                .contains("th:each=\"past : ${pastMembers}\"")
                .contains("th:text=\"${past.displayName}\"")
                .contains("/members/${past.memberId}/allow-rejoin|}")
                .contains("다시 참여 허용")
                // 이미 허용한 사람에게는 버튼 대신 상태만 보여 준다
                .contains("th:if=\"${past.rejoinAllowed}\"")
                .contains("재참여 허용됨")
                .contains("th:unless=\"${past.rejoinAllowed}\"")
                .contains("내보낸 멤버");

        // 바로 복귀하는 것이 아니라는 점을 확인 문구에서 알린다
        assertThat(members)
                .contains("님이 다시 초대 링크로 참여할 수 있게 할까요?")
                .contains("참여자로 바로 복귀하는 것은 아니며");

        // 이전 참여자 영역에는 개인정보가 없다
        String past = between(members, "class=\"travel-plan-past-members\"",
                "<!-- 나가기는 MEMBER 본인에게만");
        for (String personal : new String[]{
                "userId", "user_id", "username", "email", "nickname"}) {
            assertThat(past).as("개인정보 노출: %s", personal).doesNotContain(personal);
        }
    }

    @Test
    void theRemovedListIsOnlyEverReadForTheOwner() throws IOException {
        String select = between(resource("/mapper/TravelPlanMapper.xml"),
                "<select id=\"findMembersByPlanAndStatus\"", "</select>");

        assertThat(select)
                .contains("SELECT id, display_name, role, rejoin_allowed")
                .contains("FROM travel_plan_members")
                .contains("WHERE travel_plan_id = #{travelPlanId}")
                .contains("AND status = #{memberStatus}")
                .doesNotContain("user_id")
                .doesNotContain("JOIN users")
                .doesNotContain("${");

        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanService.java"),
                StandardCharsets.UTF_8);
        String pastMembers = between(service, "private List<TravelPlanPastMemberDto> pastMembers(",
                "private void requireUser");
        // OWNER 가 아니면 조회조차 하지 않는다
        assertThat(pastMembers.indexOf("!= TravelPlanRole.OWNER"))
                .isLessThan(pastMembers.indexOf("findMembersByPlanAndStatus"));
        assertThat(pastMembers).contains("TravelPlanMemberStatus.REMOVED.name()");
    }

    @Test
    void lettingSomeoneBackInOnlyLiftsTheFlag() throws IOException {
        String update = between(resource("/mapper/TravelPlanMapper.xml"),
                "<update id=\"allowMemberRejoin\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plan_members")
                .contains("SET rejoin_allowed = 1")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_id = #{travelPlanId}")
                .contains("AND status = #{memberStatus}")
                .contains("AND role = #{role}")
                // 이미 허용된 사람에게 두 번 반영되지 않는다
                .contains("AND rejoin_allowed = 0")
                .doesNotContain("${");

        // 상태는 REMOVED 그대로 둔다. 바로 복귀시키지 않는다
        assertThat(between(update, "SET", "WHERE"))
                .doesNotContain("status")
                .doesNotContain("removed_at")
                .doesNotContain("display_name");

        // 다시 내보내면 언제나 rejoin_allowed 가 내려간다
        assertThat(between(resource("/mapper/TravelPlanMapper.xml"),
                "<update id=\"markMemberRemoved\"", "</update>"))
                .contains("rejoin_allowed = 0");
    }

    @Test
    void theHandoverOnlyEverSwapsTheRoomRole() throws IOException {
        String update = between(resource("/mapper/TravelPlanMapper.xml"),
                "<update id=\"changeMemberRole\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plan_members")
                .contains("SET role = #{toRole}")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_id = #{travelPlanId}")
                .contains("AND status = #{memberStatus}")
                // 이미 바뀐 상태에는 두 번 반영되지 않는다
                .contains("AND role = #{fromRole}")
                .doesNotContain("${");

        // 방 안의 역할만 바꾼다. 신분/상태/이름은 그대로다
        assertThat(between(update, "SET", "WHERE"))
                .doesNotContain("status")
                .doesNotContain("user_id")
                .doesNotContain("display_name");

        // OWNER 유일성을 보장하는 DB 제약은 없으므로 Service 가 지킨다
        String schema = Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
        assertThat(between(schema, "CREATE TABLE `travel_plan_members`", ") ENGINE=InnoDB"))
                .doesNotContain("UNIQUE KEY `uk_travel_plan_members_plan_role`");

        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanMemberService.java"),
                StandardCharsets.UTF_8);
        String transfer = between(service, "public void transferOwnership(", "private void requireActivePlan");
        // 잠금 -> 방장 재확인 -> 내려놓기 -> 넘기기 순서다
        assertThat(transfer.indexOf("findPlanByIdAndStatusForUpdate"))
                .isLessThan(transfer.indexOf("requireActiveMember"));
        assertThat(transfer.indexOf("OWNER.name(), TravelPlanRole.MEMBER.name()"))
                .isLessThan(transfer.indexOf("MEMBER.name(), TravelPlanRole.OWNER.name()"));
        // 두 UPDATE 모두 영향 행이 1 인지 본다
        assertThat(countOf(transfer, "!= 1")).isEqualTo(2);
    }

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    /**
     * 참여자 팝오버의 속.
     * 처음 그릴 때와 실시간 갱신이 같은 조각을 쓰므로 화면이 갈라지지 않는다.
     */
    private String membersHtml() throws IOException {
        return resource("/templates/travelplan/fragments/members.html");
    }

    /**
     * 방장에게만 있는 상단 액션(확정 / 초대 / 확정 확인 창).
     * 방장이 바뀌면 통째로 갈리므로 상세 화면이 아니라 이 조각에 있다.
     */
    private String ownerActionsHtml() throws IOException {
        return resource("/templates/travelplan/fragments/owner-actions.html");
    }

    /**
     * 따로 뜨는 창들을 뺀 나머지 화면.
     * 창을 띄우는 것은 투표 센터와 확정 확인뿐이고,
     * 참여자·초대·일정은 지금도 그 자리에서 다룬다.
     */
    private String outsideThePollModal(String detail) {
        // 공용 템플릿의 확정본 전용 조각은 계획 중 참여자 UI의 계약에 포함하지 않는다.
        var activeDetail = org.jsoup.Jsoup.parse(detail);
        activeDetail.getElementsByAttributeValue("th:if", "${planReadonly == true}").remove();
        detail = activeDetail.outerHtml();
        // 확정 확인 창은 방장 전용 조각으로 옮겨 가 여기에는 투표 센터만 남았다
        int pollStart = detail.indexOf("class=\"travel-plan-poll-modal\"");
        assertThat(pollStart).as("투표 센터 창").isGreaterThanOrEqualTo(0);
        return detail.substring(0, pollStart);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
