package com.tripbora.controller.comment;

import com.tripbora.dto.ReviewImageDto;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import com.tripbora.service.comment.CommentImageLimitException;
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
@RequestMapping("/comments/{commentId}/images")
public class DestinationCommentImageController {
    private final ReviewImageService reviewImageService;


    @GetMapping
    public List<ReviewImageDto> list(@PathVariable Long commentId,
                                    @AuthenticationPrincipal CustomUserDetails user) {
        return reviewImageService.ownerImages(commentId, userId(user));
    }

    @PostMapping
    public ResponseEntity<Void> add(@PathVariable Long commentId,
                                   @RequestParam("images") List<MultipartFile> images,
                                   @RequestParam(required = false) Long photoUploadPolicyVersionId,
                                   @AuthenticationPrincipal CustomUserDetails user) {
        reviewImageService.add(commentId, userId(user), images, photoUploadPolicyVersionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable Long commentId, @PathVariable Long imageId,
                                      @AuthenticationPrincipal CustomUserDetails user) {
        reviewImageService.remove(commentId, imageId, userId(user));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{imageId}/consent")
    public ResponseEntity<Void> consent(@PathVariable Long commentId, @PathVariable Long imageId,
                                       @RequestBody ConsentRequest request,
                                       @AuthenticationPrincipal CustomUserDetails user) {
        if (request.consent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동의 여부를 선택해 주세요.");
        }
        reviewImageService.consent(commentId, imageId, userId(user),
                request.consent(), Boolean.TRUE.equals(request.rightsConfirmed()));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({CommentImageLimitException.class, UnsupportedImageFormatException.class})
    public ResponseEntity<Map<String, String>> uploadError(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    private Long userId(CustomUserDetails user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.getId();
    }

    public record ConsentRequest(Boolean consent, Boolean rightsConfirmed) { }
}
