package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여행지 댓글 사진 저장소 계약.
 * STEP B 단계라 기존 댓글 저장/조회 흐름에는 아직 연결하지 않는다.
 */
class DestinationCommentImageMapperContractTest {

    @Test
    void insertStoresCommentIdUrlAndDisplayOrder() throws IOException {
        String insert = between(mapperXml(), "<insert id=\"insert\"", "</insert>");

        assertThat(insert)
                .contains("INSERT INTO destination_comment_images")
                .contains("comment_id", "image_url", "display_order")
                .contains("#{commentId}", "#{imageUrl}", "#{displayOrder}")
                .contains("useGeneratedKeys=\"true\"");
    }

    @Test
    void findByCommentIdsReadsManyCommentsAtOnceInDisplayOrder() throws IOException {
        String select = between(mapperXml(), "<select id=\"findByCommentIds\"", "</select>");

        assertThat(select)
                .contains("comment_id IN")
                .contains("<foreach item=\"commentId\" collection=\"commentIds\"")
                // 댓글별 사진 순서를 보장한다
                .contains("ORDER BY comment_id, display_order")
                // 빈 목록으로 호출해도 SQL 이 깨지지 않는다
                .contains("1 = 0");
    }

    @Test
    void deletionLifecycleQueryReadsEveryImageUrlWithoutUserFacingFilters() throws IOException {
        String select = between(mapperXml(),
                "<select id=\"findAllImageUrlsByDestinationId\"", "</select>");

        assertThat(select)
                // 파일 정리에는 URL 만 필요하다
                .contains("SELECT i.image_url")
                .contains("FROM destination_comment_images i")
                .contains("JOIN destination_comments c ON c.id = i.comment_id")
                .contains("c.destination_id = #{destinationId}")
                // 삭제 lifecycle 이므로 soft-deleted 댓글의 사진도 정리 대상이다
                .doesNotContain("c.deleted")
                // 갤러리와 달리 노출 개수 제한이 없다
                .doesNotContain("LIMIT");
    }

    @Test
    void legacySingleImageEditPathIsGone() throws IOException {
        // 어디서도 호출되지 않던 image_url 단건 수정 경로(엔드포인트~쿼리)를 제거했다.
        assertThat(readFile("src/main/java/com/tripbora/controller/destination/"
                + "DestinationCommentController.java"))
                .doesNotContain("edit-image")
                .doesNotContain("updateCommentImage");
        assertThat(readFile("src/main/java/com/tripbora/service/comment/"
                + "DestinationCommentService.java"))
                .doesNotContain("updateCommentImage")
                .doesNotContain("findImagePathById")
                .doesNotContain("updateImagePath");
        assertThat(readFile("src/main/java/com/tripbora/repository/comment/"
                + "DestinationCommentMapper.java"))
                .doesNotContain("findImagePathById")
                .doesNotContain("updateImagePath");
        assertThat(resource("/mapper/DestinationCommentMapper.xml"))
                .doesNotContain("findImagePathById")
                .doesNotContain("updateImagePath");
        // 단일 image_url 교체 경로 대신 본문+복수 첨부의 원자적 수정 경로를 쓴다.
        assertThat(resource("/static/js/comment/events.js"))
                .contains("updateCommentWithImages")
                .doesNotContain("updateCommentImage");
    }

    @Test
    void commentCodeNoLongerDependsOnTheLegacyImageUrlColumn() throws IOException {
        // DB 에서 destination_comments.image_url 을 DROP 해도 코드가 깨지지 않아야 한다.
        assertThat(resource("/mapper/DestinationCommentMapper.xml"))
                .doesNotContain("image_url");
        assertThat(readFile("src/main/java/com/tripbora/model/DestinationComment.java"))
                .doesNotContain("imageUrl");
        assertThat(readFile("src/main/java/com/tripbora/repository/comment/"
                + "DestinationCommentMapper.java"))
                .doesNotContain("selectCommentsWithImages");
        assertThat(readFile("src/main/java/com/tripbora/service/comment/"
                + "DestinationCommentService.java"))
                .doesNotContain("setImageUrl(null)")
                .doesNotContain("selectCommentsWithImages");
    }

    @Test
    void commentAndImageStoragesStaySeparate() throws IOException {
        // 사진은 destination_comment_images 매퍼만 다룬다 (댓글 매퍼는 계속 댓글 테이블만)
        assertThat(resource("/mapper/DestinationCommentMapper.xml"))
                .doesNotContain("destination_comment_images");
    }

    @Test
    void schemaDocumentKeepsCommentImagesInTheirOwnTable() throws IOException {
        String schema = readFile("docs/db/tripbora_schema_reference.md");

        // 댓글 사진 저장소는 destination_comment_images 하나뿐이다
        assertThat(schema)
                .contains("CREATE TABLE `destination_comment_images`")
                .contains("UNIQUE KEY `uq_destination_comment_images_order` (`comment_id`,`display_order`)")
                .contains("REFERENCES `destination_comments` (`id`) ON DELETE CASCADE")
                .contains("`display_order` between 1 and 3");
        // destination_comments 에는 더 이상 사진 컬럼이 없다 (실제 DB 에서 DROP 완료)
        assertThat(between(schema, "CREATE TABLE `destination_comments`", "ENGINE=InnoDB"))
                .doesNotContain("image_url");
    }

    private String mapperXml() throws IOException {
        return resource("/mapper/DestinationCommentImageMapper.xml");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readFile(String path) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)),
                StandardCharsets.UTF_8);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
