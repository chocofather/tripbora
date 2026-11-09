package com.tripbora.controller.destination;

import com.tripbora.dto.DestinationGalleryPage;
import com.tripbora.service.destination.DestinationGalleryService;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class DestinationGalleryTestSupport {
    static DestinationGalleryService empty() {
        var gallery = mock(DestinationGalleryService.class);
        lenient().when(gallery.page(anyLong(), anyString(), isNull()))
                .thenReturn(new DestinationGalleryPage(List.of(), null, null));
        return gallery;
    }
}
