package com.tripbora.service.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 공개 ResourceHandler에 포함되지 않는 업로드 볼륨의 전용 비공개 저장소. */
@Service
public class TravelPlanChatImageStorage {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Pattern MANAGED_KEY = Pattern.compile(
            "^[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(?:jpg|png|webp)$");
    private final FileUploadService fileUploadService;
    private final Path root;
    private String staleCursor = "";

    public TravelPlanChatImageStorage(FileUploadService fileUploadService,
                                      @Value("${custom.upload-path}") String uploadPath) {
        this.fileUploadService = fileUploadService;
        this.root = Path.of(uploadPath).toAbsolutePath().normalize().resolve("private/travel-plan-chat");
    }

    public StoredChatImage store(Long planId, MultipartFile file) {
        if (planId == null || planId <= 0) {
            throw new IllegalArgumentException("올바르지 않은 여행 계획입니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImageFormatException(FileUploadService.EMPTY_IMAGE_MESSAGE);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new UnsupportedImageFormatException(FileUploadService.OVERSIZED_IMAGE_MESSAGE);
        }
        byte[] content = uploadSnapshot(file);
        FileUploadService.ValidatedImage image = fileUploadService.validatedGeneralImageDetails(new UploadSnapshot(content));
        if (Math.max(image.width(), image.height()) > 20_000
                || (long) image.width() * image.height() > 40_000_000L) {
            throw new UnsupportedImageFormatException("이미지는 한 변 20000px, 4000만 화소 이하만 업로드할 수 있습니다.");
        }
        String key = planId + "/" + UUID.randomUUID() + "." + image.extension();
        Path target = resolve(key);
        boolean created = false;
        try {
            createDirectoriesWithoutLinks(target.getParent());
            // CREATE_NEW never follows an existing file link and never overwrites another upload.
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(content);
            }
            return new StoredChatImage(key, image.contentType(), content.length, image.width(), image.height());
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    rejectLinks(target);
                    Files.deleteIfExists(target);
                } catch (IOException | IllegalArgumentException ignored) {
                    // Preserve the original upload failure.
                }
            }
            if (exception instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalStateException("채팅 이미지를 저장하지 못했습니다.", exception);
        }
    }

    /** 호출자가 계획 멤버 권한을 확인한 뒤에만 사용하는 서버 경로. */
    public Path read(String storageKey) {
        Path target = resolve(storageKey);
        rejectLinks(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("채팅 이미지를 찾을 수 없습니다.");
        }
        try {
            StoredImageFormat format = StoredImageFormat.detect(target).orElseThrow(
                    () -> new IllegalArgumentException("채팅 이미지 형식이 올바르지 않습니다."));
            if (!format.matchesStorageKey(storageKey) || format == StoredImageFormat.GIF) {
                throw new IllegalArgumentException("채팅 이미지 형식이 올바르지 않습니다.");
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalArgumentException("채팅 이미지를 읽을 수 없습니다.", exception);
        }
    }

    /** 없는 파일 삭제는 성공으로 취급하므로 롤백·정리에서 재시도할 수 있다. */
    public void delete(String storageKey) {
        Path target = resolve(storageKey);
        rejectLinks(target);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("채팅 이미지를 삭제하지 못했습니다.", exception);
        }
    }

    /** DB 참조 여부는 호출자가 검사한다. 파일 후보는 24시간 이상 지난 최대 200개다. */
    public synchronized List<String> staleKeys(int limit) {
        if (limit <= 0 || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        rejectLinks(root);
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int batchSize = Math.min(limit, 200);
        // Keep only a bounded page on either side of the cursor, independently of DB deletion.
        TreeSet<String> afterCursor = new TreeSet<>();
        TreeSet<String> wrapped = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(root, 2)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> MANAGED_KEY.matcher(keyOf(path)).matches())
                    .filter(path -> olderThan(path, cutoff))
                    .map(this::keyOf).forEach(key -> {
                        TreeSet<String> page = key.compareTo(staleCursor) > 0 ? afterCursor : wrapped;
                        page.add(key);
                        if (page.size() > batchSize) {
                            page.pollLast();
                        }
                    });
            List<String> result = new ArrayList<>(afterCursor);
            for (String key : wrapped) {
                if (result.size() == batchSize) break;
                result.add(key);
            }
            if (!result.isEmpty()) {
                staleCursor = result.get(result.size() - 1);
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("채팅 이미지 정리 대상을 확인하지 못했습니다.", exception);
        }
    }

    private byte[] uploadSnapshot(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] content = input.readNBytes(MAX_BYTES + 1);
            if (content.length == 0 || content.length > MAX_BYTES) {
                throw new UnsupportedImageFormatException(FileUploadService.OVERSIZED_IMAGE_MESSAGE);
            }
            return content;
        } catch (IOException exception) {
            throw new UnsupportedImageFormatException(FileUploadService.UNSUPPORTED_IMAGE_MESSAGE);
        }
    }

    /** All validation reads and the final write use the same bounded upload bytes. */
    private record UploadSnapshot(byte[] content) implements MultipartFile {
        @Override public String getName() { return "image"; }
        @Override public String getOriginalFilename() { return null; }
        @Override public String getContentType() { return null; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), content); }
    }

    private boolean olderThan(Path path, Instant cutoff) {
        try {
            rejectLinks(path);
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    private String keyOf(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !MANAGED_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("올바르지 않은 채팅 이미지 저장 키입니다.");
        }
        return root.resolve(storageKey);
    }

    /** Existing ancestors, including the configured upload path, may not be symbolic links. */
    private void rejectLinks(Path target) {
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("링크 경로는 채팅 이미지 저장소로 사용할 수 없습니다.");
            }
        }
    }

    private void createDirectoriesWithoutLinks(Path directory) throws IOException {
        rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            createDirectoriesWithoutLinks(directory.getParent());
            try {
                Files.createDirectory(directory);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another upload may have created this same plan directory.
            }
        }
        rejectLinks(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("채팅 이미지 저장 폴더가 올바르지 않습니다.");
        }
    }

    public record StoredChatImage(String storageKey, String contentType, long byteSize, int width, int height) {
    }
}
