package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoWorkflowMapperTest {
    @Test void exactGroupsIncludeHashOnlyPendingRegardlessOfApprovalEligibility() throws Exception {
        String exact=sql("workflowGroups",Map.of("destinationId",10L,"status","PENDING","offset",0L,"limit",21)).split("UNION ALL")[0];
        assertThat(exact).contains("anchor.analysis_state IN ('READY','HASH_ONLY')","first_photo.analysis_state IN ('READY','HASH_ONLY')");
        assertThat(exact).doesNotContain("anchor.analysis_state='READY'","consent.agreed=1");
    }
    @Test void regularPhotosAndPreviewsExposeSpecificApprovalRestrictions() throws Exception {
        var args=Map.<String,Object>of("destinationId",10L,"status","PENDING","section","all","offset",0L,"limit",101);
        assertThat(sql("workflowPhotos",args)).contains("i.eligible","AS eligibility_reason","PHOTO_CONSENT_MISSING");
        var group=new com.tripbora.dto.ReviewPhotoWorkflowGroup();group.setAnchorId(1L);group.setKind("exact");
        assertThat(sql("workflowPreviews",Map.of("destinationId",10L,"groups",List.of(group)))).contains("AS eligibility_reason");
    }
    @Test void reviewQueueGroupsRequireReviewableStatesAndRankRealPendingWorkFirst() throws Exception {
        String groups=sql("workflowGroups",Map.of("destinationId",10L,"status","ALL","offset",0L,"limit",21));
        assertThat(groups).contains("candidate.gallery_status IN ('PENDING','APPROVED')","AS reviewable","reviewable_pending DESC");
        assertThat(sql("workflowCounts",Map.of("destinationId",10L,"status","ALL"))).contains("gallery_status NOT IN ('PENDING','APPROVED')");
    }
    @Test void classificationIsAggregatedWithEligibilityAndCurrentPublishedConsent() throws Exception {
        String sql = sql("workflowCounts", Map.of("destinationId",10L,"status","PENDING"));
        assertThat(sql).contains("SUM(", "AS clean", "AS `groups`", "AS limited", "analysis_state='READY'",
                "analysis_destination_id=c.destination_id", "REGEXP", "perceptual_hash IS NOT NULL",
                "hash_band_3 IS NOT NULL", "parent_comment_id IS NULL", "content_moderations",
                "published_at IS NOT NULL", "REVIEW_GALLERY", "stop_event.id > grant_event.id", "current_basis.id = original_basis.id");
    }

    @Test void photoPagesAndGroupAnchorsAreDatabaseBounded() throws Exception {
        var args = Map.<String,Object>of("destinationId",10L,"status","PENDING","section","clean","offset",0L,"limit",101);
        assertThat(sql("workflowPhotos",args)).contains("workflow_page AS", "LIMIT ? OFFSET ?", "gallery_status='PENDING'", "AS exact_count", "AS similar_count");
        String groups = sql("workflowGroups",args);
        assertThat(groups).contains("UNION ALL", "MIN(", "candidate.id > anchor.id", "LIMIT ? OFFSET ?");
        assertThat(groups).doesNotContain("RECURSIVE");
    }

    @Test void currentSelectionIsLockedAndDoesNotDiscardUnanalyzedOrMovedPhotos() throws Exception {
        String sql = sql("workflowSelection",Map.of("destinationId",10L,"ids",List.of(1L,2L),"lock",true));
        assertThat(sql).contains("FOR UPDATE", "c.destination_id=?", "analysis_destination_id", "perceptual_hash");
        assertThat(sql).doesNotContain("AND i.analysis_state IN");
    }

    @Test void previewsUseOneUnionQueryWithFourRowsPerAnchor() throws Exception {
        var exact=new com.tripbora.dto.ReviewPhotoWorkflowGroup();exact.setAnchorId(1L);exact.setKind("exact");
        var similar=new com.tripbora.dto.ReviewPhotoWorkflowGroup();similar.setAnchorId(2L);similar.setKind("similar");
        String sql=sql("workflowPreviews",Map.of("destinationId",10L,"groups",List.of(exact,similar)));
        assertThat(sql).contains("UNION ALL", "LIMIT 4", "candidate.hash_band_3 IS NOT NULL");
        assertThat(sql.split("LIMIT 4",-1)).hasSize(3);
    }

    @Test void groupDetailReturnsPerPhotoEligibilityAndUsesTheSameCompleteMetadata() throws Exception {
        String sql=sql("groupPage",Map.of("destinationId",10L,"imageId",1L,"kind","similar","offset",0L,"limit",101));
        assertThat(sql).contains("AS eligible", "candidate.hash_band_3 IS NOT NULL", "anchor.hash_band_3 IS NOT NULL");
        assertThat(sql("groupCounts",Map.of("destinationId",10L,"imageId",1L,"kind","exact"))).contains("AS reviewableApproved");
    }

    private String sql(String id,Map<String,Object> args) throws Exception {
        var configuration = CanonicalPhotoConsentMapperTest.configuration();
        return configuration.getMappedStatement("com.tripbora.repository.comment.ReviewPhotoAnalysisMapper."+id)
                .getBoundSql(args).getSql().replaceAll("\\s+"," ");
    }
}
