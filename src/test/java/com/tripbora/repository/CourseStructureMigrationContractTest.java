package com.tripbora.repository;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL을 실행하지 않는 Phase 1 계약 검사. 실제 MySQL 적용 성공을 대신하지 않는다. */
class CourseStructureMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V20261015_001__add_course_structure.sql");
    private static final Path REFERENCE = Path.of("docs/db/tripbora_schema_reference.md");

    @Test
    void versionIsAfterTheExistingBaseline() throws IOException {
        assertThat(MigrationVersion.fromVersion("20261015_001"))
                .isGreaterThan(MigrationVersion.fromVersion("20261014.0"));
        assertThat(sql()).isNotBlank();
    }

    @Test
    void migrationOnlyAddsTheNewStructureAndSynchronizesMissingLegacyCountries() throws IOException {
        String sql = sql();
        assertThat(Pattern.compile("(?im)^\\s*(DROP|DELETE|TRUNCATE|REPLACE)\\b").matcher(sql).find()).isFalse();
        assertThat(sql).doesNotContainIgnoringCase("IF NOT EXISTS", "INSERT IGNORE", "ON DUPLICATE KEY",
                "FOREIGN_KEY_CHECKS", "ALTER COLUMN");
        var alterations = Pattern.compile("(?i)ALTER TABLE\\s+(\\w+)").matcher(sql);
        assertThat(alterations.results().map(match -> match.group(1)).toList()).containsExactly("courses");
        var updates = Pattern.compile("(?i)UPDATE\\s+(\\w+)\\s+c\\b").matcher(sql);
        assertThat(updates.results().map(match -> match.group(1)).toList()).containsExactly("courses");
        assertThat(sql).contains("SET c.country_id = primary_country.country_id,", "c.updated_at = c.updated_at",
                "WHERE c.country_id IS NULL;");
        var inserts = Pattern.compile("(?i)INSERT INTO\\s+(\\w+)").matcher(sql);
        assertThat(inserts.results().map(match -> match.group(1)).toList())
                .containsOnly("course_themes", "course_days", "course_items", "course_countries");
    }

    @Test
    void foreignKeysMatchSignedReferenceIdsAndHaveSafeDeleteActions() throws IOException {
        String sql = sql();
        String reference = Files.readString(REFERENCE, StandardCharsets.UTF_8);
        for (String table : List.of("courses", "destinations", "country_categories")) {
            assertThat(referenceTable(reference, table)).contains("`id` bigint NOT NULL AUTO_INCREMENT");
        }
        assertThat(sql).doesNotContainIgnoringCase("UNSIGNED");
        assertThat(table(sql, "course_days"))
                .contains("FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE CASCADE");
        assertThat(table(sql, "course_items"))
                .contains("FOREIGN KEY (course_day_id) REFERENCES course_days (id) ON DELETE CASCADE",
                        "FOREIGN KEY (destination_id) REFERENCES destinations (id) ON DELETE SET NULL",
                        "FOREIGN KEY (country_id) REFERENCES country_categories (id) ON DELETE SET NULL");
        assertThat(table(sql, "course_countries"))
                .contains("FOREIGN KEY (country_id) REFERENCES country_categories (id) ON DELETE RESTRICT");
        assertThat(table(sql, "course_theme_mappings"))
                .contains("FOREIGN KEY (theme_id) REFERENCES course_themes (id) ON DELETE RESTRICT");
        Set<String> names = new HashSet<>();
        var constraints = Pattern.compile("(?i)CONSTRAINT\\s+([a-z0-9_]+)").matcher(sql);
        while (constraints.find()) {
            String name = constraints.group(1);
            assertThat(names.add(name)).as("unique constraint %s", name).isTrue();
            assertThat(reference).doesNotContain("`" + name + "`");
        }
    }

    @Test
    void orderAndThemeConstraintsPreventDuplicateSlotsAndMoreThanThreeThemes() throws IOException {
        String sql = sql();
        assertThat(table(sql, "course_days")).contains("UNIQUE KEY uq_course_days_number (course_id, day_number)",
                "CHECK (day_number >= 1)");
        assertThat(table(sql, "course_items")).contains(
                "UNIQUE KEY uq_course_items_order (course_day_id, display_order)", "CHECK (display_order >= 1)",
                "CHECK (item_type IN ('PLACE', 'STAY', 'FOOD', 'TRANSPORT', 'ETC'))");
        assertThat(table(sql, "course_countries")).contains("PRIMARY KEY (course_id, country_id)",
                "UNIQUE KEY uq_course_countries_order (course_id, display_order)", "CHECK (display_order >= 1)")
                .doesNotContain("is_primary");
        assertThat(table(sql, "course_theme_mappings")).contains("PRIMARY KEY (course_id, theme_id)",
                "UNIQUE KEY uq_course_theme_mappings_order (course_id, display_order)",
                "CHECK (display_order BETWEEN 1 AND 3)");
        assertThat(sql).contains("CHECK (course_kind IN ('OFFICIAL', 'TRAVELER'))",
                "CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN'))",
                "course_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TRAVELER'",
                "status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLISHED'",
                "summary VARCHAR(300) NULL", "cover_image_url VARCHAR(255) NULL", "structured_content JSON NULL");
    }

    @Test
    void coordinateChecksDoNotReferenceColumnsWithForeignKeyActions() throws IOException {
        String items = table(sql(), "course_items");
        assertThat(items).contains("latitude DECIMAL(10,8) NULL", "longitude DECIMAL(11,8) NULL",
                "(latitude IS NULL AND longitude IS NULL)", "latitude IS NOT NULL AND longitude IS NOT NULL",
                "latitude BETWEEN -90 AND 90", "longitude BETWEEN -180 AND 180");
        for (String table : List.of("course_days", "course_items", "course_countries", "course_theme_mappings")) {
            String definition = table(sql(), table);
            var foreignKeys = Pattern.compile("FOREIGN KEY \\(([^)]+)\\)").matcher(definition);
            Set<String> fkColumns = new HashSet<>();
            foreignKeys.results().forEach(match -> fkColumns.add(match.group(1)));
            int start = 0;
            while ((start = definition.indexOf("CHECK (", start)) >= 0) {
                String expression = balancedExpression(definition, start + "CHECK ".length());
                for (String column : fkColumns) {
                    assertThat(expression).doesNotContain(column);
                }
                start += 7;
            }
        }
    }

    @Test
    void backfillIncludesDeletedAndEmptyCoursesAndResequencesEveryLegacyStop() throws IOException {
        String sql = sql();
        assertThat(sql).contains("INSERT INTO course_days (course_id, day_number)\nSELECT id, 1 FROM courses;");
        String items = statement(sql, "INSERT INTO course_items");
        assertThat(items).contains("PARTITION BY cd.course_id ORDER BY cd.visit_order, cd.id",
                "FROM course_destinations cd", "LEFT JOIN course_days day", "day.day_number = 1",
                "WHEN 'ACCOMMODATION' THEN 'STAY'", "WHEN 'RESTAURANTS' THEN 'FOOD'", "WHEN 'CAFE' THEN 'FOOD'",
                "ELSE 'PLACE'", "cd.destination_id", "snapshot.name", "NULL AS address")
                .doesNotContain("deleted", "Destination #", "Unknown Place", "updated_at");
        // both CASE expressions use exactly the same validity condition, never repairing coordinates.
        assertThat(Pattern.compile("d.latitude IS NOT NULL AND d.longitude IS NOT NULL").matcher(items).results().count())
                .isEqualTo(2);
    }

    @Test
    void nameFallbackAndCountryResolutionAreDeterministicWithoutInventedValues() throws IOException {
        String items = statement(sql(), "INSERT INTO course_items");
        assertThat(items).contains("PARTITION BY dt.destination_id", "WHEN 'ko' THEN 1", "WHEN 'en' THEN 2",
                "WHEN 'ja' THEN 3", "WHEN 'zh-CN' THEN 4", "WHEN 'zh-TW' THEN 5", "ELSE 6",
                "BINARY dt.language_code, dt.id", "NULLIF(TRIM(dt.name), '') IS NOT NULL",
                "d.region_id", "JOIN country_categories parent ON parent.id = ancestry.parent_id",
                "UNION DISTINCT", "region.code = ancestry.country_code", "HAVING COUNT(DISTINCT region.id) = 1")
                .doesNotContain("depth =", "region_name =");
        assertThat(Pattern.compile("(?:region|parent)\\.id\\s*=\\s*\\d+").matcher(items).find()).isFalse();
    }

    @Test
    void countryBackfillHonorsLegacyCountryBeforeFirstOccurrenceOfDistinctItemCountries() throws IOException {
        String sql = sql();
        assertThat(sql).contains("SELECT id, country_id, 1 FROM courses WHERE country_id IS NOT NULL;",
                "MIN(item.display_order) AS first_order", "GROUP BY day.course_id, item.country_id",
                "PARTITION BY first_country.course_id ORDER BY first_country.first_order, first_country.country_id",
                "c.country_id IS NULL AND item.country_id IS NOT NULL", "primary_country.display_order = 1")
                .doesNotContain("INSERT INTO course_theme_mappings");
    }

    @Test
    void seedsExactlyTheTenRequestedThemesWithoutGuessingExistingCourseThemes() throws IOException {
        String seed = statement(sql(), "INSERT INTO course_themes");
        var rows = Pattern.compile("\\('([A-Z_]+)', '([^']+)', (\\d+)\\)").matcher(seed);
        assertThat(rows.results().map(match -> match.group(1) + ":" + match.group(2) + ":" + match.group(3)).toList())
                .containsExactly("ESSENTIAL:필수 코스:1", "HISTORY_CULTURE:역사·문화:2", "NATURE_SCENERY:자연·풍경:3",
                        "FOOD_CAFE:맛집·카페:4", "HEALING:휴양·힐링:5", "ACTIVITY:액티비티·체험:6",
                        "SHOPPING:쇼핑:7", "PHOTO_SPOT:사진·감성:8", "THEME_PARK:테마파크·즐길거리:9", "LOCAL:로컬여행:10");
    }

    private String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "").trim();
    }

    private String table(String sql, String name) {
        return statement(sql, "CREATE TABLE " + name + " (");
    }

    private String statement(String sql, String marker) {
        int start = sql.indexOf(marker);
        assertThat(start).as(marker).isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private String referenceTable(String sql, String name) {
        return statement(sql, "CREATE TABLE `" + name + "`");
    }

    private String balancedExpression(String sql, int open) {
        int depth = 0;
        for (int index = open; index < sql.length(); index++) {
            if (sql.charAt(index) == '(') depth++;
            if (sql.charAt(index) == ')' && --depth == 0) return sql.substring(open, index + 1);
        }
        throw new AssertionError("Unclosed CHECK expression");
    }
}
