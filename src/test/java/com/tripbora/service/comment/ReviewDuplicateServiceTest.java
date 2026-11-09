package com.tripbora.service.comment;

import com.tripbora.dto.ReviewPhotoMatch;
import com.tripbora.repository.comment.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewDuplicateServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={2,5,35,100})
    void handlesWholeSelectedChunkWithoutTruncation(int count) {
        var rows=java.util.stream.LongStream.rangeClosed(1,count).mapToObj(id->photo(id,"PENDING")).toList();
        var states=new LinkedHashMap<Long,String>();rows.forEach(p->states.put(p.getId(),p.getGalleryStatus()));
        when(analysis.selection(eq(10L),anyList(),anyBoolean())).thenReturn(rows);
        service.keep(10L,1L,states,9L);
        verify(reviews).review(1L,9L,"APPROVE",null);
        verify(reviews,times(count-1)).review(anyLong(),eq(9L),eq("REJECT"),anyString());
        var order=inOrder(images,analysis,reviews);
        order.verify(analysis).selection(10L,new ArrayList<>(states.keySet()),false);
        for(long id=1;id<=count;id++)order.verify(images).lockUser(id);
        order.verify(images).lockDestination(10L);
        for(long id=1;id<=count;id++)order.verify(images).lockComment(id);
        order.verify(analysis).selection(10L,new ArrayList<>(states.keySet()),true);
        verify(images,never()).deleteById(anyLong());
    }
    @Test void springTransactionRollsBackWholeChunkOnMidwayConsentConflict() {
        var rows=List.of(photo(1,"PENDING"),photo(2,"PENDING"),photo(3,"PENDING"));
        when(analysis.selection(eq(10L),anyList(),anyBoolean())).thenReturn(rows);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"동의 철회"))
                .when(reviews).review(eq(3L),eq(9L),eq("REJECT"),anyString());
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction=mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        var proxy=new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(ReviewDuplicateService)proxy.getProxy();
        assertThatThrownBy(()->transactional.keep(10L,1L,Map.of(1L,"PENDING",2L,"PENDING",3L,"PENDING"),9L)).hasMessageContaining("동의 철회");
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel()
                == org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED));
    }
    final ReviewPhotoAnalysisMapper analysis = mock(ReviewPhotoAnalysisMapper.class);
    final DestinationCommentImageMapper images = mock(DestinationCommentImageMapper.class);
    final ReviewImageService reviews = mock(ReviewImageService.class);
    final ReviewDuplicateService service = new ReviewDuplicateService(analysis, images, reviews);
    ReviewPhotoMatch photo(long id, String status) {
        var p = new ReviewPhotoMatch(); p.setId(id); p.setUserId(id); p.setCommentId(id);
        p.setGalleryStatus(status); p.setSha256("a".repeat(64)); return p;
    }
    @Test void mixedStatusesUseExistingReviewAndNeverTouchInactivePhotos() {
        var rows=List.of(photo(1,"APPROVED"),photo(2,"PENDING"),photo(3,"APPROVED"),photo(4,"REJECTED"),photo(5,"REVOKED"),photo(6,"NONE"));
        var states=new LinkedHashMap<Long,String>(); rows.forEach(p->states.put(p.getId(),p.getGalleryStatus()));
        when(analysis.selection(10L,new ArrayList<>(states.keySet()),false)).thenReturn(rows);
        when(analysis.selection(10L,new ArrayList<>(states.keySet()),true)).thenReturn(rows);
        service.keep(10L,1L,states,9L);
        verify(reviews).review(1L,9L,"KEEP",null);
        verify(reviews).review(2L,9L,"REJECT","동일 파일 중복 · 대표 사진 #1 유지");
        verify(reviews).review(3L,9L,"REVOKE","동일 파일 중복 · 대표 사진 #1 유지");
        verifyNoMoreInteractions(reviews);
    }
    @Test void rejectsOversizedChunkBeforeAnyReads() {
        var states=new LinkedHashMap<Long,String>(); for(long i=1;i<=101;i++)states.put(i,"PENDING");
        assertThatThrownBy(()->service.keep(10L,1L,states,9L)).hasMessageContaining("100");
        verifyNoInteractions(analysis,images,reviews);
    }
    @Test void changedStatusOrUnrelatedHashCannotBeReviewed() {
        var rows=List.of(photo(1,"APPROVED"),photo(2,"APPROVED"));
        when(analysis.selection(eq(10L),anyList(),anyBoolean())).thenReturn(rows);
        assertThatThrownBy(()->service.keep(10L,1L,Map.of(1L,"APPROVED",2L,"PENDING"),9L)).hasMessageContaining("변경");
        rows.get(1).setSha256("b".repeat(64));
        assertThatThrownBy(()->service.keep(10L,1L,Map.of(1L,"APPROVED",2L,"APPROVED"),9L)).hasMessageContaining("동일");
        verifyNoInteractions(reviews);
    }
}
