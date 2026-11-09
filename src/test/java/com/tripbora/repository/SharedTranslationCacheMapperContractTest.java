package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedTranslationCacheMapperContractTest {
    @Test void staleOriginalInvalidationKeepsProfileProviderAndReadyGuards() {
        String query=sql(mapperConfiguration(),"invalidateUnchangedReady",parameters());
        assertThat(query).contains("source_hash = ?","source_language = ?","target_language = ?","provider = ?","translation_profile = ?","status = 'READY'","BINARY translated_text = BINARY ?","retry_after = NULL");
        assertThat(query).doesNotContain("DELETE", "TRUNCATE");
    }

    @Test
    void lookupAndInsertUseOnlyTheExactGlobalTranslationKey() {
        Configuration configuration = mapperConfiguration();
        Map<String, Object> parameters = parameters();

        String find = sql(configuration, "find", parameters);
        assertThat(find)
                .contains("source_hash = ?")
                .contains("source_language = ?")
                .contains("target_language = ?")
                .contains("provider = ?")
                .contains("translation_profile = ?")
                .doesNotContain("content_id", "user_id", "source_text");

        String insert = sql(configuration, "insertProcessing", parameters);
        assertThat(insert)
                .contains("INSERT IGNORE INTO shared_translation_cache")
                .contains("source_hash, source_language, target_language, provider, translation_profile")
                .doesNotContain("content_id", "user_id", "source_text");
    }

    @Test
    void leaseCanOnlyBeReclaimedAfterProcessingOrFailureDelayExpires() {
        Configuration configuration = mapperConfiguration();
        Map<String, Object> parameters = parameters();

        String claim = sql(configuration, "tryClaim", parameters);
        assertThat(claim)
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at <= ?")
                .contains("status = 'FAILED'")
                .contains("retry_after <= ?");

        String ready = sql(configuration, "markReady", parameters);
        assertThat(ready)
                .contains("status = 'PROCESSING'")
                .contains("lease_token = ?")
                .contains("translated_text = ?");

        String failed = sql(configuration, "markFailed", parameters);
        assertThat(failed)
                .contains("status = 'FAILED'")
                .contains("retry_after = ?")
                .contains("lease_token = ?");
    }

    @Test
    void readyBackfillInsertIsIdempotent() {
        String insert = sql(mapperConfiguration(), "insertReadyIfAbsent", parameters());

        assertThat(insert)
                .contains("INSERT IGNORE INTO shared_translation_cache")
                .contains("'READY'")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    private Configuration mapperConfiguration() {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/SharedTranslationCacheMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration,
                    "mapper/SharedTranslationCacheMapper.xml", configuration.getSqlFragments()).parse();
            return configuration;
        } catch (Exception e) {
            throw new AssertionError("공용 번역 캐시 mapper XML을 파싱할 수 없습니다.", e);
        }
    }

    private String sql(Configuration configuration, String statement, Object parameters) {
        BoundSql boundSql = configuration.getMappedStatement(
                        "com.tripbora.repository.translation.SharedTranslationCacheMapper."
                                + statement)
                .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> parameters() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-09T01:00:00Z"));
        Map<String, Object> values = new HashMap<>();
        values.put("sourceHash", new byte[32]);
        values.put("sourceLanguage", "en");
        values.put("targetLanguage", "ko");
        values.put("provider", "GOOGLE");
        values.put("translationProfile", "google-v3-general-v1");
        values.put("leaseToken", "00000000-0000-0000-0000-000000000001");
        values.put("leaseExpiresAt", Timestamp.from(now.toInstant().plusSeconds(30)));
        values.put("retryAfter", Timestamp.from(now.toInstant().plusSeconds(30)));
        values.put("translatedText", "안녕하세요");
        values.put("detectedSourceLanguage", "en");
        values.put("now", now);
        return values;
    }
}
