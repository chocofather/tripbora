package com.tripbora.service.travelplan;

import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.repository.travelplan.TravelPlanDashboardMapper;
import com.tripbora.repository.travelplan.TravelPlanMapper;
import com.tripbora.repository.travelplan.TravelPlanFinalMapper;
import com.tripbora.model.TravelPlanDashboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelPlanDashboardServiceTest {
    private final TravelPlanMapper plans = mock(TravelPlanMapper.class);
    private final TravelPlanDashboardMapper dashboard = mock(TravelPlanDashboardMapper.class);
    private final TravelPlanFinalMapper finals = mock(TravelPlanFinalMapper.class);
    private final org.springframework.context.ApplicationEventPublisher publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
    private final TravelPlanDashboardService service = new TravelPlanDashboardService(plans, dashboard, finals, publisher);

    @BeforeEach
    void access() {
        lenient().when(plans.findMemberByPlanAndUser(42L, 7L, "ACTIVE")).thenReturn(new TravelPlanMember());
        lenient().when(plans.findPlanByIdAndStatus(42L, "ACTIVE")).thenReturn(new TravelPlan());
        lenient().when(plans.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(new TravelPlan());
        lenient().when(dashboard.findActiveMemberForUpdate(42L, 7L)).thenReturn(3L);
        lenient().when(dashboard.findChecklist(42L)).thenReturn(List.of());
        lenient().when(dashboard.findExpenses(42L)).thenReturn(List.of());
    }

    @Test
    void rejectsInvalidAmountsWithoutWriting() {
        for (String amount : List.of("-1", "0.001", "10000000000000")) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.addExpense(7L, 42L, "식비", new BigDecimal(amount), "")).getStatusCode().value());
        }
        verifyNoInteractions(dashboard);
        verify(plans, never()).touchLastActivity(anyLong());
    }

    @Test
    void zeroAmountIsSavedExactlyAfterPlanLockAndMembershipRecheck() {
        service.addExpense(7L, 42L, "무료 입장", BigDecimal.ZERO, "");
        var order = inOrder(plans, dashboard);
        order.verify(plans).findMemberByPlanAndUser(42L, 7L, "ACTIVE");
        order.verify(plans).findPlanByIdAndStatusForUpdate(42L, "ACTIVE");
        order.verify(dashboard).findActiveMemberForUpdate(42L, 7L);
        order.verify(dashboard).insertExpense(42L, "무료 입장", new BigDecimal("0.00"), "");
        order.verify(plans).touchLastActivity(42L);
    }

    @Test
    void removalWhileWaitingForPlanLockPreventsWrites() {
        when(dashboard.findActiveMemberForUpdate(42L, 7L)).thenReturn(null);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.addChecklist(7L, 42L, "여권")).getStatusCode().value());
        verify(dashboard, never()).insertChecklist(anyLong(), anyString());
        verify(plans, never()).touchLastActivity(anyLong());
    }

    @Test
    void crossPlanChildIsNotFoundAndNeverUpdated() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.updateChecklist(7L, 42L, 99L, "여권", true, 0L)).getStatusCode().value());
        verify(dashboard, never()).updateChecklist(anyLong(), anyLong(), anyString(), anyBoolean(), anyLong());
    }

    @Test
    void staleNoticeRollsBackWithConflict() {
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.saveNotice(7L, 42L, "만남 장소", 2L)).getStatusCode().value());
        verify(dashboard).initializeNotice(42L);
        verify(dashboard).updateNotice(42L, "만남 장소", 2L);
        verify(plans, never()).touchLastActivity(anyLong());
    }

    @Test
    void rejectsHtmlAndAcceptsUnicodeTitles() {
        assertThrows(ResponseStatusException.class, () -> service.addChecklist(7L, 42L, "<b>여권</b>"));
        verifyNoInteractions(dashboard);
        service.addChecklist(7L, 42L, "여권 🧳");
        verify(dashboard).insertChecklist(42L, "여권 🧳");
    }

    @Test
    void missingVersionsAndOversizedFieldsAreRejectedBeforeWrites() {
        assertThrows(ResponseStatusException.class, () -> service.saveNotice(7L, 42L, "공지", null));
        assertThrows(ResponseStatusException.class, () -> service.saveNotice(7L, 42L, "가".repeat(10001), 0L));
        assertThrows(ResponseStatusException.class, () -> service.addChecklist(7L, 42L, "가".repeat(201)));
        assertThrows(ResponseStatusException.class, () -> service.addExpense(7L, 42L, "식비", BigDecimal.ONE, "가".repeat(1001)));
        assertThrows(ResponseStatusException.class, () -> service.updateChecklist(7L, 42L, 99L, "여권", null, 0L));
        verifyNoInteractions(dashboard);
    }

    @Test
    void successfulChecklistUpdateReturnsStoredValues() {
        var stored = new TravelPlanDashboard.Checklist();
        stored.setId(99L);
        stored.setTitle("여권");
        stored.setCompleted(true);
        stored.setVersion(2L);
        when(dashboard.findChecklistById(42L, 99L)).thenReturn(stored);
        when(dashboard.updateChecklist(42L, 99L, "여권", true, 1L)).thenReturn(1);
        when(dashboard.findChecklist(42L)).thenReturn(List.of(stored));
        var result = service.updateChecklist(7L, 42L, 99L, "여권", true, 1L);
        assertTrue(result.checklist().get(0).isCompleted());
        assertEquals(2L, result.checklist().get(0).getVersion());
        verify(plans).touchLastActivity(42L);
    }

    @Test
    void nonParticipantCannotReadOrWrite() {
        when(plans.findMemberByPlanAndUser(42L, 7L, "ACTIVE")).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.read(7L, 42L));
        assertThrows(ResponseStatusException.class, () -> service.addChecklist(7L, 42L, "여권"));
        verifyNoInteractions(dashboard);
        verify(plans, never()).findPlanByIdAndStatusForUpdate(anyLong(), anyString());
    }

    @Test
    void completedPlanRequiresVisibleFinalMembershipAndCannotBeWritten() {
        when(plans.findPlanByIdAndStatus(42L, "ACTIVE")).thenReturn(null);
        when(plans.findPlanByIdAndStatus(42L, "COMPLETED")).thenReturn(new TravelPlan());
        when(finals.existsMemberByPlanAndUser(42L, 7L)).thenReturn(true);
        assertEquals("KRW", service.read(7L, 42L).currency());
        when(finals.existsMemberByPlanAndUser(42L, 7L)).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.read(7L, 42L));
        when(plans.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.addChecklist(7L, 42L, "여권"));
        verify(dashboard, never()).insertChecklist(anyLong(), anyString());
    }

    @Test
    void staleChecklistAndExpenseDeletesCannotRemoveUpdatedRows() {
        when(dashboard.findChecklistById(42L, 99L)).thenReturn(new TravelPlanDashboard.Checklist());
        when(dashboard.findExpenseById(42L, 99L)).thenReturn(new TravelPlanDashboard.Expense());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.deleteChecklist(7L, 42L, 99L, 1L)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.deleteExpense(7L, 42L, 99L, 1L)).getStatusCode().value());
        verify(plans, never()).touchLastActivity(anyLong());
    }

    @Test
    void successfulChecklistCreateAndDeleteNoticeSaveAndExpenseDeleteTouchActivity() {
        when(dashboard.findChecklistById(42L, 99L)).thenReturn(new TravelPlanDashboard.Checklist());
        when(dashboard.findExpenseById(42L, 88L)).thenReturn(new TravelPlanDashboard.Expense());
        when(dashboard.deleteChecklist(42L, 99L, 0L)).thenReturn(1);
        when(dashboard.updateNotice(42L, "공지", 0L)).thenReturn(1);
        when(dashboard.deleteExpense(42L, 88L, 0L)).thenReturn(1);

        service.addChecklist(7L, 42L, "여권");
        service.deleteChecklist(7L, 42L, 99L, 0L);
        service.saveNotice(7L, 42L, "공지", 0L);
        service.deleteExpense(7L, 42L, 88L, 0L);

        verify(plans, times(4)).touchLastActivity(42L);
    }

    @Test
    void totalsUseExactDecimalArithmetic() {
        var first = new TravelPlanDashboard.Expense();
        first.setAmount(new BigDecimal("0.10"));
        var second = new TravelPlanDashboard.Expense();
        second.setAmount(new BigDecimal("0.20"));
        when(dashboard.findExpenses(42L)).thenReturn(List.of(first, second));
        assertEquals(new BigDecimal("0.30"), service.read(7L, 42L).totalAmount());
    }

    @Test
    void transactionProxyRollsBackNoticeInitializationOnConflictAndCommitsSuccess() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy = (TravelPlanDashboardService) factory.getProxy();
        assertThrows(ResponseStatusException.class, () -> proxy.saveNotice(7L, 42L, "공지", 0L));
        verify(manager).rollback(transaction);
        when(dashboard.updateNotice(42L, "공지", 0L)).thenReturn(1);
        proxy.saveNotice(7L, 42L, "공지", 0L);
        verify(manager).commit(transaction);
    }
}
