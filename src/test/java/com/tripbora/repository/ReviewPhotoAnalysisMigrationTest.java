package com.tripbora.repository;

import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoAnalysisMigrationTest {
    private static final Path DIRECTORY=Path.of("src/main/resources/db/migration");

    @Test void appliedOriginalKeepsItsVerifiedFlywayChecksum() {
        var resource=new FileSystemResource(null,DIRECTORY.resolve("V20261102_001__add_review_photo_analysis_and_priority.sql").toString(),StandardCharsets.UTF_8,false);
        // Reported by DEV and verified against the first creation patch. Applied migrations are immutable.
        assertThat(ChecksumCalculator.calculate(resource)).isEqualTo(764143629);
    }

    @Test void hashOnlyExtensionUsesNewVersionAndPreservesAllExistingAnalysisStates() throws Exception {
        var original=Files.readString(DIRECTORY.resolve("V20261102_001__add_review_photo_analysis_and_priority.sql"));
        var extension=Files.readString(DIRECTORY.resolve("V20261102_002__allow_review_photo_hash_only_analysis.sql"));
        assertThat(original).contains("('PENDING','RUNNING','READY','FAILED')").doesNotContain("HASH_ONLY");
        assertThat(extension).contains("ALTER TABLE destination_comment_images", "DROP CHECK chk_review_photo_analysis_state",
                "ADD CONSTRAINT chk_review_photo_analysis_state", "('PENDING','RUNNING','READY','HASH_ONLY','FAILED')");
        assertThat(extension).doesNotContain("DROP TABLE","DROP COLUMN","DELETE FROM","UPDATE ","INSERT ","gallery_status");
        assertThat(org.flywaydb.core.api.MigrationVersion.fromVersion("20261102.002"))
                .isGreaterThan(org.flywaydb.core.api.MigrationVersion.fromVersion("20261102.001"));
    }

    @Test void mysqlParserReadsExtensionAsOneAlterWithoutExecutingIt() {
        var resource=new FileSystemResource(null,DIRECTORY.resolve("V20261102_002__allow_review_photo_hash_only_analysis.sql").toString(),StandardCharsets.UTF_8,false);
        var parser=new org.flywaydb.database.mysql.MySQLParser(
                new org.flywaydb.core.api.configuration.ClassicConfiguration(),new org.flywaydb.core.internal.parser.ParsingContext());
        try(var statements=parser.parse(resource)) {
            assertThat(statements.hasNext()).isTrue();
            assertThat(statements.next().getSql()).contains("ALTER TABLE destination_comment_images", "DROP CHECK", "ADD CONSTRAINT");
            assertThat(statements.hasNext()).isFalse();
        }
    }
}
