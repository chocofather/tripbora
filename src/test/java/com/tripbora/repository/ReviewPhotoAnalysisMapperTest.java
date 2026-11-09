package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoAnalysisMapperTest {
    @Test void exactAndSimilarGroupsAreSeparateNonRecursivePagedAndCounted() throws Exception {
        var exact=sql("groupPage",Map.of("destinationId",10L,"imageId",1L,"kind","exact","offset",100L,"limit",101));
        assertThat(exact).contains("candidate.file_sha256=anchor.file_sha256","anchor.analysis_destination_id=ac.destination_id","LIMIT ? OFFSET ?");
        assertThat(exact).doesNotContain("<= 3","RECURSIVE");
        var similar=sql("groupPage",Map.of("destinationId",10L,"imageId",1L,"kind","similar","offset",0L,"limit",101));
        assertThat(similar).contains("candidate.file_sha256 != anchor.file_sha256","candidate.id=anchor.id","<= 3");
        var counts=sql("groupCounts",Map.of("destinationId",10L,"imageId",1L,"kind","exact"));
        assertThat(counts).contains("COUNT(*) AS total","gallery_status='APPROVED'","gallery_status='PENDING'","gallery_status='REJECTED'");
        assertThat(sql("selection",Map.of("destinationId",10L,"ids",java.util.List.of(1L,2L),"lock",true))).contains("FOR UPDATE","c.destination_id=?","i.analysis_destination_id=?");
    }
    private String sql(String id,Map<String,Object> args)throws Exception {
        var c=CanonicalPhotoConsentMapperTest.configuration();
        return c.getMappedStatement("com.tripbora.repository.comment.ReviewPhotoAnalysisMapper."+id).getBoundSql(args).getSql().replaceAll("\\s+"," ");
    }
    @Test void queueAndClaimsAreBoundedRetryableAndTokenProtected() throws Exception {
        assertThat(sql("findDue",Map.of("limit",5))).contains("analysis_attempts < 3","analysis_lease_until <= CURRENT_TIMESTAMP","LIMIT ?");
        assertThat(sql("claim",Map.of("id",1L,"token","owner"))).contains("analysis_attempts = analysis_attempts + 1","INTERVAL 10 MINUTE");
        assertThat(sql("fail",Map.of("id",1L,"token","owner"))).contains("analysis_state = 'RUNNING' AND analysis_token = ?");
        assertThat(sql("expireExhausted",Map.of("limit",5))).contains("analysis_attempts >= 3","LIMIT ?");
    }
    @Test void matchCandidatesAreDestinationScopedBandedAndPaged()throws Exception {
        var q=sql("matches",Map.of("destinationId",10L,"imageId",1L,"offset",20L,"limit",21));
        assertThat(q).contains("ac.destination_id = ?","c.destination_id = ac.destination_id","candidate.file_sha256 = anchor.file_sha256","candidate.hash_band_0 = anchor.hash_band_0","BIT_COUNT", "<= 3", "LIMIT ? OFFSET ?");
        assertThat(q).doesNotContain("UPDATE", "DELETE");
    }
    @Test void analysisWritesOnlyMetadataAndUsesFileScopeAndLease() throws Exception {
        var job=new com.tripbora.dto.ReviewPhotoAnalysisJob();job.setId(1L);job.setCommentId(5L);job.setDestinationId(10L);job.setImageUrl("/a.jpg");
        var q=sql("complete",Map.of("job",job,"token","owner","result",new com.tripbora.service.comment.ReviewPhotoAnalyzer.Analysis("sha",1,800,500,80,30,"BLUR"),"b0",1,"b1",0,"b2",0,"b3",0));
        assertThat(q).contains("i.comment_id = ?","i.image_url = ?","i.analysis_token = ?");
        assertThat(q).doesNotContain("JOIN", "gallery_status", "deleted", "gallery_priority", "review_reason");
        assertThat(org.apache.ibatis.reflection.SystemMetaObject.forObject(new com.tripbora.service.comment.ReviewPhotoAnalyzer.Analysis("sha",-1L,800,500,80,30,"BLUR")).getValue("dHash")).isEqualTo(-1L);
    }
}
