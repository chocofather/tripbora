package com.tripbora.service.comment;

import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.comment.*;
import com.tripbora.repository.user.UserMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewPhotoWorkflowServiceTest {
    @Test void manualBatchAllowsUnanalyzedConsentingPendingApprovalEvenWithDuplicateCandidates() {
        var row=photo(1,"PENDING",0L);row.setAnalysisState("PENDING");row.setSha256(null);row.setDuplicate(true);stub(List.of(row));
        service.batchModerate(10L,"APPROVE",states(List.of(row)),9L);
        verify(reviews).review(1L,9L,"APPROVE",null);
    }
    @Test void manualBatchRejectsLegacyUnanalyzedPendingWithoutOwnerOrConsent() {
        var row=photo(1,"PENDING",0L);row.setAnalysisState("FAILED");row.setEligible(false);row.setEligibilityReason("PHOTO_CONSENT_MISSING");row.setUserId(null);stub(List.of(row));
        service.batchModerate(10L,"REJECT",states(List.of(row)),9L);
        verify(reviews).review(eq(1L),eq(9L),eq("REJECT"),argThat(reason->reason!=null && !reason.isBlank() && reason.length()<=500));
        verify(images,never()).lockUser(isNull());verify(images,never()).deleteById(anyLong());
    }
    @Test void manualBatchValidatesEveryApprovalAndCurrentStatusBeforeFirstWrite() {
        var first=photo(1,"PENDING",0L);var second=photo(2,"PENDING",0L);second.setEligible(false);second.setEligibilityReason("MEMBER_CONSENT_MISSING");stub(List.of(first,second));
        assertThatThrownBy(()->service.batchModerate(10L,"APPROVE",states(List.of(first,second)),9L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        second.setEligible(true);second.setEligibilityReason(null);second.setGalleryStatus("APPROVED");
        assertThatThrownBy(()->service.batchModerate(10L,"REJECT",Map.of(1L,"PENDING",2L,"PENDING"),9L)).hasMessageContaining("변경");
        second.setGalleryStatus("NONE");assertThatThrownBy(()->service.batchModerate(10L,"APPROVE",states(List.of(first,second)),9L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(reviews);
    }
    @Test void manualBatchAccepts100AndRefuses101WithoutAnyPartialProcessing() {
        var rows=java.util.stream.LongStream.rangeClosed(1,100).mapToObj(id->photo(id,"PENDING",0L)).toList();stub(rows);
        service.batchModerate(10L,"REJECT",states(rows),9L);verify(reviews,times(100)).review(anyLong(),eq(9L),eq("REJECT"),anyString());
        clearInvocations(reviews);var expected=new LinkedHashMap<>(states(rows));expected.put(101L,"PENDING");
        assertThatThrownBy(()->service.batchModerate(10L,"REJECT",expected,9L)).hasMessageContaining("100");verifyNoInteractions(reviews);
    }
    @Test void wholeGroupRejectionRejectsOnlyExplicitPendingChunkAndNeverRevokesApprovedAnchor() {
        var anchor=photo(1,"APPROVED",0L);anchor.setEligible(false);
        var rows=java.util.stream.LongStream.rangeClosed(2,5).mapToObj(id->{var p=photo(id,"PENDING",0L);p.setEligible(false);return p;}).toList();
        when(analysis.workflowSelection(eq(10L),anyList(),anyBoolean())).thenAnswer(invocation->{List<Long> ids=invocation.getArgument(1);return java.util.stream.Stream.concat(java.util.stream.Stream.of(anchor),rows.stream()).filter(p->ids.contains(p.getId())).toList();});
        service.rejectGroup(10L,1L,"exact",states(rows),9L);
        for(long id:List.of(2L,3L,4L,5L))verify(reviews).review(eq(id),eq(9L),eq("REJECT"),anyString());
        verify(reviews,never()).review(eq(1L),anyLong(),anyString(),any());
        clearInvocations(reviews);
        assertThatThrownBy(()->service.rejectGroup(10L,1L,"exact",Map.of(1L,"APPROVED",2L,"PENDING"),9L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(reviews);
    }
    @Test void wholeGroupRejectionChecksDirectRelationAndRequiresRealComparisonGroup() {
        var anchor=photo(1,"PENDING",0L);var near=photo(2,"PENDING",7L);near.setSha256("b".repeat(64));var far=photo(3,"PENDING",63L);far.setSha256("c".repeat(64));stub(List.of(anchor,near,far));
        assertThatThrownBy(()->service.rejectGroup(10L,1L,"similar",states(List.of(anchor,near,far)),9L)).hasMessageContaining("직접");
        stub(List.of(anchor));when(analysis.groupCounts(10L,1L,"exact")).thenReturn(Map.of("total",1L));
        assertThatThrownBy(()->service.rejectGroup(10L,1L,"exact",states(List.of(anchor)),9L)).hasMessageContaining("그룹");verifyNoInteractions(reviews);
    }
    @Test void similarFiveKeepTwoAndRejectThreeWithoutMergingTransitiveNeighbors() {
        var rows=new ArrayList<ReviewPhotoWorkflowPhoto>();for(int id=1;id<=5;id++){var p=photo(id,"PENDING",id==1?0L:1L<<(id-2));p.setSha256(String.valueOf((char)('a'+id)).repeat(64));rows.add(p);}stub(rows);
        service.decide(10L,1L,"similar",List.of(1L,2L),states(rows),9L);
        for(long id:List.of(1L,2L))verify(reviews).review(id,9L,"APPROVE",null);
        for(long id:List.of(3L,4L,5L))verify(reviews).review(eq(id),eq(9L),eq("REJECT"),anyString());
    }
    @Test void explicitWholeGroupRejectionProcessesFourPendingIncludingRestrictedAnchor() {
        var rows=java.util.stream.LongStream.rangeClosed(1,4).mapToObj(id->{var p=photo(id,"PENDING",0L);p.setEligible(false);p.setEligibilityReason("MEMBER_CONSENT_WITHDRAWN");return p;}).toList();stub(rows);
        service.rejectGroup(10L,1L,"exact",states(rows),9L);
        for(long id:List.of(1L,2L,3L,4L))verify(reviews).review(eq(id),eq(9L),eq("REJECT"),anyString());verifyNoMoreInteractions(reviews);
    }
    @Test void emptyGroupSelectionCannotImplicitlyRejectPendingPhotos() {
        assertThatThrownBy(()->service.decide(10L,1L,"exact",List.of(),Map.of(1L,"PENDING",2L,"PENDING"),9L)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(reviews);verify(analysis,never()).workflowSelection(anyLong(),anyList(),anyBoolean());
    }
    @Test void foreignDestinationAndChangedGroupStatusPreventEveryWrite() {
        var first=photo(1,"PENDING",0L);stub(List.of(first));
        assertThatThrownBy(()->service.batchModerate(10L,"REJECT",Map.of(1L,"PENDING",2L,"PENDING"),9L)).hasMessageContaining("소속");
        var second=photo(2,"APPROVED",0L);stub(List.of(first,second));
        assertThatThrownBy(()->service.rejectGroup(10L,1L,"exact",Map.of(1L,"PENDING",2L,"PENDING"),9L)).hasMessageContaining("변경");verifyNoInteractions(reviews);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"manual","rejectGroup","decide"})
    void eachModerationWorkflowRollsBackIfCurrentConsentOrWriteFailsMidChunk(String operation) {
        var rows=List.of(photo(1,"PENDING",0L),photo(2,"PENDING",0L));stub(rows);
        doThrow(new IllegalStateException("현재 동의 또는 상태 변경")).when(reviews).review(eq(2L),eq(9L),anyString(),any());
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx=mock(org.springframework.transaction.TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        var factory=new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy=(ReviewPhotoWorkflowService)factory.getProxy();
        assertThatThrownBy(()->{
            switch(operation) {
                case "manual" -> proxy.batchModerate(10L,"APPROVE",states(rows),9L);
                case "rejectGroup" -> proxy.rejectGroup(10L,1L,"exact",states(rows),9L);
                case "decide" -> proxy.decide(10L,1L,"exact",List.of(1L),states(rows),9L);
            }
        }).hasMessageContaining("현재 동의 또는 상태 변경");
        verify(reviews).review(eq(1L),eq(9L),anyString(),any());verify(manager).rollback(tx);verify(manager,never()).commit(any());
        verify(manager).getTransaction(argThat(definition->definition.getIsolationLevel()==org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED));
        verify(images,never()).deleteById(anyLong());
    }
    @Test void excludedIneligibleApprovedPhotoCanBeRevokedByTheGroupEndpoint() {
        var approved=photo(6,"APPROVED",0L);approved.setEligible(false);approved.setEligibilityReason("REVIEW_DELETED");
        var pending=photo(25,"PENDING",0L);stub(List.of(approved,pending));
        service.decide(10L,6L,"exact",List.of(25L),states(List.of(approved,pending)),9L);
        verify(reviews).review(6L,9L,"REVOKE","관리자 그룹 비교 심사에서 미선택");
        verify(reviews).review(25L,9L,"APPROVE",null);
    }
    @Test void referenceAnchorCanBeComparedWithoutBeingIncludedInAnyModerationAction() {
        var deleted=photo(6,"PENDING",0L);deleted.setEligible(false);deleted.setEligibilityReason("REVIEW_DELETED");
        var none=photo(25,"NONE",0L);none.setEligible(false);none.setEligibilityReason("PHOTO_CONSENT_MISSING");
        var pending=photo(30,"PENDING",0L);
        when(analysis.workflowSelection(eq(10L),anyList(),anyBoolean())).thenAnswer(invocation->{
            List<Long> ids=invocation.getArgument(1);return List.of(deleted,none,pending).stream().filter(p->ids.contains(p.getId())).toList();
        });
        service.decide(10L,6L,"exact",List.of(30L),Map.of(30L,"PENDING"),9L);
        verify(reviews).review(30L,9L,"APPROVE",null);verifyNoMoreInteractions(reviews);
    }
    @Test void referenceOnlyRequestsDoNotPretendToHaveCompletedModeration() {
        var rows=List.of(photo(6,"NONE",0L),photo(25,"REJECTED",0L));stub(rows);
        assertThatThrownBy(()->service.decide(10L,6L,"exact",List.of(),states(rows),9L)).hasMessageContaining("선택");
        verifyNoInteractions(reviews);
    }
    @Test void referenceAnchorWithoutAnOwnerDoesNotBlockAnotherOwnersEligiblePhoto() {
        var anchor=photo(6,"PENDING",0L);anchor.setUserId(null);anchor.setEligible(false);anchor.setEligibilityReason("MEMBER_RESTRICTED");
        var pending=photo(30,"PENDING",0L);
        when(analysis.workflowSelection(eq(10L),anyList(),anyBoolean())).thenAnswer(invocation->{List<Long> ids=invocation.getArgument(1);return List.of(anchor,pending).stream().filter(p->ids.contains(p.getId())).toList();});
        service.decide(10L,6L,"exact",List.of(30L),Map.of(30L,"PENDING"),9L);
        verify(images,never()).lockUser(isNull());verify(reviews).review(30L,9L,"APPROVE",null);verifyNoMoreInteractions(reviews);
        clearInvocations(reviews);
        service.decide(10L,6L,"exact",List.of(30L),Map.of(6L,"PENDING",30L,"PENDING"),9L);
        verify(reviews).review(6L,9L,"REJECT","관리자 그룹 비교 심사에서 미선택");
        verify(reviews).review(30L,9L,"APPROVE",null);
    }
    @Test void fourConsentingPendingPhotosApproveOneAndRejectThree() {
        var rows=List.of(photo(8,"PENDING",0L),photo(11,"PENDING",0L),photo(14,"PENDING",0L),photo(19,"PENDING",0L));
        stub(rows);service.decide(10L,8L,"exact",List.of(8L),states(rows),9L);
        verify(reviews).review(8L,9L,"APPROVE",null);
        for(long id:List.of(11L,14L,19L))verify(reviews).review(id,9L,"REJECT","관리자 그룹 비교 심사에서 미선택");
        verifyNoMoreInteractions(reviews);verify(images,never()).deleteById(anyLong());
    }
    @Test void ineligiblePendingCannotBeSelectedButMayBeExplicitlyExcludedForRejection() {
        for(String reason:List.of("PHOTO_CONSENT_MISSING","MEMBER_CONSENT_MISSING","MEMBER_CONSENT_WITHDRAWN","REVIEW_DELETED","REVIEW_HIDDEN","MEMBER_RESTRICTED","DESTINATION_PRIVATE")) {
            var row=photo(8,"PENDING",0L);row.setEligible(false);row.setEligibilityReason(reason);var selected=photo(9,"PENDING",0L);stub(List.of(row,selected));
            assertThatThrownBy(()->service.decide(10L,8L,"exact",List.of(8L),states(List.of(row,selected)),9L)).hasMessageContaining(row.getEligibilityMessage());
            service.decide(10L,8L,"exact",List.of(9L),states(List.of(row,selected)),9L);
        }
        verify(reviews,times(7)).review(8L,9L,"REJECT","관리자 그룹 비교 심사에서 미선택");
        verify(reviews,times(7)).review(9L,9L,"APPROVE",null);
    }
    final ReviewPhotoAnalysisMapper analysis=mock(ReviewPhotoAnalysisMapper.class);
    final DestinationCommentImageMapper images=mock(DestinationCommentImageMapper.class);
    final ReviewImageService reviews=mock(ReviewImageService.class);
    final UserMapper users=mock(UserMapper.class);
    final ReviewPhotoWorkflowService service=new ReviewPhotoWorkflowService(analysis,images,reviews,users);

    @BeforeEach void admin() {
        var admin=new User(); admin.setUserRole(UserRole.ADMIN); admin.setStatus(UserStatus.ACTIVE);
        when(users.findById(9L)).thenReturn(admin);
        when(analysis.groupCounts(eq(10L),anyLong(),anyString())).thenReturn(Map.of("total",2L));
    }

    @Test void photoPageUses101SentinelAndCountsWithoutMaterializingDestination() {
        when(analysis.workflowCounts(10L,"PENDING")).thenReturn(Map.of("clean",101L,"groups",5L,"limited",2L));
        var rows=java.util.stream.LongStream.rangeClosed(1,101).mapToObj(id->{var p=new ReviewImageAdminDto();p.setId(id);return p;}).toList();
        when(analysis.workflowPhotos(10L,"clean","PENDING",100L,101)).thenReturn(rows);
        var view=service.view(10L,"clean",null,1);
        assertThat(view.photos()).hasSize(100); assertThat(view.hasNext()).isTrue();
        assertThat(view.counts()).containsEntry("groups",5L);
        verify(analysis,never()).workflowGroups(anyLong(),anyString(),anyLong(),anyInt());
    }

    @Test void groupsUse21AnchorsAndOnePreviewBatch() {
        var rows=new ArrayList<ReviewPhotoWorkflowGroup>();
        for(long id=1;id<=21;id++){var g=new ReviewPhotoWorkflowGroup();g.setAnchorId(id);g.setKind("exact");g.setGroupKey("sha"+id);g.setTotal(5);rows.add(g);}
        when(analysis.workflowGroups(10L,"PENDING",0L,21)).thenReturn(rows);
        when(analysis.workflowCounts(10L,"PENDING")).thenReturn(Map.of());
        var preview=new ReviewPhotoWorkflowPreview();preview.setId(1L);preview.setAnchorId(1L);preview.setKind("exact");
        when(analysis.workflowPreviews(eq(10L),anyList())).thenReturn(List.of(preview));
        var view=service.view(10L,"groups","PENDING",0);
        assertThat(view.groups()).hasSize(20);assertThat(view.hasNext()).isTrue();
        assertThat(view.groups().get(0).photos()).extracting(ReviewPhotoMatch::getId).containsExactly(1L);
        verify(analysis).workflowPreviews(eq(10L),argThat(groups->groups.size()==20));
    }

    @Test void cleanChunkApprovesAll100AfterSortedOwnerDestinationCommentLocks() {
        var expected=new LinkedHashMap<Long,String>();var rows=new ArrayList<ReviewPhotoWorkflowPhoto>();
        for(long id=1;id<=100;id++){expected.put(id,"PENDING");rows.add(photo(id,"PENDING",0L));}
        when(analysis.workflowSelection(eq(10L),anyList(),anyBoolean())).thenReturn(rows);
        service.approveClean(10L,expected,9L);
        verify(reviews,times(100)).review(anyLong(),eq(9L),eq("APPROVE"),isNull());
        var order=inOrder(images,analysis,reviews);
        order.verify(analysis).workflowSelection(eq(10L),anyList(),eq(false));
        for(long id=1;id<=100;id++)order.verify(images).lockUser(id);
        order.verify(images).lockDestination(10L);
        for(long id=1;id<=100;id++)order.verify(images).lockComment(id);
        order.verify(analysis).workflowSelection(eq(10L),anyList(),eq(true));
    }

    @Test void groupDecisionsRetainManySelectedAndLeaveInactiveStatusesUntouched() {
        var rows=List.of(photo(1,"APPROVED",0L),photo(2,"PENDING",0L),photo(3,"PENDING",0L),
                photo(4,"APPROVED",0L),photo(5,"REJECTED",0L),photo(6,"NONE",0L));
        stub(rows);service.decide(10L,1L,"exact",List.of(1L,2L),states(rows),9L);
        verify(reviews).review(1L,9L,"KEEP",null);verify(reviews).review(2L,9L,"APPROVE",null);
        verify(reviews).review(3L,9L,"REJECT","관리자 그룹 비교 심사에서 미선택");
        verify(reviews).review(4L,9L,"REVOKE","관리자 그룹 비교 심사에서 미선택");
        verifyNoMoreInteractions(reviews);
    }

    @Test void similarRelationMustBeDirectToAnchorAndAnchorIsLockedOutsideChunk() {
        var anchor=photo(1,"APPROVED",0L);var near=photo(2,"PENDING",7L);var transitive=photo(3,"PENDING",63L);
        near.setSha256("b".repeat(64));transitive.setSha256("c".repeat(64));
        stub(List.of(anchor,near,transitive));
        assertThatThrownBy(()->service.decide(10L,1L,"similar",List.of(2L),Map.of(2L,"PENDING",3L,"PENDING"),9L)).hasMessageContaining("직접");
        verify(images).lockUser(1L);verify(images).lockComment(1L);verifyNoInteractions(reviews);
        stub(List.of(anchor,near));service.decide(10L,1L,"similar",List.of(2L),Map.of(2L,"PENDING"),9L);
        verify(reviews).review(2L,9L,"APPROVE",null);verify(reviews,never()).review(eq(1L),anyLong(),anyString(),any());
    }

    @Test void rejectsChangedStatusMovedMetadataAndNewDuplicatesBeforeAnyWrite() {
        var row=photo(1,"PENDING",0L);stub(List.of(row));
        row.setDuplicate(true);
        assertThatThrownBy(()->service.approveClean(10L,Map.of(1L,"PENDING"),9L)).hasMessageContaining("변경");
        row.setDuplicate(false);row.setAnalysisDestinationId(11L);
        assertThatThrownBy(()->service.approveClean(10L,Map.of(1L,"PENDING"),9L)).hasMessageContaining("변경");
        row.setAnalysisDestinationId(10L);row.setGalleryStatus("APPROVED");
        assertThatThrownBy(()->service.approveClean(10L,Map.of(1L,"PENDING"),9L)).hasMessageContaining("변경");
        verifyNoInteractions(reviews);
    }

    @Test void corruptHashBandsCannotHideDuplicateCandidatesFromCleanApproval() {
        var row=photo(1,"PENDING",7L);row.setHashBand0(99);stub(List.of(row));
        assertThatThrownBy(()->service.approveClean(10L,Map.of(1L,"PENDING"),9L)).hasMessageContaining("변경");
        verifyNoInteractions(reviews);
    }

    @Test void rejects101DuplicateSelectionsAndInvalidSelectedStatuses() {
        var expected=new HashMap<Long,String>();for(long id=1;id<=101;id++)expected.put(id,"PENDING");
        assertThatThrownBy(()->service.approveClean(10L,expected,9L)).hasMessageContaining("100");
        assertThatThrownBy(()->service.decide(10L,1L,"exact",List.of(1L,1L),Map.of(1L,"PENDING"),9L)).hasMessageContaining("중복");
        var rows=List.of(photo(1,"REJECTED",0L));stub(rows);
        assertThatThrownBy(()->service.decide(10L,1L,"exact",List.of(1L),states(rows),9L)).hasMessageContaining("선택");
        verifyNoInteractions(reviews);
    }

    @Test void zeroSelectedStillRequiresAnActiveAdministrator() {
        when(users.findById(9L)).thenReturn(null);
        assertThatThrownBy(()->service.decide(10L,1L,"exact",List.of(),Map.of(1L,"NONE"),9L)).hasMessageContaining("관리자");
        verifyNoInteractions(reviews);
    }

    @Test void aLonePhotoCannotMasqueradeAsAComparisonGroup() {
        var rows=List.of(photo(1,"PENDING",0L));stub(rows);
        when(analysis.groupCounts(10L,1L,"exact")).thenReturn(Map.of("total",1L));
        assertThatThrownBy(()->service.decide(10L,1L,"exact",List.of(1L),states(rows),9L)).hasMessageContaining("그룹");
        verifyNoInteractions(reviews);
    }

    @Test void hashOnlyMayBeComparedExactlyButCannotEnterCleanOrSimilarApproval() {
        var rows=List.of(photo(1,"PENDING",0L),photo(2,"PENDING",0L));rows.get(1).setAnalysisState("HASH_ONLY");stub(rows);
        assertThatThrownBy(()->service.approveClean(10L,states(rows),9L)).hasMessageContaining("변경");
        assertThatThrownBy(()->service.decide(10L,1L,"similar",List.of(1L),states(rows),9L)).hasMessageContaining("직접");
        verifyNoInteractions(reviews);
        service.decide(10L,1L,"exact",List.of(1L),states(rows),9L);
        verify(reviews).review(1L,9L,"APPROVE",null);
        verify(reviews).review(2L,9L,"REJECT","관리자 그룹 비교 심사에서 미선택");
    }

    @Test void transactionRollsBackMidChunkFailure() {
        var rows=List.of(photo(1,"PENDING",0L),photo(2,"PENDING",0L));stub(rows);
        doThrow(new IllegalStateException("동의 변경")).when(reviews).review(2L,9L,"APPROVE",null);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx=mock(org.springframework.transaction.TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        var factory=new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy=(ReviewPhotoWorkflowService)factory.getProxy();
        assertThatThrownBy(()->proxy.approveClean(10L,states(rows),9L)).hasMessageContaining("동의 변경");
        verify(manager).rollback(tx);verify(manager,never()).commit(any());
    }

    void stub(List<ReviewPhotoWorkflowPhoto> rows){when(analysis.workflowSelection(eq(10L),anyList(),anyBoolean())).thenReturn(rows);}
    Map<Long,String> states(List<ReviewPhotoWorkflowPhoto> rows){var result=new LinkedHashMap<Long,String>();rows.forEach(p->result.put(p.getId(),p.getGalleryStatus()));return result;}
    ReviewPhotoWorkflowPhoto photo(long id,String status,long hash){
        var p=new ReviewPhotoWorkflowPhoto();p.setId(id);p.setUserId(id);p.setCommentId(id);p.setGalleryStatus(status);
        p.setSha256("a".repeat(64));p.setAnalysisState("READY");p.setAnalysisDestinationId(10L);
        p.setPerceptualHash(hash);p.setHashBand0((int)(hash&65535));p.setHashBand1(0);p.setHashBand2(0);p.setHashBand3(0);p.setEligible(true);return p;
    }
}
