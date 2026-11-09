package com.tripbora.repository;

import com.tripbora.model.translation.ContentTranslationCache;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTranslationCacheMapperContractTest {
    @Test void staleOriginalInvalidationIsRestrictedToTheExactReadyVersion() {
        String query=sql(mapperConfiguration(),"invalidateUnchangedReady",Map.of("contentType","DESTINATION_COMMENT","contentId",1L,"sourceField","content","targetLanguage","ko","sourceHash",new byte[32],"translatedText","원문"));
        assertThat(query).contains("source_hash = ?","content_type = ?","content_id = ?","source_field = ?","target_language = ?","status = 'READY'","BINARY translated_text = BINARY ?","retry_after = NULL");
        assertThat(query).doesNotContain("DELETE", "TRUNCATE");
    }

    @Test
    void leaseClaimAndCompletionQueriesGuardConcurrentProviderCalls() {
        Configuration configuration = mapperConfiguration();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-09T01:00:00Z"));
        Map<String, Object> parameters = Map.of(
                "contentType", "DESTINATION_COMMENT",
                "contentId", 7L,
                "sourceField", "content",
                "targetLanguage", "en",
                "sourceHash", new byte[32],
                "leaseToken", "00000000-0000-0000-0000-000000000001",
                "now", now,
                "leaseExpiresAt", Timestamp.from(now.toInstant().plusSeconds(30)),
                "translatedText", "translated",
                "detectedSourceLanguage", "ko");

        String claim = sql(configuration, "tryClaim", parameters);
        assertThat(claim)
                .contains("source_hash != ?")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at <= ?")
                .contains("status = 'FAILED'")
                .contains("retry_after <= ?");

        String ready = sql(configuration, "markReady", parameters);
        assertThat(ready)
                .contains("source_hash = ?")
                .contains("status = 'PROCESSING'")
                .contains("lease_token = ?");
    }

    @Test
    void processingInsertUsesTheFullContentSpecificNaturalKey() {
        Configuration configuration = mapperConfiguration();
        ContentTranslationCache cache = new ContentTranslationCache();
        String insert = sql(configuration, "insertProcessing", cache);

        assertThat(insert)
                .contains("INSERT IGNORE INTO content_translation_cache")
                .contains("content_type, content_id, source_field, target_language, source_hash");
    }

    @Test
    void sharedResultCanPopulateTheContentCacheAndReadyRowsCanBeBackfilledInBatches() {
        Configuration configuration = mapperConfiguration();
        ContentTranslationCache cache = new ContentTranslationCache();

        String upsert = sql(configuration, "upsertReady", cache);
        assertThat(upsert)
                .contains("INSERT INTO content_translation_cache")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("status = 'READY'")
                .contains("lease_token = NULL");

        String readyRows = sql(configuration, "findReadyAfter", Map.of(
                "afterId", 0L, "limit", 500));
        assertThat(readyRows)
                .contains("status = 'READY'")
                .contains("translated_text IS NOT NULL")
                .contains("id > ?")
                .contains("ORDER BY id")
                .contains("LIMIT ?");
    }

    @Test
    void languageBackfillSelectsOnlyUndRowsIncludingDeletedOnesAndUpdatesConditionally() {
        String mapper = resourceText("/mapper/DestinationCommentMapper.xml");
        String select = between(mapper,
                "<select id=\"findUndeterminedLanguagesAfter\"", "</select>");
        assertThat(select)
                .contains("source_language = 'und'")
                .contains("id &gt; #{afterId}")
                .contains("ORDER BY id")
                .contains("LIMIT #{limit}")
                .doesNotContain("deleted");

        String update = between(mapper,
                "<update id=\"updateDetectedLanguageIfUndetermined\"", "</update>");
        assertThat(update)
                .contains("source_language = 'und'")
                .contains("updated_at &lt;=&gt; #{updatedAt}")
                .contains("CAST(content AS BINARY) = CAST(#{content} AS BINARY)");
    }

    private Configuration mapperConfiguration() {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/ContentTranslationCacheMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration,
                    "mapper/ContentTranslationCacheMapper.xml", configuration.getSqlFragments()).parse();
            return configuration;
        } catch (Exception e) {
            throw new AssertionError("번역 캐시 mapper XML을 파싱할 수 없습니다.", e);
        }
    }

    private String sql(Configuration configuration, String statement, Object parameters) {
        BoundSql boundSql = configuration.getMappedStatement(
                        "com.tripbora.repository.translation.ContentTranslationCacheMapper."
                                + statement)
                .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private String resourceText(String path) {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("리소스를 읽을 수 없습니다: " + path, e);
        }
    }

    private String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from);
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return value.substring(from, to);
    }
}
