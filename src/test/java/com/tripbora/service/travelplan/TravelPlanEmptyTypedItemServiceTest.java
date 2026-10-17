package com.tripbora.service.travelplan;

import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelPlanEmptyTypedItemServiceTest {
    @Mock TravelPlanMapper travelPlanMapper;
    @Mock TravelPlanItemMapper travelPlanItemMapper;
    @Mock TravelPlanAlternativeMapper travelPlanAlternativeMapper;
    @Mock TravelPlanFinalMapper travelPlanFinalMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks TravelPlanService service;

    private void member() {
        TravelPlanMember member = new TravelPlanMember();
        member.setId(11L);
        when(travelPlanMapper.findMemberByPlanAndUser(42L, 7L, "ACTIVE")).thenReturn(member);
    }

    private void access() {
        member();
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(new TravelPlan());
        TravelPlanDay day = new TravelPlanDay();
        day.setPlanDate(LocalDate.of(2026, 10, 18));
        when(travelPlanMapper.findDayByPlanAndId(42L, 100L)).thenReturn(day);
    }

    @Test void persistsEmptyContentAndReturnsGeneratedIdsForCanonicalTypesAndAliases() {
        access();
        when(travelPlanItemMapper.findMaxDisplayOrder(100L)).thenReturn(3);
        when(travelPlanItemMapper.insertItem(any())).thenAnswer(invocation -> {
            TravelPlanItem item = invocation.getArgument(0);
            item.setId(500L);
            assertThat(item.getContent()).isEmpty();
            assertThat(item.getDisplayOrder()).isEqualTo(4);
            assertThat(item.getCreatedByMemberId()).isEqualTo(11L);
            assertThat(item.getTravelPlanDayId()).isEqualTo(100L);
            assertThat(item.getStartTime()).isNull();
            if (item.getItemType() == TravelPlanItemType.SCHEDULE) {
                assertThat(item.getDetails()).isNull();
            } else {
                assertThat(item.getDetails().getFlightNumber()).isNull();
                assertThat(item.getDetails().getCheckInDate()).isNull();
                assertThat(item.getDetails().getDepartureDate()).isNull();
            }
            assertThat(item.getTag()).isIn("FLIGHT", "ACCOMMODATION", "TRANSPORT", "SCHEDULE");
            return 1;
        });
        for (String type : new String[]{"FLIGHT", "STAY", "MOVE", "ACCOMMODATION", "TRANSPORT", "SCHEDULE"}) {
            assertThat(service.addEmptyTypedItem(7L, 42L, 100L, type)).isEqualTo(500L);
        }
        verify(travelPlanMapper, times(6)).touchLastActivity(42L);
        verify(eventPublisher, times(6)).publishEvent(any(TravelPlanScheduleChangedEvent.class));
    }

    @Test void nonMemberCannotCreateOrLockPlan() {
        assertThatThrownBy(() -> service.addEmptyTypedItem(7L, 42L, 100L, "FLIGHT"))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never()).findPlanByIdAndStatusForUpdate(anyLong(), anyString());
        verifyNoInteractions(travelPlanItemMapper, eventPublisher);
    }

    @Test void finalizedOrFinalizingPlanCannotCreate() {
        member();
        assertThatThrownBy(() -> service.addEmptyTypedItem(7L, 42L, 100L, "FLIGHT"))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper).findPlanByIdAndStatusForUpdate(42L, "ACTIVE");
        verifyNoInteractions(travelPlanItemMapper, eventPublisher);
    }

    @Test void foreignDayCannotCreate() {
        member();
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(new TravelPlan());
        assertThatThrownBy(() -> service.addEmptyTypedItem(7L, 42L, 999L, "FLIGHT"))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper).findDayByPlanAndId(42L, 999L);
        verifyNoInteractions(travelPlanItemMapper, eventPublisher);
    }

    @Test void unsupportedOrMissingTypesCannotBypassContentValidation() {
        access();
        for (String type : new String[]{"NOTE", "MEAL", "UNKNOWN"}) {
            assertThatThrownBy(() -> service.addEmptyTypedItem(7L, 42L, 100L, type))
                    .isInstanceOf(TravelPlanValidationException.class);
        }
        verifyNoInteractions(travelPlanItemMapper, eventPublisher);
    }

    @Test void failedInsertDoesNotTouchPlanOrPublishEvent() {
        access();
        assertThatThrownBy(() -> service.addEmptyTypedItem(7L, 42L, 100L, "MOVE"))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanMapper, never()).touchLastActivity(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test void transportEditsKeepVersionConflictProtectionAndRouteDetails() {
        access();
        when(travelPlanItemMapper.findByIdAndDayId(500L, 100L)).thenReturn(new TravelPlanItem());
        var details = new TravelPlanItemDetails();
        details.setDeparturePlace("Station A");
        details.setArrivalPlace("Station B");
        assertThatThrownBy(() -> service.updateTypedItem(7L, 42L, 100L, 500L,
                "Train", 2, "MOVE", null, details)).isInstanceOf(TravelPlanConflictException.class);
        ArgumentCaptor<TravelPlanItemDetails> captured = ArgumentCaptor.forClass(TravelPlanItemDetails.class);
        verify(travelPlanItemMapper).updateStructuredDetails(eq(500L), eq(100L), eq("Train"),
                eq("TRANSPORT"), isNull(), captured.capture(), eq(2));
        assertThat(captured.getValue().getDeparturePlace()).isEqualTo("Station A");
        assertThat(captured.getValue().getArrivalPlace()).isEqualTo("Station B");
        verify(travelPlanMapper, never()).touchLastActivity(anyLong());
        verifyNoInteractions(eventPublisher);
    }
}
