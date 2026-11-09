package com.tripbora.service.comment;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.dto.ReviewImageAdminDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoAdminServiceTest {
    @Test void storedAnalysisFilterStillFetchesOnlyFiftyPhotosWithoutFileReads() {
        var row=new com.tripbora.dto.ReviewDestinationAdminDto();row.setDestinationId(10L);
        when(mapper.findAdminDestinationMetadata(List.of(10L))).thenReturn(List.of(row));
        when(mapper.findAdminDestinationStatistics(List.of(10L))).thenReturn(List.of());
        when(mapper.findAdminDestinationPhotoPageFiltered(10L,"ALL","latest","duplicates",1000,51)).thenReturn(List.of());
        assertThat(service.destination(10L,"ALL","latest","duplicates",20).photos()).isEmpty();
        verify(mapper).findAdminDestinationPhotoPageFiltered(10L,"ALL","latest","duplicates",1000,51);
        verify(mapper,never()).findAdminDestinationPhotoPage(any(),any(),any(),anyLong(),anyInt());
    }
    @Test void destinationPageKeepsSameNamesSeparateAndOnlyLoadsTwentyStatistics() {
        var ids=java.util.stream.LongStream.rangeClosed(1,21).boxed().toList();
        when(mapper.findAdminDestinationIds("산굼부리","pending",20,21)).thenReturn(ids);
        var first=new com.tripbora.dto.ReviewDestinationAdminDto(); first.setDestinationId(1L);first.setDestinationName("같은 이름");
        var second=new com.tripbora.dto.ReviewDestinationAdminDto(); second.setDestinationId(2L);second.setDestinationName("같은 이름");
        var stats=new com.tripbora.dto.ReviewDestinationAdminDto();stats.setDestinationId(1L);stats.setTotal(13);stats.setPending(5);stats.setApproved(4);stats.setRejected(2);stats.setRevoked(1);stats.setNone(1);stats.setReviewCount(3);
        when(mapper.findAdminDestinationMetadata(ids.subList(0,20))).thenReturn(List.of(second,first));
        when(mapper.findAdminDestinationStatistics(ids.subList(0,20))).thenReturn(List.of(stats));
        var result=service.destinations("산굼부리","pending",1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.destinations()).extracting(com.tripbora.dto.ReviewDestinationAdminDto::getDestinationId).containsExactly(1L,2L);
        assertThat(result.destinations().get(0).getTotal()).isEqualTo(13);
        assertThat(result.destinations().get(0).getReviewCount()).isEqualTo(3);
        verify(mapper).findAdminDestinationStatistics(ids.subList(0,20));
        verify(mapper,never()).findAdminReviewPhotos(anyList());
    }
    @Test void detailPagesFiftyPhotosWithoutReviewGrouping() {
        var row=new com.tripbora.dto.ReviewDestinationAdminDto();row.setDestinationId(10L);
        when(mapper.findAdminDestinationMetadata(List.of(10L))).thenReturn(List.of(row));
        when(mapper.findAdminDestinationStatistics(List.of(10L))).thenReturn(List.of());
        var photos=java.util.stream.LongStream.rangeClosed(1,51).mapToObj(id->{var p=new ReviewImageAdminDto();p.setId(id);p.setCommentId(id%10);return p;}).toList();
        when(mapper.findAdminDestinationPhotoPage(10L,"PENDING","latest",100,51)).thenReturn(photos);
        var result=service.destination(10L,"PENDING","latest",2);
        assertThat(result.hasNext()).isTrue();assertThat(result.photos()).hasSize(50);
        assertThat(result.photos()).extracting(ReviewImageAdminDto::getId).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,50).boxed().toList());
        verify(mapper).findAdminDestinationPhotoPage(10L,"PENDING","latest",100,51);
    }
    @Test void photoCountsOneFiveAndThirtyFiveAreIndependentOfReviewCount() {
        var row=new com.tripbora.dto.ReviewDestinationAdminDto();row.setDestinationId(10L);
        when(mapper.findAdminDestinationMetadata(List.of(10L))).thenReturn(List.of(row));
        when(mapper.findAdminDestinationStatistics(List.of(10L))).thenReturn(List.of());
        for(int count:new int[]{0,1,5,35,50}) {
            var photos=java.util.stream.LongStream.range(0,count).mapToObj(id->{var p=new ReviewImageAdminDto();p.setId(id);p.setCommentId(id%10);p.setUserId(id%10);return p;}).toList();
            when(mapper.findAdminDestinationPhotoPage(10L,"ALL","oldest",0,51)).thenReturn(photos);
            var result=service.destination(10L,"ALL","oldest",0);
            assertThat(result.photos()).containsExactlyElementsOf(photos);assertThat(result.hasNext()).isFalse();
        }
    }
    @Test void foreignDestinationSelectionNeverReachesModeration() {
        when(mapper.countAdminDestinationSelection(10L,List.of(1L,2L))).thenReturn(1L);
        assertThatThrownBy(()->service.batchForDestination(10L,List.of(1L,2L),9L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(reviews);
    }
    @Test void moreThanHundredPhotosAreReadInBoundedPagesWithoutLoss() {
        var row=new com.tripbora.dto.ReviewDestinationAdminDto();row.setDestinationId(10L);
        when(mapper.findAdminDestinationMetadata(List.of(10L))).thenReturn(List.of(row));
        when(mapper.findAdminDestinationStatistics(List.of(10L))).thenReturn(List.of());
        var all=java.util.stream.LongStream.rangeClosed(1,137).mapToObj(id->{var p=new ReviewImageAdminDto();p.setId(id);p.setCommentId(id%10);return p;}).toList();
        var read=new ArrayList<Long>();
        for(int page=0;page<3;page++) {
            int start=page*50;
            when(mapper.findAdminDestinationPhotoPage(10L,"ALL","oldest",start,51)).thenReturn(all.subList(start,Math.min(start+51,all.size())));
            var result=service.destination(10L,"ALL","oldest",page);
            read.addAll(result.photos().stream().map(ReviewImageAdminDto::getId).toList());
            assertThat(result.hasNext()).isEqualTo(page<2);
        }
        assertThat(read).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,137).boxed().toList());
    }
    @Test void scopedBatchReusesIndependentModeration() {
        when(mapper.countAdminDestinationSelection(10L,List.of(1L,2L))).thenReturn(2L);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(reviews).review(2L,9L,"APPROVE",null);
        assertThat(service.batchForDestination(10L,List.of(1L,2L),9L,"APPROVE",null)).isEqualTo(new ReviewPhotoAdminService.Result(1,1));
    }
    DestinationCommentImageMapper mapper=mock(DestinationCommentImageMapper.class);
    ReviewImageService reviews=mock(ReviewImageService.class);
    ReviewPhotoAdminService service=new ReviewPhotoAdminService(mapper,reviews);
    @Test void groupedPageFetchesPhotosOnceAndDoesNotSplitReviews() {
        when(mapper.findAdminReviewIds("PENDING","Paris",0,21)).thenReturn(java.util.stream.LongStream.rangeClosed(1,21).boxed().toList());
        var photos=new ArrayList<ReviewImageAdminDto>();
        for(long review=1;review<=20;review++)for(int i=0;i<5;i++){var p=new ReviewImageAdminDto();p.setCommentId(review);p.setGalleryStatus(i==0?"PENDING":"APPROVED");photos.add(p);}
        when(mapper.findAdminReviewPhotos(anyList())).thenReturn(photos);
        when(mapper.countAdminStatuses("Paris")).thenReturn(List.of());
        var result=service.list("PENDING","Paris",0);
        assertThat(result.groups()).hasSize(20); assertThat(result.hasNext()).isTrue();
        assertThat(result.groups().get(0).count("PENDING")).isEqualTo(1);
        assertThat(result.groups().get(0).count("APPROVED")).isEqualTo(4);
        verify(mapper,times(1)).findAdminReviewPhotos(anyList());
    }
    @Test void batchCountsIndependentResultsAndLimitsToExplicitIds() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(reviews).review(2L,9L,"APPROVE",null);
        assertThat(service.batch(List.of(1L,2L),9L,"APPROVE",null)).isEqualTo(new ReviewPhotoAdminService.Result(1,1));
        verify(reviews).review(1L,9L,"APPROVE",null); verify(reviews).review(2L,9L,"APPROVE",null); verifyNoMoreInteractions(reviews);
    }
    @Test void duplicateOversizedAndMissingReasonAreRejectedBeforeWrites() {
        assertThatThrownBy(()->service.batch(List.of(1L,1L),9L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.batch(java.util.stream.LongStream.rangeClosed(1,101).boxed().toList(),9L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.batch(List.of(1L),9L,"REJECT"," ")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(reviews);
    }
    @Test void authorizationFailureIsNotReportedAsSuccess() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(reviews).review(1L,9L,"APPROVE",null);
        assertThatThrownBy(()->service.batch(List.of(1L),9L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
    }
}
