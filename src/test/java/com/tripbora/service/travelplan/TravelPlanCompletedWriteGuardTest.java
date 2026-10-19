package com.tripbora.service.travelplan;

import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TravelPlanCompletedWriteGuardTest {
    @ParameterizedTest
    @EnumSource(TravelPlanRole.class)
    void completedPlanRejectsEveryContentWriteForOwnerAndMember(TravelPlanRole role) {
        var plans = mock(TravelPlanMapper.class);
        var items = mock(TravelPlanItemMapper.class);
        var alternatives = mock(TravelPlanAlternativeMapper.class);
        var dashboard = mock(TravelPlanDashboardMapper.class);
        var finals = mock(TravelPlanFinalMapper.class);
        var events = mock(ApplicationEventPublisher.class);
        var member = new TravelPlanMember();
        member.setId(11L); member.setRole(role); member.setStatus(TravelPlanMemberStatus.ACTIVE);
        when(plans.findMemberByPlanAndUser(42L, 7L, "ACTIVE")).thenReturn(member);
        // COMPLETED 방은 ACTIVE 조건의 잠금 조회에 나오지 않는다.
        when(plans.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(null);
        var service = new TravelPlanService(plans, items, alternatives, finals,
                mock(TravelPlanInvitationService.class), events);
        var preparation = new TravelPlanDashboardService(plans, dashboard, finals, events);
        List<Runnable> writes = List.of(
                () -> service.addItem(7L, 42L, 100L, "일정"),
                () -> service.addTypedItem(7L, 42L, 100L, "항공", "FLIGHT", null, new TravelPlanItemDetails()),
                () -> service.addEmptyTypedItem(7L, 42L, 100L, "ACCOMMODATION"),
                () -> service.addEmptyTypedItem(7L, 42L, 100L, "TRANSPORT", 500L),
                () -> service.updateDayTitle(7L, 42L, 100L, "제목", 0),
                () -> service.updateItem(7L, 42L, 100L, 500L, "수정", 0),
                () -> service.updateTypedItem(7L, 42L, 100L, 500L, "항공", 0, "FLIGHT", null, new TravelPlanItemDetails()),
                () -> service.deleteItem(7L, 42L, 100L, 500L),
                () -> service.deleteItemGroup(7L, 42L, 100L, 500L),
                () -> service.addAlternative(7L, 42L, 100L, 500L, "비", "실내"),
                () -> service.updateAlternative(7L, 42L, 100L, 500L, 900L, "비", "실내", 0),
                () -> service.deleteAlternative(7L, 42L, 100L, 500L, 900L),
                () -> service.moveItemUp(7L, 42L, 100L, 500L, 0),
                () -> service.moveItemDown(7L, 42L, 100L, 500L, 0),
                () -> service.moveItemToDay(7L, 42L, 100L, 500L, 200L, 0),
                () -> preparation.addChecklist(7L, 42L, "여권"),
                () -> preparation.updateChecklist(7L, 42L, 1L, "여권", true, 0L),
                () -> preparation.deleteChecklist(7L, 42L, 1L, 0L),
                () -> preparation.saveNotice(7L, 42L, "메모", 0L),
                () -> preparation.addExpense(7L, 42L, "택시", BigDecimal.TEN, "메모"),
                () -> preparation.deleteExpense(7L, 42L, 1L, 0L));
        for (Runnable write : writes) {
            assertThatThrownBy(write::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode().value()).isEqualTo(404));
        }
        verifyNoInteractions(items, alternatives, dashboard, events);
        verify(plans, never()).touchLastActivity(anyLong());
    }
}
