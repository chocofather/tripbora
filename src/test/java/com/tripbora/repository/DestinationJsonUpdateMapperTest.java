package com.tripbora.repository;

import com.tripbora.model.DestinationType;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Generates SQL only; never opens a DB connection. */
class DestinationJsonUpdateMapperTest {
    private String sql(String statement, Map<String, Object> parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/DestinationJsonUpdateMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DestinationJsonUpdateMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.tripbora.repository.destination.DestinationJsonUpdateMapper." + statement)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }

    @Test
    void batchPreviewUsesBoundIdsAndRawTranslationsWithoutLocksOrFallback() throws Exception {
        assertThat(sql("findExternalTargets", Map.of("sourceType", "KTO_TOURAPI", "contentIds", List.of("123", "456"))))
                .contains("source_type=?", "external_content_id IN ( ? , ? )").doesNotContain("LIMIT 1", "${");
        for (DestinationType type : DestinationType.values()) {
            var parameters = Map.<String, Object>of("ids", List.of(4L, 5L), "type", type);
            for (String statement : List.of("findBases", "findTranslationsBatch", "findCategoriesBatch",
                    "findInfoBatch", "findInfoTranslationsBatch", "findAmenitiesBatch")) {
                assertThat(sql(statement, parameters)).contains("IN ( ? , ? )")
                        .doesNotContain("FOR UPDATE", "COALESCE", "${", "UPDATE ", "DELETE ");
            }
            assertThat(sql("findAmenitiesBatch", parameters)).contains("AS destination_id");
        }
    }

    @Test
    void eachUpdateSetsExactlyOneSelectedColumnAndKeepsIdentity() throws Exception {
        Map<String, Object> parameters = new HashMap<>(Map.of("id", 4L, "type", DestinationType.ATTRACTION,
                "language", "en", "field", "description", "value", "new description"));
        assertThat(sql("updateTranslationField", parameters)).isEqualTo(
                "UPDATE destination_translations SET description =? WHERE destination_id=? AND language_code=?");
        parameters.put("field", "latitude");
        assertThat(sql("updateBaseField", parameters)).isEqualTo("UPDATE destinations SET latitude =? WHERE id=?");
        parameters.put("field", "openingHours");
        assertThat(sql("updateInfoTranslationField", parameters)).contains("opening_hours =?")
                .doesNotContain("DELETE", "name =", "admission_fee =", "id =");
        assertThat(sql("insertTranslation", parameters)).contains("name) VALUES(?,?,'')")
                .doesNotContain("DELETE", "REPLACE", "ON DUPLICATE");
    }

    @Test
    void everySubtypeUsesItsOwnLiteralTablesAndLockingReads() throws Exception {
        for (DestinationType type : DestinationType.values()) {
            String family = type == DestinationType.CAFE || type == DestinationType.RESTAURANTS
                    ? "restaurant" : type.name().toLowerCase(java.util.Locale.ROOT);
            Map<String, Object> parameters = new HashMap<>(Map.of("id", 4L, "type", type, "lock", true,
                    "language", "ja", "field", "openingHours", "value", "9-5", "amenityId", 3));
            for (String statement : List.of("findInfo", "findInfoTranslations", "findAmenities")) {
                assertThat(sql(statement, parameters)).contains(family + (statement.equals("findAmenities") ? "_amenities" : "_info"))
                        .endsWith("FOR UPDATE");
            }
            assertThat(sql("deleteAmenity", parameters)).contains("amenity_id=?").doesNotContain("DELETE FROM destinations");
            assertThat(sql("insertInfo", parameters)).contains("INSERT INTO " + family + "_info", "destination_id)")
                    .doesNotContain("REPLACE", "DELETE");
            parameters.put("lock", false);
            assertThat(sql("findTranslations", parameters)).doesNotContain("FOR UPDATE");
        }
    }
}
