package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationImportItem;
import com.tripbora.dto.destinationimport.DestinationUpdatePreview;
import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import com.tripbora.model.DestinationType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationUpdateMergeTest {
    private final DestinationUpdateMerge merge = new DestinationUpdateMerge();
    private final DestinationUpdateSnapshot snapshot = new DestinationUpdateSnapshot(1L, DestinationType.ATTRACTION,
            "KTO_TOURAPI", "123", Map.of("translations.ko.shortDescription", "old short",
            "translations.ko.description", "old body", "translations.en.shortDescription", "old en short",
            "translations.en.description", "old en body", "categories", List.of(10L), "mainCategory", 10L,
            "amenities", List.of(1)), Map.of());

    @Test
    void shortAndBodyAndTheirTranslationsAreIndependentAndUncheckedNullFieldsArePreserved() {
        var item = item(Map.of("ko", new DestinationImportItem.Text("ignored name", "new short", "new body"),
                "en", new DestinationImportItem.Text(null, "new en short", "new en body")), DestinationImportItem.Info.EMPTY);
        for (var fields : List.of(Set.<String>of(), Set.of("translations.ko.shortDescription"),
                Set.of("translations.ko.description"), Set.of("translations.ko.shortDescription", "translations.ko.description"),
                Set.of("translations.en.shortDescription"), Set.of("translations.en.description"))) {
            var plan = merge.plan(snapshot, item, resolution(), fields, false);
            assertThat(plan.patch()).containsOnlyKeys(fields);
        }
        assertThat(merge.plan(snapshot, item(Map.of("ko", new DestinationImportItem.Text(null, null, null)),
                DestinationImportItem.Info.EMPTY), resolution(), Set.of("translations.ko.description"), false).patch())
                .isEmpty();
        assertThat(merge.plan(snapshot, item, resolution(), Set.of("is_public"), false).errors()).isNotEmpty();
    }

    @Test
    void allFiveLanguagesAndInfoUseExactLanguageFieldKeysAndNoOpIsSafe() {
        Map<String, DestinationImportItem.Text> text = new LinkedHashMap<>();
        for (String language : DestinationImportFields.LANGUAGES) {
            text.put(language, new DestinationImportItem.Text(language + " name", language + " short", language + " body"));
        }
        var info = new DestinationImportItem.Info(Map.of("openingHours", "09-18", "parkingAvailable", false),
                Map.of("en", Map.of("openingHours", "9am-6pm"), "ja", Map.of("openingHours", "9時-18時"),
                        "zh-CN", Map.of("openingHours", "9点-18点"), "zh-TW", Map.of("openingHours", "9點-18點")));
        Set<String> fields = new LinkedHashSet<>();
        DestinationImportFields.LANGUAGES.forEach(language -> fields.add("translations." + language + ".description"));
        fields.addAll(Set.of("info.openingHours", "info.parkingAvailable", "info.translations.en.openingHours"));
        var plan = merge.plan(snapshot, item(text, info), resolution(), fields, false);
        assertThat(plan.patch()).containsEntry("info.parkingAvailable", false)
                .containsEntry("info.translations.ko.openingHours", "09-18")
                .doesNotContainKeys("translations.en.shortDescription", "info.translations.ja.openingHours");
        var after = new HashMap<>(snapshot.values());
        after.putAll(plan.patch());
        assertThat(merge.plan(new DestinationUpdateSnapshot(1L, snapshot.type(), snapshot.sourceType(),
                snapshot.externalContentId(), after, snapshot.labels()), item(text, info), resolution(), fields, false).patch())
                .isEmpty();
        Set<String> infoTranslations = new LinkedHashSet<>();
        DestinationImportFields.INFO_TRANSLATION_LANGUAGES.forEach(language ->
                infoTranslations.add("info.translations." + language + ".openingHours"));
        var translatedOnly = merge.plan(snapshot, item(text, info), resolution(), infoTranslations, false);
        assertThat(translatedOnly.patch()).containsOnlyKeys(infoTranslations)
                .doesNotContainKeys("info.openingHours", "info.translations.ko.openingHours");
    }

    @Test
    void relationsAddByDefaultAndRemovalRequiresSeparateReplacementSelection() {
        var item = new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of("new category"), "new category", List.of("PARKING"), Map.of(), null, null);
        var fields = Set.of("categories", "mainCategory", "amenities");
        var added = merge.plan(snapshot, item, resolution(), fields, false);
        assertThat(added.patch()).containsEntry("categories", List.of(10L, 20L)).containsEntry("amenities", List.of(1, 2));
        assertThat(added.hasRemovals()).isFalse();
        var replaced = merge.plan(snapshot, item, resolution(), fields, true);
        assertThat(replaced.patch()).containsEntry("categories", List.of(20L)).containsEntry("amenities", List.of(2));
        assertThat(replaced.hasRemovals()).isTrue();
        assertThat(replaced.changes()).filteredOn(change -> change.path().equals("categories")).singleElement()
                .satisfies(change -> assertThat(change.afterText()).contains("추가:", "제거:"));
    }

    @Test
    void coordinatesMustBeSelectedTogetherAndEquivalentDecimalValuesAreNotRewritten() {
        var lat = new java.math.BigDecimal("37.5796");
        var lon = new java.math.BigDecimal("126.9770");
        var item = new DestinationImportItem(null, snapshot.type(), null, null, lat, lon, null,
                List.of(), null, List.of(), Map.of(), null, null);
        assertThat(merge.plan(snapshot, item, resolution(), Set.of("latitude"), false).errors()).isNotEmpty();
        var selected = Set.of("latitude", "longitude");
        assertThat(merge.plan(snapshot, item, resolution(), selected, false).patch())
                .containsOnlyKeys(selected).containsEntry("latitude", lat).containsEntry("longitude", lon);
        var existing = new HashMap<>(snapshot.values());
        existing.put("latitude", new java.math.BigDecimal("37.57960000"));
        existing.put("longitude", new java.math.BigDecimal("126.97700000"));
        assertThat(merge.plan(new DestinationUpdateSnapshot(1L, snapshot.type(), snapshot.sourceType(),
                snapshot.externalContentId(), existing, snapshot.labels()), item, resolution(), selected, false).patch()).isEmpty();
    }

    @Test
    void initialSelectionAddsOnlyRawMissingNullAndBlankFieldsIndependently() {
        Map<String, Object> values = new LinkedHashMap<>(snapshot.values());
        values.put("translations.ko.shortDescription", null);
        values.put("translations.en.shortDescription", "   ");
        var item = item(Map.of(
                "ko", new DestinationImportItem.Text(null, "new short", "replacement body"),
                "en", new DestinationImportItem.Text(null, "new en short", "replacement en body"),
                "ja", new DestinationImportItem.Text(null, null, "missing language body")), DestinationImportItem.Info.EMPTY);

        var plan = merge.plan(withValues(values), item, resolution(), null, false);

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.patch()).containsOnlyKeys("translations.ko.shortDescription", "translations.en.shortDescription",
                "translations.ja.description");
        assertThat(plan.protectedFields()).containsExactlyInAnyOrderElementsOf(plan.patch().keySet());
        assertKind(plan, "translations.ko.shortDescription", "AUTO_ADD");
        assertKind(plan, "translations.ko.description", "REPLACE");
        assertKind(plan, "translations.ja.description", "AUTO_ADD");
    }

    @Test
    void explicitEmptySelectionExcludesAutomaticAdditions() {
        var item = item(Map.of("ja", new DestinationImportItem.Text(null, "new summary", null)), DestinationImportItem.Info.EMPTY);
        var plan = merge.plan(snapshot, item, resolution(), Set.of(), false);
        assertThat(plan.patch()).isEmpty();
        assertThat(plan.protectedFields()).isEmpty();
        assertThat(plan.changes()).singleElement().satisfies(change -> assertThat(change.selected()).isFalse());
    }

    @Test
    void sameValueIsUnchangedAndCannotCreateProtectionEvenWhenExplicitlySelected() {
        var item = item(Map.of("ko", new DestinationImportItem.Text(null, "old short", null)), DestinationImportItem.Info.EMPTY);
        var plan = merge.plan(snapshot, item, resolution(), Set.of("translations.ko.shortDescription"), false);
        assertThat(plan.patch()).isEmpty();
        assertThat(plan.protectedFields()).isEmpty();
        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.changed()).isFalse();
            assertThat(change.selected()).isFalse();
        });
        assertKind(plan, "translations.ko.shortDescription", "UNCHANGED");
    }

    @Test
    void falseAndZeroAreExistingValuesAndNeverAutomaticReplacementCandidates() {
        var before = new DestinationUpdateSnapshot(1L, DestinationType.ACCOMMODATION, "ADMIN", null,
                Map.of("info.parkingAvailable", false, "info.roomCount", 0L), Map.of());
        var item = new DestinationImportItem(null, before.type(), null, null, null, null, null,
                List.of(), null, List.of(), Map.of(),
                new DestinationImportItem.Info(Map.of("parkingAvailable", true, "roomCount", 1L), Map.of()), null);
        var plan = merge.plan(before, item, resolution(), null, false);

        assertThat(plan.patch()).isEmpty();
        assertThat(plan.protectedFields()).isEmpty();
        assertKind(plan, "info.parkingAvailable", "REPLACE");
        assertKind(plan, "info.roomCount", "REPLACE");
    }

    @Test
    void missingBooleanAndNumberAcceptFalseAndZeroAsIncomingAdditions() {
        var before = new DestinationUpdateSnapshot(1L, DestinationType.ACCOMMODATION, "ADMIN", null, Map.of(), Map.of());
        var item = new DestinationImportItem(null, before.type(), null, null, null, null, null,
                List.of(), null, List.of(), Map.of(),
                new DestinationImportItem.Info(Map.of("parkingAvailable", false, "roomCount", 0L), Map.of()), null);
        var plan = merge.plan(before, item, resolution(), null, false);

        assertThat(plan.patch()).containsExactlyInAnyOrderEntriesOf(Map.of("info.parkingAvailable", false, "info.roomCount", 0L));
        assertKind(plan, "info.parkingAvailable", "AUTO_ADD");
        assertKind(plan, "info.roomCount", "AUTO_ADD");
    }

    @Test
    void nullIncomingHasNoCandidateNoAutomaticPatchAndNoProtection() {
        var item = item(Map.of("ja", new DestinationImportItem.Text(null, null, null)), DestinationImportItem.Info.EMPTY);
        var plan = merge.plan(snapshot, item, resolution(), null, false);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.patch()).isEmpty();
        assertThat(plan.protectedFields()).isEmpty();
    }

    @Test
    void coordinatesAreAutomaticOnlyWhenBothExistingCoordinatesAreNull() {
        var lat = new java.math.BigDecimal("37.5796");
        var lon = new java.math.BigDecimal("126.9770");
        var item = new DestinationImportItem(null, snapshot.type(), null, null, lat, lon, null,
                List.of(), null, List.of(), Map.of(), null, null);
        var empty = merge.plan(snapshot, item, resolution(), null, false);
        assertThat(empty.patch()).containsOnlyKeys("latitude", "longitude");
        assertKind(empty, "latitude", "AUTO_ADD");
        assertKind(empty, "longitude", "AUTO_ADD");

        var partialValues = new LinkedHashMap<>(snapshot.values());
        partialValues.put("latitude", new java.math.BigDecimal("37.5"));
        var partial = merge.plan(withValues(partialValues), item, resolution(), null, false);
        assertThat(partial.patch()).isEmpty();
        assertKind(partial, "latitude", "REPLACE");
        assertKind(partial, "longitude", "REPLACE");
    }

    @Test
    void unchangedCoordinatePartnerIsNotProtectedWhenThePairIsExplicitlySelected() {
        var lat = new java.math.BigDecimal("37.5796");
        var lon = new java.math.BigDecimal("126.9770");
        var item = new DestinationImportItem(null, snapshot.type(), null, null, lat, lon, null,
                List.of(), null, List.of(), Map.of(), null, null);
        var values = new LinkedHashMap<>(snapshot.values());
        values.put("latitude", lat);
        var plan = merge.plan(withValues(values), item, resolution(), Set.of("latitude", "longitude"), false);

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.patch()).containsOnlyKeys("longitude");
        assertThat(plan.protectedFields()).containsExactly("longitude");
        assertKind(plan, "latitude", "UNCHANGED");
        assertKind(plan, "longitude", "REPLACE");
    }

    @Test
    void emptyRelationsAreAutomaticButExistingRelationAdditionsRemainReplacementCandidates() {
        var item = new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of("new category"), null, List.of("PARKING"), Map.of(), null, null);
        var values = new LinkedHashMap<>(snapshot.values());
        values.put("categories", List.of());
        values.remove("mainCategory");
        var plan = merge.plan(withValues(values), item, resolution(), null, false);
        assertThat(plan.patch()).containsOnlyKeys("categories").containsEntry("categories", List.of(20L));
        assertKind(plan, "categories", "AUTO_ADD");
        assertKind(plan, "amenities", "REPLACE");
        assertThat(plan.hasRemovals()).isFalse();
    }

    @Test
    void automaticInfoNormalizationNeverOverwritesExistingKoreanTranslation() {
        var values = new LinkedHashMap<>(snapshot.values());
        values.put("info.openingHours", null);
        values.put("info.translations.ko.openingHours", "기존 한국어 정보");
        var item = item(Map.of(), new DestinationImportItem.Info(Map.of("openingHours", "09-18"), Map.of()));
        var plan = merge.plan(withValues(values), item, resolution(), null, false);

        assertThat(plan.patch()).containsOnlyKeys("info.openingHours");
        assertThat(plan.protectedFields()).containsExactly("info.openingHours");
        assertThat(plan.warnings()).isNotEmpty();
    }

    @Test
    void initialSelectionRetainsNullToDistinguishDefaultsFromExplicitOptOut() {
        assertThat(DestinationUpdatePreview.Selection.empty(0).fields()).isNull();
        assertThat(new DestinationUpdatePreview.Selection(0, null, false, Set.of(), false).fields()).isEmpty();
    }

    @Test
    void previewJsonOmitsDuplicateRawLongContentButJavaChangeStillExposesIt() {
        String body = "긴 상세 본문".repeat(1000);
        var change = new DestinationUpdatePreview.Change("translations.ko.description", "본문", body, body,
                body, body, false, false);
        Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(change, Map.class);

        assertThat(change.before()).isEqualTo(body);
        assertThat(change.after()).isEqualTo(body);
        assertThat(json.containsKey("before")).isFalse();
        assertThat(json.containsKey("after")).isFalse();
        assertThat(json.get("beforeText")).isEqualTo(body);
        assertThat(json.get("afterText")).isEqualTo(body);
    }

    @Test
    void incompleteIncomingCoordinatePairCannotBeAutomaticallyApplied() {
        var item = new DestinationImportItem(null, snapshot.type(), null, null,
                new java.math.BigDecimal("37.5796"), null, null,
                List.of(), null, List.of(), Map.of(), null, null);
        var plan = merge.planDefault(snapshot, item, resolution(), false);
        assertThat(plan.errors()).isNotEmpty();
        assertThat(plan.patch()).isEmpty();
        assertThat(plan.protectedFields()).isEmpty();
    }

    @Test
    void automaticFieldHelperReturnsVisibleSelectionPathsWithoutInternalKoreanMirror() {
        var item = item(Map.of("ja", new DestinationImportItem.Text(null, "new summary", null)),
                new DestinationImportItem.Info(Map.of("openingHours", "09-18"), Map.of()));
        assertThat(merge.automaticFields(snapshot, item, resolution()))
                .containsExactlyInAnyOrder("translations.ja.shortDescription", "info.openingHours");
        assertThat(merge.planDefault(snapshot, item, resolution(), false).patch())
                .containsKeys("info.openingHours", "info.translations.ko.openingHours");
    }

    @Test
    void optionalFilePreviewTokenPreservesExistingConstructors() {
        assertThat(new DestinationUpdatePreview(List.of(), List.of()).previewToken()).isNull();
        assertThat(new DestinationUpdatePreview.Request("{}", List.of()).previewToken()).isNull();
        assertThat(new DestinationUpdatePreview.Request(null, List.of(), "preview-id").previewToken()).isEqualTo("preview-id");
    }

    @Test
    void automaticMainCategoryWaitsUntilItsCategoryIsIncludedInTheSelectedResult() {
        var values = new LinkedHashMap<>(snapshot.values());
        values.remove("mainCategory");
        var item = new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of("new category"), "new category", List.of(), Map.of(), null, null);

        var automatic = merge.plan(withValues(values), item, resolution(), null, false);
        assertThat(automatic.errors()).isEmpty();
        assertThat(automatic.patch()).isEmpty();
        assertKind(automatic, "mainCategory", "REPLACE");

        var selected = merge.plan(withValues(values), item, resolution(), Set.of("categories", "mainCategory"), false);
        assertThat(selected.errors()).isEmpty();
        assertThat(selected.patch()).containsEntry("categories", List.of(10L, 20L)).containsEntry("mainCategory", 20L);

        values.put("categories", List.of());
        var empty = merge.plan(withValues(values), item, resolution(), null, false);
        assertThat(empty.errors()).isEmpty();
        assertThat(empty.patch()).containsEntry("categories", List.of(20L)).containsEntry("mainCategory", 20L);
        assertKind(empty, "mainCategory", "AUTO_ADD");
    }

    @Test
    void collectionChangesExposeActualAdditionAndRemovalCounts() {
        var item = new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of("new category"), "new category", List.of("PARKING"), Map.of(), null, null);
        var fields = Set.of("categories", "mainCategory", "amenities");
        var added = merge.plan(snapshot, item, resolution(), fields, false);
        assertThat(added.changes()).filteredOn(change -> change.path().equals("categories")).singleElement()
                .satisfies(change -> {
                    assertThat(change.addedCount()).isEqualTo(1);
                    assertThat(change.removedCount()).isZero();
                });
        var replaced = merge.plan(snapshot, item, resolution(), fields, true);
        assertThat(replaced.changes()).filteredOn(change -> change.path().equals("amenities")).singleElement()
                .satisfies(change -> {
                    assertThat(change.addedCount()).isEqualTo(1);
                    assertThat(change.removedCount()).isEqualTo(1);
                });
    }

    @Test
    void selectingAbsentMainCategoryCannotAuthorizeRemovingTheExistingMainCategory() {
        var item = new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of("new category"), null, List.of(), Map.of(), null, null);
        var plan = merge.plan(snapshot, item, resolution(), Set.of("categories", "mainCategory"), true);

        assertThat(plan.patch()).doesNotContainKey("mainCategory");
        assertThat(plan.errors()).anyMatch(error -> error.contains("대표 카테고리"));
    }

    private DestinationUpdateSnapshot withValues(Map<String, Object> values) {
        return new DestinationUpdateSnapshot(snapshot.destinationId(), snapshot.type(), snapshot.sourceType(),
                snapshot.externalContentId(), values, snapshot.labels());
    }

    private static void assertKind(DestinationUpdateMerge.Plan plan, String path, String kind) {
        var change = plan.changes().stream().filter(candidate -> candidate.path().equals(path)).findFirst().orElseThrow();
        Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(change, Map.class);
        assertThat(json.get("kind")).isEqualTo(kind);
    }

    private DestinationImportItem item(Map<String, DestinationImportItem.Text> text, DestinationImportItem.Info info) {
        return new DestinationImportItem(null, snapshot.type(), null, null, null, null, null,
                List.of(), null, List.of(), text, info, null);
    }
    private DestinationImportMasterResolver.Resolution resolution() {
        return new DestinationImportMasterResolver.Resolution(7L, true, List.of("대한민국"),
                List.of(20L), 20L, "new category", 20L, List.of(2), List.of());
    }
}
