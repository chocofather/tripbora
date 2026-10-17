package com.tripbora.service.travelplan;

import com.tripbora.model.TravelPlan;
import com.tripbora.model.TravelPlanMember;
import com.tripbora.repository.travelplan.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.mockito.Mockito.*;

class TravelPlanDashboardBroadcastTest {
    @Test void sharedChangesUseTheExistingScheduleChannelAndPublishNoPrivateContent() {
        var plans = mock(TravelPlanMapper.class);
        var mapper = mock(TravelPlanDashboardMapper.class);
        var publisher = mock(ApplicationEventPublisher.class);
        when(plans.findMemberByPlanAndUser(42L,7L,"ACTIVE")).thenReturn(new TravelPlanMember());
        when(plans.findPlanByIdAndStatusForUpdate(42L,"ACTIVE")).thenReturn(new TravelPlan());
        when(mapper.findActiveMemberForUpdate(42L,7L)).thenReturn(11L);
        when(mapper.findExpenses(42L)).thenReturn(List.of());
        when(mapper.findChecklist(42L)).thenReturn(List.of());
        var service = new TravelPlanDashboardService(plans,mapper,mock(TravelPlanFinalMapper.class),publisher);
        service.addChecklist(7L,42L,"여권");
        var event = new TravelPlanScheduleChangedEvent(42L,List.of(),TravelPlanScheduleChangeType.DASHBOARD_UPDATED);
        verify(publisher).publishEvent(event);
        var messaging = mock(SimpMessagingTemplate.class);
        new TravelPlanScheduleChangedListener(messaging).onScheduleChanged(event);
        verify(messaging).convertAndSend("/topic/travel-plans/42/schedule",event);
    }
}
