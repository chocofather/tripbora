package com.tripbora.dto.kto;

import java.util.List;

public record KtoFestivalBulkJob(
        String id, String status, int total, int processed, int success, int failed, int duplicate,
        List<Item> items) {
    public KtoFestivalBulkJob { items = List.copyOf(items); }

    public record Item(String contentId, int eventYear, String title, String status,
                       Long festivalId, String message, String warning) {}
}
