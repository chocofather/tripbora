package com.tripbora.controller.board;

import com.tripbora.dto.BoardListDto;
import com.tripbora.seo.SeoModel;
import com.tripbora.service.board.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    // 1. 전체 페이지(SSR 최초 진입)
    @GetMapping("/board/list")
    public String boardListPage(
            @RequestParam(value = "boardType", required = false) String boardType,
            @RequestParam(value = "postType", required = false) String postType,
            @RequestParam(value = "scope", defaultValue = "all") String scope,
            @RequestParam(value = "countryId", required = false) Long countryId,
            @RequestParam(value = "sort", defaultValue = "latest") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            Model model
    ) {
        if ("course".equalsIgnoreCase(boardType)) {
            return legacyCourseRedirect(countryId, sort);
        }
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);
        List<BoardListDto> boardList = boardService.getBoardList(boardType, postType,
                "all", null, sort, safePage, safeSize);
        int totalCount = boardService.getBoardCount(boardType, postType,
                "all", null);
        int totalPages = (int) Math.ceil((double) totalCount / safeSize);

        model.addAttribute("boardList", boardList);
        model.addAttribute("boardType", boardType);
        model.addAttribute("postType", postType);
        model.addAttribute("emptyMessage", "등록된 게시글이 없습니다.");
        model.addAttribute("sort", sort);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("pageTitle", "여행 커뮤니티");

        Map<String, Object> canonicalParameters = SeoModel.parameters();
        canonicalParameters.put("boardType", boardType == null
                ? null : boardType.toLowerCase(Locale.ROOT));
        canonicalParameters.put("postType", postType == null
                ? null : postType.toLowerCase(Locale.ROOT));
        canonicalParameters.put("page", safePage);
        model.addAttribute("seoCanonicalPath",
                SeoModel.canonicalPath("/board/list", canonicalParameters));

        return "board/list";
    }

    // 2. fragment만 반환 (AJAX 페이징/정렬/검색 등)
    @GetMapping("/board/fragment")
    public String boardFragment(
            @RequestParam(value = "boardType", required = false) String boardType,
            @RequestParam(value = "postType", required = false) String postType,
            @RequestParam(value = "scope", defaultValue = "all") String scope,
            @RequestParam(value = "countryId", required = false) Long countryId,
            @RequestParam(value = "sort", defaultValue = "latest") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            Model model
    ) {
        if ("course".equalsIgnoreCase(boardType)) {
            return legacyCourseRedirect(countryId, sort);
        }
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);
        List<BoardListDto> boardList = boardService.getBoardList(boardType, postType,
                "all", null, sort, safePage, safeSize);
        int totalCount = boardService.getBoardCount(boardType, postType,
                "all", null);
        int totalPages = (int) Math.ceil((double) totalCount / safeSize);

        model.addAttribute("boardList", boardList);
        model.addAttribute("boardType", boardType);
        model.addAttribute("postType", postType);
        model.addAttribute("emptyMessage", "등록된 게시글이 없습니다.");
        model.addAttribute("sort", sort);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);

        return "board/fragment :: boardListFragment";
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private String legacyCourseRedirect(Long countryId, String sort) {
        String target = "redirect:/courses?sort=" + ("views".equalsIgnoreCase(sort) ? "views" : "latest");
        return countryId != null && countryId > 0 ? target + "&countryId=" + countryId : target;
    }
}
