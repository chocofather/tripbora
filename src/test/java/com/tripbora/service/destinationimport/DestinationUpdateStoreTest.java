package com.tripbora.service.destinationimport;

import com.tripbora.model.DestinationType;
import com.tripbora.model.DestinationTranslation;
import com.tripbora.model.DestinationTranslationSource;
import com.tripbora.repository.destination.DestinationJsonUpdateMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.destination.DestinationTranslationSourceMapper;
import com.tripbora.service.wikidata.WikidataRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DestinationUpdateStoreTest {
    private DestinationJsonUpdateMapper mapper;
    private DestinationMapper destinations;
    private DestinationTranslationSourceMapper sources;
    private DestinationUpdateStore store;

    @BeforeEach
    void setup() {
        mapper = mock(DestinationJsonUpdateMapper.class);
        destinations = mock(DestinationMapper.class);
        sources = mock(DestinationTranslationSourceMapper.class);
        store = new DestinationUpdateStore(mapper, destinations, sources);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockDestination(4L)).thenReturn(4L);
        when(mapper.findBase(4L, true)).thenReturn(new HashMap<>(Map.of("id", 4L, "type", "ATTRACTION",
                "region_id", 5L, "region_name", "서울", "source_type", "KTO_TOURAPI", "external_content_id", "123")));
        when(mapper.findTranslations(4L, true)).thenReturn(List.of(Map.of("id", 20L, "language_code", "en",
                "name", "Existing", "description", "Old", "short_description", "Summary")));
        when(mapper.findInfo(4L, DestinationType.ATTRACTION, true)).thenReturn(Map.of("opening_hours", "9-5"));
        when(mapper.findInfoTranslations(4L, DestinationType.ATTRACTION, true))
                .thenReturn(List.of(Map.of("language_code", "en", "opening_hours", "9-5", "guide", "Keep")));
        when(mapper.findCategories(4L, true)).thenReturn(List.of(Map.of("category_id", 1L, "name", "문화", "is_main", 1)));
        when(mapper.findAmenities(4L, DestinationType.ATTRACTION, true)).thenReturn(List.of(Map.of("amenity_id", 7, "code", "PARKING")));
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test
    void externalIdentityBatchNeverSelectsAmbiguousOrOtherSourceRecords() {
        when(mapper.findExternalTargets("KTO_TOURAPI", List.of("123", "456"))).thenReturn(List.of(
                Map.of("id", 4L, "external_content_id", "123"),
                Map.of("id", 5L, "external_content_id", "456"),
                Map.of("id", 6L, "external_content_id", "456")));
        assertThat(store.externalTargets("KTO_TOURAPI", List.of("123", "456"))).containsExactlyEntriesOf(Map.of("123", 4L));
        verify(mapper).findExternalTargets("KTO_TOURAPI", List.of("123", "456"));
        assertThatThrownBy(() -> store.externalTargets("ADMIN_UPLOAD", List.of("123")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewBatchUsesSixQueriesForThirtyRowsAndKeepsRawMissingTranslations() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 30).boxed().toList();
        when(mapper.findBases(ids)).thenReturn(ids.stream().map(id -> Map.<String, Object>of(
                "id", id, "type", "ATTRACTION", "region_id", 5L, "region_name", "서울",
                "source_type", "KTO_TOURAPI", "external_content_id", "kto-" + id)).toList());
        when(mapper.findTranslationsBatch(ids)).thenReturn(List.of(Map.of("destination_id", 1L,
                "language_code", "ko", "name", "한국어 이름")));
        var snapshots = store.readAll(ids);
        assertThat(snapshots).hasSize(30);
        assertThat(snapshots.get(1L).values()).containsEntry("translations.ko.name", "한국어 이름")
                .doesNotContainKey("translations.en.name");
        assertThat(snapshots.get(2L).externalContentId()).isEqualTo("kto-2");
        verify(mapper).findBases(ids);
        verify(mapper).findTranslationsBatch(ids);
        verify(mapper).findCategoriesBatch(ids);
        verify(mapper).findInfoBatch(ids, DestinationType.ATTRACTION);
        verify(mapper).findInfoTranslationsBatch(ids, DestinationType.ATTRACTION);
        verify(mapper).findAmenitiesBatch(ids, DestinationType.ATTRACTION);
        verify(mapper, never()).findBase(anyLong(), anyBoolean());
        verify(mapper, never()).lockDestination(anyLong());
        verifyNoInteractions(destinations, sources);
    }

    @Test
    void emptyBatchDoesNotIssueQueries() {
        clearInvocations(mapper);
        assertThat(store.readAll(List.of())).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void updatingOneLanguageFieldKeepsRowAndOtherFieldsAndWikipediaAttribution() {
        DestinationTranslation translation = new DestinationTranslation();
        translation.setId(20L);
        when(destinations.findTranslationByDestinationAndLanguage(4L, "en")).thenReturn(translation);
        DestinationTranslationSource source = new DestinationTranslationSource();
        source.setOriginalContentSha256(WikidataRegistrationService.sha256("Old"));
        when(sources.findByTranslationId(20L)).thenReturn(source);
        store.apply(4L, DestinationType.ATTRACTION, Map.of("translations.en.description", "New"));
        verify(mapper).updateTranslationField(4L, "en", "description", "New");
        verify(sources).updateModified(20L, true);
        verify(sources, never()).deleteByTranslationId(anyLong());
        verify(mapper, never()).insertTranslation(anyLong(), anyString());
        verify(mapper, never()).updateBaseField(anyLong(), anyString(), any());
        verify(mapper, never()).updateInfoField(anyLong(), any(), anyString(), any());
        verify(destinations, never()).deleteDestinationCategory(anyLong(), anyLong());
        verify(mapper, never()).deleteAmenity(anyLong(), any(), anyInt());
    }

    @Test
    void subtypeTranslationPatchesOnlySelectedFieldAndNeverRewritesKorean() {
        store.apply(4L, DestinationType.ATTRACTION, Map.of("info.translations.en.openingHours", "10-6"));
        verify(mapper).updateInfoTranslationField(4L, DestinationType.ATTRACTION, "en", "openingHours", "10-6");
        verify(mapper, never()).insertInfoTranslation(anyLong(), any(), anyString());
        verify(mapper, never()).updateInfoField(anyLong(), any(), anyString(), any());
        verifyNoInteractions(destinations, sources);
    }

    @Test
    void amenitiesUseDifferenceAndInvalidMetadataPathsNeverMutate() {
        store.apply(4L, DestinationType.ATTRACTION, Map.of("amenities", List.of(7, 8)));
        verify(mapper).insertAmenity(4L, DestinationType.ATTRACTION, 8);
        verify(mapper, never()).deleteAmenity(anyLong(), any(), anyInt());
        assertThatThrownBy(() -> store.apply(4L, DestinationType.ATTRACTION, Map.of("type", "SHOP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.apply(4L, DestinationType.ATTRACTION, Map.of("categories", List.of(2L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotRetainsNullsAndSourceAndReadsParentLockFirst() {
        var snapshot = store.read(4L, true);
        assertThat(snapshot.sourceType()).isEqualTo("KTO_TOURAPI");
        assertThat(snapshot.externalContentId()).isEqualTo("123");
        assertThat(snapshot.values()).containsEntry("latitude", null)
                .containsEntry("info.translations.en.guide", "Keep")
                .containsEntry("mainCategory", 1L);
        var ordered = inOrder(mapper);
        ordered.verify(mapper).lockDestination(4L);
        ordered.verify(mapper).findBase(4L, true);
    }
}
