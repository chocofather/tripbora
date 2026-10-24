package com.tripbora.service.kto;

import com.tripbora.dto.kto.KtoTourImageCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TourAPI 대표/추가 이미지를 여행지 이미지로 내려받는다.
 * 저장 위치와 파일 검증은 단건 등록과 같은 {@link KtoPhotoDownloadService} 를 쓰고,
 * DB 저장은 {@link KtoPhotoImportPersistenceService} 가 그대로 맡는다.
 * 저작권 코드를 확인할 수 없거나 내려받지 못한 이미지는 건너뛰고 여행지 등록 자체는 진행한다.
 */
@Slf4j
@Service
public class KtoTourImageImportService {

    private static final String SOURCE_TYPE = "KTO_TOURAPI";
    private static final int MAX_PHOTOS = 5;
    private static final int MAX_CANDIDATES = 1 + KtoTourService.BULK_IMPORT_ADDITIONAL_IMAGE_LIMIT;
    private static final int MAX_DOWNLOADS = 12;
    // KTO 원본 중 640px급도 허용한다. 부족하면 320px급을 사용하되 썸네일/극단 비율은 제외한다.
    private static final int NORMAL_LONG_EDGE = 640;
    private static final int NORMAL_SHORT_EDGE = 400;
    private static final int FALLBACK_LONG_EDGE = 320;
    private static final int FALLBACK_SHORT_EDGE = 200;
    private static final double NORMAL_MAX_ASPECT = 2.5;
    private static final double FALLBACK_MAX_ASPECT = 4.0;
    private static final Comparator<DownloadedCandidate> RESOLUTION_ORDER = Comparator
            .comparing(DownloadedCandidate::normalQuality).reversed()
            .thenComparing(Comparator.comparingLong(DownloadedCandidate::pixels).reversed());
    private static final Comparator<DownloadedCandidate> MAIN_ORDER = Comparator
            .comparing(DownloadedCandidate::normalQuality).reversed()
            .thenComparing(Comparator.comparing(DownloadedCandidate::landscape).reversed())
            .thenComparing(Comparator.comparingLong(DownloadedCandidate::pixels).reversed());

    private final KtoTourService ktoTourService;
    private final KtoPhotoDownloadService downloadService;
    private final Clock clock;

    @Autowired
    public KtoTourImageImportService(KtoTourService ktoTourService,
                                     KtoPhotoDownloadService downloadService) {
        this(ktoTourService, downloadService, Clock.systemDefaultZone());
    }

    KtoTourImageImportService(KtoTourService ktoTourService,
                              KtoPhotoDownloadService downloadService,
                              Clock clock) {
        this.ktoTourService = ktoTourService;
        this.downloadService = downloadService;
        this.clock = clock;
    }

    public List<PreparedKtoPhoto> preparePhotos(String contentId, String fallbackTitle) {
        List<KtoTourImageCandidate> candidates;
        try {
            candidates = ktoTourService.getBulkImportImages(contentId).stream().limit(MAX_CANDIDATES).toList();
        } catch (KtoTourApiException exception) {
            log.warn("TourAPI 이미지를 조회하지 못해 이미지 없이 등록합니다. (contentId={})", contentId);
            logSelection(contentId, 0, new SelectionCounts(), 0, "NO_CANDIDATES");
            return List.of();
        }

        Timestamp licenseCheckedAt = Timestamp.from(clock.instant());
        List<DownloadedCandidate> usable = new ArrayList<>();
        List<KtoDownloadedPhoto> ownedFiles = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenHashes = new HashSet<>();
        SelectionCounts counts = new SelectionCounts();
        Set<String> retainedUrls = new HashSet<>();
        try {
            inspectCandidates(candidates, usable, ownedFiles, seenUrls, seenHashes, counts);
            DownloadedCandidate main = usable.stream()
                    .filter(photo -> photo.candidate().main() && photo.normalQuality())
                    .findFirst().orElseGet(() -> usable.stream().min(MAIN_ORDER).orElse(null));
            List<DownloadedCandidate> selected = new ArrayList<>();
            if (main != null) {
                selected.add(main);
                usable.stream().filter(photo -> photo != main).sorted(RESOLUTION_ORDER)
                        .limit(MAX_PHOTOS - 1).forEach(selected::add);
            }
            List<PreparedKtoPhoto> prepared = new ArrayList<>();
            for (DownloadedCandidate photo : selected) {
                prepared.add(new PreparedKtoPhoto(
                        photo.downloaded().photo().localImageUrl(),
                        photo.downloaded().photo().sourceImageUrl(),
                        photo.candidate().contentId(),
                        firstNonBlank(photo.candidate().imageName(), fallbackTitle),
                        null, photo == main, new Timestamp(licenseCheckedAt.getTime()),
                        SOURCE_TYPE, photo.license().name()));
            }
            logSelection(contentId, candidates.size(), counts, prepared.size(), mainReason(main));
            List<PreparedKtoPhoto> result = List.copyOf(prepared);
            // 반환한 파일만 호출부의 DB 저장/실패 정리 정책으로 소유권을 넘긴다.
            result.forEach(photo -> retainedUrls.add(photo.localImageUrl()));
            return result;
        } finally {
            for (KtoDownloadedPhoto file : ownedFiles) {
                if (!retainedUrls.contains(file.localImageUrl())) {
                    cleanup(file, contentId);
                }
            }
        }
    }

    private void inspectCandidates(List<KtoTourImageCandidate> candidates,
                                   List<DownloadedCandidate> usable, List<KtoDownloadedPhoto> ownedFiles,
                                   Set<String> seenUrls, Set<String> seenHashes, SelectionCounts counts) {
        for (KtoTourImageCandidate candidate : candidates) {
            if (counts.downloadAttempts >= MAX_DOWNLOADS) break;
            counts.inspected++;
            KtoFestivalImageLicense license = KtoFestivalImageLicense
                    .fromCopyrightDivisionCode(candidate.copyrightDivisionCode())
                    .orElse(null);
            if (license == null) {
                counts.licenseExcluded++;
                continue;
            }
            try {
                if (!seenUrls.add(imageUrlKey(candidate.imageUrl()))) {
                    counts.duplicateExcluded++;
                    continue;
                }
                counts.downloadAttempts++;
                KtoPhotoDownloadService.InspectedPhoto downloaded =
                        downloadService.downloadForSelection(candidate.imageUrl());
                ownedFiles.add(downloaded.photo());
                if (!seenHashes.add(downloaded.sha256())) {
                    counts.duplicateExcluded++;
                    continue;
                }
                boolean normal = meetsQuality(downloaded, NORMAL_LONG_EDGE, NORMAL_SHORT_EDGE, NORMAL_MAX_ASPECT);
                if (!normal) counts.qualityBelowStandard++;
                if (!meetsQuality(downloaded, FALLBACK_LONG_EDGE, FALLBACK_SHORT_EDGE, FALLBACK_MAX_ASPECT)) {
                    counts.qualityExcluded++;
                    continue;
                }
                usable.add(new DownloadedCandidate(candidate, downloaded, license, normal));
                // 적정 품질 5장과 대표 후보가 있으면 불필요한 추가 다운로드를 멈춘다.
                if (usable.stream().filter(DownloadedCandidate::normalQuality).count() >= MAX_PHOTOS
                        && usable.stream().anyMatch(photo -> photo.normalQuality()
                        && (photo.candidate().main() || photo.landscape()))) {
                    break;
                }
            } catch (InvalidKtoPhotoUrlException | KtoPhotoDownloadException exception) {
                counts.downloadFailed++;
                log.warn("TourAPI 이미지를 저장하지 못해 건너뜁니다. (contentId={})", candidate.contentId());
            }
        }
    }

    private boolean meetsQuality(KtoPhotoDownloadService.InspectedPhoto photo,
                                 int minLong, int minShort, double maxAspect) {
        int longEdge = Math.max(photo.width(), photo.height());
        int shortEdge = Math.min(photo.width(), photo.height());
        return longEdge >= minLong && shortEdge >= minShort && (double) longEdge / shortEdge <= maxAspect;
    }

    /** 경로/인코딩/쿼리 및 HTTP와 HTTPS 차이는 보존해 서로 다른 원본을 합치지 않는다. */
    private String imageUrlKey(String url) {
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return scheme + "://" + (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo() + "@")
                    + host + (port == -1 || defaultPort ? "" : ":" + port) + uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidKtoPhotoUrlException();
        }
    }

    private void cleanup(KtoDownloadedPhoto photo, String contentId) {
        try {
            downloadService.deleteDownloadedPhoto(photo);
        } catch (RuntimeException exception) {
            log.warn("TourAPI 미선택 이미지 정리 실패 (contentId={})", contentId);
        }
    }

    private String mainReason(DownloadedCandidate main) {
        if (main == null) return "NO_USABLE_IMAGE";
        if (main.normalQuality() && main.candidate().main()) return "FIRSTIMAGE_STANDARD";
        if (main.normalQuality()) return main.landscape() ? "ADDITIONAL_LANDSCAPE" : "ADDITIONAL_PORTRAIT";
        return main.landscape() ? "FALLBACK_LANDSCAPE" : "FALLBACK_PORTRAIT";
    }

    private void logSelection(String contentId, int candidates, SelectionCounts counts, int selected, String reason) {
        log.info("TourAPI 이미지 선별 (contentId={}, candidates={}, inspected={}, downloadAttempts={}, "
                        + "licenseExcluded={}, qualityBelowStandard={}, qualityExcluded={}, duplicateExcluded={}, "
                        + "downloadFailed={}, selected={}, mainReason={})",
                contentId, candidates, counts.inspected, counts.downloadAttempts, counts.licenseExcluded,
                counts.qualityBelowStandard, counts.qualityExcluded, counts.duplicateExcluded,
                counts.downloadFailed, selected, reason);
    }

    private record DownloadedCandidate(KtoTourImageCandidate candidate,
                                       KtoPhotoDownloadService.InspectedPhoto downloaded,
                                       KtoFestivalImageLicense license, boolean normalQuality) {
        long pixels() { return (long) downloaded.width() * downloaded.height(); }
        boolean landscape() { return downloaded.width() >= downloaded.height(); }
    }

    private static class SelectionCounts {
        int inspected;
        int downloadAttempts;
        int licenseExcluded;
        int qualityBelowStandard;
        int qualityExcluded;
        int duplicateExcluded;
        int downloadFailed;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
