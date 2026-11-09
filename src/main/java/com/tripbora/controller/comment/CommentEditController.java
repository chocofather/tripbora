package com.tripbora.controller.comment;

import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import com.tripbora.service.file.UnsupportedImageFormatException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CommentEditController {
    private final ReviewImageService reviewImageService;

    @PutMapping(path = "/comments/{commentId}/with-images", consumes = "multipart/form-data")
    public ResponseEntity<Void> edit(@PathVariable Long commentId, @RequestParam String content,
                                    @RequestParam(required = false) List<Long> removeImageIds,
                                    @RequestParam(required = false) List<MultipartFile> images,
                                    @RequestParam(required = false) String rating,
                                    @RequestParam(required = false) Long photoUploadPolicyVersionId,
                                    @AuthenticationPrincipal CustomUserDetails user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        reviewImageService.edit(commentId, user.getId(), content, removeImageIds, images,
                com.tripbora.service.comment.DestinationReviewPolicy.parseRating(rating), rating != null, photoUploadPolicyVersionId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ResponseEntity<Map<String,String>> uploadError(UnsupportedImageFormatException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
