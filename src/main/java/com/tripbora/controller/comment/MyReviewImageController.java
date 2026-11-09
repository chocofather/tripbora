package com.tripbora.controller.comment;

import com.tripbora.dto.ReviewImageAdminDto;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import com.tripbora.service.policy.GalleryConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;

/** 공개 댓글에서 사라진 사진도 작성자가 직접 삭제·철회할 수 있는 비공개 관리 화면. */
@Controller
@RequiredArgsConstructor
public class MyReviewImageController {
    private final ReviewImageService reviewImageService;
    private final GalleryConsentService galleryConsentService;

    @GetMapping("/mypage/review-images")
    public String list(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        int currentPage = Math.max(0, page);
        var photos = reviewImageService.myPhotos(user.getId(), currentPage);
        var comments = new LinkedHashMap<Long, ReviewImageAdminDto>();
        photos.forEach(photo -> comments.putIfAbsent(photo.getCommentId(), photo));
        model.addAttribute("comments", List.copyOf(comments.values()));
        model.addAttribute("photos", photos);
        model.addAttribute("ownerId", user.getId());
        model.addAttribute("legacyGalleryWithdrawal", galleryConsentService.legacyWithdrawalState(user.getId()));
        model.addAttribute("page", currentPage);
        model.addAttribute("hasNext", photos.size() == 20);
        model.addAttribute("pageTitle", "내 리뷰 사진 관리");
        return "mypage/review-images";
    }
}
