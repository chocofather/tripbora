package com.tripbora.controller.comment;

import com.tripbora.controller.destination.DestinationCommentController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** 기존 fetch 작성·수정 화면이 서버 검증 사유를 표시할 수 있도록 한다. */
@RestControllerAdvice(assignableTypes = {DestinationCommentController.class, CommentEditController.class,
        GalleryConsentController.class, DestinationCommentImageController.class})
public class ReviewWriteExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalidReview(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "리뷰를 저장할 수 없습니다." : exception.getReason()));
    }
}
