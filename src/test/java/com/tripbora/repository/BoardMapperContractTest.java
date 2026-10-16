package com.tripbora.repository;

import com.tripbora.dto.BoardListDto;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoardMapperContractTest {
    @Test
    void communitySourceCannotSelectCourseOrCountryBranches() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/mapper/BoardMapper.xml")) {
            assertThat(input).isNotNull();
            String mapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String community = mapper.substring(0, mapper.indexOf("<sql id=\"postBoardSelectBase\""));
            assertThat(community).contains("target_type = 'POST'", "GROUP BY target_id",
                            "COALESCE(postBookmarks.bookmarkCount, 0) AS bookmarkCount")
                    .doesNotContain("FROM courses", "courseCountryFilters", "publicCourseBranch", "UNION ALL");
        }
    }

    @Test
    void boardListDtoCarriesZeroSafeBookmarkCount() {
        BoardListDto dto = new BoardListDto();
        assertThat(dto.getBookmarkCount()).isZero();
        dto.setBookmarkCount(3);
        assertThat(dto.getBookmarkCount()).isEqualTo(3);
    }
}
