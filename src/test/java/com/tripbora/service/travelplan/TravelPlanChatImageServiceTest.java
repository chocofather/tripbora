package com.tripbora.service.travelplan;

import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import com.tripbora.service.file.TravelPlanChatImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import java.security.Principal;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TravelPlanChatImageServiceTest {
    final TravelPlanRoomAccess access = mock(TravelPlanRoomAccess.class);
    final TravelPlanMapper plans = mock(TravelPlanMapper.class);
    final TravelPlanChatMapper messages = mock(TravelPlanChatMapper.class);
    final TravelPlanChatImageMapper images = mock(TravelPlanChatImageMapper.class);
    final TravelPlanChatImageStorage storage = mock(TravelPlanChatImageStorage.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final Principal principal = () -> "member";
    final String request = UUID.randomUUID().toString();
    final MockMultipartFile file = new MockMultipartFile("images", "a.png", "image/png", new byte[]{1});
    TravelPlanChatImageService service;
    @BeforeEach void setup() {
        service = new TravelPlanChatImageService(access, plans, messages, images, storage, events);
        TravelPlanMember member = new TravelPlanMember(); member.setId(11L); member.setDisplayName("여행자");
        member.setJoinedAt(Timestamp.valueOf("2026-10-19 10:00:00"));
        when(access.findActiveMember(principal, 42L)).thenReturn(Optional.of(member));
        when(plans.findPlanByIdAndStatusForUpdate(42L, "ACTIVE")).thenReturn(new TravelPlan());
        when(storage.store(42L, file)).thenReturn(new TravelPlanChatImageStorage.StoredChatImage("42/image.png", "image/png", 1L, 10, 20));
        when(messages.insertMessage(any())).thenAnswer(invocation -> {
            TravelPlanChatMessage message = invocation.getArgument(0);message.setId(9L);return 1;
        });
        TravelPlanChatMessage persisted = new TravelPlanChatMessage();persisted.setCreatedAt(Timestamp.valueOf("2026-10-19 11:00:00"));
        when(messages.findByIdAndPlanId(9L,42L)).thenReturn(persisted);
        when(images.insert(any())).thenAnswer(invocation -> {((TravelPlanChatImage)invocation.getArgument(0)).setId(7L);return 1;});
    }
    @Test void imageOnlyMessageIsSavedAndPublishedWithControlledUrl() {
        var result = service.send(principal, 42L, "", request, List.of(file));
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().get(0).url()).isEqualTo("/travel-plans/42/chat/messages/9/images/7");
        assertThat(result.memberId()).isEqualTo(11L);
        verify(events).publishEvent(any(TravelPlanChatChangedEvent.class));
        var order = inOrder(plans,access,storage);
        order.verify(plans).findPlanByIdAndStatusForUpdate(42L,"ACTIVE");
        order.verify(access).findActiveMember(principal,42L);
        order.verify(storage).store(42L,file);
    }
    @Test void databaseRollbackAfterSuccessfulMethodAlsoRemovesTheFile() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.send(principal,42L,"숙소 사진",request,List.of(file));
            verify(storage,never()).delete(anyString());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(storage).delete("42/image.png");
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void fivePhotosAndTextRemainOneMessageInFileOrder() {
        final int[] sequence={0};
        when(storage.store(42L,file)).thenAnswer(call -> new TravelPlanChatImageStorage.StoredChatImage(
                "42/"+(++sequence[0])+".png","image/png",1L,10,20));
        var result=service.send(principal,42L,"숙소 후보",request,Collections.nCopies(5,file));
        assertThat(result.content()).isEqualTo("숙소 후보");
        assertThat(result.images()).hasSize(5);
        verify(messages,times(1)).insertMessage(any());
        var rows=org.mockito.ArgumentCaptor.forClass(TravelPlanChatImage.class);
        verify(images,times(5)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(TravelPlanChatImage::getSortOrder).containsExactly(0,1,2,3,4);
    }
    @Test void repeatedRequestReturnsExistingMessageWithoutSavingFilesOrPublishingAgain() {
        TravelPlanChatMessage previous = new TravelPlanChatMessage();previous.setId(9L);
        when(messages.findByRequestId(42L,11L,request)).thenReturn(previous);
        when(images.findByMessageIds(List.of(9L))).thenReturn(List.of());
        assertThat(service.send(principal,42L,"",request,List.of(file)).id()).isEqualTo(9L);
        verifyNoInteractions(storage,events);
        verify(messages,never()).insertMessage(any());
    }
    @Test void nonMemberCannotWriteOrReadAnyImage() {
        when(access.findActiveMember(principal,42L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.send(principal,42L,"",request,List.of(file))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.image(principal,42L,9L,7L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(storage);
    }
    @Test void rejectsTooManyFilesBeforeStoringAnything() {
        assertThatThrownBy(()->service.send(principal,42L,"",request,Collections.nCopies(6,file)))
                .isInstanceOf(TravelPlanValidationException.class);
        verifyNoInteractions(storage);
    }
    @Test void metadataFailureCleansAllWrittenFilesAndDoesNotPublish() {
        doReturn(0).when(images).insert(any());
        assertThatThrownBy(()->service.send(principal,42L,"숙소",request,List.of(file)))
                .isInstanceOf(TravelPlanValidationException.class);
        verify(storage).delete("42/image.png");verifyNoInteractions(events);
    }
    @Test void cannotReadDeletedOrPreJoinOrWrongMessageImage() {
        TravelPlanChatMessage message = new TravelPlanChatMessage();message.setId(9L);
        message.setCreatedAt(Timestamp.valueOf("2026-10-19 09:00:00"));
        when(messages.findByIdAndPlanId(9L,42L)).thenReturn(message);
        assertThatThrownBy(()->service.image(principal,42L,9L,7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        message.setCreatedAt(Timestamp.valueOf("2026-10-19 11:00:00"));message.setDeletedAt(new Timestamp(1));
        assertThatThrownBy(()->service.image(principal,42L,9L,7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        message.setDeletedAt(null);
        assertThatThrownBy(()->service.image(principal,42L,9L,7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(storage);
    }
    @Test void anotherActiveParticipantCanReadAnImageVisibleSinceJoining() {
        TravelPlanMember other=new TravelPlanMember();other.setId(12L);other.setRole(TravelPlanRole.MEMBER);
        other.setJoinedAt(Timestamp.valueOf("2026-10-19 10:00:00"));
        when(access.findActiveMember(principal,42L)).thenReturn(Optional.of(other));
        TravelPlanChatImage image=new TravelPlanChatImage();image.setId(7L);image.setMessageId(9L);
        image.setStorageKey("42/image.png");image.setContentType("image/png");
        when(images.findByIdAndMessage(7L,9L)).thenReturn(image);
        java.nio.file.Path path=java.nio.file.Path.of("private-image.png");
        when(storage.read("42/image.png")).thenReturn(path);
        assertThat(service.image(principal,42L,9L,7L).path()).isEqualTo(path);
    }
    @Test void laterUploadFailureRemovesEarlierFilesWithoutCreatingAMessage() {
        var second=new MockMultipartFile("images","b.png","image/png",new byte[]{2});
        when(storage.store(42L,second)).thenThrow(new IllegalArgumentException("잘못된 이미지"));
        assertThatThrownBy(()->service.send(principal,42L,"",request,List.of(file,second)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage).delete("42/image.png");verify(messages,never()).insertMessage(any());
        verifyNoInteractions(events);
    }
    @Test void deletedMessageDtosNeverContainImageMetadata() {
        TravelPlanChatMessage message=new TravelPlanChatMessage();message.setId(9L);
        message.setContent("숨길 본문");message.setDeletedAt(new Timestamp(1));
        message.setImages(List.of(new com.tripbora.dto.TravelPlanChatImageDto(7L,"private-url",10,10)));
        assertThat(com.tripbora.dto.TravelPlanChatMessageDto.of(message).images()).isEmpty();
        assertThat(com.tripbora.dto.TravelPlanChatTimelineItemDto.ofMessage(message).images()).isEmpty();
    }
}
