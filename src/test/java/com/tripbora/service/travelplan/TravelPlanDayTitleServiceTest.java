package com.tripbora.service.travelplan;

import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.repository.travelplan.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelPlanDayTitleServiceTest {
    @Mock TravelPlanMapper travelPlanMapper;
    @Mock TravelPlanItemMapper travelPlanItemMapper;
    @Mock TravelPlanAlternativeMapper travelPlanAlternativeMapper;
    @Mock TravelPlanFinalMapper travelPlanFinalMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks TravelPlanService service;

    private void member() {
        when(travelPlanMapper.findMemberByPlanAndUser(42L, 7L, "ACTIVE"))
                .thenReturn(new TravelPlanMember());
    }

    private void access() {
        member();
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(42L, "ACTIVE"))
                .thenReturn(new TravelPlan());
        when(travelPlanMapper.findDayByPlanAndId(42L, 100L)).thenReturn(new TravelPlanDay());
    }

    @Test void normalizesAndPersistsTitleWithVersionAndPublishesOneEvent() {
        access();
        when(travelPlanMapper.updateDayTitle(42L, 100L, "Paris", 2)).thenReturn(1);
        TravelPlanDay saved = service.updateDayTitle(7L, 42L, 100L, "  Paris  ", 2);
        assertThat(saved.getTitle()).isEqualTo("Paris");
        assertThat(saved.getTitleVersion()).isEqualTo(3);
        verify(travelPlanMapper).touchLastActivity(42L);
        verify(eventPublisher).publishEvent(TravelPlanScheduleChangedEvent.ofDay(
                42L, 100L, TravelPlanScheduleChangeType.DAY_UPDATED));
    }

    @Test void blankTitleClearsTheOptionalValue() {
        access();
        when(travelPlanMapper.updateDayTitle(42L, 100L, null, 0)).thenReturn(1);
        assertThat(service.updateDayTitle(7L, 42L, 100L, "   ", 0).getTitle()).isNull();
    }

    @Test void staleOrMissingVersionDoesNotTouchActivityOrPublish() {
        access();
        for (Integer version : new Integer[]{null, -1, 2}) {
            assertThatThrownBy(() -> service.updateDayTitle(7L, 42L, 100L, "Paris", version))
                    .isInstanceOf(TravelPlanConflictException.class);
        }
        verify(travelPlanMapper, times(1)).updateDayTitle(42L, 100L, "Paris", 2);
        verify(travelPlanMapper, never()).touchLastActivity(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test void oversizedTitleCannotReachUpdate() {
        access();
        assertThatThrownBy(() -> service.updateDayTitle(7L, 42L, 100L, "a".repeat(151), 0))
                .isInstanceOf(TravelPlanValidationException.class);
        verify(travelPlanMapper, never()).updateDayTitle(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test void nonMemberCannotLockOrUpdate() {
        assertThatThrownBy(() -> service.updateDayTitle(7L, 42L, 100L, "Paris", 0))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never()).findPlanByIdAndStatusForUpdate(anyLong(), anyString());
        verify(travelPlanMapper, never()).updateDayTitle(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test void completedOrFinalizingPlanCannotUpdate() {
        member();
        assertThatThrownBy(() -> service.updateDayTitle(7L, 42L, 100L, "Paris", 0))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never()).updateDayTitle(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test void foreignDayCannotUpdate() {
        member();
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(42L, "ACTIVE"))
                .thenReturn(new TravelPlan());
        assertThatThrownBy(() -> service.updateDayTitle(7L, 42L, 999L, "Paris", 0))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never()).updateDayTitle(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(eventPublisher);
    }
}
