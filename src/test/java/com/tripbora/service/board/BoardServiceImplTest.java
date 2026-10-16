package com.tripbora.service.board;

import com.tripbora.repository.board.BoardMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BoardServiceImplTest {

    @Mock
    private BoardMapper boardMapper;

    @Mock
    private com.tripbora.service.course.CourseThemeService courseThemeService;

    private BoardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BoardServiceImpl(boardMapper, courseThemeService);
    }

    @Test
    void listNormalizesFiltersSortAndPagination() {
        service.getBoardList("POST", "tip", "overseas", 8L, "unsupported", 0, 200);

        verify(boardMapper).findBoardList("post", "TIP", "all", null, "latest", 0L, 100);
    }

    @Test
    void listKeepsSupportedSortAndCalculatesOffset() {
        service.getBoardList("course", null, "overseas", 8L, "comments", 3, 10);
        service.getBoardCount("course", null, "overseas", 8L);

        verify(boardMapper).findBoardList("post", null, "all", null, "comments", 20L, 10);
        verify(boardMapper).countBoard("post", null, "all", null);
        org.mockito.Mockito.verifyNoInteractions(courseThemeService);
    }

    @Test
    void listKeepsBookmarkSort() {
        service.getBoardList(null, null, "domestic", 7L, "BOOKMARKS", 1, 10);

        verify(boardMapper).findBoardList("post", null, "all", null, "bookmarks", 0L, 10);
    }

    @Test
    void countUsesTheSameNormalizedFilters() {
        service.getBoardCount("invalid", "question", "overseas", 8L);

        verify(boardMapper).countBoard("post", "QUESTION", "all", null);
    }

    @Test
    void obsoleteCourseScopeAndCountryNeverReachCommunityQueries() {
        service.getBoardList("course", null, "DOMESTIC", 8L, "views", 1, 10);
        service.getBoardCount("course", null, "DOMESTIC", 8L);
        service.getBoardList("course", null, "unsupported", 8L, "latest", 1, 10);
        service.getBoardCount("course", null, "unsupported", 8L);

        verify(boardMapper).findBoardList("post", null, "all", null, "views", 0L, 10);
        verify(boardMapper).findBoardList("post", null, "all", null, "latest", 0L, 10);
        verify(boardMapper, org.mockito.Mockito.times(2)).countBoard("post", null, "all", null);
        org.mockito.Mockito.verifyNoInteractions(courseThemeService);
    }

    @Test
    void profileQuestionFilterUsesDedicatedAuthorQueryAndPagination() {
        service.getBoardListByUserId(7L, "QUESTION", 2, 10);

        verify(boardMapper).findBoardListByUserId(7L, "post", "QUESTION", 10L, 10);
    }

    @Test
    void profileFiltersMapTipCourseAndInvalidTypeSafely() {
        service.getBoardCountByUserId(7L, "tip");
        service.getBoardCountByUserId(7L, "course");
        service.getBoardCountByUserId(7L, "unsupported");

        verify(boardMapper).countBoardByUserId(7L, "post", "TIP");
        verify(boardMapper).countBoardByUserId(7L, "course", null);
        verify(boardMapper).countBoardByUserId(7L, null, null);
    }

    @Test
    void publicProfileUsesPublishedQueriesWithTheSameFiltersAndPagination() {
        service.getPublicBoardListByUserId(7L, "COURSE", 2, 10);
        service.getPublicBoardCountByUserId(7L, "COURSE");
        verify(boardMapper).findPublicBoardListByUserId(7L, "course", null, 10L, 10);
        verify(boardMapper).countPublicBoardByUserId(7L, "course", null);
    }
}
