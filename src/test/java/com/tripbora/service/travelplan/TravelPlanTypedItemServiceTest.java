package com.tripbora.service.travelplan;

import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelPlanTypedItemServiceTest {
    @Mock TravelPlanMapper travelPlanMapper;
    @Mock TravelPlanItemMapper travelPlanItemMapper;
    @Mock TravelPlanAlternativeMapper travelPlanAlternativeMapper;
    @Mock TravelPlanFinalMapper travelPlanFinalMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks TravelPlanService service;

    private void access() {
        TravelPlanMember member = new TravelPlanMember();
        member.setId(11L);
        when(travelPlanMapper.findMemberByPlanAndUser(42L, 7L, "ACTIVE")).thenReturn(member);
        when(travelPlanMapper.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(new TravelPlan());
        when(travelPlanMapper.findDayByPlanAndId(42L, 100L)).thenReturn(new TravelPlanDay());
    }

    @Test void typedCreationKeepsOriginalMultilineContentAndStoresExactTime() {
        access();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1);
        service.addTypedItem(7L, 42L, 100L, " 공항 도착\n터미널 확인 ", "FLIGHT", "09:05");
        ArgumentCaptor<TravelPlanItem> captured = ArgumentCaptor.forClass(TravelPlanItem.class);
        verify(travelPlanItemMapper).insertItem(captured.capture());
        assertThat(captured.getValue().getTag()).isEqualTo("FLIGHT");
        assertThat(captured.getValue().getStartTime()).isEqualTo(LocalTime.of(9, 5));
        assertThat(captured.getValue().getContent()).isEqualTo("공항 도착\n터미널 확인");
        assertThat(captured.getValue().getTitle()).isEqualTo("공항 도착");
        assertThat(captured.getValue().getNote()).isEqualTo("터미널 확인");
    }

    @Test void invalidTypeOrTimeNeverWrites() {
        access();
        assertThatThrownBy(() -> service.addTypedItem(7L,42L,100L,"일정","UNKNOWN","09:00"))
                .isInstanceOf(TravelPlanValidationException.class);
        for (String time : new String[]{"24:00", "9:00", "12:00:00", "-01:20"}) {
            assertThatThrownBy(() -> service.addTypedItem(7L,42L,100L,"일정","SCHEDULE",time))
                    .isInstanceOf(TravelPlanValidationException.class);
        }
        verify(travelPlanItemMapper, never()).insertItem(any());
    }

    @Test void typedUpdateRequiresVersionAndScopesItemToDay() {
        access();
        when(travelPlanItemMapper.findByIdAndDayId(500L,100L)).thenReturn(new TravelPlanItem());
        assertThatThrownBy(() -> service.updateTypedItem(7L,42L,100L,500L,"숙소",3,"ACCOMMODATION","15:00"))
                .isInstanceOf(TravelPlanConflictException.class);
        verify(travelPlanItemMapper).updateDetails(500L,100L,"숙소","ACCOMMODATION",LocalTime.of(15,0),3);
        verify(travelPlanMapper,never()).touchLastActivity(anyLong());
    }

    @Test void foreignItemAndNonMemberCannotUpdateTypedDetails() {
        assertThatThrownBy(() -> service.updateTypedItem(7L,42L,100L,500L,"일정",0,"SCHEDULE",""))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(travelPlanItemMapper);
    }

    @Test void aValidMemberCannotUpdateAnItemFromAnotherDay() {
        access();
        assertThatThrownBy(() -> service.updateTypedItem(7L,42L,100L,999L,"일정",0,"SCHEDULE",""))
                .isInstanceOf(ResponseStatusException.class);
        verify(travelPlanItemMapper,never()).updateDetails(any(),any(),any(),any(),any(),any());
    }

    @Test void typeAndTimeOnlyEditsArePersistedWithTheSameContentAndVersion() {
        access();
        when(travelPlanItemMapper.findByIdAndDayId(500L,100L)).thenReturn(new TravelPlanItem());
        when(travelPlanItemMapper.updateDetails(500L,100L,"숙소","ACCOMMODATION",LocalTime.of(15,0),3)).thenReturn(1);
        service.updateTypedItem(7L,42L,100L,500L,"숙소",3,"ACCOMMODATION","15:00");
        verify(travelPlanMapper).touchLastActivity(42L);
        verify(eventPublisher).publishEvent(any(TravelPlanScheduleChangedEvent.class));
    }

    @Test void existingFreeTextDefaultsToScheduleAndSplitsOnlyForDisplay() {
        TravelPlanItem item = new TravelPlanItem();
        item.setContent("오전 10시 박물관\n둘째 줄\n셋째 줄");
        assertThat(item.getItemType()).isEqualTo(TravelPlanItemType.SCHEDULE);
        assertThat(item.getTitle()).isEqualTo("오전 10시 박물관");
        assertThat(item.getNote()).isEqualTo("둘째 줄\n셋째 줄");
        assertThat(item.getContent()).isEqualTo("오전 10시 박물관\n둘째 줄\n셋째 줄");
    }

    @Test void flightCreationPersistsDetailsAndUsesDepartureTimeWithoutChangingContent() {
        access();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1);
        var details = new TravelPlanItemDetails();
        details.setFlightNumber("KE123");
        details.setDepartureTime("23:30");
        details.setArrivalTime("04:30");
        service.addTypedItem(7L, 42L, 100L, "기존 제목\n기존 메모", "FLIGHT", "09:00", details);
        ArgumentCaptor<TravelPlanItem> captured = ArgumentCaptor.forClass(TravelPlanItem.class);
        verify(travelPlanItemMapper).insertItem(captured.capture());
        assertThat(captured.getValue().getDetails().getFlightNumber()).isEqualTo("KE123");
        assertThat(captured.getValue().getStartTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(captured.getValue().getContent()).isEqualTo("기존 제목\n기존 메모");
    }

    @Test void accommodationAliasAndPartialDetailsPreserveSubmittedGeneralTime() {
        access();
        when(travelPlanItemMapper.insertItem(any())).thenReturn(1);
        var details = new TravelPlanItemDetails();
        details.setStayName("호텔");
        service.addTypedItem(7L, 42L, 100L, "체크인", "STAY", "15:00", details);
        ArgumentCaptor<TravelPlanItem> captured = ArgumentCaptor.forClass(TravelPlanItem.class);
        verify(travelPlanItemMapper).insertItem(captured.capture());
        assertThat(captured.getValue().getTag()).isEqualTo("ACCOMMODATION");
        assertThat(captured.getValue().getStartTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(captured.getValue().getDetails().getStayName()).isEqualTo("호텔");
    }

    @Test void submittedJsonUpdateUsesVersionAndCannotTouchOtherDays() {
        access();
        when(travelPlanItemMapper.findByIdAndDayId(500L,100L)).thenReturn(new TravelPlanItem());
        var details = new TravelPlanItemDetails();
        details.setStayName("호텔");
        assertThatThrownBy(() -> service.updateTypedItem(7L,42L,100L,500L,"숙소",3,"STAY","15:00",details))
                .isInstanceOf(TravelPlanConflictException.class);
        verify(travelPlanItemMapper).updateStructuredDetails(eq(500L),eq(100L),eq("숙소"),eq("ACCOMMODATION"),
                eq(LocalTime.of(15,0)), any(TravelPlanItemDetails.class), eq(3));
        verify(travelPlanMapper,never()).touchLastActivity(anyLong());
    }
}
