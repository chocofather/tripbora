package com.tripbora.service.kto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KtoPhotoDownloadServiceTest {

    private static final String SOURCE_URL =
            "https://tong.visitkorea.or.kr/cms2/website/75/original-name.jpg";
    private static byte[] jpeg;
    private static byte[] png;

    @TempDir
    Path uploadRoot;

    @BeforeAll
    static void createImages() throws IOException {
        jpeg = image("jpg");
        png = image("png");
    }

    @Test
    void downloadsValidatedImageWithGeneratedNameAndReturnsLocalMetadata() throws Exception {
        FakeTransport transport = responding(200, "image/jpeg", jpeg.length, jpeg);
        KtoPhotoDownloadService service = service(transport, 10 * 1024);

        KtoDownloadedPhoto result = service.download(SOURCE_URL);

        assertThat(result.localImageUrl())
                .startsWith("/uploads/destinations/")
                .endsWith(".jpg")
                .doesNotContain("original-name");
        assertThat(result.sourceImageUrl()).isEqualTo(SOURCE_URL);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.fileSize()).isEqualTo(jpeg.length);
        assertThat(storedPath(result)).hasBinaryContent(jpeg);
        assertThat(filesInDestinationDirectory()).containsExactly(storedPath(result));
    }

    @Test
    void downloadsTourApiResourcePhotoImage() throws Exception {
        String resourcePhotoUrl = "https://tong.visitkorea.or.kr/cms/resource_photo/79/3414579_image2_1.jpg";
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), 10 * 1024);

        KtoDownloadedPhoto result = service.download(resourcePhotoUrl);

        assertThat(result.sourceImageUrl()).isEqualTo(resourcePhotoUrl);
        assertThat(storedPath(result)).hasBinaryContent(jpeg);
    }

    @Test
    void selectionInspectionKeepsJpegBytesAndReportsDimensionsAndOriginalFileHash() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), 10 * 1024);

        var inspected = service.downloadForSelection(SOURCE_URL);

        assertThat(inspected.width()).isEqualTo(2);
        assertThat(inspected.height()).isEqualTo(2);
        assertThat(inspected.sha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(jpeg)));
        assertThat(storedPath(inspected.photo())).hasBinaryContent(jpeg);
    }

    @Test
    void acceptsKtoImageJpgAliasAsCanonicalJpeg() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpg", jpeg.length, jpeg), 10 * 1024);

        KtoDownloadedPhoto result = service.download(SOURCE_URL);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.localImageUrl()).endsWith(".jpg");
        assertThat(storedPath(result)).hasBinaryContent(jpeg);
    }

    @Test
    void usesVerifiedPngTypeForGeneratedExtension() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/png; charset=binary", -1, png), 10 * 1024);

        KtoDownloadedPhoto result = service.download(SOURCE_URL);

        assertThat(result.localImageUrl()).endsWith(".png");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(storedPath(result)).hasBinaryContent(png);
    }

    @Test
    void rejectsHtmlJsonEmptyAndBytesThatAreNotAnActualImage() throws Exception {
        assertDownloadFailure(responding(200, "text/html", 13, "<html></html>".getBytes()));
        assertDownloadFailure(responding(200, "application/json", 2, "{}".getBytes()));
        assertDownloadFailure(responding(200, "image/jpeg", 0, new byte[0]));
        assertDownloadFailure(responding(200, "image/jpeg", 12, "not an image".getBytes()));

        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void rejectsUnsupportedMimeTypesWithoutBroadeningTheImageAllowList() throws Exception {
        for (String contentType : List.of(
                "image/gif",
                "image/svg+xml",
                "image/webp",
                "image/bmp",
                "application/octet-stream",
                "text/html",
                "application/json")) {
            assertDownloadFailure(responding(200, contentType, jpeg.length, jpeg));
        }

        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void rejectsImageJpgAliasWhenBodyIsNotAnActualJpeg() throws Exception {
        byte[] notAnImage = "not an image".getBytes();

        assertDownloadFailure(responding(200, "image/jpg", notAnImage.length, notAnImage));

        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void rejectsRedirectClientAndServerErrorsWithoutSavingFiles() throws Exception {
        assertDownloadFailure(responding(302, "text/html", 0, new byte[0]));
        assertDownloadFailure(responding(404, "image/jpeg", jpeg.length, jpeg));
        assertDownloadFailure(responding(500, "image/jpeg", jpeg.length, jpeg));

        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void wrapsTimeoutWithoutExposingSourceUrl() {
        KtoPhotoHttpTransport transport = uri -> {
            throw new SocketTimeoutException("synthetic timeout");
        };
        KtoPhotoDownloadService service = service(transport, 10 * 1024);

        assertThatThrownBy(() -> service.download(SOURCE_URL))
                .isInstanceOf(KtoPhotoDownloadException.class)
                .hasMessage("관광사진을 다운로드하지 못했습니다.")
                .hasNoCause()
                .message().doesNotContain(SOURCE_URL);
    }

    @Test
    void rejectsOversizedContentLengthBeforeReadingBody() throws Exception {
        TrackingInputStream body = new TrackingInputStream(jpeg);
        KtoPhotoHttpTransport transport = uri ->
                new KtoPhotoHttpResponse(200, "image/jpeg", 1025, body);
        KtoPhotoDownloadService service = service(transport, 1024);

        assertThatThrownBy(() -> service.download(SOURCE_URL))
                .isInstanceOf(KtoPhotoDownloadException.class);
        assertThat(body.bytesRead()).isZero();
        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void stopsStreamingWhenUnknownLengthBodyExceedsLimitAndCleansTemporaryFile() throws Exception {
        TrackingInputStream body = new TrackingInputStream(new byte[4096]);
        KtoPhotoHttpTransport transport = uri ->
                new KtoPhotoHttpResponse(200, "image/jpeg", -1, body);
        KtoPhotoDownloadService service = service(transport, 1024);

        assertThatThrownBy(() -> service.download(SOURCE_URL))
                .isInstanceOf(KtoPhotoDownloadException.class);
        assertThat(body.bytesRead()).isLessThanOrEqualTo(1025);
        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void acceptsAnImageExactlyAtConfiguredLimit() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), jpeg.length);

        KtoDownloadedPhoto result = service.download(SOURCE_URL);

        assertThat(result.fileSize()).isEqualTo(jpeg.length);
        assertThat(storedPath(result)).isRegularFile();
    }

    @Test
    void deletesOnlyTheManagedDownloadedPhoto() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), 10 * 1024);
        KtoDownloadedPhoto downloaded = service.download(SOURCE_URL);
        Path stored = storedPath(downloaded);

        assertThat(service.deleteDownloadedPhoto(downloaded)).isTrue();

        assertThat(stored).doesNotExist();
    }

    @Test
    void deletesManagedDownloadedPhotoByItsLocalUrl() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), 10 * 1024);
        KtoDownloadedPhoto downloaded = service.download(SOURCE_URL);
        Path stored = storedPath(downloaded);

        assertThat(service.deleteDownloadedPhoto(downloaded.localImageUrl())).isTrue();

        assertThat(stored).doesNotExist();
    }

    @Test
    void refusesForgedOrUnmanagedCleanupPaths() throws Exception {
        KtoPhotoDownloadService service = service(
                responding(200, "image/jpeg", jpeg.length, jpeg), 10 * 1024);
        Path outside = uploadRoot.resolve("outside.jpg");
        Files.write(outside, jpeg);

        assertThat(service.deleteDownloadedPhoto(new KtoDownloadedPhoto(
                "/uploads/destinations/../../outside.jpg", SOURCE_URL, "image/jpeg", jpeg.length)))
                .isFalse();
        assertThat(service.deleteDownloadedPhoto(new KtoDownloadedPhoto(
                "/uploads/destinations/original-name.jpg", SOURCE_URL, "image/jpeg", jpeg.length)))
                .isFalse();
        assertThat(service.deleteDownloadedPhoto("/uploads/destinations/../../outside.jpg"))
                .isFalse();

        assertThat(outside).isRegularFile();
    }

    @Test
    void commonsImageUsesItsOwnTransportAndSameManagedStorage() throws Exception {
        List<URI> requested = new java.util.ArrayList<>();
        KtoPhotoHttpTransport commonsTransport = uri -> {
            requested.add(uri);
            return new KtoPhotoHttpResponse(200, "image/jpeg", jpeg.length, new ByteArrayInputStream(jpeg));
        };
        KtoPhotoHttpTransport ktoTransport = uri -> {
            throw new AssertionError("관광사진 transport 로 Commons 를 받으면 안 된다");
        };
        KtoPhotoDownloadService service = new KtoPhotoDownloadService(publicAddressValidator(), ktoTransport,
                KtoPhotoUrlValidator.wikimediaCommons(host -> new InetAddress[]{
                        InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 7})}),
                commonsTransport, uploadRoot, 10 * 1024);
        String url = "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a8/Tour_Eiffel.jpg/1920px-Tour_Eiffel.jpg";

        KtoDownloadedPhoto result = service.downloadCommonsImage(url);
        KtoDownloadedPhoto original = service.downloadCommonsImage(
                "https://upload.wikimedia.org/wikipedia/commons/a/a8/Tour_Eiffel.jpg");
        service.deleteDownloadedPhoto(original.localImageUrl());

        assertThat(requested).hasSize(2).first().isEqualTo(URI.create(url));
        assertThat(result.localImageUrl()).startsWith("/uploads/destinations/").endsWith(".jpg")
                .doesNotContain("Tour_Eiffel");
        assertThat(storedPath(result)).hasBinaryContent(jpeg);
        assertThat(service.deleteDownloadedPhoto(result.localImageUrl())).isTrue();
        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    @Test
    void commonsRedirectAndUntrustedAddressesAreRefusedWithoutLeavingFiles() throws Exception {
        KtoPhotoDownloadService redirecting = new KtoPhotoDownloadService(publicAddressValidator(),
                responding(200, "image/jpeg", jpeg.length, jpeg),
                KtoPhotoUrlValidator.wikimediaCommons(host -> new InetAddress[]{
                        InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 7})}),
                responding(302, "text/html", 0, new byte[0]), uploadRoot, 10 * 1024);
        String url = "https://upload.wikimedia.org/wikipedia/commons/a/a8/Tour_Eiffel.jpg";

        assertThatThrownBy(() -> redirecting.downloadCommonsImage(url))
                .isInstanceOf(KtoPhotoDownloadException.class);
        assertThatThrownBy(() -> redirecting.downloadCommonsImage(
                "http://upload.wikimedia.org/wikipedia/commons/a/a8/Tour_Eiffel.jpg"))
                .isInstanceOf(InvalidKtoPhotoUrlException.class);
        assertThatThrownBy(() -> redirecting.downloadCommonsImage(
                "https://upload.wikimedia.org.evil.example/wikipedia/commons/a.jpg"))
                .isInstanceOf(InvalidKtoPhotoUrlException.class);
        assertThatThrownBy(() -> redirecting.downloadCommonsImage(
                "https://thumb.wikimedia.org/wikipedia/en/a.jpg"))
                .isInstanceOf(InvalidKtoPhotoUrlException.class);
        assertThatThrownBy(() -> redirecting.downloadCommonsImage(SOURCE_URL))
                .isInstanceOf(InvalidKtoPhotoUrlException.class);

        KtoPhotoDownloadService privateAddress = new KtoPhotoDownloadService(publicAddressValidator(),
                responding(200, "image/jpeg", jpeg.length, jpeg),
                KtoPhotoUrlValidator.wikimediaCommons(host -> new InetAddress[]{InetAddress.getByName("10.0.0.5")}),
                responding(200, "image/jpeg", jpeg.length, jpeg), uploadRoot, 10 * 1024);
        assertThatThrownBy(() -> privateAddress.downloadCommonsImage(url))
                .isInstanceOf(InvalidKtoPhotoUrlException.class);
        assertThat(filesInDestinationDirectory()).isEmpty();
    }

    private void assertDownloadFailure(KtoPhotoHttpTransport transport) {
        KtoPhotoDownloadService service = service(transport, 10 * 1024);
        assertThatThrownBy(() -> service.download(SOURCE_URL))
                .isInstanceOf(KtoPhotoDownloadException.class)
                .hasMessage("관광사진을 다운로드하지 못했습니다.");
    }

    private KtoPhotoDownloadService service(KtoPhotoHttpTransport transport, long maxBytes) {
        return new KtoPhotoDownloadService(publicAddressValidator(), transport, uploadRoot, maxBytes);
    }

    private KtoPhotoUrlValidator publicAddressValidator() {
        return new KtoPhotoUrlValidator(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 10})
        });
    }

    private static FakeTransport responding(int status, String contentType, long contentLength, byte[] body) {
        return new FakeTransport(new KtoPhotoHttpResponse(
                status, contentType, contentLength, new ByteArrayInputStream(body)));
    }

    private Path storedPath(KtoDownloadedPhoto result) {
        return uploadRoot.resolve(result.localImageUrl().substring("/uploads/".length()));
    }

    private List<Path> filesInDestinationDirectory() throws IOException {
        Path directory = uploadRoot.resolve("destinations");
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private record FakeTransport(KtoPhotoHttpResponse response) implements KtoPhotoHttpTransport {
        @Override
        public KtoPhotoHttpResponse get(URI uri) {
            return response;
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int bytesRead;

        private TrackingInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        private int bytesRead() {
            return bytesRead;
        }
    }
}
