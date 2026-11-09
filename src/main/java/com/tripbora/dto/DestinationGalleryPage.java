package com.tripbora.dto;

import java.util.List;

public record DestinationGalleryPage(List<DestinationGalleryImage> items, String nextCursor, String previousCursor) {
    public DestinationGalleryPage {
        items = List.copyOf(items);
    }
}
