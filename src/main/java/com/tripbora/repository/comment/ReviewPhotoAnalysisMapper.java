package com.tripbora.repository.comment;

import com.tripbora.dto.*;
import com.tripbora.service.comment.ReviewPhotoAnalyzer;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ReviewPhotoAnalysisMapper {
    java.util.Map<String,Object> workflowCounts(@Param("destinationId") Long destinationId,@Param("status") String status);
    List<ReviewImageAdminDto> workflowPhotos(@Param("destinationId") Long destinationId,@Param("section") String section,
            @Param("status") String status,@Param("offset") long offset,@Param("limit") int limit);
    List<ReviewPhotoWorkflowGroup> workflowGroups(@Param("destinationId") Long destinationId,@Param("status") String status,
            @Param("offset") long offset,@Param("limit") int limit);
    List<ReviewPhotoWorkflowPreview> workflowPreviews(@Param("destinationId") Long destinationId,@Param("groups") List<ReviewPhotoWorkflowGroup> groups);
    List<ReviewPhotoWorkflowPhoto> workflowSelection(@Param("destinationId") Long destinationId,@Param("ids") List<Long> ids,@Param("lock") boolean lock);
    List<ReviewPhotoMatch> selection(@Param("destinationId") Long destinationId, @Param("ids") List<Long> ids, @Param("lock") boolean lock);
    List<ReviewPhotoMatch> groupPage(@Param("destinationId") Long destinationId,@Param("imageId") Long imageId,
                                    @Param("kind") String kind,@Param("offset") long offset,@Param("limit") int limit);
    java.util.Map<String,Object> groupCounts(@Param("destinationId") Long destinationId,@Param("imageId") Long imageId,@Param("kind") String kind);
    int expireExhausted(@Param("limit") int limit);
    List<ReviewPhotoAnalysisJob> findDue(@Param("limit") int limit);
    int claim(@Param("id") Long id,@Param("token") String token);
    int complete(@Param("job") ReviewPhotoAnalysisJob job,@Param("token") String token,
                 @Param("result") ReviewPhotoAnalyzer.Analysis result,
                 @Param("b0") int b0,@Param("b1") int b1,@Param("b2") int b2,@Param("b3") int b3);
    int fail(@Param("id") Long id,@Param("token") String token);
    int completeHashOnly(@Param("job") ReviewPhotoAnalysisJob job,@Param("token") String token,@Param("sha256") String sha256);
    List<ReviewPhotoMatch> matches(@Param("destinationId") Long destinationId,@Param("imageId") Long imageId,
                                 @Param("offset") long offset,@Param("limit") int limit);
}
