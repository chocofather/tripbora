package com.tripbora.service.destination;

import com.tripbora.model.BookmarkTargetType;
import com.tripbora.model.Destination;
import com.tripbora.model.DestinationImage;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.info.AccommodationInfoService;
import com.tripbora.service.info.ActivityInfoService;
import com.tripbora.service.info.AttractionInfoService;
import com.tripbora.service.info.RestaurantInfoService;
import com.tripbora.service.info.ShopInfoService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 관리자 목록 선택 삭제. 단건 삭제 규칙(연관 데이터·코스 STOP·커밋 후 파일 정리)을 그대로 쓰고,
 * 고른 여행지 중 하나라도 지울 수 없으면 아무것도 지우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationServiceBulkDeletionTest {

    @Mock private DestinationMapper destinationMapper;
    @Mock private BookmarkMapper bookmarkMapper;
    @Mock private AmenityService amenityService;
    @Mock private DestinationCommentMapper destinationCommentMapper;
    @Mock private DestinationCommentImageMapper destinationCommentImageMapper;
    @Mock private UserMapper userMapper;
    @Mock private AccommodationInfoService accommodationInfoService;
    @Mock private AttractionInfoService attractionInfoService;
    @Mock private RestaurantInfoService restaurantInfoService;
    @Mock private ActivityInfoService activityInfoService;
    @Mock private ShopInfoService shopInfoService;
    @Mock private CourseService courseService;

    @TempDir
    Path uploadRoot;

    private DestinationService destinationService;

    @BeforeEach
    void setUp() {
        FileUploadService fileUploadService = new FileUploadService(uploadRoot.toString());
        DestinationImageService destinationImageService =
                new DestinationImageService(destinationMapper, fileUploadService);
        ReflectionTestUtils.setField(destinationImageService, "uploadDir", uploadRoot.toString());
        DestinationCommentService destinationCommentService = new DestinationCommentService(
                destinationMapper,
                destinationCommentMapper,
                destinationCommentImageMapper,
                userMapper,
                fileUploadService,
                new LocalContentLanguageDetector(),
                TestWithdrawnMemberName.real());
        ReflectionTestUtils.setField(destinationCommentService, "uploadPath", uploadRoot.toString());
        destinationService = new DestinationService(
                destinationMapper,
                destinationImageService,
                bookmarkMapper,
                amenityService,
                destinationCommentService,
                courseService,
                accommodationInfoService,
                attractionInfoService,
                restaurantInfoService,
                activityInfoService,
                shopInfoService,
                new DestinationLocalizationService(destinationMapper)
        );
        ReflectionTestUtils.setField(destinationService, "uploadPath", uploadRoot.toString());
    }

    @Test
    void everySelectedDestinationGoesThroughTheSingleDeletionCleanup() {
        existing(3L, 5L, 8L);
        when(courseService.getCourseIdsContainingDestination(5L)).thenReturn(List.of(40L));

        int deleted = withTransactionSynchronization(() -> destinationService.deleteByIds(List.of(3L, 5L, 8L)));

        assertThat(deleted).isEqualTo(3);
        for (Long id : List.of(3L, 5L, 8L)) {
            verify(bookmarkMapper).deleteByTarget(BookmarkTargetType.DESTINATION.name(), id);
            verify(destinationMapper).deleteImagesByDestinationId(id);
            verify(destinationMapper).deleteTranslationsByDestinationId(id);
            verify(destinationMapper).deleteCommentsByDestinationId(id);
            verify(destinationMapper).deleteDestinationCategoriesByDestinationId(id);
            verify(destinationMapper).deleteById(id);
        }
        verify(courseService).resequenceStops(List.of(40L));
    }

    @Test
    void destinationsThatWereNotSelectedAreNeverTouched() {
        existing(3L, 5L);

        withTransactionSynchronization(() -> destinationService.deleteByIds(List.of(3L, 5L)));

        verify(destinationMapper, times(2)).deleteById(anyLong());
        verify(destinationMapper, never()).deleteById(4L);
        verify(bookmarkMapper, never()).deleteByTarget(anyString(), org.mockito.ArgumentMatchers.eq(4L));
    }

    @Test
    void duplicateIdsAreDeletedOnlyOnce() {
        existing(3L, 5L);

        int deleted = withTransactionSynchronization(() -> destinationService.deleteByIds(List.of(3L, 5L, 3L)));

        assertThat(deleted).isEqualTo(2);
        verify(destinationMapper).deleteById(3L);
        verify(destinationMapper).deleteById(5L);
    }

    @Test
    void emptyOrMissingSelectionIsRejected() {
        assertThatThrownBy(() -> destinationService.deleteByIds(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("삭제할 여행지를 선택해 주세요.");
        assertThatThrownBy(() -> destinationService.deleteByIds(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("삭제할 여행지를 선택해 주세요.");

        verifyNoInteractions(destinationMapper, bookmarkMapper, courseService);
    }

    @Test
    void invalidIdValuesAreRejectedBeforeAnythingIsDeleted() {
        assertThatThrownBy(() -> destinationService.deleteByIds(Arrays.asList(3L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> destinationService.deleteByIds(List.of(3L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> destinationService.deleteByIds(List.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(destinationMapper, bookmarkMapper, courseService);
    }

    @Test
    void moreThanOnePageOfDestinationsIsRejected() {
        List<Long> tooMany = LongStream.rangeClosed(1, DestinationService.ADMIN_BULK_DELETE_LIMIT + 1)
                .boxed().toList();

        assertThatThrownBy(() -> destinationService.deleteByIds(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("한 번에 최대 30건까지 삭제할 수 있습니다.");
        verifyNoInteractions(destinationMapper, bookmarkMapper, courseService);

        // 정확히 한 쪽(30건)은 지울 수 있다
        List<Long> onePage = new ArrayList<>(tooMany.subList(0, DestinationService.ADMIN_BULK_DELETE_LIMIT));
        existing(onePage.toArray(Long[]::new));
        assertThat(withTransactionSynchronization(() -> destinationService.deleteByIds(onePage))).isEqualTo(30);
    }

    @Test
    void aSelectionWithAnAlreadyDeletedDestinationDeletesNothing() {
        existing(3L, 8L);
        // 5 는 그 사이 지워졌다(findById 가 null)

        assertThatThrownBy(() -> destinationService.deleteByIds(List.of(3L, 5L, 8L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 삭제된");

        verify(destinationMapper, never()).deleteById(any());
        verify(destinationMapper, never()).deleteImagesByDestinationId(any());
        verifyNoInteractions(bookmarkMapper);
    }

    @Test
    void imageFilesOfEverySelectedDestinationAreDeletedOnlyAfterCommit() throws Exception {
        existing(3L, 5L);
        Path first = managedImage("first.jpg");
        Path second = managedImage("second.jpg");
        Path commentPhoto = managedCommentImage("comment.jpg");
        Path unselected = managedImage("unselected.jpg");
        when(destinationMapper.findImagesByDestinationId(3L)).thenReturn(List.of(image("/uploads/destinations/first.jpg")));
        when(destinationMapper.findImagesByDestinationId(5L)).thenReturn(List.of(image("/uploads/destinations/second.jpg")));
        when(destinationCommentImageMapper.findAllImageUrlsByDestinationId(5L))
                .thenReturn(List.of("/uploads/comments/comment.jpg"));

        withTransactionSynchronization(() -> {
            destinationService.deleteByIds(List.of(3L, 5L));

            assertThat(first).exists();
            assertThat(second).exists();
            assertThat(commentPhoto).exists();
            commitSynchronizations();
            return null;
        });

        assertThat(first).doesNotExist();
        assertThat(second).doesNotExist();
        assertThat(commentPhoto).doesNotExist();
        assertThat(unselected).exists();
    }

    @Test
    void aFailureOnALaterDestinationKeepsTheFilesOfTheEarlierOnes() throws Exception {
        existing(3L, 5L);
        Path first = managedImage("first.jpg");
        when(destinationMapper.findImagesByDestinationId(3L)).thenReturn(List.of(image("/uploads/destinations/first.jpg")));
        doThrow(new IllegalStateException("db failure")).when(destinationMapper).deleteById(5L);

        withTransactionSynchronization(() -> {
            assertThatThrownBy(() -> destinationService.deleteByIds(List.of(3L, 5L)))
                    .isInstanceOf(IllegalStateException.class);
            rollbackSynchronizations();
            return null;
        });

        assertThat(first).exists();
    }

    @Test
    void theWholeSelectionSharesOneTransaction() throws Exception {
        Transactional transactional = DestinationService.class.getMethod("deleteByIds", List.class)
                .getAnnotation(Transactional.class);

        // 중간에 실패하면 앞서 지운 여행지까지 함께 되돌아간다
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private void existing(Long... ids) {
        for (Long id : ids) {
            Destination destination = new Destination();
            destination.setId(id);
            when(destinationMapper.findById(id)).thenReturn(destination);
        }
    }

    private Path managedImage(String fileName) throws Exception {
        Path destinations = Files.createDirectories(uploadRoot.resolve("destinations"));
        return Files.write(destinations.resolve(fileName), new byte[]{1, 2, 3});
    }

    private Path managedCommentImage(String fileName) throws Exception {
        Path comments = Files.createDirectories(uploadRoot.resolve("comments"));
        return Files.write(comments.resolve(fileName), new byte[]{1, 2, 3});
    }

    private DestinationImage image(String imageUrl) {
        DestinationImage image = new DestinationImage();
        image.setImageUrl(imageUrl);
        return image;
    }

    private <T> T withTransactionSynchronization(java.util.concurrent.Callable<T> action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            return action.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void commitSynchronizations() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void rollbackSynchronizations() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization ->
                        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
