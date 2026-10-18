package com.tripbora.service.travelplan;

import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.model.TravelPlanMemberStatus;
import com.tripbora.model.TravelPlanRole;
import com.tripbora.model.TravelPlanStatus;
import com.tripbora.repository.travelplan.TravelPlanMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 방 생성: 검증을 모두 끝낸 뒤 plan / OWNER / DAY 를 한 트랜잭션에서 저장한다.
 */
@ExtendWith(MockitoExtension.class)
class TravelPlanCreateServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Mock
    private TravelPlanMapper travelPlanMapper;
    @Mock
    private TravelPlanInvitationService travelPlanInvitationService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private TravelPlanService travelPlanService;

    @Test
    void createsThePlanTheOwnerAndOneDayPerDate() {
        givenSavedPlan(42L);

        Long planId = travelPlanService.createPlan(
                7L, "제주 여행", START, START.plusDays(2), "민준");

        assertThat(planId).isEqualTo(42L);

        TravelPlan plan = capturedPlan();
        assertThat(plan.getCreatedByUserId()).isEqualTo(7L);
        assertThat(plan.getTitle()).isEqualTo("제주 여행");
        assertThat(plan.getStartDate()).isEqualTo(START);
        assertThat(plan.getEndDate()).isEqualTo(START.plusDays(2));
        assertThat(plan.getStatus()).isEqualTo(TravelPlanStatus.ACTIVE);
        // 대표 이미지는 이번 단계에서 다루지 않는다
        assertThat(plan.getRepresentativeImageUrl()).isNull();

        // DAY 는 기간과 정확히 같은 개수, 1부터 연속, 날짜는 하루씩
        assertThat(capturedDays())
                .extracting(TravelPlanDay::getDayNumber, TravelPlanDay::getPlanDate)
                .containsExactly(
                        tuple(1, START),
                        tuple(2, START.plusDays(1)),
                        tuple(3, START.plusDays(2)));
        verify(travelPlanMapper).insertDays(eqPlanId(42L), anyList());
        verify(travelPlanInvitationService).createInvitation(7L, 42L);
    }

    @Test
    void invitationFailurePropagatesSoTheCreationTransactionCanRollBack() {
        givenSavedPlan(42L);
        org.mockito.Mockito.doThrow(new IllegalStateException("invite unavailable"))
                .when(travelPlanInvitationService).createInvitation(7L, 42L);
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", START, START, "민준"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSingleDayTripCreatesOneDay() {
        givenSavedPlan(42L);

        travelPlanService.createPlan(7L, "당일치기", START, START, "민준");

        assertThat(capturedDays())
                .extracting(TravelPlanDay::getDayNumber, TravelPlanDay::getPlanDate)
                .containsExactly(tuple(1, START));
    }

    @Test
    void theCreatorIsStoredAsAnActiveOwner() {
        givenSavedPlan(42L);

        travelPlanService.createPlan(7L, "제주 여행", START, START.plusDays(1), "민준");

        TravelPlanMember owner = capturedMember();
        assertThat(owner.getTravelPlanId()).isEqualTo(42L);
        assertThat(owner.getUserId()).isEqualTo(7L);
        assertThat(owner.getDisplayName()).isEqualTo("민준");
        assertThat(owner.getRole()).isEqualTo(TravelPlanRole.OWNER);
        assertThat(owner.getStatus()).isEqualTo(TravelPlanMemberStatus.ACTIVE);
        // DB DEFAULT 가 있는 값은 애플리케이션에서 채우지 않는다
        assertThat(owner.getRejoinAllowed()).isNull();
        assertThat(owner.getJoinedAt()).isNull();
    }

    @Test
    void titleAndDisplayNameAreTrimmedBeforeSaving() {
        givenSavedPlan(42L);

        travelPlanService.createPlan(7L, "  제주 여행  ", START, START, "  민준  ");

        assertThat(capturedPlan().getTitle()).isEqualTo("제주 여행");
        assertThat(capturedMember().getDisplayName()).isEqualTo("민준");
    }

    @Test
    void exactlyNinetyDaysIsAllowed() {
        givenSavedPlan(42L);

        travelPlanService.createPlan(7L, "장기 여행", START, START.plusDays(89), "민준");

        assertThat(capturedDays()).hasSize(90);
        assertThat(capturedDays().get(89).getDayNumber()).isEqualTo(90);
        assertThat(capturedDays().get(89).getPlanDate()).isEqualTo(START.plusDays(89));
    }

    @Test
    void ninetyOneDaysIsRejected() {
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "너무 긴 여행", START, START.plusDays(90), "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .hasMessageContaining("90일")
                .extracting("field").isEqualTo("endDate");

        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void rejectsAMissingOrTooLongTitle() {
        for (String title : new String[]{null, "", "   ", "가".repeat(151)}) {
            assertThatThrownBy(() -> travelPlanService.createPlan(7L, title, START, START, "민준"))
                    .as("title=%s", title)
                    .isInstanceOf(TravelPlanValidationException.class)
                    .extracting("field").isEqualTo("title");
        }
        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void rejectsAMissingOrTooLongDisplayName() {
        for (String displayName : new String[]{null, "", "   ", "가".repeat(51)}) {
            assertThatThrownBy(() -> travelPlanService.createPlan(
                    7L, "제주 여행", START, START, displayName))
                    .as("displayName=%s", displayName)
                    .isInstanceOf(TravelPlanValidationException.class)
                    .extracting("field").isEqualTo("displayName");
        }
        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void rejectsMissingDatesAndAnEndBeforeTheStart() {
        assertThatThrownBy(() -> travelPlanService.createPlan(7L, "제주 여행", null, START, "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("startDate");
        assertThatThrownBy(() -> travelPlanService.createPlan(7L, "제주 여행", START, null, "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("endDate");
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", START, START.minusDays(1), "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("endDate");

        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void rejectsAYearThatIsNotFourDigits() {
        // 화면의 min/max 를 지나쳐 온 값도 여기서 걸린다
        LocalDate tooFar = LocalDate.of(12026, 9, 1);
        LocalDate tooEarly = LocalDate.of(999, 9, 1);

        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", tooFar, tooFar.plusDays(2), "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .hasMessageContaining("4자리")
                .extracting("field").isEqualTo("startDate");
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", START, LocalDate.of(12026, 9, 3), "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("endDate");
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", tooEarly, tooEarly.plusDays(2), "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("startDate");

        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void theEdgesOfTheFourDigitRangeAreStillAllowed() {
        givenSavedPlan(42L);

        // 1000 ~ 9999 는 그대로 통과한다
        travelPlanService.createPlan(
                7L, "먼 여행", LocalDate.of(9999, 12, 30), LocalDate.of(9999, 12, 31), "민준");

        verify(travelPlanMapper).insertPlan(any());
    }

    @Test
    void rejectsAMissingUserBeforeAnyInsert() {
        assertThatThrownBy(() -> travelPlanService.createPlan(null, "제주 여행", START, START, "민준"))
                .isInstanceOf(TravelPlanValidationException.class)
                .extracting("field").isEqualTo("userId");

        verifyNoInteractions(travelPlanMapper);
    }

    @Test
    void aFailureInALaterInsertIsNotSwallowed() {
        givenSavedPlan(42L);
        when(travelPlanMapper.insertDays(anyLong(), anyList()))
                .thenThrow(new IllegalStateException("DAY 저장 실패"));

        // 예외가 그대로 올라가야 트랜잭션이 롤백된다
        assertThatThrownBy(() -> travelPlanService.createPlan(
                7L, "제주 여행", START, START.plusDays(2), "민준"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnexpectedInsertResultIsNotTreatedAsSuccess() {
        // plan INSERT 가 0건
        when(travelPlanMapper.insertPlan(any(TravelPlan.class))).thenReturn(0);
        assertThatThrownBy(() -> travelPlanService.createPlan(7L, "제주 여행", START, START, "민준"))
                .isInstanceOf(RuntimeException.class);
        verify(travelPlanMapper, never()).insertMember(any());
        verify(travelPlanMapper, never()).insertDays(anyLong(), anyList());
    }

    @Test
    void planCreationRunsInsideATransaction() throws NoSuchMethodException {
        Method create = TravelPlanService.class.getMethod(
                "createPlan", Long.class, String.class, LocalDate.class, LocalDate.class, String.class);

        assertThat(create.isAnnotationPresent(Transactional.class))
                .as("createPlan 은 하나의 트랜잭션으로 묶여야 한다").isTrue();
    }

    private void givenSavedPlan(Long generatedId) {
        doAnswer(invocation -> {
            invocation.getArgument(0, TravelPlan.class).setId(generatedId);
            return 1;
        }).when(travelPlanMapper).insertPlan(any(TravelPlan.class));
        when(travelPlanMapper.insertMember(any(TravelPlanMember.class))).thenReturn(1);
        // 여러 행 INSERT 는 영향 행 수를 돌려준다
        when(travelPlanMapper.insertDays(anyLong(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
    }

    private TravelPlan capturedPlan() {
        ArgumentCaptor<TravelPlan> captor = ArgumentCaptor.forClass(TravelPlan.class);
        verify(travelPlanMapper).insertPlan(captor.capture());
        return captor.getValue();
    }

    private TravelPlanMember capturedMember() {
        ArgumentCaptor<TravelPlanMember> captor = ArgumentCaptor.forClass(TravelPlanMember.class);
        verify(travelPlanMapper).insertMember(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<TravelPlanDay> capturedDays() {
        ArgumentCaptor<List<TravelPlanDay>> captor = ArgumentCaptor.forClass(List.class);
        verify(travelPlanMapper).insertDays(anyLong(), captor.capture());
        return captor.getValue();
    }

    private long eqPlanId(long planId) {
        return org.mockito.ArgumentMatchers.eq(planId);
    }
}
