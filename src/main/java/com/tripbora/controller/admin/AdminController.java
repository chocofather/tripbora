package com.tripbora.controller.admin;

import com.tripbora.model.UserStatus;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.user.AdminAppealService;
import com.tripbora.service.user.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final DestinationService destinationService;
    private final AdminUserService adminUserService;
    private final AdminAppealService adminAppealService;
    private final com.tripbora.repository.comment.DestinationCommentImageMapper reviewImages;

    @GetMapping
    public String adminHome(Model model) {
        model.addAttribute("pageTitle", "관리자 홈");
        // ↳ layout/main.html 의 <title>이 이 값으로 대체됩니다.
        model.addAttribute("totalUserCount", adminUserService.countUsers(null, null));
        model.addAttribute("activeUserCount",
                adminUserService.countUsers(null, UserStatus.ACTIVE));
        model.addAttribute("restrictedUserCount",
                adminUserService.countUsers(null, UserStatus.RESTRICTED));
        model.addAttribute("pendingAppealCount", adminAppealService.countPendingAppeals());
        model.addAttribute("pendingReviewPhotoCount", reviewImages.countPendingPhotos());
        return "admin/index";
    }


}
