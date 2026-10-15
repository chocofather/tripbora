package com.tripbora.service.bookmark;

import com.tripbora.model.BookmarkTargetType;
import com.tripbora.model.Course;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.post.PostMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContentBookmarkService {

    private final BookmarkMapper bookmarkMapper;
    private final PostMapper postMapper;
    private final CourseMapper courseMapper;
    private final TravelInfoMapper travelInfoMapper;

    @Transactional
    public void bookmarkPost(Long postId, Long userId) {
        requireActivePost(postId);
        bookmarkMapper.insertIgnore(userId, BookmarkTargetType.POST.name(), postId);
    }

    @Transactional
    public void unbookmarkPost(Long postId, Long userId) {
        requireActivePost(postId);
        bookmarkMapper.delete(userId, BookmarkTargetType.POST.name(), postId);
    }

    @Transactional
    public void bookmarkCourse(Long courseId, Long userId) {
        requireActiveCourse(courseId);
        bookmarkMapper.insertIgnore(userId, BookmarkTargetType.COURSE.name(), courseId);
    }

    @Transactional
    public void unbookmarkCourse(Long courseId, Long userId) {
        requireActiveCourse(courseId);
        bookmarkMapper.delete(userId, BookmarkTargetType.COURSE.name(), courseId);
    }

    @Transactional
    public void bookmarkTravelInfo(Long infoId, Long userId) {
        requirePublicTravelInfo(infoId);
        bookmarkMapper.insertIgnore(userId, BookmarkTargetType.TRAVEL_INFO.name(), infoId);
    }

    @Transactional
    public void unbookmarkTravelInfo(Long infoId, Long userId) {
        requirePublicTravelInfo(infoId);
        bookmarkMapper.delete(userId, BookmarkTargetType.TRAVEL_INFO.name(), infoId);
    }

    private void requireActivePost(Long postId) {
        if (postId == null || postMapper.findActivePostForUpdate(postId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }
    }

    private void requireActiveCourse(Long courseId) {
        Course course = courseId == null ? null : courseMapper.findActiveCourseForUpdate(courseId);
        if (course == null || !"PUBLISHED".equals(course.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행 코스를 찾을 수 없습니다.");
        }
    }

    private void requirePublicTravelInfo(Long infoId) {
        Long publicInfoId = infoId == null
                ? null
                : travelInfoMapper.findPublicBookmarkTargetForUpdate(infoId);
        if (!Objects.equals(infoId, publicInfoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행정보를 찾을 수 없습니다.");
        }
    }
}
