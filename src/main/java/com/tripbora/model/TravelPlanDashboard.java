package com.tripbora.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** 공동 계획 대시보드의 plain-text 저장 행. */
public final class TravelPlanDashboard {
    private TravelPlanDashboard() { }

    @Data
    public static class Checklist {
        private Long id;
        private String title;
        private boolean completed;
        private long version;
    }

    @Data
    public static class Notice {
        private String content = "";
        private long version;
    }

    @Data
    public static class Expense {
        private Long id;
        private String title;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal amount;
        private String memo;
        private long version;
    }
}
