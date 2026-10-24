package com.tripbora.service.kto;

import com.tripbora.dto.kto.KtoTourImageCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KtoTourImageImportServiceTest {
    private static final String BASE = "https://tong.visitkorea.or.kr/cms2/website/";
    private static final Instant CHECKED = Instant.parse("2026-10-24T00:00:00Z");
    @TempDir Path root;
    private final Map<String, byte[]> responses = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private int color;

    @Test
    void prefersAdequateFirstImageAndPreservesOriginalBytesAndLicenseMetadata() throws Exception {
        var first = photo("first", 640, 400, true, "Type3");
        var larger = photo("larger", 1200, 800, false, "Type1");

        var result = prepare(List.of(first, larger));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).sourceImageUrl()).isEqualTo(first.imageUrl());
        assertThat(result.get(0).isMain()).isTrue();
        assertThat(result.get(0).sourceType()).isEqualTo("KTO_TOURAPI");
        assertThat(result.get(0).licenseType()).isEqualTo("KOGL_TYPE_3");
        assertThat(result.get(0).licenseCheckedAt().toInstant()).isEqualTo(CHECKED);
        assertThat(result.get(0).externalContentId()).isEqualTo("123");
        for (var selected : result) {
            assertThat(path(selected)).hasBinaryContent(responses.get(selected.sourceImageUrl()));
        }
    }

    @Test
    void replacesSmallFirstImageAndOrdersAdditionalPhotosByResolution() throws Exception {
        var small = photo("small", 320, 200, true, "Type1");
        var medium = photo("medium", 640, 400, false, "Type1");
        var large = photo("large", 1000, 700, false, "Type1");
        var result = prepare(List.of(small, medium, large));

        assertThat(result).extracting(PreparedKtoPhoto::sourceImageUrl)
                .containsExactly(large.imageUrl(), medium.imageUrl(), small.imageUrl());
        assertThat(result).filteredOn(PreparedKtoPhoto::isMain).hasSize(1);
    }

    @Test
    void usesNextCandidatesWhenFirstAndSomeAdditionalDownloadsFail() throws Exception {
        var first = candidate(BASE + "missing.jpg", true, "Type1");
        var second = candidate(BASE + "broken.jpg", false, "Type1");
        responses.put(second.imageUrl(), new byte[]{1, 2, 3});
        var good = photo("good", 800, 600, false, "Type1");

        var result = prepare(List.of(first, second, good));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceImageUrl()).isEqualTo(good.imageUrl());
        assertThat(result.get(0).isMain()).isTrue();
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void prefersLandscapeReplacementOverLargerPortrait() throws Exception {
        var portrait = photo("portrait", 400, 800, false, "Type1");
        var landscape = photo("landscape", 640, 400, false, "Type1");
        assertThat(prepare(List.of(portrait, landscape)).get(0).sourceImageUrl())
                .isEqualTo(landscape.imageUrl());
    }

    @Test
    void keepsAdequatePortraitFirstImageAheadOfLandscape() throws Exception {
        var portrait = photo("first-portrait", 400, 800, true, "Type3");
        var landscape = photo("landscape", 800, 600, false, "Type1");
        assertThat(prepare(List.of(portrait, landscape)).get(0).sourceImageUrl())
                .isEqualTo(portrait.imageUrl());
    }

    @Test
    void fallsBackToPortraitAndLowerResolutionWithoutKeepingTinyOrExtremeImages() throws Exception {
        var tiny = photo("tiny", 160, 100, true, "Type1");
        var extreme = photo("extreme", 2000, 200, false, "Type1");
        var portrait = photo("portrait", 240, 360, false, "Type3");
        var result = prepare(List.of(tiny, extreme, portrait));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceImageUrl()).isEqualTo(portrait.imageUrl());
        assertThat(result.get(0).isMain()).isTrue();
        assertThat(storedFiles()).containsExactly(path(result.get(0)));
    }

    @Test
    void keepsNormalPortraitAheadOfFallbackLandscape() throws Exception {
        var landscape = photo("small-landscape", 320, 200, false, "Type1");
        var portrait = photo("portrait", 400, 640, false, "Type1");
        assertThat(prepare(List.of(landscape, portrait)).get(0).sourceImageUrl())
                .isEqualTo(portrait.imageUrl());
    }

    @Test
    void stopsAfterFiveGoodPhotosEvenWhenOverOneHundredCandidatesAreReturned() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            candidates.add(i < 5 ? photo("photo" + i, 640, 400, i == 0, "Type1")
                    : candidate(BASE + i + ".png", false, "Type1"));
        }
        var result = prepare(candidates);
        assertThat(result).hasSize(5);
        assertThat(result).filteredOn(PreparedKtoPhoto::isMain).hasSize(1);
        assertThat(requests).hasSize(5);
        assertThat(storedFiles()).hasSize(5);
    }

    @Test
    void limitsDownloadAttemptsAndCleansUpUnselectedFallbackFiles() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            candidates.add(photo("fallback" + i, 320 + i, 200, false, "Type1"));
        }
        var result = prepare(candidates);
        assertThat(result).hasSize(5);
        assertThat(result.get(0).sourceImageUrl()).isEqualTo(candidates.get(11).imageUrl());
        assertThat(requests).hasSize(12);
        assertThat(storedFiles()).hasSize(5);
    }

    @Test
    void fillsFivePhotosAfterSevenDownloadFailuresWithinTheAttemptBudget() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 7; i++) candidates.add(candidate(BASE + "missing" + i + ".png", i == 0, "Type1"));
        for (int i = 0; i < 5; i++) candidates.add(photo("valid" + i, 640, 400, false, "Type1"));
        var result = prepare(candidates);
        assertThat(result).hasSize(5);
        assertThat(result).filteredOn(PreparedKtoPhoto::isMain).hasSize(1);
        assertThat(requests).hasSize(12);
        assertThat(storedFiles()).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void savesAllAvailablePhotosWhenFewerThanFive(int count) throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) candidates.add(photo("p" + i, 640, 400, i == 0, "Type1"));
        var result = prepare(candidates);
        assertThat(result).hasSize(count);
        assertThat(result).filteredOn(PreparedKtoPhoto::isMain).hasSize(1);
    }

    @Test
    void deduplicatesNormalizedUrlsAndIdenticalBytesFromDifferentUrls() throws Exception {
        var first = photo("first", 640, 400, true, "Type1");
        var normalizedDuplicate = candidate(
                "HTTPS://TONG.VISITKOREA.OR.KR:443/cms2/website/first.png", false, "Type1");
        var sameBytes = candidate(BASE + "copy.png", false, "Type3");
        responses.put(sameBytes.imageUrl(), responses.get(first.imageUrl()));
        var different = photo("different", 640, 400, false, "Type1");
        var result = prepare(List.of(first, normalizedDuplicate, sameBytes, different, different));
        assertThat(result).extracting(PreparedKtoPhoto::sourceImageUrl)
                .containsExactly(first.imageUrl(), different.imageUrl());
        assertThat(requests).hasSize(3);
        assertThat(storedFiles()).hasSize(2);
    }

    @Test
    void preservesQueriesPathCaseAndSchemeWhenComparingUrls() throws Exception {
        var a = photo("Photo?size=one", 640, 400, true, "Type1");
        var b = photo("Photo?size=two", 640, 400, false, "Type1");
        var c = photo("photo?size=one", 640, 400, false, "Type1");
        var d = candidate(a.imageUrl().replace("https:", "http:"), false, "Type1");
        responses.put(d.imageUrl(), image(640, 400));
        assertThat(prepare(List.of(a, b, c, d))).hasSize(4);
        assertThat(requests).hasSize(4);
    }

    @Test
    void excludesDisallowedLicensesBeforeDownloading() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (String code : new String[]{"Type2", "Type4", null, "", "unknown"}) {
            candidates.add(candidate(BASE + code + ".png", false, code));
        }
        var first = photo("allowed1", 640, 400, true, " type1 ");
        var third = photo("allowed3", 640, 400, false, "TYPE3");
        candidates.addAll(List.of(first, third));
        var result = prepare(candidates);
        assertThat(result).extracting(PreparedKtoPhoto::licenseType)
                .containsExactly("KOGL_TYPE_1", "KOGL_TYPE_3");
        assertThat(requests).hasSize(2);
    }

    @Test
    void doesNotInspectBeyondCandidateLimitEvenWhenLicenseFilteringLeavesNoPhotos() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 21; i++) candidates.add(candidate(BASE + i + ".png", i == 0, "Type2"));
        candidates.add(photo("beyond-limit", 640, 400, false, "Type1"));
        assertThat(prepare(candidates)).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void emptyCandidatesAndCommonApiFailuresProduceNoPhotos() throws Exception {
        assertThat(prepare(List.of())).isEmpty();
        KtoTourService api = mock(KtoTourService.class);
        when(api.getBulkImportImages("123")).thenThrow(KtoTourApiException.upstreamFailure());
        var service = new KtoTourImageImportService(api, mock(KtoPhotoDownloadService.class));
        assertThat(service.preparePhotos("123", "여행지")).isEmpty();
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void returnsNoPhotosWhenAllDownloadsFail() throws Exception {
        List<KtoTourImageCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 101; i++) candidates.add(candidate(BASE + i + ".png", i == 0, "Type1"));
        assertThat(prepare(candidates)).isEmpty();
        assertThat(requests).hasSize(12);
        assertThat(storedFiles()).isEmpty();
    }

    private List<PreparedKtoPhoto> prepare(List<KtoTourImageCandidate> candidates) throws Exception {
        KtoTourService api = mock(KtoTourService.class);
        when(api.getBulkImportImages("123")).thenReturn(candidates);
        KtoPhotoUrlValidator validator = new KtoPhotoUrlValidator(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 10})});
        KtoPhotoHttpTransport transport = uri -> {
            requests.add(uri.toString());
            byte[] bytes = responses.get(uri.toString());
            return new KtoPhotoHttpResponse(bytes == null ? 404 : 200, "image/png",
                    bytes == null ? 0 : bytes.length,
                    new ByteArrayInputStream(bytes == null ? new byte[0] : bytes));
        };
        var downloads = new KtoPhotoDownloadService(validator, transport, root, 20 * 1024 * 1024);
        return new KtoTourImageImportService(api, downloads, Clock.fixed(CHECKED, ZoneOffset.UTC))
                .preparePhotos("123", "여행지");
    }

    private KtoTourImageCandidate photo(String name, int width, int height, boolean main, String license)
            throws Exception {
        String url = BASE + name + ".png";
        responses.put(url, image(width, height));
        return candidate(url, main, license);
    }

    private byte[] image(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ++color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private KtoTourImageCandidate candidate(String url, boolean main, String license) {
        return new KtoTourImageCandidate("123", "사진", url, license, main);
    }

    private Path path(PreparedKtoPhoto photo) {
        return root.resolve(photo.localImageUrl().substring("/uploads/".length()));
    }

    private List<Path> storedFiles() throws Exception {
        if (Files.notExists(root.resolve("destinations"))) return List.of();
        try (var files = Files.list(root.resolve("destinations"))) {
            return files.toList();
        }
    }
}
