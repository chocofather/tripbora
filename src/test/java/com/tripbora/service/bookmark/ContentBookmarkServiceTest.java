package com.tripbora.service.bookmark;

import com.tripbora.model.Course;
import com.tripbora.model.UserPost;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.post.PostMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentBookmarkServiceTest {

    @Mock
    private BookmarkMapper bookmarkMapper;
    @Mock
    private PostMapper postMapper;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private TravelInfoMapper travelInfoMapper;

    private ContentBookmarkService service;

    @BeforeEach
    void setUp() {
        service = new ContentBookmarkService(
                bookmarkMapper, postMapper, courseMapper, travelInfoMapper);
    }

    @Test
    void bookmarksActiveQuestionOrTipWithServerFixedPostType() {
        when(postMapper.findActivePostForUpdate(10L)).thenReturn(new UserPost());
        when(bookmarkMapper.insertIgnore(7L, "POST", 10L)).thenReturn(1);

        service.bookmarkPost(10L, 7L);

        verify(postMapper).findActivePostForUpdate(10L);
        verify(bookmarkMapper).insertIgnore(7L, "POST", 10L);
    }

    @Test
    void duplicatePostBookmarkInsertIsSuccessfulNoop() {
        when(postMapper.findActivePostForUpdate(10L)).thenReturn(new UserPost());
        when(bookmarkMapper.insertIgnore(7L, "POST", 10L)).thenReturn(0);

        service.bookmarkPost(10L, 7L);

        verify(bookmarkMapper).insertIgnore(7L, "POST", 10L);
    }

    @Test
    void unbookmarksPostIdempotentlyAfterActiveTargetLock() {
        when(postMapper.findActivePostForUpdate(10L)).thenReturn(new UserPost());

        service.unbookmarkPost(10L, 7L);

        verify(postMapper).findActivePostForUpdate(10L);
        verify(bookmarkMapper).delete(7L, "POST", 10L);
    }

    @Test
    void missingOrDeletedOrUnsupportedPostReturnsNotFound() {
        assertNotFound(() -> service.bookmarkPost(10L, 7L));
        assertNotFound(() -> service.unbookmarkPost(10L, 7L));

        verify(bookmarkMapper, never()).insertIgnore(any(), any(), any());
        verify(bookmarkMapper, never()).delete(any(), any(), any());
    }

    @Test
    void bookmarksActiveCourseWithServerFixedCourseType() {
        when(courseMapper.findActiveCourseForUpdate(20L)).thenReturn(publishedCourse());
        when(bookmarkMapper.insertIgnore(7L, "COURSE", 20L)).thenReturn(1);

        service.bookmarkCourse(20L, 7L);

        verify(courseMapper).findActiveCourseForUpdate(20L);
        verify(bookmarkMapper).insertIgnore(7L, "COURSE", 20L);
    }

    @Test
    void duplicateCourseBookmarkInsertIsSuccessfulNoop() {
        when(courseMapper.findActiveCourseForUpdate(20L)).thenReturn(publishedCourse());
        when(bookmarkMapper.insertIgnore(7L, "COURSE", 20L)).thenReturn(0);

        service.bookmarkCourse(20L, 7L);

        verify(bookmarkMapper).insertIgnore(7L, "COURSE", 20L);
    }

    @Test
    void unbookmarksCourseIdempotentlyAfterActiveTargetLock() {
        when(courseMapper.findActiveCourseForUpdate(20L)).thenReturn(publishedCourse());

        service.unbookmarkCourse(20L, 7L);

        verify(courseMapper).findActiveCourseForUpdate(20L);
        verify(bookmarkMapper).delete(7L, "COURSE", 20L);
    }

    @Test
    void missingOrDeletedCourseReturnsNotFound() {
        assertNotFound(() -> service.bookmarkCourse(20L, 7L));
        assertNotFound(() -> service.unbookmarkCourse(20L, 7L));

        verify(bookmarkMapper, never()).insertIgnore(any(), any(), any());
        verify(bookmarkMapper, never()).delete(any(), any(), any());
    }

    @Test
    void unpublishedCourseCannotBeBookmarked() {
        for (String status : java.util.List.of("DRAFT", "HIDDEN")) {
            Course course = new Course();
            course.setStatus(status);
            when(courseMapper.findActiveCourseForUpdate(20L)).thenReturn(course);
            assertNotFound(() -> service.bookmarkCourse(20L, 7L));
        }
        verify(bookmarkMapper, never()).insertIgnore(any(), any(), any());
    }

    @Test
    void bookmarksPublicTravelInfoIdempotentlyWithServerFixedTargetType() {
        when(travelInfoMapper.findPublicBookmarkTargetForUpdate(30L)).thenReturn(30L);
        when(bookmarkMapper.insertIgnore(7L, "TRAVEL_INFO", 30L)).thenReturn(0);

        service.bookmarkTravelInfo(30L, 7L);

        verify(travelInfoMapper).findPublicBookmarkTargetForUpdate(30L);
        verify(travelInfoMapper, never()).incrementPublicViews(any());
        verify(bookmarkMapper).insertIgnore(7L, "TRAVEL_INFO", 30L);
    }

    @Test
    void unbookmarksPublicTravelInfoIdempotently() {
        when(travelInfoMapper.findPublicBookmarkTargetForUpdate(30L)).thenReturn(30L);

        service.unbookmarkTravelInfo(30L, 7L);

        verify(travelInfoMapper).findPublicBookmarkTargetForUpdate(30L);
        verify(bookmarkMapper).delete(7L, "TRAVEL_INFO", 30L);
    }

    @Test
    void missingOrHiddenTravelInfoReturnsNotFoundWithoutBookmarkMutation() {
        assertNotFound(() -> service.bookmarkTravelInfo(30L, 7L));
        assertNotFound(() -> service.unbookmarkTravelInfo(30L, 7L));

        verify(bookmarkMapper, never()).insertIgnore(any(), any(), any());
        verify(bookmarkMapper, never()).delete(any(), any(), any());
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Course publishedCourse() {
        Course course = new Course();
        course.setStatus("PUBLISHED");
        return course;
    }
}
