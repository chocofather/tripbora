package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanChatEventDto;
import com.tripbora.dto.TravelPlanChatImageDto;
import com.tripbora.dto.TravelPlanChatMessageDto;
import com.tripbora.model.*;
import com.tripbora.repository.travelplan.*;
import com.tripbora.service.file.TravelPlanChatImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.security.Principal;
import java.sql.Timestamp;
import java.util.*;

/** 사진과 텍스트를 한 메시지로 저장한다. 업로드만 성공한 공개 URL은 발급하지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPlanChatImageService {
    private final TravelPlanRoomAccess access;
    private final TravelPlanMapper plans;
    private final TravelPlanChatMapper messages;
    private final TravelPlanChatImageMapper images;
    private final TravelPlanChatImageStorage storage;
    private final ApplicationEventPublisher events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TravelPlanChatMessageDto send(Principal principal, Long planId, String content,
                                         String requestId, List<MultipartFile> files) {
        // 파일 처리 전, 방 잠금을 잡은 뒤 처음 권한을 읽어 MyBatis 세션 캐시 재사용도 피한다.
        if (files == null || files.isEmpty() || files.size() > 5) {
            throw invalid("사진은 메시지당 1~5장까지 첨부할 수 있어요.");
        }
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("전송 정보를 확인하지 못했어요. 창을 다시 열어 주세요.");
        }
        String text = content == null ? "" : content.strip();
        if (text.length() > TravelPlanChatService.MAX_CONTENT_LENGTH) throw invalid("메시지는 2000자까지 보낼 수 있어요.");
        // 방 삭제/완료 및 같은 방의 중복 전송을 같은 잠금 순서로 처리한다.
        if (plans.findPlanByIdAndStatusForUpdate(planId, TravelPlanStatus.ACTIVE.name()) == null) throw denied();
        TravelPlanMember sender = member(principal, planId);
        TravelPlanChatMessage existing = messages.findByRequestId(planId, sender.getId(), requestId);
        if (existing != null) {
            if (existing.getDeletedAt() == null) existing.setImages(images.findByMessageIds(List.of(existing.getId()))
                    .stream().map(image -> TravelPlanChatImageDto.of(planId,image)).toList());
            return TravelPlanChatMessageDto.of(existing);
        }

        List<String> written = new ArrayList<>();
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) cleanup(written);
            }
        });
        try {
            List<TravelPlanChatImageStorage.StoredChatImage> stored = new ArrayList<>();
            for (MultipartFile file : files) {
                var saved = storage.store(planId, file);
                written.add(saved.storageKey());stored.add(saved);
            }
            TravelPlanChatMessage message = new TravelPlanChatMessage();
            message.setTravelPlanId(planId);message.setSenderMemberId(sender.getId());
            message.setSenderDisplayName(sender.getDisplayName());message.setMessageType(TravelPlanChatMessageType.USER);
            message.setContent(text);message.setClientRequestId(requestId);
            if (messages.insertMessage(message) != 1 || message.getId() == null) throw invalid("메시지를 저장하지 못했어요.");
            List<TravelPlanChatImageDto> attachments = new ArrayList<>();
            for (int index=0;index<stored.size();index++) {
                var saved = stored.get(index);
                TravelPlanChatImage image = new TravelPlanChatImage();
                image.setMessageId(message.getId());image.setStorageKey(saved.storageKey());
                image.setContentType(saved.contentType());image.setByteSize(saved.byteSize());
                image.setWidth(saved.width());image.setHeight(saved.height());image.setSortOrder(index);
                if (images.insert(image) != 1 || image.getId() == null) throw invalid("사진 정보를 저장하지 못했어요.");
                attachments.add(TravelPlanChatImageDto.of(planId,image));
            }
            // created_at은 실제 DB 저장 시각을 사용한다(클라이언트 시각을 신뢰하지 않는다).
            TravelPlanChatMessage persisted = messages.findByIdAndPlanId(message.getId(),planId);
            if (persisted == null || persisted.getCreatedAt() == null) throw invalid("저장된 메시지를 확인하지 못했어요.");
            message.setCreatedAt(persisted.getCreatedAt());
            message.setImages(List.copyOf(attachments));
            TravelPlanChatMessageDto dto = TravelPlanChatMessageDto.of(message);
            events.publishEvent(new TravelPlanChatChangedEvent(planId,TravelPlanChatEventDto.created(dto)));
            return dto;
        } catch (RuntimeException exception) {
            if (!synchronizedTransaction) cleanup(written);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ImageContent image(Principal principal, Long planId, Long messageId, Long imageId) {
        TravelPlanMember viewer = member(principal,planId);
        TravelPlanChatMessage message = messages.findByIdAndPlanId(messageId,planId);
        Timestamp since = viewer.getJoinedAt() != null ? viewer.getJoinedAt() : viewer.getCreatedAt();
        if (message == null || message.getDeletedAt() != null
                || (since != null && (message.getCreatedAt() == null || message.getCreatedAt().before(since)))) throw notFound();
        TravelPlanChatImage image = images.findByIdAndMessage(imageId,messageId);
        if (image == null) throw notFound();
        Path path = storage.read(image.getStorageKey());
        if (path == null) throw notFound();
        return new ImageContent(path,image.getContentType());
    }

    private TravelPlanMember member(Principal principal, Long planId) {
        return access.findActiveMember(principal,planId).orElseThrow(this::denied);
    }
    private void cleanup(List<String> keys) {
        for (String key : keys) try { storage.delete(key); }
        catch (RuntimeException exception) { log.warn("채팅 사진 정리를 주기 작업에서 다시 시도합니다."); }
    }
    private TravelPlanValidationException invalid(String message) { return new TravelPlanValidationException("images",message); }
    private AccessDeniedException denied() { return new AccessDeniedException("여행계획에 참여 중이 아닙니다."); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    public record ImageContent(Path path, String contentType) {}
}
