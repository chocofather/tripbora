package com.tripbora.repository;

import com.tripbora.model.FestivalInfoTranslation;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축제·행사 상세정보 번역 Mapper 계약.
 */
class FestivalInfoTranslationMapperContractTest {

    private static final String NAMESPACE =
            "com.tripbora.repository.travelinfo.FestivalInfoMapper";

    @Test
    void translationResultMapBindsEveryLiveColumnToTheModel() throws IOException {
        ResultMap resultMap = mapperConfiguration()
                .getResultMap(NAMESPACE + ".FestivalInfoTranslationResultMap");
        Map<String, String> columns = resultMap.getResultMappings().stream()
                .collect(Collectors.toMap(mapping -> mapping.getProperty(),
                        mapping -> mapping.getColumn()));

        assertThat(resultMap.getType()).isEqualTo(FestivalInfoTranslation.class);
        assertThat(columns)
                .containsEntry("id", "id")
                .containsEntry("infoId", "info_id")
                .containsEntry("languageCode", "language_code")
                .containsEntry("eventPlace", "event_place")
                .containsEntry("address", "address")
                .containsEntry("playTime", "play_time")
                .containsEntry("useTime", "use_time")
                .containsEntry("sponsor1", "sponsor1")
                .containsEntry("sponsor2", "sponsor2")
                // 연락처·홈페이지·TourAPI 식별자는 번역 대상이 아니다.
                .doesNotContainKeys("sponsor1Tel", "sponsor2Tel", "contactTel",
                        "homepageUrl", "sourceType", "externalContentId");
    }

    @Test
    void singleFestivalTranslationsAreReadInDeterministicOrder() throws IOException {
        BoundSql boundSql = mapperConfiguration()
                .getMappedStatement(NAMESPACE + ".findTranslationsByInfoId")
                .getBoundSql(41L);

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "SELECT id, info_id, language_code, event_place, address, play_time, "
                        + "use_time, sponsor1, sponsor2 FROM festival_info_translations "
                        + "WHERE info_id = ? ORDER BY language_code ASC, id ASC");
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("infoId");
    }

    @Test
    void manyFestivalTranslationsAreReadInOneQueryWithoutNPlusOne() throws IOException {
        BoundSql boundSql = mapperConfiguration()
                .getMappedStatement(NAMESPACE + ".findTranslationsByInfoIds")
                .getBoundSql(Map.of("infoIds", List.of(41L, 42L, 43L)));

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "SELECT id, info_id, language_code, event_place, address, play_time, "
                        + "use_time, sponsor1, sponsor2 FROM festival_info_translations "
                        + "WHERE info_id IN (? , ? , ?) "
                        + "ORDER BY info_id ASC, language_code ASC, id ASC");
        assertThat(boundSql.getParameterMappings()).hasSize(3);
    }

    @Test
    void emptyInfoIdListNeverWidensIntoAFullTableRead() throws IOException {
        BoundSql boundSql = mapperConfiguration()
                .getMappedStatement(NAMESPACE + ".findTranslationsByInfoIds")
                .getBoundSql(Map.of("infoIds", List.of()));

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "SELECT id, info_id, language_code, event_place, address, play_time, "
                        + "use_time, sponsor1, sponsor2 FROM festival_info_translations "
                        + "WHERE 1 = 0 ORDER BY info_id ASC, language_code ASC, id ASC");
        assertThat(boundSql.getParameterMappings()).isEmpty();
    }

    @Test
    void insertWritesOneLanguageRowForTheGivenFestival() throws IOException {
        FestivalInfoTranslation translation = new FestivalInfoTranslation();
        translation.setInfoId(41L);
        translation.setLanguageCode("en");
        translation.setEventPlace("Gyeongbokgung Palace");

        BoundSql boundSql = mapperConfiguration()
                .getMappedStatement(NAMESPACE + ".insertTranslation")
                .getBoundSql(translation);

        assertThat(normalize(boundSql.getSql())).isEqualTo(
                "INSERT INTO festival_info_translations (info_id, language_code, event_place, "
                        + "address, play_time, use_time, sponsor1, sponsor2) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("infoId", "languageCode", "eventPlace", "address",
                        "playTime", "useTime", "sponsor1", "sponsor2");
    }

    @Test
    void updateAndDeleteTouchOnlyTheRequestedLanguageRow() throws IOException {
        Configuration configuration = mapperConfiguration();
        FestivalInfoTranslation translation = new FestivalInfoTranslation();
        translation.setInfoId(41L);
        translation.setLanguageCode("ja");

        BoundSql update = configuration
                .getMappedStatement(NAMESPACE + ".updateTranslation").getBoundSql(translation);
        BoundSql delete = configuration
                .getMappedStatement(NAMESPACE + ".deleteTranslation")
                .getBoundSql(Map.of("infoId", 41L, "languageCode", "zh-CN"));

        // 축제 번호와 언어 코드는 조건일 뿐 갱신 대상이 아니다.
        assertThat(normalize(update.getSql())).isEqualTo(
                "UPDATE festival_info_translations SET event_place = ?, address = ?, "
                        + "play_time = ?, use_time = ?, sponsor1 = ?, sponsor2 = ? "
                        + "WHERE info_id = ? AND language_code = ?");
        assertThat(update.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("eventPlace", "address", "playTime", "useTime",
                        "sponsor1", "sponsor2", "infoId", "languageCode");
        assertThat(normalize(delete.getSql())).isEqualTo(
                "DELETE FROM festival_info_translations "
                        + "WHERE info_id = ? AND language_code = ?");
        assertThat(delete.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("infoId", "languageCode");
    }

    @Test
    void addingTranslationStatementsDoesNotChangeBaseFestivalInfoStatements() throws IOException {
        Configuration configuration = mapperConfiguration();
        BoundSql findByInfoId = configuration
                .getMappedStatement(NAMESPACE + ".findByInfoId").getBoundSql(41L);
        BoundSql deleteByInfoId = configuration
                .getMappedStatement(NAMESPACE + ".deleteByInfoId").getBoundSql(41L);

        assertThat(normalize(findByInfoId.getSql()))
                .contains("FROM festival_info WHERE info_id = ?")
                .contains("sponsor1_tel", "contact_tel", "homepage_url", "source_type")
                .doesNotContain("festival_info_translations");
        assertThat(normalize(deleteByInfoId.getSql())).isEqualTo(
                "DELETE FROM festival_info WHERE info_id = ?");
    }

    @Test
    void translationTableMatchesTheLiveDatabaseContract() throws IOException {
        String translations = between(schema(),
                "CREATE TABLE `festival_info_translations`",
                ") ENGINE=");

        assertThat(translations)
                .contains("`id` bigint NOT NULL AUTO_INCREMENT")
                .contains("`info_id` bigint NOT NULL")
                .contains("`language_code` varchar(10) CHARACTER SET ascii "
                        + "COLLATE ascii_bin NOT NULL")
                .contains("`event_place` varchar(255)")
                .contains("`address` varchar(500)")
                .contains("`play_time` varchar(500)")
                .contains("`use_time` text")
                .contains("`sponsor1` varchar(255)")
                .contains("`sponsor2` varchar(255)")
                // 한 축제에 같은 언어 줄이 둘 생기지 않게 막는 키.
                .contains("UNIQUE KEY `uk_festival_info_translation` "
                        + "(`info_id`,`language_code`)")
                .contains("KEY `idx_festival_info_translation_locale` "
                        + "(`language_code`,`info_id`)")
                .contains("CONSTRAINT `fk_festival_info_translation` "
                        + "FOREIGN KEY (`info_id`) REFERENCES `festival_info` (`info_id`) "
                        + "ON DELETE CASCADE")
                // 언어와 무관한 값은 번역 테이블에 두지 않는다.
                .doesNotContain("sponsor1_tel", "contact_tel", "homepage_url",
                        "source_type", "external_content_id");
    }

    private Configuration mapperConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/FestivalInfoMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "mapper/FestivalInfoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String schema() throws IOException {
        return Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(")
                .replaceAll(" \\)", ")")
                .trim();
    }
}
