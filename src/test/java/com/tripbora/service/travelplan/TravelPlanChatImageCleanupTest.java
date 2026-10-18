package com.tripbora.service.travelplan;

import com.tripbora.repository.travelplan.TravelPlanChatImageMapper;
import com.tripbora.service.file.TravelPlanChatImageStorage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class TravelPlanChatImageCleanupTest {
    @Test void keepsReferencedFilesAndDoesNotDeleteWhenDatabaseCheckFails() {
        var mapper=mock(TravelPlanChatImageMapper.class);var storage=mock(TravelPlanChatImageStorage.class);
        when(storage.staleKeys(200)).thenReturn(List.of("live","orphan","unavailable"));
        when(mapper.isReferenced("live")).thenReturn(true);
        when(mapper.isReferenced("unavailable")).thenThrow(new IllegalStateException("database unavailable"));
        new TravelPlanChatImageCleanup(mapper,storage).clean();
        verify(storage).delete("orphan");
        verify(storage,never()).delete("live");verify(storage,never()).delete("unavailable");
    }
}
