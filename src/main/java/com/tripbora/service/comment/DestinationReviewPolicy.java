package com.tripbora.service.comment;

import com.tripbora.model.DestinationComment;
import com.tripbora.model.UserStatus;
import com.tripbora.model.UserRole;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 호출 Service의 트랜잭션 안에서 사용한다. 사진 편집과 같은 잠금 순서를 유지한다. */
@RequiredArgsConstructor
public final class DestinationReviewPolicy {
    private final DestinationCommentMapper comments;
    private final DestinationCommentImageMapper images;

    void lockWriter(Long userId, Long destinationId) {
        var user = images.lockUser(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE)
            throw error(HttpStatus.FORBIDDEN, "활성 회원만 리뷰를 작성하거나 수정할 수 있습니다.");
        var destination = images.lockDestination(destinationId);
        if (destination == null || (!destination.isPublicVisible() && user.getUserRole() != UserRole.ADMIN))
            throw error(HttpStatus.NOT_FOUND, "여행지를 찾을 수 없습니다.");
    }

    void create(Long userId, Long destinationId, Long parentId, Integer rating, String content) {
        validateContent(content);
        validateRating(rating);
        if (parentId == null && rating == null)
            throw error(HttpStatus.BAD_REQUEST, "리뷰 별점을 1~5점 중 선택해 주세요.");
        if (parentId != null && rating != null)
            throw error(HttpStatus.BAD_REQUEST, "답글에는 별점을 입력할 수 없습니다.");
        lockWriter(userId, destinationId);
        if (parentId == null) {
            requireNoOtherReview(userId, destinationId, null);
        } else {
            var parent = images.lockComment(parentId);
            if (parent == null || !destinationId.equals(parent.getDestinationId())
                    || parent.getParentCommentId() != null || Boolean.TRUE.equals(parent.getDeleted())
                    || parent.isModerated())
                throw error(HttpStatus.BAD_REQUEST, "같은 여행지의 공개 최상위 리뷰에만 답글을 작성할 수 있습니다.");
        }
    }

    void edit(DestinationComment comment, Integer rating, boolean specified, String content) {
        validateContent(content);
        if (Boolean.TRUE.equals(comment.getDeleted()) || comment.isModerated())
            throw error(HttpStatus.CONFLICT, "삭제되거나 숨김 처리된 리뷰는 수정할 수 없습니다.");
        if (!specified) return; // 과거 NULL 및 기존 별점 모두 보존
        validateRating(rating);
        if (comment.getParentCommentId() != null && rating != null)
            throw error(HttpStatus.BAD_REQUEST, "답글에는 별점을 입력할 수 없습니다.");
        if (comment.getRating() != null && rating == null)
            throw error(HttpStatus.BAD_REQUEST, "작성한 리뷰의 별점을 제거할 수 없습니다.");
        if (rating != null) requireNoOtherReview(comment.getUserId(), comment.getDestinationId(), comment.getId());
        comment.setRating(rating);
    }

    private void requireNoOtherReview(Long userId, Long destinationId, Long excludeId) {
        Long existingId = comments.findBlockingReviewId(userId, destinationId, excludeId);
        if (existingId != null)
            throw error(HttpStatus.CONFLICT, "이미 별점 리뷰를 작성했습니다. 기존 리뷰를 수정해 주세요. (리뷰 #" + existingId + ")");
    }

    public static Integer parseRating(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[1-5]")) throw error(HttpStatus.BAD_REQUEST, "별점은 1~5점만 입력할 수 있습니다.");
        return Integer.valueOf(value);
    }

    private static void validateRating(Integer rating) {
        if (rating != null && (rating < 1 || rating > 5))
            throw error(HttpStatus.BAD_REQUEST, "별점은 1~5점만 입력할 수 있습니다.");
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > 2000)
            throw error(HttpStatus.BAD_REQUEST, "리뷰 본문은 1~2000자로 입력해 주세요.");
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
