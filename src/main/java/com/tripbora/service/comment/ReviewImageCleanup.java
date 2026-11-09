package com.tripbora.service.comment;

import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.service.file.FileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.regex.Pattern;

/** 커밋 후 삭제 실패 및 중단된 업로드를 DB 참조와 대조해 재처리한다. */
@Component
@Slf4j
public class ReviewImageCleanup {
    private static final Pattern NAME = Pattern.compile("[0-9a-f-]{36}\\.(jpg|png|webp)");
    private final DestinationCommentImageMapper images;
    private final FileUploadService files;
    private final Path root;
    private String cursor = "";

    public ReviewImageCleanup(DestinationCommentImageMapper images, FileUploadService files,
                              @Value("${custom.upload-path}") String uploadPath) {
        this.images = images;
        this.files = files;
        this.root = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public synchronized void clean() {
        Path directory = root.resolve("comments");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        try {
            if (!directory.toRealPath().startsWith(root.toRealPath())) return;
            try (var stream = Files.list(directory)) {
                var batch = stream.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                        .filter(p -> p.getFileName().toString().compareTo(cursor) > 0)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString())).limit(200).toList();
                for (Path path : batch) {
                    cursor = path.getFileName().toString();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                                .isAfter(Instant.now().minus(24, ChronoUnit.HOURS))) continue;
                        String url = "/uploads/comments/" + cursor;
                        // deleted 댓글도 참조로 인정한다. 숨김 복구 정책을 유지한다.
                        if (!images.isReferenced(url)) files.deleteCommentFile(url);
                    } catch (Exception failure) {
                        log.warn("리뷰 사진 정리를 다음 주기에 재시도합니다. 원인={}", failure.getClass().getSimpleName());
                    }
                }
                if (batch.size() < 200) cursor = "";
            }
        } catch (Exception failure) {
            log.warn("리뷰 사진 폴더를 확인하지 못했습니다. 원인={}", failure.getClass().getSimpleName());
        }
    }
}
