package com.tripbora.service.board;

import com.tripbora.dto.BoardListDto;
import com.tripbora.dto.CourseThemeDto;
import com.tripbora.repository.board.BoardMapper;
import com.tripbora.repository.course.CourseThemeMapper;
import com.tripbora.service.course.CourseThemeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BoardCourseThemeTest {
    @Test
    void authoredListsKeepThemeBatchWithoutDuplicatingCourseRows() {
        var boards = mock(BoardMapper.class); var themes = mock(CourseThemeMapper.class);
        var service = new BoardServiceImpl(boards, new CourseThemeService(themes));
        var post = row("post", 10L); var course = row("course", 10L); var other = row("course", 20L);
        var first = theme(10L, 7L); var second = theme(10L, 4L); var third = theme(10L, 8L);
        when(themes.findByCourseIds(List.of(10L, 20L))).thenReturn(List.of(first, second, third));
        when(boards.findPublicBoardListByUserId(5L, null, null, 0, 20)).thenReturn(List.of(post, course, other));
        var result = service.getPublicBoardListByUserId(5L, null, 1, 20);
        assertThat(result).containsExactly(post, course, other);
        assertThat(course.getThemes()).containsExactly(first, second, third);
        assertThat(post.getThemes()).isEmpty(); assertThat(other.getThemes()).isEmpty();
        verify(themes).findByCourseIds(List.of(10L, 20L));
        when(boards.findBoardListByUserId(5L, "course", null, 0, 20)).thenReturn(List.of(course, other));
        assertThat(service.getBoardListByUserId(5L, "course", 1, 20)).containsExactly(course, other);
        verify(themes, times(2)).findByCourseIds(List.of(10L, 20L));
    }

    @Test
    void communityListNeverLoadsCourseThemesEvenForLegacyArguments() {
        var boards = mock(BoardMapper.class);
        var themes = mock(CourseThemeMapper.class);
        var service = new BoardServiceImpl(boards, new CourseThemeService(themes));
        var post = row("post", 10L);
        when(boards.findBoardList("post", null, "all", null, "latest", 0, 20)).thenReturn(List.of(post));
        assertThat(service.getBoardList("course", null, "overseas", 8L, null, 1, 20)).containsExactly(post);
        verifyNoInteractions(themes);
    }
    private BoardListDto row(String type, Long id) { var row = new BoardListDto(); row.setId(id); row.setBoardType(type); return row; }
    private CourseThemeDto theme(Long courseId, Long id) { var theme = new CourseThemeDto(); theme.setCourseId(courseId); theme.setId(id); return theme; }
}
