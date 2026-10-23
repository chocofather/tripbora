package com.tripbora.repository;

import com.tripbora.model.DestinationImage;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationImageMapperContractTest {

    @Test
    void positionSaveChangesOnlyTheLinkedMainImagesCoordinatesAndSwitchingMainKeepsThem() throws IOException {
        Configuration configuration = mapperConfiguration();
        assertThat(normalizedSql(configuration, "updateImageCardPosition",
                Map.of("destinationId", 3L, "imageId", 8L, "x", 0, "y", 100)))
                .isEqualTo("UPDATE destination_images SET card_position_x = ?, card_position_y = ? "
                        + "WHERE id = ? AND destination_id = ? AND is_main = 1");
        for (String statement : List.of("setMainImage", "clearMainImagesByDestinationId")) {
            assertThat(normalizedSql(configuration, statement, 8L)).doesNotContain("card_position");
        }
        assertThat(configuration.getResultMap(NAMESPACE + ".imageMap").getResultMappings())
                .extracting(org.apache.ibatis.mapping.ResultMapping::getProperty)
                .contains("cardPositionX", "cardPositionY");
    }

    private static final String NAMESPACE =
            "com.tripbora.repository.destination.DestinationMapper";

    @Test
    void imageReadPrefersWholeSourceRowAndFallsBackOnlyWhenItIsAbsent() throws IOException {
        Configuration configuration = mapperConfiguration();

        for (String statementId : List.of("findDestinationDetail", "findImagesByDestinationId",
                "findImageById")) {
            String sql = normalizedSql(configuration, statementId, 10L);
            assertThat(sql)
                    .contains("LEFT JOIN destination_image_sources s ON s.destination_image_id = di.id")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.source_name ELSE di.source_name END AS img_source_name")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.external_content_id ELSE di.external_content_id END AS img_external_content_id")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.source_title ELSE di.source_title END AS img_source_title")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.author_text ELSE di.photographer END AS img_photographer")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.license_type ELSE di.license_type END AS img_license_type")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.license_detail ELSE di.license_detail END AS img_license_detail")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.legacy_source_url ELSE di.source_url END AS img_source_url")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.original_image_url ELSE di.source_image_url END AS img_source_image_url")
                    .contains("CASE WHEN s.destination_image_id IS NOT NULL "
                            + "THEN s.license_checked_at ELSE di.license_checked_at END AS img_license_checked_at");
        }
    }

    @Test
    void destinationImageExposesSourceMetadataProperties() throws IntrospectionException {
        Map<String, Class<?>> propertyTypes = Arrays.stream(
                        Introspector.getBeanInfo(DestinationImage.class).getPropertyDescriptors())
                .collect(Collectors.toMap(PropertyDescriptor::getName,
                        PropertyDescriptor::getPropertyType));

        assertThat(propertyTypes)
                .containsEntry("sourceType", String.class)
                .containsEntry("sourceName", String.class)
                .containsEntry("externalContentId", String.class)
                .containsEntry("sourceTitle", String.class)
                .containsEntry("photographer", String.class)
                .containsEntry("licenseType", String.class)
                .containsEntry("licenseDetail", String.class)
                .containsEntry("sourceUrl", String.class)
                .containsEntry("commonSourceUrl", String.class)
                .containsEntry("workPageUrl", String.class)
                .containsEntry("sourceImageUrl", String.class)
                .containsEntry("licenseCheckedAt", Timestamp.class)
                .containsEntry("sourceRecordPresent", Boolean.class);
    }

    @Test
    void imageQueriesMapAllSourceMetadataColumns() throws IOException {
        Configuration configuration = mapperConfiguration();
        ResultMap imageMap = configuration.getResultMap(NAMESPACE + ".imageMap");
        Map<String, String> columnsByProperty = imageMap.getResultMappings().stream()
                .collect(Collectors.toMap(mapping -> mapping.getProperty(),
                        mapping -> mapping.getColumn()));

        assertThat(columnsByProperty)
                .containsEntry("sourceType", "img_source_type")
                .containsEntry("sourceName", "img_source_name")
                .containsEntry("externalContentId", "img_external_content_id")
                .containsEntry("sourceTitle", "img_source_title")
                .containsEntry("photographer", "img_photographer")
                .containsEntry("licenseType", "img_license_type")
                .containsEntry("licenseDetail", "img_license_detail")
                .containsEntry("sourceUrl", "img_source_url")
                .containsEntry("commonSourceUrl", "img_common_source_url")
                .containsEntry("workPageUrl", "img_work_page_url")
                .containsEntry("sourceImageUrl", "img_source_image_url")
                .containsEntry("licenseCheckedAt", "img_license_checked_at")
                .containsEntry("sourceRecordPresent", "img_source_record_present");

        for (String statementId : List.of("findDestinationDetail", "findImagesByDestinationId",
                "findImageById")) {
            String sql = normalizedSql(configuration, statementId, 10L);
            assertThat(sql)
                    .contains("source_type")
                    .contains("source_name")
                    .contains("external_content_id")
                    .contains("source_title")
                    .contains("photographer")
                    .contains("license_type")
                    .contains("license_detail")
                    .contains("source_url")
                    .contains("source_image_url")
                    .contains("license_checked_at");
        }
    }

    @Test
    void insertStoresSourceMetadataAndDefaultsMissingSourceTypeToAdminUpload() throws IOException {
        Configuration configuration = mapperConfiguration();
        DestinationImage image = new DestinationImage();
        image.setImageUrl("/uploads/destinations/admin.jpg");
        image.setSourceType(null);

        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + ".insertImage")
                .getBoundSql(image);
        String sql = normalizedSql(configuration, "insertImage", image);

        assertThat(sql)
                .contains("image_url, source_type, source_name, external_content_id, source_title, "
                        + "photographer, license_type, license_detail, source_url, source_image_url, license_checked_at")
                .contains("VALUES (?, COALESCE(?, 'ADMIN_UPLOAD'), ?, ?, ?, ?, ?, ?, ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly(
                        "imageUrl",
                        "sourceType",
                        "sourceName",
                        "externalContentId",
                        "sourceTitle",
                        "photographer",
                        "licenseType",
                        "licenseDetail",
                        "sourceUrl",
                        "sourceImageUrl",
                        "licenseCheckedAt",
                        "isMain",
                        "isSlide",
                        "orderIndex",
                        "destinationId",
                        "createdAt"
                );
        assertThat(configuration.getMappedStatement(NAMESPACE + ".insertImage").getKeyGenerator())
                .isInstanceOf(Jdbc3KeyGenerator.class);
    }

    @Test
    void sourceInsertKeepsLegacyUrlDistinctFromKnownSourcePages() throws IOException {
        Configuration configuration = mapperConfiguration();
        String sql = normalizedSql(configuration, "insertImageSource", new DestinationImage());
        List<String> properties = configuration.getMappedStatement(NAMESPACE + ".insertImageSource")
                .getBoundSql(new DestinationImage()).getParameterMappings().stream()
                .map(ParameterMapping::getProperty).toList();

        assertThat(sql).contains("INSERT INTO destination_image_sources")
                .contains("common_source_url, work_page_url, legacy_source_url")
                .doesNotContain("ON DUPLICATE KEY");
        assertThat(properties).containsSubsequence(
                "id", "sourceName", "externalContentId", "sourceTitle", "photographer",
                "commonSourceUrl", "workPageUrl", "sourceUrl", "sourceImageUrl",
                "licenseType", "licenseDetail", "licenseCheckedAt");
    }

    @Test
    void metadataEditUpdatesLegacyColumnsAndOnlyEditableSourceFields() throws IOException {
        Configuration configuration = mapperConfiguration();
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("imageId", 2L);
        params.put("sourceName", "한국관광공사");
        params.put("photographer", null);
        params.put("licenseType", "KOGL_TYPE_1");
        params.put("licenseDetail", null);
        params.put("sourceUrl", "https://example.com/source");

        String sql = normalizedSql(configuration, "upsertImageSourceMetadata", params);
        assertThat(sql)
                .contains("INSERT INTO destination_image_sources")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("legacy_source_url = ?")
                .doesNotContain("common_source_url", "work_page_url", "original_image_url");
    }

    @Test
    void bulkSourceUpdateTouchesOnlyChosenFieldsAndNeverReclassifiesLegacyUrl() throws IOException {
        Configuration configuration = mapperConfiguration();
        DestinationImage image = new DestinationImage();
        image.setId(2L);
        image.setCommonSourceUrl("https://example.com/collection");
        Map<String, Object> sourceParams = Map.of("image", image,
                "fields", java.util.Set.of("commonSourceUrl"));
        String sourceSql = normalizedSql(configuration, "updateBulkImageSource", sourceParams);
        assertThat(sourceSql).contains("common_source_url = ?")
                .doesNotContain("work_page_url", "legacy_source_url", "author_text", "license_type");

        image.setSourceName("공공기관");
        Map<String, Object> legacyParams = Map.of("image", image,
                "fields", java.util.Set.of("sourceName"));
        String legacySql = normalizedSql(configuration, "updateBulkImageLegacy", legacyParams);
        assertThat(legacySql).contains("source_name = ?")
                .doesNotContain("source_url", "photographer", "license_type", "license_detail");
    }

    @Test
    void slideStateCanBeUpdatedForOneImage() throws IOException {
        Configuration configuration = mapperConfiguration();

        String sql = normalizedSql(configuration, "updateImageSlide", Map.of(
                "imageId", 2L,
                "isSlide", true
        ));

        assertThat(sql)
                .isEqualTo("UPDATE destination_images SET is_slide = ? WHERE id = ?");
    }

    @Test
    void sourceMetadataCanBeUpdatedWithoutReplacingTheImage() throws IOException {
        Configuration configuration = mapperConfiguration();

        String sql = normalizedSql(configuration, "updateImageMetadata", Map.of(
                "imageId", 2L,
                "sourceName", "한국관광공사",
                "photographer", "한국관광공사 김지호",
                "licenseType", "KOGL_TYPE_1",
                "licenseDetail", "CC BY 4.0",
                "sourceUrl", "https://example.com/source"
        ));

        assertThat(sql).isEqualTo("UPDATE destination_images SET source_name = ?, "
                + "photographer = ?, license_type = ?, license_detail = ?, source_url = ? WHERE id = ?");
        assertThat(sql).doesNotContain("source_title", "source_image_url", "external_content_id",
                "license_checked_at");
    }

    @Test
    void existingDestinationCommonsAddLocksTheDestinationRowAndReadsOnlyWikidataQid() throws IOException {
        Configuration configuration = mapperConfiguration();

        assertThat(normalizedSql(configuration, "lockDestinationForImageUpdate", 9L))
                .isEqualTo("SELECT id FROM destinations WHERE id = ? FOR UPDATE");
        assertThat(normalizedSql(configuration, "findExternalContentIdBySourceType",
                Map.of("destinationId", 9L, "sourceType", "WIKIDATA")))
                .isEqualTo("SELECT external_content_id FROM destinations WHERE id = ? AND source_type = ?");
    }

    @Test
    void commonsSourceInsertWritesOnlyTheNewSourceTableWithFullMetadataColumns() throws IOException {
        Configuration configuration = mapperConfiguration();
        String sql = normalizedSql(configuration, "insertCommonsImageSource",
                new com.tripbora.model.DestinationImageCommonsSource());

        assertThat(sql).startsWith("INSERT INTO destination_image_sources (")
                .doesNotContain("destination_images")
                .contains("author_text", "work_page_url", "original_image_url", "license_name",
                        "license_version", "license_url", "attribution_text", "source_credit",
                        "custom_attribution", "credit_links", "license_evidence_url",
                        "license_evidence_detail", "license_evidence_revision_id", "license_conditions",
                        "license_restrictions", "attribution_required", "changes_required",
                        "share_alike_required", "content_modified", "license_checked_at",
                        "wikidata_qid", "commons_file_title")
                .doesNotContain("legacy_source_url");
        assertThat(normalizedSql(configuration, "countCommonsImageSource",
                Map.of("destinationId", 1L, "commonsFileTitle", "File:A.jpg")))
                .contains("JOIN destination_images di ON di.id = s.destination_image_id")
                .contains("di.destination_id = ?")
                .contains("s.commons_file_title = ?");
        for (String statementId : List.of("findDestinationDetail", "findImagesByDestinationId", "findImageById")) {
            assertThat(normalizedSql(configuration, statementId, 10L))
                    .contains("s.license_name AS img_license_name", "s.license_url AS img_license_url",
                            "s.custom_attribution AS img_custom_attribution",
                            "s.commons_file_title AS img_commons_file_title");
        }
    }

    private Configuration mapperConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/DestinationMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "mapper/DestinationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String normalizedSql(Configuration configuration, String statementId, Object parameter) {
        return configuration.getMappedStatement(NAMESPACE + "." + statementId)
                .getBoundSql(parameter)
                .getSql()
                .replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(")
                .replaceAll(" \\)", ")")
                .trim();
    }
}
