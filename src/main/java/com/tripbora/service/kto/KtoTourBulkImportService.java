package com.tripbora.service.kto;

import com.tripbora.dto.DestinationForm;
import com.tripbora.dto.kto.KtoTourAreaCandidateListResponse;
import com.tripbora.dto.kto.KtoTourAreaCandidateResponse;
import com.tripbora.dto.kto.KtoTourAutofillResponse;
import com.tripbora.dto.kto.KtoTourBulkImportRequest;
import com.tripbora.dto.kto.KtoTourBulkImportResponse;
import com.tripbora.service.destination.DestinationDuplicateCheck;
import com.tripbora.service.destination.DestinationDuplicateQuery;
import com.tripbora.service.destination.DestinationDuplicateService;
import com.tripbora.service.destination.DestinationDuplicateStatus;
import com.tripbora.service.destination.DestinationSavePersistenceService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.destination.DuplicateTourApiDestinationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 지역별 TourAPI 여행지 후보 조회 → 공통 중복 판별(contentId·이름·좌표·지역) → 선택 항목 등록.
 * 조회 결과를 그 자리에서 저장하지 않고, 관리자가 고른 항목만 {@link #importSelected} 로 저장한다.
 *
 * <p>항목별로 독립 트랜잭션을 쓰므로 한 건이 실패해도 나머지 등록은 그대로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KtoTourBulkImportService {

    private static final String UNMATCHED_REGION_MESSAGE =
            "주소로 지역을 찾지 못했습니다. 단건 등록 화면에서 지역을 직접 선택해 주세요.";
    private static final String UPSTREAM_FAILURE_MESSAGE = "TourAPI 상세정보를 불러오지 못했습니다.";
    private static final String UNEXPECTED_FAILURE_MESSAGE = "여행지를 저장하지 못했습니다.";

    private final KtoTourService ktoTourService;
    private final KtoTourAreaCandidateCache candidateCache;
    private final KtoTourDetailLookupService ktoTourDetailLookupService;
    private final KtoTourDestinationFormMapper ktoTourDestinationFormMapper;
    private final KtoTourImageImportService ktoTourImageImportService;
    private final KtoPhotoImportService ktoPhotoImportService;
    private final DestinationService destinationService;
    private final DestinationSavePersistenceService destinationSavePersistenceService;
    private final KtoTourDuplicateMarker duplicateMarker;
    private final DestinationDuplicateService duplicateService;

    /**
     * 후보 조회. 순서가 중요하다.
     * <ol>
     *   <li>선택한 유형(또는 전체 4종)의 후보를 TourAPI 페이지 끝까지 모아 합친다</li>
     *   <li>contentId 기준 중복 제거 후 제목순 정렬</li>
     *   <li>공통 중복 판별로 등록됨·중복 확인·미등록 판정 (1차 중복 확인)</li>
     *   <li>등록상태 필터 적용</li>
     *   <li>마지막에 우리 서버 기준으로 페이징</li>
     * </ol>
     * 페이징이 맨 뒤라서, 특정 TourAPI 페이지에 등록완료 항목이 없다고 등록완료 탭이 비지 않는다.
     */
    public KtoTourAreaCandidateListResponse findCandidates(String regionCode, String subRegionCode,
                                                           String contentTypeId,
                                                           KtoTourCandidateRegistrationFilter filter,
                                                           int pageNo, int numOfRows) {
        List<KtoTourAreaCandidateResponse> merged =
                mergedCandidates(regionCode, subRegionCode, contentTypeId);

        // contentId 뿐 아니라 이름·좌표·지역으로 관리자 직접 등록 여행지까지 함께 찾는다.
        List<KtoTourAreaCandidateResponse> withRegistration = duplicateMarker.markAreaCandidates(merged);

        int registeredCount = countStatus(withRegistration, DestinationDuplicateStatus.REGISTERED);
        int possibleDuplicateCount = countStatus(withRegistration, DestinationDuplicateStatus.POSSIBLE_DUPLICATE);
        List<KtoTourAreaCandidateResponse> filtered = withRegistration.stream()
                .filter(candidate -> filter.accepts(candidate.duplicateStatus()))
                .toList();

        int fromIndex = Math.min((pageNo - 1) * numOfRows, filtered.size());
        int toIndex = Math.min(fromIndex + numOfRows, filtered.size());
        return new KtoTourAreaCandidateListResponse(
                pageNo,
                numOfRows,
                filtered.size(),
                withRegistration.size(),
                withRegistration.size() - registeredCount - possibleDuplicateCount,
                registeredCount,
                possibleDuplicateCount,
                filtered.subList(fromIndex, toIndex));
    }

    private int countStatus(List<KtoTourAreaCandidateResponse> candidates, DestinationDuplicateStatus status) {
        return (int) candidates.stream().filter(candidate -> candidate.duplicateStatus() == status).count();
    }

    /**
     * 유형을 고르면 그 유형만, 비우면 지원하는 네 유형을 각각 조회해 하나의 목록으로 합친다.
     * 같은 조건의 TourAPI 재호출은 캐시가 막는다.
     */
    private List<KtoTourAreaCandidateResponse> mergedCandidates(String regionCode,
                                                                String subRegionCode,
                                                                String contentTypeId) {
        List<KtoTourImportContentType> targetTypes = KtoTourImportContentType
                .fromContentTypeId(contentTypeId)
                .map(List::of)
                .orElseGet(KtoTourImportContentType::supported);

        // contentId 가 겹치면 먼저 읽은 쪽을 남긴다.
        Map<String, KtoTourAreaCandidateResponse> byContentId = new LinkedHashMap<>();
        for (KtoTourImportContentType type : targetTypes) {
            KtoTourAreaCandidateCache.Key key = new KtoTourAreaCandidateCache.Key(
                    regionCode, subRegionCode, type.contentTypeId());
            List<KtoTourAreaCandidateResponse> candidates = candidateCache.get(key,
                    () -> ktoTourService.fetchAllByArea(regionCode, subRegionCode, type));
            for (KtoTourAreaCandidateResponse candidate : candidates) {
                byContentId.putIfAbsent(candidate.contentId(), candidate);
            }
        }

        Collator koreanTitleOrder = Collator.getInstance(Locale.KOREAN);
        return byContentId.values().stream()
                .sorted(Comparator.comparing(KtoTourAreaCandidateResponse::title, koreanTitleOrder)
                        .thenComparing(KtoTourAreaCandidateResponse::contentId))
                .toList();
    }

    public KtoTourBulkImportResponse importSelected(List<KtoTourBulkImportRequest.Item> items,
                                                    Long userId) {
        List<KtoTourBulkImportResponse.ItemResult> results = new ArrayList<>();
        // 같은 요청에 같은 contentId 가 두 번 들어와도 한 번만 저장한다.
        Set<String> handledContentIds = new LinkedHashSet<>();
        for (KtoTourBulkImportRequest.Item item : items) {
            String contentId = item.contentId().strip();
            if (!handledContentIds.add(contentId)) {
                continue;
            }
            results.add(importOne(contentId, item.contentTypeId().strip(),
                    item.possibleDuplicateAllowed(), userId));
        }
        return KtoTourBulkImportResponse.of(results);
    }

    private KtoTourBulkImportResponse.ItemResult importOne(String contentId, String contentTypeId,
                                                           boolean possibleDuplicateAllowed, Long userId) {
        // 1차: 상세조회 전에 이미 등록된 항목이면 바깥 API 호출도 하지 않는다.
        if (destinationService.existsTourApiDestination(contentId)) {
            return KtoTourBulkImportResponse.ItemResult.duplicate(contentId, null);
        }

        KtoTourAutofillResponse detail;
        try {
            detail = ktoTourDetailLookupService.lookup(contentId, contentTypeId);
        } catch (KtoTourApiException exception) {
            return KtoTourBulkImportResponse.ItemResult.failed(contentId, null, UPSTREAM_FAILURE_MESSAGE);
        }

        String title = detail.title();
        if (detail.regionMatch() == null || !detail.regionMatch().matched()) {
            return KtoTourBulkImportResponse.ItemResult.failed(contentId, title, UNMATCHED_REGION_MESSAGE);
        }

        DestinationForm form;
        try {
            form = ktoTourDestinationFormMapper.toForm(detail, detail.regionMatch().deepestRegionId());
        } catch (KtoTourBulkImportException exception) {
            return KtoTourBulkImportResponse.ItemResult.failed(contentId, title, exception.getMessage());
        }

        // 최종 확인: 목록을 본 뒤 다른 관리자가 같은 곳을 등록했을 수 있어, 사진을 받기 전에 같은 규칙으로 다시 본다.
        DestinationDuplicateCheck duplicate = duplicateService.check(DestinationDuplicateQuery.fromForm(
                form, DestinationService.KTO_TOUR_API_SOURCE_TYPE, contentId));
        if (duplicate.confirmed()) {
            return KtoTourBulkImportResponse.ItemResult.duplicate(contentId, title, duplicate.destinationId());
        }
        if (duplicate.needsReview() && !possibleDuplicateAllowed) {
            return KtoTourBulkImportResponse.ItemResult.possibleDuplicate(contentId, title, duplicate);
        }

        List<PreparedKtoPhoto> preparedPhotos = ktoTourImageImportService.preparePhotos(contentId, title);
        try {
            Long destinationId = destinationSavePersistenceService.registerDestination(
                    form, userId, contentId, preparedPhotos);
            log.info("TourAPI 일괄등록 이미지 저장 완료 (contentId={}, destinationId={}, saved={})",
                    contentId, destinationId, preparedPhotos.size());
            return KtoTourBulkImportResponse.ItemResult.success(contentId, title, destinationId);
        } catch (DuplicateTourApiDestinationException | DuplicateKeyException exception) {
            // 2차 중복 검사 또는 DB 유니크 제약에 걸린 경우. 저장 실패가 아니라 건너뜀으로 센다.
            ktoPhotoImportService.cleanupPreparedPhotos(preparedPhotos);
            return KtoTourBulkImportResponse.ItemResult.duplicate(contentId, title);
        } catch (RuntimeException exception) {
            ktoPhotoImportService.cleanupPreparedPhotos(preparedPhotos);
            log.warn("TourAPI 여행지 일괄등록 실패 (contentId={}, 원인={})",
                    contentId, exception.getClass().getSimpleName());
            return KtoTourBulkImportResponse.ItemResult.failed(contentId, title, UNEXPECTED_FAILURE_MESSAGE);
        }
    }
}
