package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DestinationBrowseMapperTest {
    @Test
    void browseRanksOnlyFilteredPublicIdsAndPaginatesBeforeLoadingCardData() throws Exception {
        String sql = sql("default");
        assertThat(sql).contains("WITH RECURSIVE", "ROW_NUMBER() OVER", "PARTITION BY",
                "parent.code = 'KR'", "parent.depth = 1", "d.is_public = 1",
                "destination_categories", "browse_rank", "browse_group");
        assertThat(sql.indexOf("LIMIT ? OFFSET ?")).isLessThan(sql.indexOf("card_image.image_url"));
        assertThat(sql).doesNotContain("RAND()", "SELECT d.*");
    }

    @Test
    void explicitSortsHaveUniqueTieBreakersAndDoNotPayForWindowFunctions() throws Exception {
        assertThat(sql("latest")).contains("ORDER BY d.created_at DESC, d.id DESC").doesNotContain("ROW_NUMBER()");
        assertThat(sql("views")).contains("ORDER BY d.views DESC, d.id ASC").doesNotContain("ROW_NUMBER()");
        assertThat(sql("bookmarks")).contains("ORDER BY bookmarkCount DESC, d.id ASC").doesNotContain("ROW_NUMBER()");
    }

    private String sql(String sort) throws Exception {
        Configuration configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/DestinationMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DestinationMapper.xml", configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.tripbora.repository.destination.DestinationMapper.findByRegionIdsPaged")
                .getBoundSql(Map.of("sort", sort, "regionIds", List.of(10L, 11L), "categoryIds", List.of(3L),
                        "offset", 12, "size", 12)).getSql().replaceAll("\\s+", " ");
    }
}
