package com.tripbora.service.kto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KtoPhotoDownloadService {

    private static final String DESTINATION_DIRECTORY = "destinations";
    private static final String DESTINATION_URL_PREFIX = "/uploads/destinations/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int BUFFER_SIZE = 8192;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final Pattern MANAGED_FILE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(?:jpg|png)$",
            Pattern.CASE_INSENSITIVE);

    private static final String COMMONS_USER_AGENT =
            "TripBoraCommonsImport/1.0 (https://github.com/chocofather/tripbora)";
    private static final String PIXABAY_USER_AGENT =
            "TripBoraPixabayImport/1.0 (https://github.com/chocofather/tripbora)";

    private final KtoPhotoUrlValidator urlValidator;
    private final KtoPhotoHttpTransport httpTransport;
    private final KtoPhotoUrlValidator commonsUrlValidator;
    private final KtoPhotoHttpTransport commonsHttpTransport;
    private final KtoPhotoUrlValidator pixabayUrlValidator;
    private final KtoPhotoHttpTransport pixabayHttpTransport;
    private final Path uploadRoot;
    private final long maxFileSize;

    @Autowired
    public KtoPhotoDownloadService(
            @Value("${custom.upload-path}") String uploadPath,
            @Value("${spring.servlet.multipart.max-file-size:10MB}") DataSize maxFileSize
    ) {
        this(
                new KtoPhotoUrlValidator(),
                new JdkKtoPhotoHttpTransport(CONNECT_TIMEOUT, READ_TIMEOUT),
                KtoPhotoUrlValidator.wikimediaCommons(),
                new JdkKtoPhotoHttpTransport(CONNECT_TIMEOUT, READ_TIMEOUT, COMMONS_USER_AGENT),
                KtoPhotoUrlValidator.pixabay(),
                new JdkKtoPhotoHttpTransport(CONNECT_TIMEOUT, READ_TIMEOUT, PIXABAY_USER_AGENT),
                Paths.get(uploadPath),
                maxFileSize.toBytes());
    }

    KtoPhotoDownloadService(
            KtoPhotoUrlValidator urlValidator,
            KtoPhotoHttpTransport httpTransport,
            Path uploadRoot,
            long maxFileSize
    ) {
        this(urlValidator, httpTransport, KtoPhotoUrlValidator.wikimediaCommons(), httpTransport,
                uploadRoot, maxFileSize);
    }

    KtoPhotoDownloadService(
            KtoPhotoUrlValidator urlValidator,
            KtoPhotoHttpTransport httpTransport,
            KtoPhotoUrlValidator commonsUrlValidator,
            KtoPhotoHttpTransport commonsHttpTransport,
            Path uploadRoot,
            long maxFileSize
    ) {
        this(urlValidator, httpTransport, commonsUrlValidator, commonsHttpTransport,
                KtoPhotoUrlValidator.pixabay(), httpTransport, uploadRoot, maxFileSize);
    }

    KtoPhotoDownloadService(
            KtoPhotoUrlValidator urlValidator,
            KtoPhotoHttpTransport httpTransport,
            KtoPhotoUrlValidator commonsUrlValidator,
            KtoPhotoHttpTransport commonsHttpTransport,
            KtoPhotoUrlValidator pixabayUrlValidator,
            KtoPhotoHttpTransport pixabayHttpTransport,
            Path uploadRoot,
            long maxFileSize
    ) {
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("maxFileSize must be positive");
        }
        this.urlValidator = urlValidator;
        this.httpTransport = httpTransport;
        this.commonsUrlValidator = commonsUrlValidator;
        this.commonsHttpTransport = commonsHttpTransport;
        this.pixabayUrlValidator = pixabayUrlValidator;
        this.pixabayHttpTransport = pixabayHttpTransport;
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
    }

    public KtoDownloadedPhoto download(String sourceImageUrl) {
        return download(urlValidator.validate(sourceImageUrl), httpTransport, false).photo();
    }

    /** 원본을 변환하지 않고 크기와 바이트 해시만 반환하는 일괄등록 전용 경로. */
    InspectedPhoto downloadForSelection(String sourceImageUrl) {
        return download(urlValidator.validate(sourceImageUrl), httpTransport, true);
    }

    /**
     * Wikimedia Commons 사진을 여행지 이미지 저장소에 내려받는다.
     * 관광사진과 같은 크기·형식·경로 검증을 쓰고, 주소는 upload.wikimedia.org HTTPS만 허용한다.
     * 리다이렉트는 따라가지 않고 실패로 처리한다.
     */
    public KtoDownloadedPhoto downloadCommonsImage(String imageUrl) {
        return download(commonsUrlValidator.validate(imageUrl), commonsHttpTransport, false).photo();
    }

    /**
     * Pixabay 사진을 여행지 이미지 저장소에 내려받는다. 주소는 서버가 Pixabay 검색 응답에서 받은 값만 넘기며,
     * pixabay.com·cdn.pixabay.com HTTPS만 허용한다. 크기·형식·경로 검증과 리다이렉트 거부는 관광사진과 같다.
     */
    public KtoDownloadedPhoto downloadPixabayImage(String imageUrl) {
        return download(pixabayUrlValidator.validate(imageUrl), pixabayHttpTransport, false).photo();
    }

    private InspectedPhoto download(URI sourceUri, KtoPhotoHttpTransport transport, boolean inspect) {
        Path temporary = null;
        Path completedFile = null;
        boolean completed = false;

        try (KtoPhotoHttpResponse response = transport.get(sourceUri)) {
            String declaredContentType = validateResponse(response);
            Path destinationDirectory = destinationDirectory();
            temporary = Files.createTempFile(destinationDirectory, ".kto-photo-", ".download");

            MessageDigest digest = inspect ? sha256Digest() : null;
            long fileSize = copyBounded(response.body(), temporary, digest);
            if (fileSize == 0) {
                throw new KtoPhotoDownloadException("빈 이미지 파일");
            }

            ValidatedImage validated = detectAndValidateImage(temporary, declaredContentType);
            ImageFormat imageFormat = validated.format();
            String generatedName = UUID.randomUUID() + "." + imageFormat.extension;
            completedFile = destinationDirectory.resolve(generatedName).normalize();
            ensureContained(destinationDirectory, completedFile);
            moveCompletedFile(temporary, completedFile);

            completed = true;
            KtoDownloadedPhoto photo = new KtoDownloadedPhoto(
                    DESTINATION_URL_PREFIX + generatedName,
                    sourceUri.toString(),
                    imageFormat.contentType,
                    fileSize);
            return new InspectedPhoto(photo, validated.width(), validated.height(),
                    digest == null ? null : HexFormat.of().formatHex(digest.digest()));
        } catch (InvalidKtoPhotoUrlException | KtoPhotoDownloadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new KtoPhotoDownloadException("다운로드 중 오류(" + exception.getClass().getSimpleName() + ")");
        } finally {
            deleteQuietly(temporary);
            if (!completed) {
                deleteQuietly(completedFile);
            }
        }
    }

    public boolean deleteDownloadedPhoto(KtoDownloadedPhoto downloadedPhoto) {
        return downloadedPhoto != null && deleteDownloadedPhoto(downloadedPhoto.localImageUrl());
    }

    public boolean deleteDownloadedPhoto(String localImageUrl) {
        String fileName = managedFileName(localImageUrl);
        if (fileName == null) {
            return false;
        }

        try {
            if (Files.notExists(uploadRoot)) {
                return false;
            }
            Path realUploadRoot = uploadRoot.toRealPath();
            Path destinationDirectory = realUploadRoot.resolve(DESTINATION_DIRECTORY).normalize();
            ensureContained(realUploadRoot, destinationDirectory);
            if (Files.notExists(destinationDirectory)) {
                return false;
            }
            Path realDestinationDirectory = destinationDirectory.toRealPath();
            ensureContained(realUploadRoot, realDestinationDirectory);
            Path target = realDestinationDirectory.resolve(fileName).normalize();
            ensureContained(realDestinationDirectory, target);
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new KtoPhotoDownloadException();
        }
    }

    private String validateResponse(KtoPhotoHttpResponse response) {
        // 요청 제한은 다른 실패와 구분해 둔다. KTO 흐름에서는 기존과 같은 다운로드 실패로 처리된다.
        if (response != null && (response.statusCode() == 429 || response.statusCode() == 503)) {
            throw new PhotoDownloadRateLimitedException(response.retryAfter());
        }
        if (response == null) {
            throw new KtoPhotoDownloadException("응답 없음");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new KtoPhotoDownloadException("HTTP 상태 " + response.statusCode());
        }
        if (response.body() == null) {
            throw new KtoPhotoDownloadException("응답 본문 없음");
        }
        if (response.contentLength() > maxFileSize) {
            throw new KtoPhotoDownloadException(
                    "용량 제한 초과(" + response.contentLength() + " bytes > " + maxFileSize + " bytes)");
        }

        String contentType = normalizeContentType(response.contentType());
        if (!("image/jpeg".equals(contentType) || "image/png".equals(contentType))) {
            throw new KtoPhotoDownloadException("지원하지 않는 Content-Type(" + contentType + ")");
        }
        return contentType;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        String mediaType = parameters >= 0 ? contentType.substring(0, parameters) : contentType;
        String normalized = mediaType.strip().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    private long copyBounded(InputStream input, Path temporary, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (true) {
                long remainingWithSentinel = maxFileSize - total + 1;
                int requested = (int) Math.min(buffer.length, remainingWithSentinel);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    return total;
                }
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxFileSize) {
                    throw new KtoPhotoDownloadException("용량 제한 초과(" + maxFileSize + " bytes 초과)");
                }
                output.write(buffer, 0, read);
                if (digest != null) {
                    digest.update(buffer, 0, read);
                }
            }
        }
    }

    private ValidatedImage detectAndValidateImage(Path file, String declaredContentType) {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(file.toFile())) {
            if (imageInput == null) {
                throw new KtoPhotoDownloadException("이미지 파일을 읽을 수 없음");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new KtoPhotoDownloadException("이미지 형식을 판별할 수 없음");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                ImageFormat detected = ImageFormat.from(reader.getFormatName());
                if (detected == null || !detected.contentType.equals(declaredContentType)) {
                    throw new KtoPhotoDownloadException("실제 이미지 형식(" + reader.getFormatName()
                            + ")과 Content-Type(" + declaredContentType + ") 불일치");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new KtoPhotoDownloadException("이미지 크기 제한 초과(" + width + "x" + height
                            + ", 최대 " + MAX_IMAGE_PIXELS + "픽셀)");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new KtoPhotoDownloadException("이미지 디코딩 실패");
                }
                return new ValidatedImage(detected, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KtoPhotoDownloadException downloadException) {
                throw downloadException;
            }
            throw new KtoPhotoDownloadException(
                    "이미지 디코딩 중 오류(" + exception.getClass().getSimpleName() + ")");
        }
    }

    private Path destinationDirectory() throws IOException {
        Files.createDirectories(uploadRoot);
        Path realUploadRoot = uploadRoot.toRealPath();
        Path destinationDirectory = realUploadRoot.resolve(DESTINATION_DIRECTORY).normalize();
        ensureContained(realUploadRoot, destinationDirectory);
        Files.createDirectories(destinationDirectory);
        Path realDestinationDirectory = destinationDirectory.toRealPath();
        ensureContained(realUploadRoot, realDestinationDirectory);
        return realDestinationDirectory;
    }

    private String managedFileName(String localImageUrl) {
        if (localImageUrl == null || !localImageUrl.startsWith(DESTINATION_URL_PREFIX)) {
            return null;
        }
        String fileName = localImageUrl.substring(DESTINATION_URL_PREFIX.length());
        return MANAGED_FILE_NAME.matcher(fileName).matches() ? fileName : null;
    }

    private void moveCompletedFile(Path temporary, Path completedFile) throws IOException {
        try {
            Files.move(temporary, completedFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, completedFile);
        }
    }

    private void ensureContained(Path root, Path target) {
        if (!target.startsWith(root)) {
            throw new KtoPhotoDownloadException();
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 원래 다운로드 실패를 우선 전달한다.
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    record InspectedPhoto(KtoDownloadedPhoto photo, int width, int height, String sha256) { }

    private record ValidatedImage(ImageFormat format, int width, int height) { }

    private enum ImageFormat {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png");

        private final String extension;
        private final String contentType;

        ImageFormat(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        private static ImageFormat from(String formatName) {
            if (formatName == null) {
                return null;
            }
            return switch (formatName.toLowerCase(Locale.ROOT)) {
                case "jpg", "jpeg" -> JPEG;
                case "png" -> PNG;
                default -> null;
            };
        }
    }
}
