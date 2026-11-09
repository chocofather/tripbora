package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReviewDestinationAdminMapperTest {
    @Test void analysisFiltersUseStoredMetadataAndDestinationScopedCandidateIndexes() throws Exception {
        var query=sql("findAdminDestinationPhotoPageFiltered",Map.of("destinationId",10L,"status","ALL","sort","latest","analysis","duplicates","offset",50,"limit",51));
        assertThat(query).contains("other.analysis_destination_id = c.destination_id","oc.destination_id = c.destination_id","other.hash_band_0 = i.hash_band_0","BIT_COUNT", "<= 3", "LIMIT ? OFFSET ?");
        var quality=sql("findAdminDestinationPhotoPageFiltered",Map.of("destinationId",10L,"status","ALL","sort","latest","analysis","quality","offset",0,"limit",51));
        assertThat(quality).contains("i.analysis_state IN ('FAILED','HASH_ONLY')","COALESCE(i.quality_flags,'') != ''");
    }
    private String sql(String id, Map<String,Object> params) throws Exception {
        var config = new Configuration();
        try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/DestinationCommentImageMapper.xml"))) {
            new XMLMapperBuilder(input, config, "images", config.getSqlFragments()).parse();
        }
        return config.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper." + id)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ");
    }
    @Test void destinationPageUsesIdsAndOnlySortsByPendingWhenRequested() throws Exception {
        String query = sql("findAdminDestinationIds", Map.of("query","산", "sort","pending", "limit",21,"offset",0));
        assertThat(query).contains("SELECT d.id", "EXISTS", "gallery_status = 'PENDING'", "LIMIT ? OFFSET ?");
        assertThat(query).doesNotContain("GROUP BY t.name");
    }
    @Test void statisticsAreScopedAndDoNotMultiplyPhotosByCategoryJoins() throws Exception {
        String query = sql("findAdminDestinationStatistics", Map.of("ids",java.util.List.of(1L,2L)));
        assertThat(query).contains("COUNT(*)", "COUNT(DISTINCT c.id)", "GROUP BY c.destination_id", "c.destination_id IN");
        assertThat(query).doesNotContain("JOIN destination_categories");
        assertThat(sql("findAdminDestinationStatistics",Map.of("ids",java.util.List.of()))).contains("1 = 0");
    }
    @Test void detailPagesPhotosWithScopedStatusAndStableOrdering() throws Exception {
        for(String sort : new String[]{"latest","oldest"}) {
            var query=sql("findAdminDestinationPhotoPage", Map.of("destinationId",4L,"status","ALL","sort",sort,"offset",50L,"limit",51));
            assertThat(query).contains("c.destination_id = ?", "LIMIT ? OFFSET ?", "LEFT JOIN users", "i.created_at",
                    sort.equals("latest") ? "i.created_at DESC, i.id DESC" : "i.created_at ASC, i.id ASC");
            assertThat(query).doesNotContain("GROUP BY", "i.comment_id IN", "i.gallery_status = ?");
        }
        assertThat(sql("findAdminDestinationPhotoPage", Map.of("destinationId",4L,"status","PENDING","sort","latest","offset",0,"limit",51)))
                .contains("c.destination_id = ?", "i.gallery_status = ?");
    }
}
