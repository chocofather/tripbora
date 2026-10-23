package com.tripbora.service.destinationimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbora.dto.destinationimport.DestinationUpdatePreview;
import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.DestinationDuplicateIndexRow;
import com.tripbora.model.DestinationType;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.category.CategoryService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.destination.DestinationDuplicateService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CPU/control-flow benchmark only: all database/service I/O boundaries are mocks. */
class DestinationUpdatePerformanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WARMUPS = 2;
    private static final int RUNS = 7;
    private static final String FIELD = "translations.ko.description";

    @Test
    void recordsSyntheticOneAndThirtyRowPreviewBaselineWithoutDatabase() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int count : List.of(1, 30)) {
            Map<String, List<Map<String, Object>>> samples = new LinkedHashMap<>();
            for (int run = -WARMUPS; run < RUNS; run++) {
                Fixture fixture = new Fixture(count);
                for (String mode : List.of("initial-manual-candidates", "confirmed-targets", "selection-repreview")) {
                    Map<String, Object> sample = fixture.measure(mode);
                    if (run >= 0) samples.computeIfAbsent(mode, ignored -> new ArrayList<>()).add(sample);
                }
            }
            for (var entry : samples.entrySet()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", count);
                result.put("scenario", entry.getKey());
                result.put("median", medians(entry.getValue()));
                result.put("samples", entry.getValue());
                scenarios.add(result);
            }
        }
        // A later broad test run must not overwrite the captured baseline.
        Path directory = Path.of("build/reports/destination-json-update-performance");
        String phase = System.getenv().getOrDefault("DESTINATION_UPDATE_PERFORMANCE_PHASE",
                Files.exists(directory.resolve("before.json")) ? "after" : "before");
        if (!Set.of("before", "after").contains(phase)) throw new IllegalArgumentException("Invalid performance phase");
        Path output = directory.resolve(phase + ".json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "phase", phase, "warmups", WARMUPS, "repetitions", RUNS,
                "limitations", "Synthetic CPU/control-flow timings with mocked DB boundaries; not real DB/network latency. Timed spy phases include Mockito overhead and may overlap nested calls.",
                "scenarios", scenarios));
        System.out.println("Destination update performance report: " + output.toAbsolutePath());
    }

    private static Map<String, Object> medians(List<Map<String, Object>> samples) {
        Map<String, Object> result = new LinkedHashMap<>();
        samples.get(0).forEach((key, value) -> {
            if (value instanceof Number) {
                List<Double> numbers = samples.stream().map(sample -> ((Number) sample.get(key)).doubleValue()).sorted().toList();
                result.put(key, numbers.get(numbers.size() / 2));
            }
        });
        return result;
    }

    private static class Fixture {
        final int count;
        final String json;
        final MockHttpSession session = new MockHttpSession();
        String previewToken;
        final Map<String, Long> nanos = new LinkedHashMap<>();
        final DestinationImportParser parser = spy(new DestinationImportParser());
        final DestinationImportValidator validator = spy(new DestinationImportValidator());
        final DestinationUpdateMerge merge = spy(new DestinationUpdateMerge());
        final DestinationMapper mapper = mock(DestinationMapper.class);
        final CountryCategoryService regions = mock(CountryCategoryService.class);
        final CategoryService categories = mock(CategoryService.class);
        final AmenityService amenities = mock(AmenityService.class);
        final DestinationUpdateStore store = mock(DestinationUpdateStore.class);
        final DestinationFieldProtectionService protections = mock(DestinationFieldProtectionService.class);
        final DestinationService destinations = mock(DestinationService.class);
        final DestinationImportMasterResolver resolver;
        final DestinationDuplicateService duplicates;
        final DestinationImportUpdateService service;

        Fixture(int count) throws Exception {
            this.count = count;
            CountryCategory country = new CountryCategory();
            country.setId(1L); country.setCode("KR"); country.setRegionName("대한민국");
            CountryCategory city = new CountryCategory();
            city.setId(2L); city.setParentId(1L); city.setRegionName("가상시");
            when(regions.getDomesticRootIds()).thenReturn(List.of(1L));
            when(regions.getById(1L)).thenReturn(country);
            when(regions.getById(2L)).thenReturn(city);
            when(regions.getOverseasRootIds()).thenReturn(List.of());
            when(regions.getRegionsByParentId(1L)).thenReturn(List.of(city));
            when(regions.getRegionsByParentId(2L)).thenReturn(List.of());
            List<DestinationDuplicateIndexRow> index = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                DestinationDuplicateIndexRow row = new DestinationDuplicateIndexRow();
                row.setDestinationId(100L + i); row.setRegionId(2L); row.setLanguageCode("ko");
                row.setName("Synthetic landmark " + i); row.setSourceType("ADMIN"); index.add(row);
                items.add(Map.of("key", "synthetic-" + i, "type", "ATTRACTION", "region", Map.of("country", "대한민국", "city", "가상시"),
                        "translations", Map.of("ko", Map.of("name", row.getName(), "description", "Synthetic replacement description " + i))));
            }
            json = JSON.writeValueAsString(Map.of("version", 1, "destinations", items));
            when(mapper.findDuplicateIndex()).thenReturn(index);
            when(protections.protectedFields(anyLong())).thenReturn(Set.of());
            when(protections.protectedFieldsAll(anyCollection())).thenAnswer(invocation -> {
                Map<Long, Set<String>> result = new LinkedHashMap<>();
                for (Long id : (Collection<Long>) invocation.getArgument(0)) result.put(id, Set.of());
                return result;
            });
            when(store.read(anyLong(), eq(false))).thenAnswer(invocation -> {
                long start = System.nanoTime();
                try {
                    Long id = invocation.getArgument(0);
                    return new DestinationUpdateSnapshot(id, DestinationType.ATTRACTION, "ADMIN", null,
                            Map.of("regionId", 2L, "season", "ALL_SEASONS", "categories", List.of(), "amenities", List.of(),
                                    "translations.ko.name", "Synthetic landmark " + (id - 100),
                                    FIELD, "Existing description"), Map.of("region.2", "가상시"));
                } finally { nanos.merge("snapshotMs", System.nanoTime() - start, Long::sum); }
            });
            when(store.readAll(anyCollection())).thenAnswer(invocation -> {
                Map<Long, DestinationUpdateSnapshot> result = new LinkedHashMap<>();
                for (Long id : (Collection<Long>) invocation.getArgument(0)) {
                    long start = System.nanoTime();
                    result.put(id, new DestinationUpdateSnapshot(id, DestinationType.ATTRACTION, "ADMIN", null,
                            Map.of("regionId", 2L, "season", "ALL_SEASONS", "categories", List.of(), "amenities", List.of(),
                                    "translations.ko.name", "Synthetic landmark " + (id - 100), FIELD, "Existing description"),
                            Map.of("region.2", "가상시")));
                    nanos.merge("snapshotMs", System.nanoTime() - start, Long::sum);
                }
                return result;
            });
            resolver = spy(new DestinationImportMasterResolver(regions, categories, amenities));
            duplicates = spy(new DestinationDuplicateService(mapper, regions));
            doAnswer(timed("parseMs")).when(parser).parseUpdateFile(anyString());
            doAnswer(timed("validateMs")).when(validator).validateUpdate(any(), anyString(), nullable(Boolean.class));
            doAnswer(timed("masterLoadMs")).when(resolver).load();
            doAnswer(timed("resolveMs")).when(resolver).resolve(any(), anyString(), any());
            doAnswer(timed("matchMs")).when(duplicates).openUpdateLookup();
            doAnswer(timed("diffMs")).when(merge).plan(any(), any(), any(), anySet(), anyBoolean());
            service = new DestinationImportUpdateService(parser, resolver, validator, merge, store, protections,
                    duplicates, destinations, regions, mock(PlatformTransactionManager.class));
        }

        Answer<Object> timed(String key) {
            return invocation -> {
                long start = System.nanoTime();
                try { return invocation.callRealMethod(); }
                finally { nanos.merge(key, System.nanoTime() - start, Long::sum); }
            };
        }

        Map<String, Object> measure(String mode) throws Exception {
            nanos.clear();
            Object[] mocks = {parser, validator, resolver, merge, mapper, regions, categories, amenities, store, protections, duplicates};
            clearInvocations(mocks);
            List<DestinationUpdatePreview.Selection> selections = new ArrayList<>();
            if (!mode.equals("initial-manual-candidates")) for (int i = 0; i < count; i++)
                selections.add(new DestinationUpdatePreview.Selection(i, 100L + i, true,
                        mode.equals("selection-repreview") ? Set.of(FIELD) : Set.of(), false));
            long start = System.nanoTime();
            var result = service.preview(new DestinationUpdatePreview.Request(json, selections, previewToken), session, 7L);
            previewToken = result.previewToken();
            long total = System.nanoTime() - start;
            assertThat(result.fileErrors()).isEmpty();
            assertThat(result.rows()).hasSize(count).allSatisfy(row -> assertThat(row.status()).isEqualTo(
                    mode.equals("initial-manual-candidates") ? "NEEDS_MATCH" : "MATCHED"));
            start = System.nanoTime();
            byte[] serialized = JSON.writeValueAsBytes(result);
            long serialize = System.nanoTime() - start;
            Map<String, Object> sample = new LinkedHashMap<>();
            for (String key : List.of("parseMs", "validateMs", "masterLoadMs", "resolveMs", "matchMs", "snapshotMs", "diffMs"))
                sample.put(key, nanos.getOrDefault(key, 0L) / 1_000_000.0);
            sample.put("previewMs", total / 1_000_000.0);
            sample.put("serializeMs", serialize / 1_000_000.0);
            sample.put("responseBytes", serialized.length);
            sample.put("jsonInputBytes", json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            for (var entry : Map.of("parser", parser, "validator", validator, "resolver", resolver, "merge", merge,
                    "destinationMapper", mapper, "regionService", regions, "categoryService", categories,
                    "amenityService", amenities, "snapshotStore", store, "protectionService", protections).entrySet()) {
                sample.put(entry.getKey() + "Calls", mockingDetails(entry.getValue()).getInvocations().size());
            }
            sample.put("duplicateIndexReads", mockingDetails(mapper).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("findDuplicateIndex")).count());
            sample.put("masterLoads", mockingDetails(resolver).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("load")).count());
            return sample;
        }
    }
}
