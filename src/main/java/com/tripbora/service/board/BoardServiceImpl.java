package com.tripbora.service.board;

import com.tripbora.dto.BoardListDto;
import com.tripbora.repository.board.BoardMapper;
import com.tripbora.service.course.CourseThemeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BoardServiceImpl implements BoardService {

    private static final Set<String> POST_TYPES = Set.of("QUESTION", "TIP");
    private static final Set<String> SORT_TYPES = Set.of("latest", "oldest", "views", "comments", "bookmarks");

    private final BoardMapper boardMapper;
    private final CourseThemeService courseThemeService;

    @Override
    public List<BoardListDto> getBoardList(String boardType, String postType, String scope,
                                           Long countryId, String sort, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);
        long offset = (long) (safePage - 1) * safeSize;
        return boardMapper.findBoardList(
                "post",
                normalizePostType(postType),
                "all",
                null,
                normalizeSort(sort),
                offset,
                safeSize
        );
    }

    @Override
    public int getBoardCount(String boardType, String postType, String scope, Long countryId) {
        return boardMapper.countBoard(
                "post",
                normalizePostType(postType),
                "all",
                null
        );
    }

    @Override
    public List<BoardListDto> getBoardListByUserId(Long userId, String type, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);
        long offset = (long) (safePage - 1) * safeSize;
        ProfileFilter filter = profileFilter(type);

        return withThemes(boardMapper.findBoardListByUserId(
                userId,
                filter.boardType(),
                filter.postType(),
                offset,
                safeSize
        ));
    }

    @Override
    public int getBoardCountByUserId(Long userId, String type) {
        ProfileFilter filter = profileFilter(type);
        return boardMapper.countBoardByUserId(userId, filter.boardType(), filter.postType());
    }

    @Override
    public List<BoardListDto> getPublicBoardListByUserId(Long userId, String type, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = clampSize(size);
        ProfileFilter filter = profileFilter(type);
        return withThemes(boardMapper.findPublicBoardListByUserId(userId, filter.boardType(), filter.postType(),
                (long) (safePage - 1) * safeSize, safeSize));
    }

    private List<BoardListDto> withThemes(List<BoardListDto> rows) {
        var ids = rows.stream().filter(row -> "course".equals(row.getBoardType())).map(BoardListDto::getId).toList();
        var themes = courseThemeService.findByCourseIds(ids);
        rows.stream().filter(row -> "course".equals(row.getBoardType()))
                .forEach(row -> row.setThemes(themes.getOrDefault(row.getId(), List.of())));
        return rows;
    }

    @Override
    public int getPublicBoardCountByUserId(Long userId, String type) {
        ProfileFilter filter = profileFilter(type);
        return boardMapper.countPublicBoardByUserId(userId, filter.boardType(), filter.postType());
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private String normalizePostType(String postType) {
        if (postType == null) {
            return null;
        }
        String normalized = postType.toUpperCase(Locale.ROOT);
        return POST_TYPES.contains(normalized) ? normalized : null;
    }

    private String normalizeSort(String sort) {
        if (sort == null) {
            return "latest";
        }
        String normalized = sort.toLowerCase(Locale.ROOT);
        return SORT_TYPES.contains(normalized) ? normalized : "latest";
    }

    private ProfileFilter profileFilter(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "question" -> new ProfileFilter("post", "QUESTION");
            case "tip" -> new ProfileFilter("post", "TIP");
            case "course" -> new ProfileFilter("course", null);
            default -> new ProfileFilter(null, null);
        };
    }

    private record ProfileFilter(String boardType, String postType) {
    }

}
