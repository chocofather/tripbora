package com.tripbora.service.comment;

import com.tripbora.dto.CommentDto;
import com.tripbora.dto.CommentImageDto;
import com.tripbora.dto.PageResult;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.DestinationCommentImage;
import com.tripbora.model.User;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** STEP D: 댓글 조회는 destination_comment_images 기준으로 사진을 채운다. */
@ExtendWith(MockitoExtension.class)
class DestinationCommentImageReadTest {
    @Test void allFivePhotosRemainPublicRegardlessOfGalleryApprovalRejectionRevocationOrNoConsent() {
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(1);
        when(commentMapper.findPagedParentComments(10L,0,5,"latest")).thenReturn(List.of(comment(1L,null,false)));
        var photos=new java.util.ArrayList<DestinationCommentImage>();
        for(int i=1;i<=5;i++)photos.add(image(1L,"/uploads/comments/"+i+".jpg",i));
        when(commentImageMapper.findByCommentIds(List.of(1L))).thenReturn(photos);
        for(String[] states:new String[][]{{"PENDING","PENDING","PENDING","PENDING","PENDING"},
                {"APPROVED","APPROVED","APPROVED","REJECTED","REJECTED"},
                {"REVOKED","APPROVED","APPROVED","REJECTED","NONE"}}) {
            for(int i=0;i<5;i++)photos.get(i).setGalleryStatus(states[i]);
            assertThat(service.getCommentsPaged(10L,null,0,5,"latest").getContent().get(0).getImageUrls()).hasSize(5);
        }
        org.mockito.Mockito.verify(commentImageMapper,org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private DestinationCommentMapper commentMapper;
    @Mock
    private DestinationCommentImageMapper commentImageMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private FileUploadService fileUploadService;

    private DestinationCommentService service;

    @BeforeEach
    void setUp() {
        service = new DestinationCommentService(
                destinationMapper, commentMapper, commentImageMapper, userMapper, fileUploadService,
                new LocalContentLanguageDetector(), TestWithdrawnMemberName.real());
    }

    @Test
    void pagedCommentsReadAllImagesInOneQueryAndKeepDisplayOrder() {
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(2);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest"))
                .thenReturn(List.of(comment(1L, null, false)));
        when(commentMapper.findRepliesForParents(10L, List.of(1L)))
                .thenReturn(List.of(comment(2L, 1L, false)));
        when(commentImageMapper.findByCommentIds(List.of(1L, 2L))).thenReturn(List.of(
                image(1L, "/uploads/comments/a.jpg", 1),
                image(1L, "/uploads/comments/b.jpg", 2),
                image(1L, "/uploads/comments/c.jpg", 3),
                image(2L, "/uploads/comments/reply.jpg", 1)));

        PageResult<CommentDto> result = service.getCommentsPaged(10L, null, 0, 5, "latest");

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getImageUrls()).containsExactly(
                "/uploads/comments/a.jpg", "/uploads/comments/b.jpg", "/uploads/comments/c.jpg");
        assertThat(result.getContent().get(0).getPhotos()).extracting(com.tripbora.dto.CommentImageDto::getId)
                .containsExactly(11L,12L,13L);
        assertThat(result.getContent().get(1).getImageUrls())
                .containsExactly("/uploads/comments/reply.jpg");
        // 댓글마다 SELECT 하지 않는다
        verify(commentImageMapper, times(1)).findByCommentIds(anyList());
    }

    @Test
    void deletedCommentsAreExcludedFromTheImageQueryAndGetNoImages() {
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(2);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest"))
                .thenReturn(List.of(comment(1L, null, true)));
        when(commentMapper.findRepliesForParents(10L, List.of(1L)))
                .thenReturn(List.of(comment(2L, 1L, false)));
        when(commentImageMapper.findByCommentIds(List.of(2L))).thenReturn(List.of());

        PageResult<CommentDto> result = service.getCommentsPaged(10L, null, 0, 5, "latest");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(commentImageMapper).findByCommentIds(captor.capture());
        assertThat(captor.getValue()).containsExactly(2L);
        // 조치/삭제된 댓글은 사진 없이 내려간다 (플레이스홀더 렌더링은 그대로)
        assertThat(result.getContent().get(0).getImageUrls()).isEmpty();
    }

    @Test
    void listPathAlsoFillsImageUrlsInOneQuery() {
        when(commentMapper.findByDestinationIdWithWriter(10L))
                .thenReturn(List.of(comment(1L, null, false), comment(2L, null, false)));
        when(commentImageMapper.findByCommentIds(List.of(1L, 2L))).thenReturn(List.of(
                image(2L, "/uploads/comments/only.jpg", 1)));

        List<CommentDto> comments = service.getCommentDtosWithWriter(10L, null, "oldest");

        assertThat(comments.get(0).getImageUrls()).isEmpty();
        assertThat(comments.get(1).getImageUrls()).containsExactly("/uploads/comments/only.jpg");
        verify(commentImageMapper, times(1)).findByCommentIds(anyList());
    }

    @Test
    void commentsWithoutImagesGetAnEmptyListNeverNull() {
        when(commentMapper.findByDestinationIdWithWriter(10L))
                .thenReturn(List.of(comment(1L, null, false)));
        when(commentImageMapper.findByCommentIds(List.of(1L))).thenReturn(List.of());

        assertThat(service.getCommentDtosWithWriter(10L, null, "oldest").get(0).getImageUrls())
                .isNotNull()
                .isEmpty();
    }

    private DestinationComment comment(long id, Long parentId, boolean deleted) {
        DestinationComment comment = new DestinationComment();
        comment.setId(id);
        comment.setParentCommentId(parentId);
        comment.setContent("내용 " + id);
        comment.setLikes(0);
        comment.setDeleted(deleted);
        comment.setUserId(7L);
        comment.setDestinationId(10L);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);

        User writer = new User();
        writer.setId(7L);
        writer.setNickname("여행자");
        comment.setWriter(writer);
        return comment;
    }

    private DestinationCommentImage image(long commentId, String url, int order) {
        DestinationCommentImage image = new DestinationCommentImage();
        image.setId(commentId * 10 + order);
        image.setCommentId(commentId);
        image.setImageUrl(url);
        image.setDisplayOrder(order);
        return image;
    }
}
