package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanFinalizeCheckDto;
import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanFinalDay;
import com.tripbora.model.TravelPlanFinalItem;
import com.tripbora.model.TravelPlanFinalItemAlternative;
import com.tripbora.model.TravelPlanFinalMember;
import com.tripbora.model.TravelPlanFinalSnapshot;
import com.tripbora.model.TravelPlanItem;
import com.tripbora.model.TravelPlanItemAlternative;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.model.TravelPlanMemberStatus;
import com.tripbora.model.TravelPlanRole;
import com.tripbora.model.TravelPlanStatus;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.travelplan.TravelPlanAlternativeMapper;
import com.tripbora.repository.travelplan.TravelPlanItemMapper;
import com.tripbora.repository.travelplan.TravelPlanMapper;
import com.tripbora.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 여행 계획 확정.
 *
 * <p>이번 단계에서 하는 일은 "지금 확정할 수 있는가" 를 보는 것까지다.
 * 진행 상태를 바꾸거나 최종본을 만들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TravelPlanFinalizeServiceTest {

    private static final Long PLAN_ID = 42L;
    private static final Long USER_ID = 7L;
    private static final Long MEMBER_ID = 11L;
    private static final Long DAY_ID = 100L;
    private static final Long ITEM_ID = 500L;
    private static final Long SNAPSHOT_ID = 900L;
    private static final Long FINAL_DAY_ID = 910L;
    private static final Long FINAL_ITEM_ID = 920L;

    @Mock
    private TravelPlanMapper travelPlanMapper;
    @Mock
    private TravelPlanItemMapper travelPlanItemMapper;
    @Mock
    private TravelPlanAlternativeMapper travelPlanAlternativeMapper;
    @Mock
    private com.tripbora.repository.travelplan.TravelPlanFinalMapper
            travelPlanFinalMapper;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 실제 장부를 그대로 쓴다. 누가 무엇을 붙잡고 있는지가 이 판단의 핵심이다. */
    private final TravelPlanEditorRealtimeService editor = new TravelPlanEditorRealtimeService();

    private TravelPlanFinalizeService finalizeService;

    @BeforeEach
    void setUp() {
        TravelPlanRoomAccess roomAccess = new TravelPlanRoomAccess(
                travelPlanMapper, travelPlanItemMapper, travelPlanAlternativeMapper);
        finalizeService = new TravelPlanFinalizeService(
                roomAccess, editor, travelPlanMapper, travelPlanItemMapper,
                travelPlanAlternativeMapper, travelPlanFinalMapper, eventPublisher);
    }

    // ── 확정할 수 있는 사람 ─────────────────────────────────

    @Test
    void anActiveOwnerOfAQuietRoomCanFinalize() {
        givenRoom(TravelPlanRole.OWNER);

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID).canFinalize()).isTrue();
    }

    @Test
    void aPlainMemberCannotFinalize() {
        givenRoom(TravelPlanRole.MEMBER);

        assertThatThrownBy(() -> finalizeService.requireFinalizable(principal(), PLAN_ID, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void whoeverLeftOrWasRemovedCannotFinalize() {
        // ACTIVE 조건이 걸린 조회라 LEFT / REMOVED / 비참여자는 여기서 비어 온다
        givenActivePlan();
        when(travelPlanMapper.findMemberByPlanAndUser(PLAN_ID, USER_ID, "ACTIVE")).thenReturn(null);

        assertThatThrownBy(() -> finalizeService.requireFinalizable(principal(), PLAN_ID, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aRoomThatIsNoLongerActiveCannotBeFinalized() {
        when(travelPlanMapper.findPlanByIdAndStatus(PLAN_ID, "ACTIVE")).thenReturn(null);

        assertThatThrownBy(() -> finalizeService.requireFinalizable(principal(), PLAN_ID, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aRoomThatDoesNotExistCannotBeFinalized() {
        // 없는 방 번호를 보내도 자격 확인에서 걸린다
        assertThatThrownBy(() -> finalizeService.checkFinalizable(principal(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void beingRefusedForWhoYouAreDoesNotComeBackAsAPoliteMessage() {
        /*
          자격이 없는 경우와 지금은 때가 아닌 경우를 다르게 다룬다.
          방장이 아니면 사유를 알려 주지 않고 막고,
          누가 쓰고 있어서 못 하는 것이라면 그 사유를 그대로 보여 준다.
        */
        givenRoom(TravelPlanRole.MEMBER);

        assertThatThrownBy(() -> finalizeService.checkFinalizable(principal(), PLAN_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── 지금 누가 쓰고 있으면 기다린다 ──────────────────────

    @Test
    void someoneWritingIsPointedOutByNameRatherThanBlocking() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());

        TravelPlanFinalizeCheckDto check =
                finalizeService.checkFinalizable(principal(), PLAN_ID);

        // 막지 않는다. 누가 쓰고 있는지 알려 주고 판단은 방장에게 맡긴다
        assertThat(check.canFinalize()).isTrue();
        assertThat(check.activeEditorExists()).isTrue();
        assertThat(check.activeEditorDisplayNames()).containsExactly("쭈니");
    }

    @Test
    void everyWriterComesBack() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());
        editor.tryAcquire(otherItemLock());

        // 잡은 자리를 담아 두는 표가 순서를 지키지 않아 순서까지 약속하지는 않는다
        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID)
                .activeEditorDisplayNames())
                .containsExactlyInAnyOrder("쭈니", "지우");
    }

    @Test
    void onePersonWithTwoEditorsIsStillOnePerson() {
        // 같은 사람이 여러 자리를 잡고 있어도 이름은 한 번만 나온다
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());
        editor.tryAcquire(alternativeLock());

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID)
                .activeEditorDisplayNames())
                .containsExactly("쭈니");
    }

    @Test
    void someoneWritingAnAlternativeIsNoticedTheSameWay() {
        // A 일정과 B/C 대안이 같은 장부에 들어 있어 한 번에 확인된다
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(alternativeLock());

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID).activeEditorExists())
                .isTrue();
    }

    @Test
    void myOwnEditorIsNotSomeoneElseWriting() {
        // 자기 편집기는 자기가 닫으면 된다. 남의 일처럼 알리지 않는다
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(myOwnLock());

        TravelPlanFinalizeCheckDto check =
                finalizeService.checkFinalizable(principal(), PLAN_ID);

        assertThat(check.activeEditorExists()).isFalse();
        assertThat(check.activeEditorDisplayNames()).isEmpty();
    }

    @Test
    void onceTheWritingIsOverThereIsNothingToWarnAbout() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());
        editor.releaseAllBySession("session-A");

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID).activeEditorExists())
                .isFalse();
    }

    // ── 알고도 하겠다면 ─────────────────────────────────────

    @Test
    void aPlainFinalisingStillStopsWhileSomeoneIsWriting() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());

        assertThatThrownBy(() ->
                finalizeService.requireFinalizable(principal(), PLAN_ID, false))
                .isInstanceOf(TravelPlanValidationException.class)
                .hasMessageContaining("현재 편집 중인 일정이 있습니다.");
    }

    @Test
    void theOwnerWhoWasWarnedCanGoAheadAnyway() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());

        assertThat(finalizeService.requireFinalizable(principal(), PLAN_ID, true))
                .isNotNull();
    }

    @Test
    void goingAheadAnywayStillNeedsToBeTheOwner() {
        // 경고를 지나칠 수 있다는 것이 자격까지 지나칠 수 있다는 뜻은 아니다
        givenRoom(TravelPlanRole.MEMBER);
        editor.tryAcquire(itemLock());

        assertThatThrownBy(() ->
                finalizeService.requireFinalizable(principal(), PLAN_ID, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void askingNeverTouchesWhatPeopleAreWriting() {
        /*
          물어보는 것만으로 남의 작성 중 내용을 걷어 내지 않는다.
          실제로 완료할 때 그것을 정리하는 일은 다음 단계의 몫이고,
          지금 구조가 그것을 막지 않는다.
        */
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(itemLock());

        finalizeService.checkFinalizable(principal(), PLAN_ID);
        finalizeService.requireFinalizable(principal(), PLAN_ID, true);

        assertThat(editor.locksOf(PLAN_ID)).hasSize(1);
    }

    @Test
    void writingInAnotherRoomDoesNotHoldThisOneBack() {
        givenRoom(TravelPlanRole.OWNER);
        editor.tryAcquire(new TravelPlanEditorRealtimeService.EditorLock(
                99L, TravelPlanEditorRealtimeService.editLockKey(ITEM_ID), "session-B",
                TravelPlanEditorRealtimeService.EDIT_MODE,
                DAY_ID, ITEM_ID, null, 12L, "쭈니", "", ""));

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID).canFinalize()).isTrue();
    }

    // ── 확정을 막지 않는 것들 ───────────────────────────────

    @Test
    void pollsAndTalkNeverStandInTheWay() {
        /*
          투표는 계획을 정하는 데 도우려는 것이지 확정의 조건이 아니다.
          대화와 접속 인원도 마찬가지다. 아예 묻지 않는다.
        */
        givenRoom(TravelPlanRole.OWNER);

        assertThat(finalizeService.checkFinalizable(principal(), PLAN_ID).canFinalize()).isTrue();
        assertThat(TravelPlanFinalizeService.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .extracting(Class::getSimpleName)
                .doesNotContain("TravelPlanPollService", "TravelPlanPollMapper",
                        "TravelPlanChatService", "TravelPlanChatMapper",
                        "TravelPlanPresenceService");
    }

    // ── 아직 아무것도 바꾸지 않는다 ─────────────────────────

    @Test
    void askingChangesNothingAtAll() {
        givenRoom(TravelPlanRole.OWNER);

        finalizeService.checkFinalizable(principal(), PLAN_ID);

        // 물어보기만 해서는 진행 상태도 최종본도 건드리지 않는다
        org.mockito.Mockito.verify(travelPlanMapper, org.mockito.Mockito.never())
                .updatePlanStatus(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verify(travelPlanFinalMapper, org.mockito.Mockito.never())
                .insertSnapshot(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theSameDoorIsLeftForTheRealFinalisingToUse() throws NoSuchMethodException {
        /*
          물어본 뒤 완료하기 전 사이에 누가 편집을 시작할 수 있다.
          그래서 다음 단계의 실제 완료도 이 자리를 다시 지나가야 한다.
          그때 "알고도 하겠다" 를 함께 넘길 수 있게 자리를 만들어 둔다.
        */
        Method door = TravelPlanFinalizeService.class.getMethod(
                "requireFinalizable", Principal.class, Long.class, boolean.class);

        assertThat(door.getReturnType()).isEqualTo(TravelPlanMember.class);
        assertThat(java.lang.reflect.Modifier.isPublic(door.getModifiers())).isTrue();
    }

    // ── 실제 완료 ───────────────────────────────────────────

    @Test
    void theOwnerFinalisesAndTheRoomWalksThroughFinalising() {
        givenRoomToFinalize();

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        /*
          ACTIVE -> FINALIZING -> 최종본 -> COMPLETED 순서로 간다.
          최종본을 뜨는 동안 FINALIZING 이라, 그 사이 들어오는 일정 저장이 걸린다.
        */
        InOrder order = inOrder(travelPlanMapper, travelPlanFinalMapper);
        order.verify(travelPlanMapper).findPlanByIdAndStatusForUpdate(PLAN_ID, "ACTIVE");
        order.verify(travelPlanMapper).updatePlanStatus(PLAN_ID, "ACTIVE", "FINALIZING", false);
        order.verify(travelPlanFinalMapper).insertSnapshot(any());
        order.verify(travelPlanMapper).updatePlanStatus(PLAN_ID, "FINALIZING", "COMPLETED", true);
    }

    @Test
    void aPlainMemberCannotFinaliseForReal() {
        givenRoom(TravelPlanRole.MEMBER);

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(AccessDeniedException.class);
        verify(travelPlanMapper, never())
                .updatePlanStatus(anyLong(), anyString(), anyString(), anyBoolean());
        verify(travelPlanFinalMapper, never()).insertSnapshot(any());
    }

    @Test
    void someoneWritingStopsAPlainFinalisingForReal() {
        givenRoomToFinalize();
        editor.tryAcquire(itemLock());

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(TravelPlanValidationException.class);
        verify(travelPlanFinalMapper, never()).insertSnapshot(any());
    }

    @Test
    void theOwnerWhoWasWarnedFinalisesAnyway() {
        givenRoomToFinalize();
        editor.tryAcquire(itemLock());

        finalizeService.finalizePlan(principal(), PLAN_ID, true);

        verify(travelPlanFinalMapper).insertSnapshot(any());
    }

    @Test
    void aRoomThatSomeoneElseJustFinalisedIsNotFinalisedTwice() {
        givenRoom(TravelPlanRole.OWNER);
        // 잠그고 읽어 보니 이미 ACTIVE 가 아니다
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(PLAN_ID, "ACTIVE")).thenReturn(null);

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(TravelPlanValidationException.class)
                .hasMessageContaining("이미 완료된");
        verify(travelPlanFinalMapper, never()).insertSnapshot(any());
    }

    @Test
    void losingTheRaceOnTheStatusStopsEverything() {
        givenRoomToFinalize();
        // 그 사이 다른 쪽이 먼저 옮겼다
        when(travelPlanMapper.updatePlanStatus(PLAN_ID, "ACTIVE", "FINALIZING", false))
                .thenReturn(0);

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(TravelPlanValidationException.class);
        verify(travelPlanFinalMapper, never()).insertSnapshot(any());
    }

    @Test
    void aRoomThatAlreadyHasItsFinalCopyIsRefused() {
        givenRoomToFinalize();
        when(travelPlanFinalMapper.existsByPlanId(PLAN_ID)).thenReturn(true);

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(TravelPlanValidationException.class)
                .hasMessageContaining("이미 완료된");
        verify(travelPlanFinalMapper, never()).insertSnapshot(any());
    }

    // ── 최종본에 담기는 것 ──────────────────────────────────

    @Test
    void whoWasThereIsCopiedAsTheyWere() {
        givenRoomToFinalize();
        // 계정 번호까지 읽는 조회를 쓴다. 화면용 조회에는 user_id 가 없다
        when(travelPlanMapper.findActiveMembersForSnapshot(PLAN_ID, "ACTIVE"))
                .thenReturn(List.of(member(11L, 7L, "민준", TravelPlanRole.OWNER),
                        member(12L, 8L, "쭈니", TravelPlanRole.MEMBER)));

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        ArgumentCaptor<TravelPlanFinalMember> captor =
                ArgumentCaptor.forClass(TravelPlanFinalMember.class);
        verify(travelPlanFinalMapper, times(2)).insertMember(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TravelPlanFinalMember::getDisplayName,
                        TravelPlanFinalMember::getUserId,
                        TravelPlanFinalMember::getRole)
                .containsExactly(
                        tuple("민준", 7L, TravelPlanRole.OWNER),
                        tuple("쭈니", 8L, TravelPlanRole.MEMBER));
        // 모두 방금 만든 최종본에 붙는다
        assertThat(captor.getAllValues())
                .allMatch(saved -> SNAPSHOT_ID.equals(saved.getSnapshotId()));
    }

    @Test
    void theDaysAndWhatWasPlannedAreCopiedInOrder() {
        givenRoomToFinalize();
        when(travelPlanMapper.findDaysByPlanId(PLAN_ID)).thenReturn(List.of(day(100L, 1)));
        when(travelPlanItemMapper.findByPlanId(PLAN_ID))
                .thenReturn(List.of(item(500L, 100L, "경복궁", 1),
                        item(501L, 100L, "북촌", 2)));

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        ArgumentCaptor<TravelPlanFinalItem> captor =
                ArgumentCaptor.forClass(TravelPlanFinalItem.class);
        verify(travelPlanFinalMapper, times(2)).insertItem(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TravelPlanFinalItem::getContent, TravelPlanFinalItem::getDisplayOrder)
                .containsExactly(tuple("경복궁", 1), tuple("북촌", 2));
        /*
          원본 day 번호를 그대로 쓰지 않는다.
          최종본 안에서만 통하는 번호로 새로 잇는다.
        */
        assertThat(captor.getAllValues())
                .allMatch(saved -> FINAL_DAY_ID.equals(saved.getFinalDayId()));
    }

    @Test
    void theAlternativesRideAlongWithTheirItem() {
        givenRoomToFinalize();
        when(travelPlanMapper.findDaysByPlanId(PLAN_ID)).thenReturn(List.of(day(100L, 1)));
        when(travelPlanItemMapper.findByPlanId(PLAN_ID))
                .thenReturn(List.of(item(500L, 100L, "경복궁", 1)));
        when(travelPlanAlternativeMapper.findByPlanId(PLAN_ID))
                .thenReturn(List.of(alternative(500L, 1, "비 오면", "아쿠아플라넷"),
                        alternative(500L, 2, null, "카페")));

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        ArgumentCaptor<TravelPlanFinalItemAlternative> captor =
                ArgumentCaptor.forClass(TravelPlanFinalItemAlternative.class);
        verify(travelPlanFinalMapper, times(2)).insertAlternative(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TravelPlanFinalItemAlternative::getAlternativeOrder,
                        TravelPlanFinalItemAlternative::getConditionLabel,
                        TravelPlanFinalItemAlternative::getContent)
                .containsExactly(
                        tuple(1, "비 오면", "아쿠아플라넷"),
                        tuple(2, null, "카페"));
        // 원본 일정이 아니라 방금 만든 최종본 일정에 붙는다
        assertThat(captor.getAllValues())
                .allMatch(saved -> FINAL_ITEM_ID.equals(saved.getFinalItemId()));
    }

    @Test
    void typeAndTimeArePreservedInTheFinalSnapshot() {
        givenRoomToFinalize();
        when(travelPlanMapper.findDaysByPlanId(PLAN_ID)).thenReturn(List.of(day(100L, 1)));
        TravelPlanItem flight = item(500L, 100L, "공항\n예약 확인", 1);
        flight.setTag("FLIGHT");
        flight.setStartTime(java.time.LocalTime.of(9, 30));
        var details = new com.tripbora.model.TravelPlanItemDetails();
        details.setFlightNumber("KE123");
        details.setDepartureDate("2026-10-18");
        details.setArrivalTime("04:30");
        flight.setDetails(details);
        when(travelPlanItemMapper.findByPlanId(PLAN_ID)).thenReturn(List.of(flight));
        finalizeService.finalizePlan(principal(), PLAN_ID, false);
        ArgumentCaptor<TravelPlanFinalItem> captor = ArgumentCaptor.forClass(TravelPlanFinalItem.class);
        verify(travelPlanFinalMapper).insertItem(captor.capture());
        assertThat(captor.getValue().getTag()).isEqualTo("FLIGHT");
        assertThat(captor.getValue().getStartTime()).isEqualTo(java.time.LocalTime.of(9, 30));
        assertThat(captor.getValue().getContent()).isEqualTo("공항\n예약 확인");
        assertThat(captor.getValue().getDetails()).isEqualTo(details);
    }

    @Test
    void theTitleAndDatesAreTakenAtThatMoment() {
        givenRoomToFinalize();

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        ArgumentCaptor<TravelPlanFinalSnapshot> captor =
                ArgumentCaptor.forClass(TravelPlanFinalSnapshot.class);
        verify(travelPlanFinalMapper).insertSnapshot(captor.capture());
        assertThat(captor.getValue().getTravelPlanId()).isEqualTo(PLAN_ID);
        assertThat(captor.getValue().getTitle()).isEqualTo("제주 여행");
    }

    @Test
    void aHalfWrittenFinalCopyStopsTheWholeThing() {
        // 한 줄이라도 들어가지 않으면 끊는다. 조각만 남거나 COMPLETED 만 남지 않는다
        givenRoomToFinalize();
        when(travelPlanMapper.findDaysByPlanId(PLAN_ID)).thenReturn(List.of(day(100L, 1)));
        // 이미 준비된 답을 다시 부르지 않도록 doReturn 으로 덮는다
        org.mockito.Mockito.doReturn(0).when(travelPlanFinalMapper).insertDay(any());

        assertThatThrownBy(() -> finalizeService.finalizePlan(principal(), PLAN_ID, false))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never())
                .updatePlanStatus(PLAN_ID, "FINALIZING", "COMPLETED", true);
    }

    // ── 완료된 뒤 ───────────────────────────────────────────

    @Test
    void whatPeopleWereWritingIsOnlyClearedAfterItSticks() {
        /*
          작성 중 내용은 커밋이 끝난 뒤에 정리한다.
          트랜잭션 안에서 먼저 지우면 뒤에서 실패했을 때 남의 편집 상태까지 사라진다.
        */
        givenRoomToFinalize();
        editor.tryAcquire(itemLock());

        finalizeService.finalizePlan(principal(), PLAN_ID, true);

        // 여기서는 아직 그대로다. 지우는 것은 커밋 뒤에 듣는 쪽의 몫이다
        assertThat(editor.locksOf(PLAN_ID)).hasSize(1);
        ArgumentCaptor<TravelPlanCompletedEvent> captor =
                ArgumentCaptor.forClass(TravelPlanCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().travelPlanId()).isEqualTo(PLAN_ID);
    }

    @Test
    void theRoomIsSweptCleanOnceItIsDone() {
        // 커밋 뒤에 듣는 쪽이 실제로 자리를 모두 놓는다
        editor.tryAcquire(itemLock());
        editor.tryAcquire(alternativeLock());

        assertThat(editor.releaseAllByPlan(PLAN_ID)).hasSize(2);
        assertThat(editor.locksOf(PLAN_ID)).isEmpty();
        // 그 연결이 다시 자리를 잡는 데 문제가 없어야 한다
        assertThat(editor.tryAcquire(itemLock())).isPresent();
    }

    @Test
    void aRunningPollNeverStopsTheFinalising() {
        // 투표는 계획을 정하는 데 도우려는 것이지 완료의 조건이 아니다
        givenRoomToFinalize();

        finalizeService.finalizePlan(principal(), PLAN_ID, false);

        verify(travelPlanFinalMapper).insertSnapshot(any());
        assertThat(TravelPlanFinalizeService.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getType)
                .extracting(Class::getSimpleName)
                .doesNotContain("TravelPlanPollMapper", "TravelPlanChatMapper");
    }

    // ── 준비 ────────────────────────────────────────────────

    /** 완료가 끝까지 갈 수 있는 상태. 필요한 것만 최소로 준비한다. */
    private void givenRoomToFinalize() {
        givenRoom(TravelPlanRole.OWNER);
        TravelPlan plan = new TravelPlan();
        plan.setId(PLAN_ID);
        plan.setTitle("제주 여행");
        plan.setStatus(TravelPlanStatus.ACTIVE);
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(PLAN_ID, "ACTIVE")).thenReturn(plan);
        when(travelPlanMapper.updatePlanStatus(PLAN_ID, "ACTIVE", "FINALIZING", false))
                .thenReturn(1);
        when(travelPlanMapper.updatePlanStatus(PLAN_ID, "FINALIZING", "COMPLETED", true))
                .thenReturn(1);
        when(travelPlanFinalMapper.existsByPlanId(PLAN_ID)).thenReturn(false);
        when(travelPlanFinalMapper.insertSnapshot(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TravelPlanFinalSnapshot.class).setId(SNAPSHOT_ID);
            return 1;
        });
        when(travelPlanFinalMapper.insertMember(any())).thenReturn(1);
        when(travelPlanFinalMapper.insertDay(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TravelPlanFinalDay.class).setId(FINAL_DAY_ID);
            return 1;
        });
        when(travelPlanFinalMapper.insertItem(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, TravelPlanFinalItem.class).setId(FINAL_ITEM_ID);
            return 1;
        });
        when(travelPlanFinalMapper.insertAlternative(any())).thenReturn(1);
    }

    private TravelPlanMember member(Long id, Long userId, String displayName,
                                    TravelPlanRole role) {
        TravelPlanMember member = new TravelPlanMember();
        member.setId(id);
        member.setTravelPlanId(PLAN_ID);
        member.setUserId(userId);
        member.setDisplayName(displayName);
        member.setRole(role);
        member.setStatus(TravelPlanMemberStatus.ACTIVE);
        return member;
    }

    private TravelPlanDay day(Long id, int dayNumber) {
        TravelPlanDay day = new TravelPlanDay();
        day.setId(id);
        day.setTravelPlanId(PLAN_ID);
        day.setDayNumber(dayNumber);
        day.setPlanDate(java.time.LocalDate.of(2026, 9, 13).plusDays(dayNumber - 1L));
        return day;
    }

    private TravelPlanItem item(Long id, Long dayId, String content, int displayOrder) {
        TravelPlanItem item = new TravelPlanItem();
        item.setId(id);
        item.setTravelPlanDayId(dayId);
        item.setContent(content);
        item.setDisplayOrder(displayOrder);
        return item;
    }

    private TravelPlanItemAlternative alternative(Long itemId, int order,
                                                  String conditionLabel, String content) {
        TravelPlanItemAlternative alternative = new TravelPlanItemAlternative();
        alternative.setTravelPlanItemId(itemId);
        alternative.setAlternativeOrder(order);
        alternative.setConditionLabel(conditionLabel);
        alternative.setContent(content);
        return alternative;
    }

    private TravelPlanEditorRealtimeService.EditorLock itemLock() {
        return new TravelPlanEditorRealtimeService.EditorLock(
                PLAN_ID, TravelPlanEditorRealtimeService.editLockKey(ITEM_ID), "session-A",
                TravelPlanEditorRealtimeService.EDIT_MODE,
                DAY_ID, ITEM_ID, null, 12L, "쭈니", "", "");
    }

    private TravelPlanEditorRealtimeService.EditorLock alternativeLock() {
        return new TravelPlanEditorRealtimeService.EditorLock(
                PLAN_ID, TravelPlanEditorRealtimeService.alternativeEditLockKey(900L),
                "session-B", TravelPlanEditorRealtimeService.ALT_EDIT_MODE,
                DAY_ID, ITEM_ID, 900L, 12L, "쭈니", "", "");
    }

    /** 다른 사람이 다른 일정을 잡고 있다. */
    private TravelPlanEditorRealtimeService.EditorLock otherItemLock() {
        return new TravelPlanEditorRealtimeService.EditorLock(
                PLAN_ID, TravelPlanEditorRealtimeService.editLockKey(501L), "session-C",
                TravelPlanEditorRealtimeService.EDIT_MODE,
                DAY_ID, 501L, null, 13L, "지우", "", "");
    }

    /** 완료하려는 본인이 잡고 있는 자리. */
    private TravelPlanEditorRealtimeService.EditorLock myOwnLock() {
        return new TravelPlanEditorRealtimeService.EditorLock(
                PLAN_ID, TravelPlanEditorRealtimeService.editLockKey(ITEM_ID), "session-A",
                TravelPlanEditorRealtimeService.EDIT_MODE,
                DAY_ID, ITEM_ID, null, MEMBER_ID, "민준", "", "");
    }

    private void givenActivePlan() {
        TravelPlan plan = new TravelPlan();
        plan.setId(PLAN_ID);
        plan.setStatus(TravelPlanStatus.ACTIVE);
        when(travelPlanMapper.findPlanByIdAndStatus(PLAN_ID, "ACTIVE")).thenReturn(plan);
    }

    private void givenRoom(TravelPlanRole role) {
        givenActivePlan();
        TravelPlanMember member = new TravelPlanMember();
        member.setId(MEMBER_ID);
        member.setTravelPlanId(PLAN_ID);
        member.setUserId(USER_ID);
        member.setDisplayName("민준");
        member.setRole(role);
        member.setStatus(TravelPlanMemberStatus.ACTIVE);
        when(travelPlanMapper.findMemberByPlanAndUser(PLAN_ID, USER_ID, "ACTIVE"))
                .thenReturn(member);
    }

    private Principal principal() {
        User user = new User();
        user.setId(USER_ID);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        CustomUserDetails userDetails = new CustomUserDetails(user);
        return new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
    }
}
