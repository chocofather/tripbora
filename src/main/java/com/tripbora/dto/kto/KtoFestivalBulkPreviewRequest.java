package com.tripbora.dto.kto;

import jakarta.validation.constraints.*;

public record KtoFestivalBulkPreviewRequest(
        @NotBlank @Pattern(regexp = "[0-9]{1,30}") String contentId,
        @Min(1900) @Max(2999) int eventYear,
        @NotBlank @Size(max = 100) String posterSelection) {
}
