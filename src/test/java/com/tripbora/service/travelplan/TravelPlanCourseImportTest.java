package com.tripbora.service.travelplan;

import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import com.tripbora.service.course.CourseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelPlanCourseImportTest {
    @Mock TravelPlanMapper travelPlanMapper;
    @Mock TravelPlanItemMapper travelPlanItemMapper;
    @Mock TravelPlanInvitationService travelPlanInvitationService;
    @Mock CourseService courseService;
    @InjectMocks TravelPlanService service;
    final LocalDate start = LocalDate.of(2026, 10, 20);

    CourseDetailDto template() {
        var course = new CourseDetailDto();
        course.setTitle("원본 코스");
        var first = new CourseDayDto(); first.setDayNumber(1);
        var second = new CourseDayDto(); second.setDayNumber(2);
        var a = new CourseStopDto(); a.setName("첫 장소"); a.setAddress("서울"); a.setMemo("방문 메모");
        var b = new CourseStopDto(); b.setName("둘째 장소");
        var c = new CourseStopDto(); c.setName("다음 날 장소");
        first.setItems(List.of(a, b)); second.setItems(List.of(c));
        course.setDays(List.of(first, second));
        when(courseService.getCourseForPlan(9L)).thenReturn(course);
        return course;
    }

    void saved() {
        when(travelPlanMapper.insertPlan(any())).thenAnswer(i -> { ((TravelPlan)i.getArgument(0)).setId(42L); return 1; });
        when(travelPlanMapper.insertMember(any())).thenAnswer(i -> { ((TravelPlanMember)i.getArgument(0)).setId(7L); return 1; });
        when(travelPlanMapper.insertDays(eq(42L), anyList())).thenReturn(2);
        var a = new TravelPlanDay(); a.setId(101L); a.setDayNumber(1);
        var b = new TravelPlanDay(); b.setId(102L); b.setDayNumber(2);
        when(travelPlanMapper.findDaysByPlanId(42L)).thenReturn(List.of(a, b));
    }

    @Test void copiesDaysOrderAndTextAsIndependentScheduleCards() {
        var source = template(); saved();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1);
        assertThat(service.createPlan(1L, "수정한 제목", start, start.plusDays(1), "나", 9L)).isEqualTo(42L);
        var cards = ArgumentCaptor.forClass(TravelPlanItem.class);
        verify(travelPlanItemMapper, times(3)).insertItem(cards.capture());
        assertThat(cards.getAllValues()).extracting(TravelPlanItem::getTravelPlanDayId, TravelPlanItem::getDisplayOrder,
                TravelPlanItem::getTitle, TravelPlanItem::getTag).containsExactly(
                tuple(101L, 1, "첫 장소", "SCHEDULE"), tuple(101L, 2, "둘째 장소", "SCHEDULE"),
                tuple(102L, 1, "다음 날 장소", "SCHEDULE"));
        assertThat(cards.getAllValues().get(0).getNote()).contains("서울", "방문 메모");
        assertThat(cards.getAllValues()).allSatisfy(card -> {
            assertThat(card.getStartTime()).isNull(); assertThat(card.getDetails()).isNull();
            assertThat(card.getCreatedByMemberId()).isEqualTo(7L);
        });
        source.getDays().get(0).getItems().get(0).setName("원본 수정");
        assertThat(cards.getAllValues().get(0).getTitle()).isEqualTo("첫 장소");
        verify(travelPlanInvitationService).createInvitation(1L, 42L);
    }

    @Test void shorterPeriodIsRejectedBeforeAnyWrite() {
        template();
        assertThatThrownBy(() -> service.createPlan(1L, "제목", start, start, "나", 9L))
                .isInstanceOf(TravelPlanValidationException.class).hasMessageContaining("2일");
        verifyNoInteractions(travelPlanMapper, travelPlanItemMapper, travelPlanInvitationService);
    }

    @Test void failedCopyPropagatesAndDoesNotIssueInvitation() {
        template(); saved();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1, 0);
        assertThatThrownBy(() -> service.createPlan(1L, "제목", start, start.plusDays(1), "나", 9L))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(travelPlanInvitationService);
    }

    @Test void springTransactionRollsBackWhenACardInsertFails() {
        template(); saved();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1, 0);
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional = (TravelPlanService) factory.getProxy();
        assertThatThrownBy(() -> transactional.createPlan(1L, "제목", start, start.plusDays(1), "나", 9L))
                .isInstanceOf(RuntimeException.class);
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
        verifyNoInteractions(travelPlanInvitationService);
    }

    @Test void singleDayKeepsItsTitleAndOnlyExistingTimes() {
        var source = template(); source.setDays(List.of(source.getDays().get(0)));
        source.getDays().get(0).setTitle("첫째 날");
        source.getDays().get(0).getItems().get(0).setStartTime("09:30");
        source.getDays().get(0).getItems().get(0).setEndTime("10:30");
        saved();
        when(travelPlanMapper.updateDayTitle(42L, 101L, "첫째 날", 0)).thenReturn(1);
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1);
        service.createPlan(1L, "제목", start, start.plusDays(1), "나", 9L);
        var cards = ArgumentCaptor.forClass(TravelPlanItem.class);
        verify(travelPlanItemMapper, times(2)).insertItem(cards.capture());
        assertThat(cards.getAllValues()).extracting(TravelPlanItem::getTravelPlanDayId).containsExactly(101L, 101L);
        assertThat(cards.getAllValues().get(0).getStartTime()).isEqualTo(java.time.LocalTime.of(9, 30));
        assertThat(cards.getAllValues().get(0).getNote()).contains("종료 시각: 10:30");
        assertThat(cards.getAllValues().get(1).getStartTime()).isNull();
        verify(travelPlanMapper).updateDayTitle(42L, 101L, "첫째 날", 0);
    }
}
