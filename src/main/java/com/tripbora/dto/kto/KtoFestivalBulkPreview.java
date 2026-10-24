package com.tripbora.dto.kto;

import java.time.LocalDate;
import java.util.List;

public record KtoFestivalBulkPreview(
        String token, String contentId, int eventYear, String title,
        LocalDate startDate, LocalDate endDate, String address, String categoryName,
        KtoFestivalThumbnailCandidate poster, String status, List<String> errors, List<String> warnings) {
    public KtoFestivalBulkPreview {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
