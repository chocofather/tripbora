package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanDashboardDto;
import com.tripbora.model.TravelPlanDashboard;
import com.tripbora.repository.travelplan.TravelPlanDashboardMapper;
import com.tripbora.repository.travelplan.TravelPlanFinalMapper;
import com.tripbora.repository.travelplan.TravelPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TravelPlanDashboardService {
    private final TravelPlanMapper plans;
    private final TravelPlanDashboardMapper dashboard;
    private final TravelPlanFinalMapper finals;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public TravelPlanDashboardDto read(Long userId, Long planId) {
        if (userId == null || planId == null) throw notFound();
        if (plans.findPlanByIdAndStatus(planId, "ACTIVE") != null) {
            requireMember(userId, planId);
        } else if (plans.findPlanByIdAndStatus(planId, "COMPLETED") == null
                || !finals.existsMemberByPlanAndUser(planId, userId)) {
            throw notFound();
        }
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto addChecklist(Long userId, Long planId, String title) {
        title = text(title, 200, true);
        lock(userId, planId);
        dashboard.insertChecklist(planId, title);
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto updateChecklist(Long userId, Long planId, Long id,
                                                   String title, Boolean completed, Long version) {
        title = text(title, 200, true);
        requireVersion(version);
        if (completed == null) throw invalid("완료 여부가 필요합니다.");
        lock(userId, planId);
        if (dashboard.findChecklistById(planId, id) == null) throw notFound();
        changed(dashboard.updateChecklist(planId, id, title, completed, version));
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto deleteChecklist(Long userId, Long planId, Long id, Long version) {
        requireVersion(version);
        lock(userId, planId);
        if (dashboard.findChecklistById(planId, id) == null) throw notFound();
        changed(dashboard.deleteChecklist(planId, id, version));
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto saveNotice(Long userId, Long planId, String content, Long version) {
        content = text(content, 10000, false);
        requireVersion(version);
        lock(userId, planId);
        // 방 잠금 안에서 초기 행을 확보하므로 최초 동시 저장도 같은 version 검사를 거친다.
        dashboard.initializeNotice(planId);
        changed(dashboard.updateNotice(planId, content, version));
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto addExpense(Long userId, Long planId, String title,
                                              BigDecimal amount, String memo) {
        title = text(title, 200, true);
        memo = text(memo == null ? "" : memo, 1000, false);
        if (amount == null || amount.signum() < 0
                || amount.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw invalid("금액은 0 이상 9,999,999,999,999.99 이하의 원화여야 합니다.");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("금액은 소수점 둘째 자리까지 입력해주세요.");
        }
        lock(userId, planId);
        dashboard.insertExpense(planId, title, amount, memo);
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanDashboardDto deleteExpense(Long userId, Long planId, Long id, Long version) {
        requireVersion(version);
        lock(userId, planId);
        if (dashboard.findExpenseById(planId, id) == null) throw notFound();
        changed(dashboard.deleteExpense(planId, id, version));
        plans.touchLastActivity(planId);
        publishChange(planId);
        return snapshot(planId);
    }

    private void publishChange(Long planId) {
        eventPublisher.publishEvent(new TravelPlanScheduleChangedEvent(
                planId, List.of(), TravelPlanScheduleChangeType.DASHBOARD_UPDATED));
    }

    private void requireMember(Long userId, Long planId) {
        if (userId == null || planId == null
                || plans.findMemberByPlanAndUser(planId, userId, "ACTIVE") == null) throw notFound();
    }

    private void lock(Long userId, Long planId) {
        requireMember(userId, planId);
        if (plans.findPlanByIdAndStatusForUpdate(planId, "ACTIVE") == null) throw notFound();
        // 일반 재조회는 REPEATABLE READ snapshot이므로 권한 변경을 놓칠 수 있다.
        if (dashboard.findActiveMemberForUpdate(planId, userId) == null) throw notFound();
    }

    private TravelPlanDashboardDto snapshot(Long planId) {
        var notice = dashboard.findNotice(planId);
        var expenses = dashboard.findExpenses(planId);
        var total = expenses.stream().map(TravelPlanDashboard.Expense::getAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new TravelPlanDashboardDto(dashboard.findChecklist(planId),
                notice == null ? new TravelPlanDashboard.Notice() : notice, expenses, total, "KRW");
    }

    private static String text(String value, int max, boolean required) {
        if (value == null) throw invalid("내용을 입력해주세요.");
        value = value.strip();
        if ((required && value.isBlank()) || value.codePointCount(0, value.length()) > max
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                    || (c >= 0xD800 && c <= 0xDFFF))) {
            throw invalid("일반 텍스트로 " + max + "자 이내로 입력해주세요.");
        }
        return value;
    }

    private static void requireVersion(Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) throw invalid("올바른 버전이 필요합니다.");
    }

    private static void changed(int count) {
        if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 참여자가 변경했습니다. 새로고침 후 다시 시도해주세요.");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "여행 계획 또는 항목을 찾을 수 없습니다.");
    }
}
