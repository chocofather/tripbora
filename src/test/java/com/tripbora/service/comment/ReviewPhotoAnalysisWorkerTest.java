package com.tripbora.service.comment;

import com.tripbora.dto.ReviewPhotoAnalysisJob;
import com.tripbora.repository.comment.ReviewPhotoAnalysisMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReviewPhotoAnalysisWorkerTest {
    @Test void decodeFailureStillAllowsExactFileComparisonWithoutQualityClaims() throws Exception {
        var mapper=mock(ReviewPhotoAnalysisMapper.class);var analyzer=mock(ReviewPhotoAnalyzer.class);var files=mock(FileUploadService.class);
        var job=new ReviewPhotoAnalysisJob();job.setId(1L);job.setDestinationId(10L);job.setImageUrl("/uploads/comments/a.jpg");
        when(mapper.findDue(5)).thenReturn(List.of(job));when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        var path=Path.of("a.jpg");when(files.commentFileForAnalysis(job.getImageUrl())).thenReturn(path);
        when(analyzer.analyze(path)).thenThrow(new java.io.IOException("decode unsupported"));when(analyzer.exactHash(path)).thenReturn("sha");
        new ReviewPhotoAnalysisWorker(mapper,analyzer,files).runBatch();
        verify(mapper).completeHashOnly(eq(job),anyString(),eq("sha"));verify(mapper,never()).fail(any(),any());
    }
    @Test void webpOnlyStoresFileHashWithoutPretendingToHaveQualityOrSimilarityMetrics() throws Exception {
        var mapper=mock(ReviewPhotoAnalysisMapper.class);var analyzer=mock(ReviewPhotoAnalyzer.class);var files=mock(FileUploadService.class);
        var job=new ReviewPhotoAnalysisJob();job.setId(1L);job.setDestinationId(10L);job.setImageUrl("/uploads/comments/a.webp");
        when(mapper.findDue(5)).thenReturn(List.of(job));when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        var path=Path.of("a.webp");when(files.commentFileForAnalysis(job.getImageUrl())).thenReturn(path);when(analyzer.exactHash(path)).thenReturn("sha256");
        new ReviewPhotoAnalysisWorker(mapper,analyzer,files).runBatch();
        verify(analyzer,never()).analyze(any());verify(mapper).completeHashOnly(eq(job),anyString(),eq("sha256"));
        verify(mapper,never()).complete(any(),anyString(),any(),anyInt(),anyInt(),anyInt(),anyInt());
    }
    @Test void batchIsBoundedClaimedBeforeReadingAndNeverChangesReviewOrGalleryStatus() throws Exception {
        var mapper=mock(ReviewPhotoAnalysisMapper.class);var analyzer=mock(ReviewPhotoAnalyzer.class);var files=mock(FileUploadService.class);
        var job=new ReviewPhotoAnalysisJob();job.setId(1L);job.setDestinationId(10L);job.setImageUrl("/uploads/comments/a.jpg");
        when(mapper.findDue(5)).thenReturn(List.of(job));when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        var path=Path.of("a.jpg");when(files.commentFileForAnalysis(job.getImageUrl())).thenReturn(path);
        var result=new ReviewPhotoAnalyzer.Analysis("hash",8L,1200,800,100,200,"");when(analyzer.analyze(path)).thenReturn(result);
        new ReviewPhotoAnalysisWorker(mapper,analyzer,files).runBatch();
        var order=inOrder(mapper,files,analyzer);order.verify(mapper).expireExhausted(5);order.verify(mapper).findDue(5);
        order.verify(mapper).claim(eq(1L),anyString());order.verify(files).commentFileForAnalysis(job.getImageUrl());
        order.verify(analyzer).analyze(path);order.verify(mapper).complete(eq(job),anyString(),eq(result),eq(8),eq(0),eq(0),eq(0));
        verify(mapper,never()).fail(any(),any());
    }
    @Test void failedDecodeAndRestartedOrConcurrentClaimsDoNotPropagateIntoUploads() throws Exception {
        var mapper=mock(ReviewPhotoAnalysisMapper.class);var analyzer=mock(ReviewPhotoAnalyzer.class);var files=mock(FileUploadService.class);
        var first=new ReviewPhotoAnalysisJob();first.setId(1L);first.setImageUrl("bad");var claimedElsewhere=new ReviewPhotoAnalysisJob();claimedElsewhere.setId(2L);
        when(mapper.findDue(5)).thenReturn(List.of(first,claimedElsewhere));when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        when(files.commentFileForAnalysis("bad")).thenThrow(new java.io.IOException("missing"));
        new ReviewPhotoAnalysisWorker(mapper,analyzer,files).runBatch();
        verify(mapper).fail(eq(1L),anyString());verify(mapper).claim(eq(2L),anyString());verifyNoInteractions(analyzer);
        verify(mapper,never()).complete(any(),anyString(),any(),anyInt(),anyInt(),anyInt(),anyInt());
    }
    @Test void databaseOutageIsIsolatedFromThePublicReviewPath() {
        var mapper=mock(ReviewPhotoAnalysisMapper.class);when(mapper.expireExhausted(5)).thenThrow(new IllegalStateException("offline"));
        new ReviewPhotoAnalysisWorker(mapper,mock(ReviewPhotoAnalyzer.class),mock(FileUploadService.class)).runBatch();
        verify(mapper,never()).findDue(anyInt());
    }
}
