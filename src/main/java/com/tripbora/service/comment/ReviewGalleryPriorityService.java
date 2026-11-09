package com.tripbora.service.comment;

import com.tripbora.model.Destination;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.DestinationCommentImage;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.comment.ReviewGalleryPriorityMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.policy.GalleryConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** 승인된 리뷰 사진의 갤러리 표시 우선순위만 변경한다. */
@Service
@RequiredArgsConstructor
public class ReviewGalleryPriorityService {
    private final DestinationCommentImageMapper images;
    private final DestinationCommentMapper comments;
    private final UserMapper users;
    private final ReviewGalleryPriorityMapper priorities;
    private final GalleryConsentService galleryConsentService;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void setPriority(Long destinationId, Long imageId, Long adminId, Integer priority,
                            long expectedVersion) {
        User admin = adminId == null ? null : users.findById(adminId);
        if (admin == null || admin.getUserRole() != UserRole.ADMIN || admin.getStatus() != UserStatus.ACTIVE)
            throw error(HttpStatus.FORBIDDEN, "관리자만 갤러리 우선순위를 변경할 수 있습니다.");
        if (destinationId == null || imageId == null || expectedVersion < 0
                || (priority != null && (priority < 1 || priority > 30)))
            throw error(HttpStatus.BAD_REQUEST, "우선순위는 1~30 또는 해제로 지정해 주세요.");

        DestinationCommentImage located = images.findById(imageId);
        if (located == null) throw error(HttpStatus.NOT_FOUND, "사진을 찾을 수 없습니다.");
        DestinationComment hint = comments.findById(located.getCommentId());
        if (hint == null || !Objects.equals(destinationId, hint.getDestinationId()))
            throw error(HttpStatus.NOT_FOUND, "여행지에 속한 사진을 찾을 수 없습니다.");

        // 기존 업로드·심사·동의 철회와 같은 순서를 사용한다. 동의도 회원 잠금 아래 재확인한다.
        User owner = images.lockUser(hint.getUserId());
        Destination destination = images.lockDestination(destinationId);
        DestinationComment comment = images.lockComment(hint.getId());
        if (comment == null || !Objects.equals(hint.getUserId(), comment.getUserId())
                || !Objects.equals(destinationId, comment.getDestinationId()))
            throw error(HttpStatus.CONFLICT, "댓글이 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
        DestinationCommentImage image = images.findByCommentIdForUpdate(comment.getId()).stream()
                .filter(attached -> Objects.equals(attached.getId(), imageId))
                .findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "댓글에 속한 사진을 찾을 수 없습니다."));
        galleryConsentService.requirePhotoValid(imageId);

        if (!"APPROVED".equals(image.getGalleryStatus())
                || owner == null || owner.getStatus() != UserStatus.ACTIVE
                || destination == null || !destination.isPublicVisible()
                || Boolean.TRUE.equals(comment.getDeleted()) || comment.isModerated()
                || comment.getParentCommentId() != null)
            throw error(HttpStatus.CONFLICT, "동의 또는 공개 상태가 변경되어 우선순위를 변경할 수 없습니다.");

        if (priorities.update(imageId, priority, expectedVersion) != 1)
            throw error(HttpStatus.CONFLICT, "사진 우선순위가 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
