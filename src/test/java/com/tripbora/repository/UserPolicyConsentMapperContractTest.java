package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동의 이력 보관정책의 삭제 조건은 전부 SQL 이 갖고 있다.
 * 조건이 하나라도 빠지면 아직 지우면 안 되는 이력이 사라지므로 문장 자체를 고정한다.
 */
class UserPolicyConsentMapperContractTest {

    /**
     * 대상은 최종 탈퇴가 끝나고 3년이 지난 회원뿐이다.
     * ACTIVE/INACTIVE/RESTRICTED/SUSPENDED/WITHDRAWAL_PENDING 은 동의일이 아무리 오래돼도 아니다.
     */
    @Test
    void onlyUsersWhoseFinalPurgeCompletedThreeYearsAgoAreSelected() throws IOException {
        String select = statement(consentXml(), "select",
                "findConsentRetentionExpiredUserIds");
        String condition = statement(consentXml(), "sql", "ConsentRetentionExpiredUser");

        // tombstone 이 된 회원만. 다른 상태는 조건에서 걸러진다.
        assertThat(condition).contains("u.status = 'DEACTIVATED'");
        // 30일 유예 중 복구한 회원은 deleted_at 이 NULL 로 돌아가 제외된다.
        assertThat(condition).contains("u.deleted_at IS NOT NULL");
        // 경계는 이하라서 정확히 3년이 된 회원부터 대상이다.
        assertThat(condition).contains("u.deleted_at &lt;= #{retentionCutoff}");

        // 기준은 반드시 deleted_at 이다. 유예 시각으로 계산하면 30일 일찍 지운다.
        assertThat(condition)
                .doesNotContain("withdrawal_requested_at")
                .doesNotContain("purge_scheduled_at");

        // 대량 데이터에 대비해 한 번에 batch 크기만큼만 읽는다.
        assertThat(select).contains("LIMIT #{limit}");
        // 이미 정리된 회원을 매 주기 다시 집지 않는다.
        assertThat(select).contains("FROM user_policy_consents c");
    }

    /**
     * 삭제는 회원 단위 전체다. 오래된 개별 동의행만 골라 지우지 않고,
     * 다른 회원의 이력에는 손대지 않는다.
     */
    @Test
    void theDeleteRemovesEveryConsentOfOneUserAndNoOneElses() throws IOException {
        String delete = statement(consentXml(), "delete",
                "deleteConsentsOfRetentionExpiredUser");

        assertThat(delete)
                .contains("DELETE FROM user_policy_consents")
                .contains("WHERE user_id = #{userId}");

        // 동의행 자체의 나이로 지우지 않는다. 회원의 보관기한만 본다.
        assertThat(delete).doesNotContain("c.created_at").doesNotContain("created_at &lt;=");

        // 목록을 읽은 뒤 조건이 깨졌으면 한 행도 지우지 않는다.
        assertThat(delete)
                .contains("EXISTS")
                .contains("FROM users u")
                .contains("<include refid=\"ConsentRetentionExpiredUser\"/>");
    }

    /** 정책 문서 자체와 회원 tombstone 은 이 mapper 가 건드리지 않는다. */
    @Test
    void neitherPolicyDocumentsNorTheUserTombstoneAreTouched() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(consentXml())));
        var allowed = Map.of("insertConsent", "insert:user_policy_consents",
                "deleteConsentsOfRetentionExpiredUser", "delete:user_policy_consents",
                "revokeGalleryImages", "update:destination_comment_images");
        for (String tag : new String[]{"insert", "update", "delete"}) {
            var statements = document.getElementsByTagName(tag);
            for (int i = 0; i < statements.getLength(); i++) {
                var item = (org.w3c.dom.Element) statements.item(i);
                String sql = item.getTextContent().replace("`", "").replaceAll("\\s+", " ").strip();
                var target = Pattern.compile("(?i)^(?:UPDATE|INSERT INTO|DELETE FROM)\\s+(\\w+)\\b").matcher(sql);
                assertThat(target.find()).as(item.getAttribute("id")).isTrue();
                assertThat(tag + ":" + target.group(1).toLowerCase(java.util.Locale.ROOT))
                        .as(item.getAttribute("id")).isEqualTo(allowed.get(item.getAttribute("id")));
                // 동의 이력의 덮어쓰기와 복수 명령은 허용하지 않는다.
                assertThat(Pattern.compile("(?i)\\b(?:ON DUPLICATE KEY UPDATE|REPLACE INTO)\\b|;")
                        .matcher(sql).find()).isFalse();
            }
        }
    }

    /** 회원 연결·상태 조건과 수정 컬럼을 해당 UPDATE 안에서만 검사한다. */
    @Test
    void galleryWithdrawalOnlyRevokesTheOwnersEligibleStatesWithoutDeletingOriginals() throws IOException {
        assertGalleryWithdrawal(statement(consentXml(), "update", "revokeGalleryImages"));
    }

    @Test
    void withdrawalContractRejectsBroaderOwnersStatesAndDestructiveAssignments() throws IOException {
        String sql = statement(consentXml(), "update", "revokeGalleryImages");
        for (String unsafe : new String[]{
                sql.replace("c.user_id = #{userId}", "c.user_id != #{userId}"),
                sql.replace("c.id = i.comment_id", "c.id != i.comment_id"),
                sql.replace("AND i.gallery_license_policy_id IS NULL", ""),
                sql.replace("'REJECTED')", "'REJECTED', 'NONE')"),
                sql.replace("'REJECTED')", "'REJECTED', 'REVOKED')"),
                sql.replace("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP, i.image_url = NULL"),
                sql.replace("c.user_id = #{userId}", "c.user_id = #{userId} OR 1=1")}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertGalleryWithdrawal(unsafe))
                    .isInstanceOf(AssertionError.class);
        }
        // 정상 SQL의 줄바꿈/들여쓰기 변화는 계약 위반이 아니다.
        assertGalleryWithdrawal(sql.replace("\n", "\n    "));
    }

    private void assertGalleryWithdrawal(String statement) {
        String sql = statement.substring(statement.indexOf('>') + 1)
                .replaceAll("\\s+", " ").strip();
        // 전체 명령을 검사하므로 다른 회원을 포함하는 OR, 추가 UPDATE/DELETE,
        // 파일 URL·리뷰 수정이나 NONE/REVOKED의 재처리를 허용하지 않는다.
        assertThat(sql).matches("(?i:UPDATE destination_comment_images i JOIN destination_comments c ON c\\.id = i\\.comment_id SET) "
                + "i\\.gallery_status = 'REVOKED', i\\.gallery_consent_withdrawn_at = (?i:CURRENT_TIMESTAMP) "
                + "(?i:WHERE) c\\.user_id = #\\{userId} (?i:AND) i\\.gallery_license_policy_id (?i:IS NULL) "
                + "(?i:AND) i\\.gallery_status (?i:IN) "
                + "\\('PENDING', 'APPROVED', 'REJECTED'\\)");
    }

    /**
     * 최종 account purge 는 동의 이력을 지우지 않는다. 보관기간은 그때부터 시작이다.
     * users tombstone 행이 남으므로 FK 의 ON DELETE CASCADE 도 일어나지 않는다.
     */
    @Test
    void theFinalAccountPurgeLeavesTheConsentHistoryInPlace() throws IOException {
        assertThat(resource("mapper/AccountPurgeMapper.xml"))
                .doesNotContain("user_policy_consents");
        assertThat(resource("mapper/UserMapper.xml"))
                .doesNotContain("DELETE FROM user_policy_consents");
    }

    private String consentXml() throws IOException {
        return resource("mapper/UserPolicyConsentMapper.xml");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/resources").resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    private String statement(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(start).as("%s statement %s", tag, id).isNotNegative();
        assertThat(end).as("%s statement %s closing tag", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
