package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.*;
import com.tripbora.model.Course;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseCoverLifecycleTest {
    @TempDir Path root;
    private CourseMapper mapper;
    private CourseStructureService structure;
    private CourseServiceImpl service;
    private FileUploadService files;
    private CourseCoverStorageService covers;
    private TransactionTemplate transaction;
    private final AtomicReference<String> savedCover = new AtomicReference<>();

    @BeforeEach
    void setup() {
        mapper = mock(CourseMapper.class);
        structure = mock(CourseStructureService.class);
        files = new FileUploadService(root.toString());
        covers = new CourseCoverStorageService(files);
        service = new CourseServiceImpl(mapper, new PostContentSanitizer(), null, null,
                new LocalContentLanguageDetector(), null, structure, covers,
                mock(com.tripbora.service.category.CountryCategoryService.class));
        transaction = new TransactionTemplate(new CourseTransitionTest.RecordingTransactionManager());
        when(mapper.countExistingDestinations(List.of(12L))).thenReturn(1);
        CourseDestinationCountryDto destination = new CourseDestinationCountryDto();
        destination.setDestinationId(12L);
        destination.setCountryId(7L);
        when(mapper.findDestinationCountries(List.of(12L))).thenReturn(List.of(destination));
    }

    @Test
    void createsWithoutCoverOrSavesOnlyTheServerGeneratedUrl() throws Exception {
        when(mapper.insertCourse(any())).thenAnswer(call -> {
            Course row = call.getArgument(0);
            row.setId(10L);
            savedCover.set(row.getCoverImageUrl());
            return 1;
        });
        CourseCreateRequest request = create();
        transaction.execute(status -> service.createCourse(request, 5L));
        assertThat(savedCover.get()).isNull();
        request.setCoverImage(image());
        transaction.execute(status -> service.createCourse(request, 5L));
        assertThat(savedCover.get()).matches("/uploads/course/cover/5/[0-9a-f-]{36}\\.png");
        assertThat(path(savedCover.get())).isRegularFile();
    }

    @Test
    void editReturnsTheStoredCoverForPreview() throws Exception {
        Course current = owned(files.saveFile(image(), "course/cover/5"));
        when(mapper.findActiveCourse(10L)).thenReturn(current);
        when(structure.findDays(10L)).thenReturn(List.of());
        when(structure.findCourseStops(10L)).thenReturn(List.of());
        when(mapper.findCourseStops(10L)).thenReturn(List.of());
        // Empty schedule avoids localization calls; only the owned Course cover is returned.
        service = new CourseServiceImpl(mapper, new PostContentSanitizer(),
                mock(com.tripbora.service.destination.DestinationLocalizationService.class),
                mock(com.tripbora.service.category.ReferenceNameLocalizationService.class),
                new LocalContentLanguageDetector(), mock(com.tripbora.service.destination.DestinationImageService.class),
                structure, covers, mock(com.tripbora.service.category.CountryCategoryService.class));
        assertThat(service.getCourseForEdit(10L, 5L, SupportedLanguage.KOREAN).getCoverImageUrl())
                .isEqualTo(current.getCoverImageUrl());
    }

    @Test
    void replacementDeletesOldCoverOnlyAfterCommit() throws Exception {
        String old = prepareEdit();
        CourseUpdateRequest request = update();
        request.setCoverImage(image());
        transaction.executeWithoutResult(status -> {
            service.updateCourse(10L, 5L, request);
            assertThat(path(old)).isRegularFile();
            assertThat(path(savedCover.get())).isRegularFile();
        });
        assertThat(path(old)).doesNotExist();
        assertThat(path(savedCover.get())).isRegularFile();
    }

    @Test
    void databaseFailureKeepsOldCoverAndRemovesNewUpload() throws Exception {
        String old = prepareEdit();
        when(mapper.updateCoverImage(eq(10L), eq(5L), any())).thenAnswer(call -> {
            savedCover.set(call.getArgument(2));
            throw new IllegalStateException("DB write failed");
        });
        CourseUpdateRequest request = update();
        request.setCoverImage(image());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.updateCourse(10L, 5L, request)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(path(old)).isRegularFile();
        assertThat(path(savedCover.get())).doesNotExist();
    }

    @Test
    void rollbackAfterSuccessfulReplacementPreservesOldFile() throws Exception {
        String old = prepareEdit();
        CourseUpdateRequest request = update();
        request.setCoverImage(image());
        transaction.executeWithoutResult(status -> {
            service.updateCourse(10L, 5L, request);
            status.setRollbackOnly();
        });
        assertThat(path(old)).isRegularFile();
        assertThat(path(savedCover.get())).doesNotExist();
    }

    @Test
    void explicitRemovalStoresNullAndCleanupWaitsForCommit() throws Exception {
        String old = prepareEdit();
        CourseUpdateRequest request = update();
        request.setRemoveCoverImage(true);
        transaction.executeWithoutResult(status -> {
            service.updateCourse(10L, 5L, request);
            assertThat(savedCover.get()).isNull();
            assertThat(path(old)).isRegularFile();
        });
        assertThat(path(old)).doesNotExist();
        verify(mapper).updateCoverImage(10L, 5L, null);
    }

    @Test
    void cannotCleanAnotherOwnerOrDomainOrTraversalPath() throws Exception {
        for (String directory : List.of("course/cover/6", "travel-info", "editor", "course/cover/5")) {
            String url = files.saveFile(image(), directory);
            String candidate = directory.equals("course/cover/5")
                    ? "/uploads/course/cover/5/../5/" + path(url).getFileName() : url;
            transaction.executeWithoutResult(status -> covers.removeAfterCommit(candidate, 5L));
            assertThat(path(url)).isRegularFile();
        }
    }

    @Test
    void unauthorizedEditCannotUploadOrDeleteAndSoftDeleteKeepsCover() throws Exception {
        String old = prepareEdit();
        CourseUpdateRequest request = update();
        request.setCoverImage(image());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.updateCourse(10L, 6L, request)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(mapper, never()).updateCoverImage(anyLong(), anyLong(), any());
        when(mapper.softDeleteCourse(10L, 5L)).thenReturn(1);
        transaction.executeWithoutResult(status -> service.deleteCourse(10L, 5L));
        assertThat(path(old)).isRegularFile();
        try (var remaining = Files.list(path(old).getParent())) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    @Test
    void rejectsFakeImagesSvgAndOversizedFilesWithoutChangingTheOldCover() throws Exception {
        String old = prepareEdit();
        var oversized = mock(org.springframework.web.multipart.MultipartFile.class);
        when(oversized.getSize()).thenReturn(10L * 1024 * 1024 + 1);
        for (var image : List.of(new MockMultipartFile("coverImage", "x.jpg", "image/jpeg", "not an image".getBytes()),
                new MockMultipartFile("coverImage", "x.svg", "image/svg+xml", "<svg/>".getBytes()), oversized)) {
            CourseUpdateRequest request = update();
            request.setCoverImage(image);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.updateCourse(10L, 5L, request)))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(path(old)).isRegularFile();
        }
        verify(mapper, never()).updateCoverImage(anyLong(), anyLong(), any());
    }

    private String prepareEdit() throws Exception {
        String old = files.saveFile(image(), "course/cover/5");
        when(mapper.findActiveCourseForUpdate(10L)).thenReturn(owned(old));
        when(mapper.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.updateCoverImage(eq(10L), eq(5L), any())).thenAnswer(call -> {
            savedCover.set(call.getArgument(2));
            return 1;
        });
        return old;
    }

    private Course owned(String url) {
        Course course = new Course();
        course.setId(10L); course.setUserId(5L); course.setCoverImageUrl(url);
        course.setContent("<p>소개</p>");
        return course;
    }

    private CourseCreateRequest create() {
        CourseCreateRequest request = new CourseCreateRequest();
        request.setTitle("서울 여행"); request.setContent("<p>소개</p>"); request.setCountryId(7L);
        request.setDays(days());
        return request;
    }

    private CourseUpdateRequest update() {
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setTitle("서울 여행"); request.setContent("<p>소개</p>"); request.setCountryId(7L);
        request.setDays(days());
        return request;
    }

    private List<CourseDayForm> days() {
        CourseItemForm item = new CourseItemForm(); item.setDestinationId(12L); item.setDisplayOrder(1);
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1); day.setItems(List.of(item));
        return List.of(day);
    }

    private MockMultipartFile image() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("coverImage", "../../unsafe.html", "text/html", bytes.toByteArray());
    }

    private Path path(String url) { return root.resolve(url.substring("/uploads/".length())); }
}
