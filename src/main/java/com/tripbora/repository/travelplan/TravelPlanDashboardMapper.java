package com.tripbora.repository.travelplan;

import com.tripbora.model.TravelPlanDashboard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface TravelPlanDashboardMapper {
    Long findActiveMemberForUpdate(@Param("planId") Long planId, @Param("userId") Long userId);
    List<TravelPlanDashboard.Checklist> findChecklist(@Param("planId") Long planId);
    TravelPlanDashboard.Checklist findChecklistById(@Param("planId") Long planId, @Param("id") Long id);
    TravelPlanDashboard.Notice findNotice(@Param("planId") Long planId);
    List<TravelPlanDashboard.Expense> findExpenses(@Param("planId") Long planId);
    TravelPlanDashboard.Expense findExpenseById(@Param("planId") Long planId, @Param("id") Long id);
    int insertChecklist(@Param("planId") Long planId, @Param("title") String title);
    int updateChecklist(@Param("planId") Long planId, @Param("id") Long id,
                        @Param("title") String title, @Param("completed") boolean completed,
                        @Param("version") long version);
    int deleteChecklist(@Param("planId") Long planId, @Param("id") Long id, @Param("version") long version);
    int initializeNotice(@Param("planId") Long planId);
    int updateNotice(@Param("planId") Long planId, @Param("content") String content, @Param("version") long version);
    int insertExpense(@Param("planId") Long planId, @Param("title") String title,
                      @Param("amount") BigDecimal amount, @Param("memo") String memo);
    int deleteExpense(@Param("planId") Long planId, @Param("id") Long id, @Param("version") long version);
}
