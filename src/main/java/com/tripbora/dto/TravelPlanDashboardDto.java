package com.tripbora.dto;

import com.tripbora.model.TravelPlanDashboard;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;

/** 금액은 환산 없는 KRW 원화 기록이다. */
public record TravelPlanDashboardDto(
        List<TravelPlanDashboard.Checklist> checklist,
        TravelPlanDashboard.Notice notice,
        List<TravelPlanDashboard.Expense> expenses,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        String currency) {
    public record ChecklistCreate(String title) { }
    public record ChecklistUpdate(String title, Boolean completed, Long version) { }
    public record Version(Long version) { }
    public record NoticeSave(String content, Long version) { }
    public record ExpenseCreate(String title, BigDecimal amount, String memo) { }
}
