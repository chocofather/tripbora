package com.tripbora.service.destinationimport;

import com.tripbora.repository.destination.DestinationFieldProtectionMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DestinationFieldProtectionServiceTest {

    private final DestinationFieldProtectionMapper mapper = mock(DestinationFieldProtectionMapper.class);
    private final DestinationFieldProtectionService service = new DestinationFieldProtectionService(mapper);

    @Test
    void batchPreviewReadsAllProtectionInOneQueryIncludingEmptyTargets() {
        when(mapper.findProtectedFieldsBatch(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(
                Map.of("destination_id", 12L, "field_path", "info.openingHours")));
        assertThat(service.protectedFieldsAll(List.of(12L, 13L)))
                .containsEntry(12L, java.util.Set.of("info.openingHours"))
                .containsEntry(13L, java.util.Set.of());
        verify(mapper).findProtectedFieldsBatch(java.util.Set.of(12L, 13L));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).findFieldPaths(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void protectionSeparatesSummaryFromDescriptionAndEachLanguage() {
        when(mapper.findFieldPaths(12L)).thenReturn(List.of("translations.ko.shortDescription"));
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("translations.ko.shortDescription", "KTO 요약");
        proposed.put("translations.ko.description", "KTO 본문");
        proposed.put("translations.en.shortDescription", "KTO summary");

        assertThat(service.filterKtoPatch(12L, proposed))
                .hasSize(2)
                .containsEntry("translations.ko.description", "KTO 본문")
                .containsEntry("translations.en.shortDescription", "KTO summary");
        assertThat(proposed).hasSize(3);
    }

    @Test
    void protectedEmptyValuesCannotBeFilledAndFalseIsPreservedForUnprotectedFields() {
        when(mapper.findFieldPaths(12L)).thenReturn(List.of(
                "info.openingHours", "translations.ja.description", "amenities"));
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("info.openingHours", null);
        proposed.put("translations.ja.description", "");
        proposed.put("amenities", List.of());
        proposed.put("info.parkingAvailable", false);

        assertThat(service.filterKtoPatch(12L, proposed))
                .containsExactlyEntriesOf(Map.of("info.parkingAvailable", false));
    }

    @Test
    void registeringProtectionDeduplicatesPathsWithoutChangingTheirMeaning() {
        service.protect(12L, List.of("info.openingHours", "info.openingHours",
                "translations.zh-CN.description"), 7L);

        verify(mapper).insert(12L, "info.openingHours", 7L);
        verify(mapper).insert(12L, "translations.zh-CN.description", 7L);
    }

    @Test
    void validatesAllPathsBeforeWritingAnyProtection() {
        assertThatThrownBy(() -> service.protect(12L,
                List.of("info.openingHours", "info..invalid"), 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void nestedObjectsCannotBypassFieldLevelProtection() {
        assertThatThrownBy(() -> service.filterKtoPatch(12L,
                Map.of("info", Map.of("openingHours", "KTO 운영시간"))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
