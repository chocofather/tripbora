package com.tripbora.dto.kto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record KtoFestivalBulkStartRequest(
        @NotNull UUID requestId,
        @NotNull @Size(min = 1, max = 30) List<@NotBlank @Size(max = 36) String> previewTokens) {
}
