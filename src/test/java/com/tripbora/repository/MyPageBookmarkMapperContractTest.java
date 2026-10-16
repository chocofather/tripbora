package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MyPageBookmarkMapperContractTest {

    private static final Path MAPPER =
            Path.of("src/main/resources/mapper/MyPageBookmarkMapper.xml");

    @Test
    void everySectionStartsFromOwnedBookmarksAndUsesBookmarkTimeOrdering() throws IOException {
        String xml = compact(read());

        assertThat(occurrences(xml, "FROM bookmarks b")).isEqualTo(4);
        assertThat(occurrences(xml, "b.user_id = #{userId}")).isEqualTo(4);
        assertThat(occurrences(xml, "b.created_at AS bookmarkCreatedAt")).isEqualTo(4);
        assertThat(occurrences(xml, "ORDER BY b.created_at DESC, b.id DESC")).isEqualTo(2);
        assertThat(xml)
                .contains("ORDER BY bookmarked_content.bookmarkCreatedAt DESC, bookmarked_content.bookmarkId DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void destinationUsesKoreanProjectionDeterministicThumbnailAndParameterizedTree() throws IOException {
        String xml = compact(read());

        assertThat(xml)
                .contains("dt.language_code = 'ko'")
                .contains("di.is_main = 1")
                .contains("ORDER BY di.order_index ASC, di.id ASC")
                .contains("WHERE id = #{koreaRootId}")
                .contains("d.region_id IN (SELECT id FROM korea_regions)")
                .contains("d.region_id NOT IN (SELECT id FROM korea_regions)")
                .doesNotContain("region_id = 7")
                .doesNotContain("WHERE id = 7");
        assertThat(occurrences(xml, "<include refid=\"destinationBookmarkFromAndFilters\"/>")).isEqualTo(2);
    }

    @Test
    void communityUnionsActiveQuestionsTipsAndCoursesWithSharedFilters() throws IOException {
        String xml = compact(read());

        assertThat(xml)
                .contains("b.target_type = 'POST'")
                .contains("p.post_type IN ('QUESTION', 'TIP')")
                .contains("p.deleted = 0")
                .contains("p.deleted_at IS NULL")
                .contains("b.target_type = 'COURSE'")
                .contains("c.deleted = 0")
                .contains("c.deleted_at IS NULL")
                .contains("bookmarked_content.postType = 'QUESTION'")
                .contains("bookmarked_content.postType = 'TIP'")
                .contains("bookmarked_content.boardType = 'course'");
        assertThat(occurrences(xml, "<include refid=\"unifiedCommunityBookmarkSelect\"/>")).isEqualTo(2);
        assertThat(occurrences(xml, "<include refid=\"communityBookmarkFilter\"/>")).isEqualTo(2);
    }

    @Test
    void officialCourseBookmarksUseTheBrandInsteadOfTheirAdminCreator() throws IOException {
        String xml = compact(read());
        int start = xml.indexOf("'course' AS boardType");
        String course = xml.substring(start, xml.indexOf("</sql>", start));

        assertThat(course)
                .contains("CASE c.course_kind WHEN 'OFFICIAL' THEN 'TripBora' ELSE u.nickname END AS nickname")
                .contains("CASE c.course_kind WHEN 'OFFICIAL' THEN FALSE ELSE (u.status = 'DEACTIVATED') END AS writerWithdrawn");
    }

    @Test
    void travelInfoKeepsOnlyPublicCategoriesAndReusesThumbnailOrdering() throws IOException {
        String xml = compact(read());

        assertThat(xml)
                .contains("b.target_type = 'TRAVEL_INFO'")
                .contains("ic.is_visible = 1")
                .contains("ti.scope = #{scope}")
                .contains("ii.is_main = 1")
                .contains("ORDER BY ii.order_index ASC, ii.id ASC");
        assertThat(occurrences(xml, "<include refid=\"travelInfoBookmarkFromAndFilters\"/>")).isEqualTo(2);
    }

    private String read() throws IOException {
        return Files.readString(MAPPER, StandardCharsets.UTF_8);
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
