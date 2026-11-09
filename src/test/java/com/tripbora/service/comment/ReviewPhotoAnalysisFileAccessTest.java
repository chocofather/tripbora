package com.tripbora.service.comment;

import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoAnalysisFileAccessTest {
    @TempDir Path root;
    @Test void analysisCanOnlyReadManagedCommentFilesAndDoesNotModifyThem() throws Exception {
        Files.createDirectories(root.resolve("comments"));
        var file=Files.write(root.resolve("comments/a.jpg"),new byte[]{1,2,3});
        Files.write(root.resolve("private.jpg"),new byte[]{4,5});
        var files=new FileUploadService(root.toString());
        assertThat(files.commentFileForAnalysis("/uploads/comments/a.jpg")).isEqualTo(file.toRealPath());
        for(String url:new String[]{"/uploads/comments/../private.jpg","/uploads/diaries/private.jpg","https://example.com/a.jpg","/uploads/comments/missing.jpg"})
            assertThatThrownBy(()->files.commentFileForAnalysis(url)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly((byte)1,(byte)2,(byte)3);
    }
}
