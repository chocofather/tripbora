package com.tripbora.service.course;

import com.tripbora.dto.CourseThemeDto;
import com.tripbora.repository.course.CourseThemeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseThemeServiceTest {
    private final CourseThemeMapper mapper = mock(CourseThemeMapper.class);
    private final CourseThemeService service = new CourseThemeService(mapper);

    @Test
    void zeroThemesAreAllowedAndInvalidRequestsNeverWrite() {
        assertThat(service.validate(null)).isEmpty();
        assertThat(service.validate(List.of())).isEmpty();
        for (List<Long> invalid : List.of(List.of(1L, 2L, 3L, 4L), List.of(1L, 1L), List.of(0L), List.of(-1L))) {
            assertThatThrownBy(() -> service.validate(invalid)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.validate(java.util.Arrays.asList((Long) null)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void onlyActiveMasterIdsAreAcceptedAndClickOrderIsPreserved() {
        when(mapper.findActiveThemes()).thenReturn(List.of(theme(1L), theme(2L), theme(3L)));
        assertThat(service.validate(List.of(3L, 1L, 2L))).containsExactly(3L, 1L, 2L);
        assertThat(service.validate(List.of(1L))).containsExactly(1L);
        assertThatThrownBy(() -> service.validate(List.of(99L))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void replacementDeletesOldMappingsThenWritesContiguousSelectionOrder() {
        when(mapper.findActiveThemes()).thenReturn(List.of(theme(5L), theme(8L)));
        when(mapper.insertMapping(anyLong(), anyLong(), anyInt())).thenReturn(1);
        service.replaceThemes(10L, List.of(8L, 5L));
        var order = inOrder(mapper);
        order.verify(mapper).findActiveThemes();
        order.verify(mapper).deleteMappings(10L);
        order.verify(mapper).insertMapping(10L, 8L, 1);
        order.verify(mapper).insertMapping(10L, 5L, 2);
        service.replaceThemes(10L, List.of());
        verify(mapper, times(2)).deleteMappings(10L);
    }

    @Test
    void failedInsertIsNotIgnoredAndBatchReadSkipsEmptyPages() {
        when(mapper.findActiveThemes()).thenReturn(List.of(theme(5L)));
        assertThatThrownBy(() -> service.replaceThemes(10L, List.of(5L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.findByCourseIds(List.of())).isEmpty();
        verify(mapper, never()).findByCourseIds(anyList());
    }

    private CourseThemeDto theme(Long id) {
        var theme = new CourseThemeDto(); theme.setId(id); return theme;
    }
}
