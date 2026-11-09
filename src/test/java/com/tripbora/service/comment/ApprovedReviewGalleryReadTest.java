package com.tripbora.service.comment;

import com.tripbora.dto.ReviewGalleryPhoto;
import com.tripbora.repository.comment.*;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApprovedReviewGalleryReadTest {
    @TempDir Path root;
    DestinationCommentImageMapper images;
    DestinationCommentService service;
    FileUploadService files;
    @BeforeEach void setup() {
        images=mock(DestinationCommentImageMapper.class);
        files=new FileUploadService(root.toString());
        service=new DestinationCommentService(mock(DestinationMapper.class),mock(DestinationCommentMapper.class),images,
                mock(UserMapper.class),files,new LocalContentLanguageDetector(),TestWithdrawnMemberName.real());
    }
    private ReviewGalleryPhoto photo(long id,String name) {
        var p=new ReviewGalleryPhoto();p.setId(id);p.setCommentId(20L);p.setDestinationId(10L);p.setImageUrl("/uploads/comments/"+name);return p;
    }
    @Test void galleryKeepsQueryOrderAndOnlyExistingFilesWithoutWritingOrCopying() throws Exception {
        Files.createDirectories(root.resolve("comments"));
        Files.write(root.resolve("comments/a.jpg"),new byte[]{1});Files.write(root.resolve("comments/b.jpg"),new byte[]{2});
        var first=photo(7,"b.jpg");var missing=photo(8,"missing.jpg");var last=photo(9,"a.jpg");
        when(images.findApprovedGallery(eq(10L),eq(30),any())).thenReturn(List.of(first,missing,last));
        assertThat(service.getApprovedGalleryPhotos(10L)).containsExactly(first,last);
        verify(images).findApprovedGallery(eq(10L),eq(30),any());
        verifyNoMoreInteractions(images);
        try(var entries=Files.list(root.resolve("comments"))){assertThat(entries.count()).isEqualTo(2);}
    }
    @Test void nextRequestReflectsRevocationDeletionHiddenReviewOrWithdrawalWithoutCache() throws Exception {
        Files.createDirectories(root.resolve("comments"));Files.write(root.resolve("comments/a.jpg"),new byte[]{1});
        when(images.findApprovedGallery(eq(10L),eq(30),any())).thenReturn(List.of(photo(7,"a.jpg")),List.of());
        assertThat(service.getApprovedGalleryPhotos(10L)).hasSize(1);
        assertThat(service.getApprovedGalleryPhotos(10L)).isEmpty();
        verify(images,times(2)).findApprovedGallery(eq(10L),eq(30),any());
    }
    @Test void missingFirstPageFilesDoNotHideLaterApprovedPhotos() throws Exception {
        Files.createDirectories(root.resolve("comments"));
        Files.write(root.resolve("comments/existing.jpg"),new byte[]{1});
        var missing=java.util.stream.LongStream.rangeClosed(1,30)
                .mapToObj(id->photo(id,"missing-"+id+".jpg")).toList();
        var existing=photo(31,"existing.jpg");
        when(images.findApprovedGallery(eq(10L),eq(30),any())).thenReturn(missing);
        when(images.findApprovedGalleryPage(10L,30,30L)).thenReturn(List.of(existing));

        assertThat(service.getApprovedGalleryPhotos(10L)).containsExactly(existing);
        verify(images).findApprovedGallery(eq(10L),eq(30),any());
        verify(images).findApprovedGalleryPage(10L,30,30L);
        verifyNoMoreInteractions(images);
    }
    @Test void fillsOnlyThirtyExistingFilesInQueryOrderAndStopsReading() throws Exception {
        Files.createDirectories(root.resolve("comments"));
        Files.write(root.resolve("comments/existing.jpg"),new byte[]{1});
        var first=java.util.stream.LongStream.rangeClosed(1,30)
                .mapToObj(id->photo(id,id==1?"missing.jpg":"existing.jpg")).toList();
        var second=java.util.stream.LongStream.rangeClosed(31,60)
                .mapToObj(id->photo(id,"existing.jpg")).toList();
        when(images.findApprovedGallery(eq(10L),eq(30),any())).thenReturn(first);
        when(images.findApprovedGalleryPage(10L,30,30L)).thenReturn(second);

        assertThat(service.getApprovedGalleryPhotos(10L)).extracting(ReviewGalleryPhoto::getId)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(2,31).boxed().toList());
        verify(images).findApprovedGallery(eq(10L),eq(30),any());
        verify(images).findApprovedGalleryPage(10L,30,30L);
        verifyNoMoreInteractions(images);
    }
    @Test void moreThanAThousandMissingFilesAreScannedInBoundedPages() throws Exception {
        Files.createDirectories(root.resolve("comments"));
        Files.write(root.resolve("comments/existing.jpg"),new byte[]{1});
        var missing=java.util.stream.LongStream.rangeClosed(1,30)
                .mapToObj(id->photo(id,"missing.jpg")).toList();
        when(images.findApprovedGallery(eq(10L),eq(30),any())).thenReturn(missing);
        when(images.findApprovedGalleryPage(eq(10L),eq(30),anyLong())).thenAnswer(call ->
                (long)call.getArgument(2)<1020L ? missing : List.of(photo(1021,"existing.jpg")));

        assertThat(service.getApprovedGalleryPhotos(10L)).extracting(ReviewGalleryPhoto::getId).containsExactly(1021L);
        verify(images,times(34)).findApprovedGalleryPage(eq(10L),eq(30),anyLong());
    }
    @Test void fileCheckRejectsMissingTraversalAndPrivateStoragePaths() throws Exception {
        Files.createDirectories(root.resolve("comments"));Files.write(root.resolve("private.jpg"),new byte[]{1});
        assertThat(files.commentFileExists("/uploads/comments/../private.jpg")).isFalse();
        assertThat(files.commentFileExists("/uploads/diaries/private.jpg")).isFalse();
        assertThat(files.commentFileExists("/uploads/comments/missing.jpg")).isFalse();
        assertThat(files.commentFileExists(null)).isFalse();
    }
    @Test void fileCheckDoesNotFollowSymbolicLinks() throws Exception {
        Files.createDirectories(root.resolve("comments"));Path secret=Files.write(root.resolve("private.jpg"),new byte[]{1});
        try {Files.createSymbolicLink(root.resolve("comments/link.jpg"),secret);}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException unsupported){Assumptions.abort("Symbolic links unavailable");}
        assertThat(files.commentFileExists("/uploads/comments/link.jpg")).isFalse();
    }
}
