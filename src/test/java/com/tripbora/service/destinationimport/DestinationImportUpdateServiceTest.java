package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationUpdatePreview;
import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.DestinationType;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.destination.DestinationDuplicateCheck;
import com.tripbora.service.destination.DestinationDuplicateReason;
import com.tripbora.service.destination.DestinationDuplicateService;
import com.tripbora.service.destination.DestinationDuplicateStatus;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DestinationImportUpdateServiceTest {
    private final DestinationImportMasterResolver resolver = mock(DestinationImportMasterResolver.class);
    private final DestinationUpdateStore store = mock(DestinationUpdateStore.class);
    private final DestinationFieldProtectionService protections = mock(DestinationFieldProtectionService.class);
    private final DestinationDuplicateService duplicates = mock(DestinationDuplicateService.class);
    private final DestinationService destinations = mock(DestinationService.class);
    private final CountryCategoryService regions = mock(CountryCategoryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final MockHttpSession session = new MockHttpSession();
    private DestinationImportUpdateService service;
    private static final String FIELD = "translations.ko.description";
    private static final String JSON = """
            {"version":1,"destinations":[{"key":"not-a-database-id","external":{"tourApiContentId":"126508"},
              "translations":{"ko":{"description":"보강 본문"}}}]}
            """;

    @BeforeEach
    void setUp() {
        service = new DestinationImportUpdateService(new DestinationImportParser(), resolver,
                new DestinationImportValidator(), new DestinationUpdateMerge(), store, protections,
                duplicates, destinations, regions, transactions);
        var master = mock(DestinationImportMasterResolver.MasterData.class);
        when(resolver.load()).thenReturn(master);
        when(resolver.resolve(any(), anyString(), any())).thenReturn(new DestinationImportMasterResolver.Resolution(
                235L, true, List.of("대한민국", "서울", "종로구"), List.of(), null, null, null, List.of(), List.of()));
        CountryCategory country = new CountryCategory();
        country.setId(235L);
        country.setCode("KR");
        when(regions.getById(235L)).thenReturn(country);
        when(destinations.findTourApiDestinationId("126508")).thenReturn(42L);
        when(store.externalTargets(anyString(), org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            Map<String, Long> result = new LinkedHashMap<>();
            String source = invocation.getArgument(0);
            for (String id : (java.util.Collection<String>) invocation.getArgument(1)) {
                Long target = DestinationService.KTO_TOUR_API_SOURCE_TYPE.equals(source)
                        ? destinations.findTourApiDestinationId(id) : destinations.findWikidataDestinationId(id);
                if (target != null) result.put(id, target);
            }
            return result;
        });
        when(store.read(eq(42L), anyBoolean())).thenReturn(snapshot("기존 본문", "126508"));
        when(store.readAll(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            Map<Long, DestinationUpdateSnapshot> result = new LinkedHashMap<>();
            for (Long id : (java.util.Collection<Long>) invocation.getArgument(0)) {
                var stored = store.read(id, false);
                if (stored != null) result.put(id, stored);
            }
            return result;
        });
        when(protections.protectedFields(anyLong())).thenReturn(Set.of());
        when(protections.protectedFieldsAll(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            Map<Long, Set<String>> result = new LinkedHashMap<>();
            for (Long id : (java.util.Collection<Long>) invocation.getArgument(0)) result.put(id, protections.protectedFields(id));
            return result;
        });
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of());
        when(duplicates.findUpdateCandidatesAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            List<List<DestinationDuplicateCheck>> results = new java.util.ArrayList<>();
            for (var query : (List<com.tripbora.service.destination.DestinationDuplicateQuery>) invocation.getArgument(0))
                results.add(duplicates.findUpdateCandidates(query));
            return results;
        });
        var lookup = mock(DestinationDuplicateService.UpdateLookup.class);
        when(duplicates.openUpdateLookup()).thenReturn(lookup);
        when(lookup.findCandidates(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation ->
                duplicates.findUpdateCandidatesAll(invocation.getArgument(0)));
        when(lookup.checkExcluding(any(), anyLong())).thenAnswer(invocation ->
                duplicates.checkExcluding(invocation.getArgument(0), invocation.getArgument(1)));
        when(duplicates.checkExcluding(any(), anyLong())).thenReturn(DestinationDuplicateCheck.NOT_REGISTERED);
        when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    @Test
    void exactContentIdMatchesWithoutTreatingJsonKeyAsDatabaseId() {
        var preview = preview(JSON, null, false);
        assertThat(preview.fileErrors()).isEmpty();
        assertThat(preview.rows().get(0).status()).isEqualTo("MATCHED");
        assertThat(preview.rows().get(0).destinationId()).isEqualTo(42L);
        assertThat(preview.rows().get(0).token()).isNotBlank();
        verify(duplicates, never()).findUpdateCandidates(any());
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void nameAndRegionCandidatesAlwaysRequireExplicitCandidateConfirmation() {
        String json = """
                {"version":1,"destinations":[{"region":{"country":"대한민국","city":"서울","district":"종로구"},
                 "translations":{"ko":{"name":"경복궁","description":"보강 본문"}}}]}
                """;
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of(candidate(42L), candidate(43L)));

        var unconfirmed = preview(json, null, false).rows().get(0);
        assertThat(unconfirmed.status()).isEqualTo("NEEDS_MATCH");
        assertThat(unconfirmed.candidates()).hasSize(2);
        assertThat(unconfirmed.token()).isNull();
        verify(store, never()).read(any(), anyBoolean());

        assertThat(preview(json, 42L, false).rows().get(0).status()).isEqualTo("NEEDS_MATCH");
        assertThat(preview(json, 42L, true).rows().get(0).token()).isNotBlank();
        assertThat(preview(json, 99L, true).rows().get(0).status()).isEqualTo("INVALID");
        verify(store, never()).read(eq(99L), anyBoolean());
    }

    @Test
    void missingExactExternalIdDoesNotFallBackToNamesOrCreateDestination() {
        when(destinations.findTourApiDestinationId("126508")).thenReturn(null);
        var row = preview(JSON, null, false).rows().get(0);
        assertThat(row.status()).isEqualTo("NOT_FOUND");
        assertThat(row.token()).isNull();
        verify(store, never()).read(any(), anyBoolean());
        verify(duplicates, never()).findUpdateCandidates(any());
    }

    @Test
    void mismatchedSourceIdentityAndForgedSelectionCannotBeApplied() {
        assertThat(preview(JSON, 99L, true).rows().get(0).status()).isEqualTo("INVALID");
        when(store.read(eq(42L), anyBoolean())).thenReturn(snapshot("기존 본문", "999999"));
        assertThat(preview(JSON, null, false).rows().get(0).status()).isEqualTo("INVALID");
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void twoRowsForOneTargetAreBothInvalidAndHaveNoTokens() {
        String json = """
                {"version":1,"destinations":[
                 {"key":"one","external":{"tourApiContentId":"126508"},"translations":{"ko":{"description":"하나"}}},
                 {"key":"two","external":{"tourApiContentId":"126508"},"translations":{"ko":{"description":"둘"}}}]}
                """;
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of(
                selection(0, null, false), selection(1, null, false))), session, 7L);
        assertThat(preview.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("INVALID");
            assertThat(row.token()).isNull();
        });
    }

    @Test
    void appliesOnlyTheBoundPatchAndProtectionInOneTransactionAndConsumesToken() {
        String token = preview(JSON, null, false).rows().get(0).token();
        var result = apply(token, 7L);
        assertThat(result.status()).isEqualTo("UPDATED");
        assertThat(result.changedFields()).isEqualTo(1);
        var order = inOrder(store, protections, transactions);
        order.verify(store).read(42L, true);
        order.verify(protections).protectedFields(42L);
        order.verify(store).apply(42L, DestinationType.ATTRACTION, Map.of(FIELD, "보강 본문"));
        order.verify(protections).protect(42L, Set.of(FIELD), 7L);
        order.verify(transactions).commit(transaction);
        assertThat(apply(token, 7L).status()).isEqualTo("INVALID");
    }

    @Test
    void authenticatedUserAndSessionAreBoundToToken() {
        String token = preview(JSON, null, false).rows().get(0).token();
        assertThat(apply(token, 8L).status()).isEqualTo("INVALID");
        assertThat(service.apply(new DestinationUpdatePreview.ApplyRequest(token, true, true, true),
                new MockHttpSession(), 7L).status()).isEqualTo("INVALID");
        verify(store, never()).apply(any(), any(), any());
        assertThat(apply(token, 7L).status()).isEqualTo("UPDATED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredPreviewCannotBeApplied() {
        String token = preview(JSON, null, false).rows().get(0).token();
        Map<String, DestinationImportUpdateService.Ticket> tickets =
                (Map<String, DestinationImportUpdateService.Ticket>) session.getAttribute(DestinationImportUpdateService.SESSION_KEY);
        var before = tickets.get(token);
        tickets.put(token, new DestinationImportUpdateService.Ticket(before.userId(), Instant.now().minusSeconds(1),
                before.parsed(), before.fields(), before.replaceRelations(), before.before(), before.previousProtection(), before.plan()));

        assertThat(apply(token, 7L).status()).isEqualTo("INVALID");
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void invalidNewPreviewRevokesPreviousFileTokens() {
        String token = preview(JSON, null, false).rows().get(0).token();
        var invalid = preview("not-json", null, false);
        assertThat(invalid.fileErrors()).isNotEmpty();
        assertThat(apply(token, 7L).status()).isEqualTo("INVALID");
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void snapshotConflictRollsBackBeforeAnyMutation() {
        String token = preview(JSON, null, false).rows().get(0).token();
        when(store.read(42L, true)).thenReturn(snapshot("다른 관리자가 수정한 본문", "126508"));

        assertThat(apply(token, 7L).status()).isEqualTo("CONFLICT");
        verify(store, never()).apply(any(), any(), any());
        verify(protections, never()).protect(any(), any(), any());
        verify(transactions).rollback(transaction);
        assertThat(apply(token, 7L).status()).isEqualTo("INVALID");
    }

    @Test
    void protectionConflictAlsoPreventsMutation() {
        String token = preview(JSON, null, false).rows().get(0).token();
        when(protections.protectedFields(42L)).thenReturn(Set.of("info.openingHours"));

        assertThat(apply(token, 7L).status()).isEqualTo("CONFLICT");
        verify(store, never()).apply(any(), any(), any());
        verify(transactions).rollback(transaction);
    }

    @Test
    void protectionFailureRollsBackTheAlreadyWrittenPatch() {
        String token = preview(JSON, null, false).rows().get(0).token();
        doThrow(new IllegalStateException("protection failed")).when(protections).protect(42L, Set.of(FIELD), 7L);

        assertThat(apply(token, 7L).status()).isEqualTo("FAILED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of(FIELD, "보강 본문"));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void unchangedSelectedValueIsExcludedAndCannotCreateApplyTicket() {
        when(store.read(eq(42L), anyBoolean())).thenReturn(snapshot("보강 본문", "126508"));
        var row = preview(JSON, null, false).rows().get(0);
        assertThat(row.token()).isNull();
        assertThat(row.changes()).filteredOn(change -> change.path().equals(FIELD)).singleElement()
                .satisfies(change -> {
                    assertThat(change.kind()).isEqualTo("UNCHANGED");
                    assertThat(change.selected()).isFalse();
                });
        verify(store, never()).apply(any(), any(), any());
        verify(protections, never()).protect(any(), any(), any());
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void warningsAndExplicitConfirmationAreRequiredBeforeStartingTransaction() {
        String token = preview(JSON, null, false).rows().get(0).token();
        assertThat(service.apply(new DestinationUpdatePreview.ApplyRequest(token, false, true, true), session, 7L).status())
                .isEqualTo("INVALID");
        assertThat(service.apply(new DestinationUpdatePreview.ApplyRequest(token, true, false, true), session, 7L).status())
                .isEqualTo("INVALID");
        verify(transactions, never()).getTransaction(any());
        assertThat(apply(token, 7L).status()).isEqualTo("UPDATED");
    }

    @Test
    void tokenBindsTheSelectedSummaryWithoutUpdatingProvidedDescription() {
        String json = """
                {"version":1,"destinations":[{"external":{"tourApiContentId":"126508"},
                 "translations":{"ko":{"shortDescription":"한 줄 보강","description":"선택하지 않은 본문"}}}]}
                """;
        String summary = "translations.ko.shortDescription";
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of(
                new DestinationUpdatePreview.Selection(0, null, false, Set.of(summary), false))), session, 7L);
        assertThat(preview.rows().get(0).changes()).hasSize(2);
        assertThat(apply(preview.rows().get(0).token(), 7L).status()).isEqualTo("UPDATED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of(summary, "한 줄 보강"));
        verify(protections).protect(42L, Set.of(summary), 7L);
    }

    @Test
    void unknownSelectedPathsAndSourceIdentityFieldsNeverReceiveTokens() {
        var preview = service.preview(new DestinationUpdatePreview.Request(JSON, List.of(
                new DestinationUpdatePreview.Selection(0, null, false,
                        Set.of(FIELD, "externalContentId"), false))), session, 7L);
        assertThat(preview.rows().get(0).status()).isEqualTo("INVALID");
        assertThat(preview.rows().get(0).token()).isNull();
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void overseasQidMatchesOnlyItsExistingSourceAndContentId() {
        String json = """
                {"version":1,"destinations":[{"external":{"wikidataQid":"Q123"},
                 "translations":{"ko":{"description":"해외 보강"}}}]}
                """;
        CountryCategory country = new CountryCategory();
        country.setId(235L);
        country.setCode("JP");
        when(regions.getById(235L)).thenReturn(country);
        when(destinations.findWikidataDestinationId("Q123")).thenReturn(42L);
        var domestic = snapshot("기존 본문", "126508");
        when(store.read(eq(42L), anyBoolean())).thenReturn(new DestinationUpdateSnapshot(42L,
                domestic.type(), "WIKIDATA", "Q123", domestic.values(), domestic.labels()));

        var row = preview(json, null, false).rows().get(0);
        assertThat(row.status()).isEqualTo("MATCHED");
        assertThat(apply(row.token(), 7L).status()).isEqualTo("UPDATED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of(FIELD, "해외 보강"));
    }

    @Test
    void manualMatchCannotMoveKtoSourceIntoOverseasRegion() {
        String json = """
                {"version":1,"destinations":[{"region":{"country":"일본","city":"도쿄"},
                 "translations":{"ko":{"name":"경복궁","description":"보강 본문"}}}]}
                """;
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of(candidate(42L)));
        when(resolver.resolve(any(), anyString(), any())).thenReturn(new DestinationImportMasterResolver.Resolution(
                99L, false, List.of("일본", "도쿄"), List.of(), null, null, null, List.of(), List.of()));

        var row = preview(json, 42L, true).rows().get(0);
        assertThat(row.status()).isEqualTo("INVALID");
        assertThat(row.token()).isNull();
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void manualMatchCannotMoveWikidataSourceIntoDomesticRegion() {
        String json = """
                {"version":1,"destinations":[{"region":{"country":"대한민국","city":"서울","district":"종로구"},
                 "translations":{"ko":{"name":"경복궁","description":"보강 본문"}}}]}
                """;
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of(candidate(42L)));
        var target = snapshot("기존 본문", "126508");
        when(store.read(eq(42L), anyBoolean())).thenReturn(new DestinationUpdateSnapshot(42L,
                target.type(), "WIKIDATA", "Q123", target.values(), target.labels()));

        assertThat(preview(json, 42L, true).rows().get(0).status()).isEqualTo("INVALID");
        verify(store, never()).apply(any(), any(), any());
    }

    @Test
    void sourceRegionMismatchIntroducedAfterPreviewRollsBackBeforeMutation() {
        String token = preview(JSON, null, false).rows().get(0).token();
        CountryCategory changed = new CountryCategory();
        changed.setId(235L);
        changed.setCode("JP");
        when(regions.getById(235L)).thenReturn(changed);

        assertThat(apply(token, 7L).status()).isEqualTo("CONFLICT");
        verify(store, never()).apply(any(), any(), any());
        verify(protections, never()).protect(any(), any(), any());
        verify(transactions).rollback(transaction);
    }

    @Test
    void relationRemovalRequiresExplicitApprovalEvenWithAValidPreviewToken() {
        var original = snapshot("기존 본문", "126508");
        var values = new LinkedHashMap<>(original.values());
        values.put("amenities", List.of(1, 2));
        when(store.read(eq(42L), anyBoolean())).thenReturn(new DestinationUpdateSnapshot(42L, original.type(),
                original.sourceType(), original.externalContentId(), values, original.labels()));
        when(resolver.resolve(any(), anyString(), any())).thenReturn(new DestinationImportMasterResolver.Resolution(
                235L, true, List.of("대한민국"), List.of(), null, null, null, List.of(1), List.of()));
        String json = """
                {"version":1,"destinations":[{"external":{"tourApiContentId":"126508"},"amenities":["PARKING"]}]}
                """;
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of(
                new DestinationUpdatePreview.Selection(0, null, false, Set.of("amenities"), true))), session, 7L);
        String token = preview.rows().get(0).token();
        assertThat(token).isNotBlank();
        assertThat(service.apply(new DestinationUpdatePreview.ApplyRequest(token, true, true, false), session, 7L).status())
                .isEqualTo("INVALID");
        verify(store, never()).apply(any(), any(), any());
        assertThat(apply(token, 7L).status()).isEqualTo("UPDATED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of("amenities", List.of(1)));
    }

    @Test
    void changingCoordinatesAfterPreviewIsAConflictAndCannotPartiallySave() {
        String token = preview(JSON, null, false).rows().get(0).token();
        var original = snapshot("기존 본문", "126508");
        var values = new LinkedHashMap<>(original.values());
        values.put("latitude", new java.math.BigDecimal("37.5"));
        values.put("longitude", new java.math.BigDecimal("127.0"));
        when(store.read(42L, true)).thenReturn(new DestinationUpdateSnapshot(42L, original.type(),
                original.sourceType(), original.externalContentId(), values, original.labels()));
        assertThat(apply(token, 7L).status()).isEqualTo("CONFLICT");
        verify(store, never()).apply(any(), any(), any());
        verify(protections, never()).protect(any(), any(), any());
        verify(transactions).rollback(transaction);
    }

    @Test
    void selectionRepreviewReusesOnlyTheSameUserSessionFileAndKeepsApplyLive() {
        var first = preview(JSON, null, false);
        assertThat(first.previewToken()).isNotBlank();
        var repeated = service.preview(new DestinationUpdatePreview.Request(JSON,
                List.of(selection(0, null, false)), first.previewToken()), session, 7L);
        assertThat(repeated.rows().get(0).token()).isNotBlank();
        verify(resolver, org.mockito.Mockito.times(1)).load();
        verify(destinations, org.mockito.Mockito.times(1)).findTourApiDestinationId("126508");
        verify(store, org.mockito.Mockito.times(1)).readAll(org.mockito.ArgumentMatchers.anyCollection());
        verify(protections, org.mockito.Mockito.times(1)).protectedFieldsAll(org.mockito.ArgumentMatchers.anyCollection());
        assertThat(apply(repeated.rows().get(0).token(), 7L).status()).isEqualTo("UPDATED");
        verify(store).read(42L, true);
        verify(resolver, org.mockito.Mockito.times(2)).load();
        assertThat(service.preview(new DestinationUpdatePreview.Request(JSON, List.of(), repeated.previewToken()), session, 7L)
                .fileErrors()).isNotEmpty();
        assertThat(preview(JSON, null, false).fileErrors()).isEmpty();
    }

    @Test
    void cachedPreviewCannotBeReusedForDifferentJsonOrDifferentUser() {
        var first = preview(JSON, null, false);
        assertThat(service.preview(new DestinationUpdatePreview.Request(JSON + " ", List.of(), first.previewToken()), session, 7L)
                .fileErrors()).isNotEmpty();
        assertThat(apply(first.rows().get(0).token(), 7L).status()).isEqualTo("INVALID");
        var second = preview(JSON, null, false);
        assertThat(service.preview(new DestinationUpdatePreview.Request(JSON, List.of(), second.previewToken()), session, 8L)
                .fileErrors()).isNotEmpty();
    }

    @Test
    void defaultAutoAddCanApplyAndExplicitEmptySelectionRevokesItsTicket() {
        when(store.read(eq(42L), anyBoolean())).thenReturn(snapshot(null, "126508"));
        var first = service.preview(new DestinationUpdatePreview.Request(JSON, List.of()), session, 7L);
        assertThat(first.rows().get(0).token()).isNotBlank();
        assertThat(first.rows().get(0).changes()).filteredOn(change -> change.path().equals(FIELD)).singleElement()
                .satisfies(change -> {
                    assertThat(change.kind()).isEqualTo("AUTO_ADD");
                    assertThat(change.selected()).isTrue();
                });
        var excluded = service.preview(new DestinationUpdatePreview.Request(JSON, List.of(
                new DestinationUpdatePreview.Selection(0, null, false, Set.of(), false)), first.previewToken()), session, 7L);
        assertThat(excluded.rows().get(0).token()).isNull();
        assertThat(apply(first.rows().get(0).token(), 7L).status()).isEqualTo("INVALID");
        var fresh = service.preview(new DestinationUpdatePreview.Request(JSON, List.of()), session, 7L);
        assertThat(apply(fresh.rows().get(0).token(), 7L).status()).isEqualTo("UPDATED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of(FIELD, "보강 본문"));
    }

    @Test
    void defaultAutoAddPreservesNullDescriptionAndUnselectedExistingName() {
        String json = """
                {"version":1,"destinations":[{"external":{"tourApiContentId":"126508"},
                  "translations":{"ko":{"name":"미선택 새 이름","description":null,"shortDescription":"신규 요약"}}}]}
                """;
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of()), session, 7L);
        var row = preview.rows().get(0);
        assertThat(row.changes()).noneMatch(change -> change.path().equals(FIELD));
        assertThat(row.changes()).filteredOn(change -> change.path().equals("translations.ko.name")).singleElement()
                .satisfies(change -> assertThat(change.selected()).isFalse());
        assertThat(apply(row.token(), 7L).status()).isEqualTo("UPDATED");
        verify(store).apply(42L, DestinationType.ATTRACTION, Map.of("translations.ko.shortDescription", "신규 요약"));
    }

    @Test
    void thirtyExactContentIdsUseOneSourceLookupAndOneSnapshotBatch() throws Exception {
        Map<String, Long> ids = new LinkedHashMap<>();
        Map<Long, DestinationUpdateSnapshot> snapshots = new LinkedHashMap<>();
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String contentId = Integer.toString(10000 + i);
            long id = 100L + i;
            ids.put(contentId, id);
            var original = snapshot("기존 본문", contentId);
            snapshots.put(id, new DestinationUpdateSnapshot(id, original.type(), original.sourceType(), contentId,
                    original.values(), original.labels()));
            items.add(Map.of("key", "exact-" + i, "external", Map.of("tourApiContentId", contentId),
                    "translations", Map.of("ko", Map.of("description", "새 본문"))));
        }
        when(store.externalTargets(eq(DestinationService.KTO_TOUR_API_SOURCE_TYPE), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(ids);
        when(store.readAll(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(snapshots);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("version", 1, "destinations", items));
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of()), session, 7L);
        assertThat(preview.rows()).hasSize(30).allSatisfy(row -> assertThat(row.status()).isEqualTo("MATCHED"));
        verify(store, org.mockito.Mockito.times(1)).externalTargets(eq(DestinationService.KTO_TOUR_API_SOURCE_TYPE), org.mockito.ArgumentMatchers.anyCollection());
        verify(store, org.mockito.Mockito.times(1)).readAll(org.mockito.ArgumentMatchers.anyCollection());
        verify(protections, org.mockito.Mockito.times(1)).protectedFieldsAll(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void unselectedForeignJsonRegionCannotEnablePlaceIdOnExistingDomesticAdminTarget() {
        var original = snapshot("기존 본문", null);
        when(store.read(eq(42L), anyBoolean())).thenReturn(new DestinationUpdateSnapshot(42L, original.type(),
                "ADMIN", null, original.values(), original.labels()));
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of(candidate(42L)));
        when(resolver.resolve(any(), anyString(), any())).thenReturn(new DestinationImportMasterResolver.Resolution(
                600L, false, List.of("프랑스"), List.of(), null, null, null, List.of(), List.of()));
        String json = """
                {"version":1,"destinations":[{"region":{"country":"프랑스"},
                 "external":{"googlePlaceId":"ChIJLU7jZClu5kcR4PcOOO6p3I0"},"translations":{"ko":{"name":"경복궁"}}}]}
                """;
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of(
                new DestinationUpdatePreview.Selection(0, 42L, true, Set.of("googlePlaceId"), false))), session, 7L);
        assertThat(preview.rows().get(0).status()).isEqualTo("INVALID");
        assertThat(preview.rows().get(0).message()).contains("실제 저장될 해외 지역");
        assertThat(preview.rows().get(0).token()).isNull();
    }

    @Test
    void placeRegionIsCheckedLiveAgainBeforeApplyEvenWhenSnapshotValuesDidNotChange() {
        var original = snapshot("기존 본문", null);
        when(store.read(eq(42L), anyBoolean())).thenReturn(new DestinationUpdateSnapshot(42L, original.type(),
                "ADMIN", null, original.values(), original.labels()));
        when(duplicates.findUpdateCandidates(any())).thenReturn(List.of(candidate(42L)));
        when(resolver.resolve(any(), anyString(), any())).thenReturn(new DestinationImportMasterResolver.Resolution(
                600L, false, List.of("프랑스"), List.of(), null, null, null, List.of(), List.of()));
        CountryCategory actualRegion = new CountryCategory();
        actualRegion.setId(235L); actualRegion.setCode("FR");
        when(regions.getById(235L)).thenReturn(actualRegion);
        String json = """
                {"version":1,"destinations":[{"region":{"country":"프랑스"},
                 "external":{"googlePlaceId":"ChIJLU7jZClu5kcR4PcOOO6p3I0"},"translations":{"ko":{"name":"경복궁"}}}]}
                """;
        var preview = service.preview(new DestinationUpdatePreview.Request(json, List.of(
                new DestinationUpdatePreview.Selection(0, 42L, true, Set.of("googlePlaceId"), false))), session, 7L);
        assertThat(preview.rows().get(0).token()).isNotBlank();
        actualRegion.setCode("KR");
        assertThat(apply(preview.rows().get(0).token(), 7L).status()).isEqualTo("CONFLICT");
        verify(store, never()).apply(any(), any(), any());
    }

    private DestinationUpdatePreview preview(String json, Long id, boolean confirmedMatch) {
        return service.preview(new DestinationUpdatePreview.Request(json, List.of(selection(0, id, confirmedMatch))), session, 7L);
    }

    @Test
    void lostApplyResponseCanBeCheckedWhileRunningAndAfterCommitWithoutRepeatingWrite() throws Exception {
        var row = preview(JSON, null, false).rows().get(0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(store.read(42L, true)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return snapshot("기존 본문", "126508");
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> apply(row.token(), 7L));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.applyStatus(row.token(), session, 7L).status()).isEqualTo("RUNNING");
            assertThat(service.applyStatus(row.token(), session, 8L).destinationId()).isNull();
            release.countDown();
            assertThat(future.get(3, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("UPDATED");
            assertThat(service.applyStatus(row.token(), session, 7L).status()).isEqualTo("UPDATED");
            assertThat(apply(row.token(), 7L).status()).isEqualTo("INVALID");
            verify(store, org.mockito.Mockito.times(1)).apply(eq(42L), any(), any());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void mvcJsonRequestReachesRealServiceAndReusesCacheBeforeApplyingOnce() throws Exception {
        var admin = new com.tripbora.model.User(); admin.setId(7L); admin.setUserRole(com.tripbora.model.UserRole.ADMIN);
        admin.setUserPassword("test-only");
        var principal = new com.tripbora.security.CustomUserDetails(admin);
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(principal, "test-only", "ROLE_ADMIN"));
        var controller = new com.tripbora.controller.admin.AdminDestinationImportController(
                mock(DestinationImportService.class), mock(DestinationImportMasterExportService.class), service);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> measurements = new LinkedHashMap<>();
        var request = new DestinationUpdatePreview.Request(JSON, List.of(selection(0, null, false)));
        try {
            String previewToken = null, applyToken = null;
            for (int iteration = 0; iteration < 2; iteration++) {
                long started = System.nanoTime();
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/admin/api/destinations/import-json/update/preview").session(session)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(new DestinationUpdatePreview.Request(request.json(), request.selections(), previewToken))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
                measurements.put(iteration == 0 ? "previewMs" : "cachedPreviewMs", (System.nanoTime() - started) / 1_000_000.0);
                var payload = mapper.readTree(response.getContentAsByteArray());
                assertThat(payload.get("rows").get(0).get("status").asText()).isEqualTo("MATCHED");
                previewToken = payload.get("previewToken").asText(); applyToken = payload.get("rows").get(0).get("token").asText();
                assertThat(response.getHeader("Server-Timing")).startsWith("preview;dur=");
            }
            verify(store, org.mockito.Mockito.times(1)).readAll(org.mockito.ArgumentMatchers.anyCollection());
            long started = System.nanoTime();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/admin/api/destinations/import-json/update/apply").session(session)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(new DestinationUpdatePreview.ApplyRequest(applyToken, true, true, true))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UPDATED"));
            measurements.put("applyMs", (System.nanoTime() - started) / 1_000_000.0);
            measurements.put("boundary", "Spring MVC + real update service; mocked DB/master/transaction manager; not DEV HTTP or DB latency");
            var output = java.nio.file.Path.of("build/reports/destination-json-update/mvc-flow.json");
            java.nio.file.Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), measurements);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test
    void receiptReportsRollbackFailureWithoutRepeatingApply() {
        var row = preview(JSON, null, false).rows().get(0);
        org.mockito.Mockito.doThrow(new IllegalStateException("test write failure")).when(store).apply(eq(42L), any(), any());
        assertThat(apply(row.token(), 7L).status()).isEqualTo("FAILED");
        assertThat(service.applyStatus(row.token(), session, 7L).status()).isEqualTo("FAILED");
        verify(transactions).rollback(transaction);
        verify(store, org.mockito.Mockito.times(1)).apply(eq(42L), any(), any());
    }

    @Test
    void commitCommunicationFailureRemainsUnknownRatherThanClaimingRollback() {
        var row = preview(JSON, null, false).rows().get(0);
        org.mockito.Mockito.doThrow(new org.springframework.transaction.TransactionSystemException("test commit outcome unknown"))
                .when(transactions).commit(transaction);
        assertThat(apply(row.token(), 7L).status()).isEqualTo("UNKNOWN");
        assertThat(service.applyStatus(row.token(), session, 7L).status()).isEqualTo("UNKNOWN");
        assertThat(apply(row.token(), 7L).status()).isEqualTo("INVALID");
        verify(store, org.mockito.Mockito.times(1)).apply(eq(42L), any(), any());
    }

    private static DestinationUpdatePreview.Selection selection(int index, Long id, boolean confirmedMatch) {
        return new DestinationUpdatePreview.Selection(index, id, confirmedMatch, Set.of(FIELD), false);
    }

    private DestinationUpdatePreview.Result apply(String token, Long userId) {
        return service.apply(new DestinationUpdatePreview.ApplyRequest(token, true, true, true), session, userId);
    }

    private static DestinationDuplicateCheck candidate(Long id) {
        return new DestinationDuplicateCheck(DestinationDuplicateStatus.POSSIBLE_DUPLICATE,
                DestinationDuplicateReason.NAME_AND_REGION, id, "경복궁", null, "이름·지역 일치");
    }

    private static DestinationUpdateSnapshot snapshot(String description, String contentId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("regionId", 235L);
        values.put("season", "ALL_SEASONS");
        values.put("translations.ko.name", "경복궁");
        values.put(FIELD, description);
        values.put("categories", List.of());
        values.put("amenities", List.of());
        return new DestinationUpdateSnapshot(42L, DestinationType.ATTRACTION, "KTO_TOURAPI", contentId, values, Map.of());
    }
}
