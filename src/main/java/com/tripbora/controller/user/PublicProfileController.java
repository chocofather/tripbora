package com.tripbora.controller.user;

import com.tripbora.dto.BoardListDto;
import com.tripbora.dto.PublicUserProfileDto;
import com.tripbora.service.board.BoardService;
import com.tripbora.service.user.PublicProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Controller
@RequiredArgsConstructor
@RequestMapping("/users")
public class PublicProfileController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> CONTENT_TYPES = Set.of("all", "question", "tip", "course");

    private final PublicProfileService publicProfileService;
    private final BoardService boardService;

    @GetMapping("/{userId:\\d+}")
    public String publicProfile(@PathVariable Long userId,
                                @RequestParam(defaultValue = "all") String type,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size,
                                Model model) {
        String safeType = normalizeType(type);
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);

        PublicUserProfileDto profile = publicProfileService.getPublicProfile(userId);
        List<BoardListDto> contents = boardService.getPublicBoardListByUserId(
                userId, safeType, safePage, safeSize);
        int totalCount = boardService.getPublicBoardCountByUserId(userId, safeType);
        int totalPages = (int) Math.ceil((double) totalCount / safeSize);

        model.addAttribute("profile", profile);
        model.addAttribute("contents", contents);
        model.addAttribute("type", safeType);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("pageTitle", profile.getNickname() + "님의 공개 프로필");
        return "user/public-profile";
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "all";
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.contains(normalized) ? normalized : "all";
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
