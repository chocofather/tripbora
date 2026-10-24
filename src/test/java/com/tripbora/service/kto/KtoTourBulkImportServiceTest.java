package com.tripbora.service.kto;

import com.tripbora.dto.DestinationForm;
import com.tripbora.dto.kto.KtoTourAreaCandidateResponse;
import com.tripbora.dto.kto.KtoTourAutofillResponse;
import com.tripbora.dto.kto.KtoTourBulkImportRequest;
import com.tripbora.dto.kto.KtoTourBulkImportResponse;
import com.tripbora.dto.kto.KtoTourImageCandidate;
import com.tripbora.dto.kto.KtoTourRegionMatchResponse;
import com.tripbora.service.destination.DestinationDuplicateCheck;
import com.tripbora.service.destination.DestinationDuplicateQuery;
import com.tripbora.service.destination.DestinationDuplicateReason;
import com.tripbora.service.destination.DestinationDuplicateService;
import com.tripbora.service.destination.DestinationDuplicateStatus;
import com.tripbora.service.destination.DestinationSavePersistenceService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.destination.DuplicateTourApiDestinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tripbora.service.kto.KtoTourCandidateRegistrationFilter.ALL;
import static com.tripbora.service.kto.KtoTourCandidateRegistrationFilter.NEW;
import static com.tripbora.service.kto.KtoTourCandidateRegistrationFilter.POSSIBLE_DUPLICATE;
import static com.tripbora.service.kto.KtoTourCandidateRegistrationFilter.REGISTERED;
import static com.tripbora.service.kto.KtoTourImportContentType.CULTURAL_FACILITY;
import static com.tripbora.service.kto.KtoTourImportContentType.LEPORTS;
import static com.tripbora.service.kto.KtoTourImportContentType.SHOPPING;
import static com.tripbora.service.kto.KtoTourImportContentType.TOURIST_SPOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KtoTourBulkImportServiceTest {

    @Mock private KtoTourService ktoTourService;
    @Mock private KtoTourDetailLookupService ktoTourDetailLookupService;
    @Mock private KtoTourImageImportService ktoTourImageImportService;
    @Mock private KtoPhotoImportService ktoPhotoImportService;
    @Mock private DestinationService destinationService;
    @Mock private DestinationSavePersistenceService destinationSavePersistenceService;
    @Mock private DestinationDuplicateService duplicateService;
    @Mock private KtoTourRegionMatchService regionMatchService;

    private KtoTourBulkImportService service;

    @BeforeEach
    void setUp() {
        service = new KtoTourBulkImportService(
                ktoTourService,
                new KtoTourAreaCandidateCache(Clock.systemDefaultZone()),
                ktoTourDetailLookupService,
                new KtoTourDestinationFormMapper(),
                ktoTourImageImportService,
                ktoPhotoImportService,
                destinationService,
                destinationSavePersistenceService,
                new KtoTourDuplicateMarker(duplicateService, regionMatchService),
                duplicateService);
        // 저장 직전 최종 확인은 따로 정하지 않으면 미등록이다.
        lenient().when(duplicateService.check(any())).thenReturn(DestinationDuplicateCheck.NOT_REGISTERED);
    }

    @Test
    void candidatesAreMarkedRegisteredByContentIdNotByName() {
        stubType(TOURIST_SPOT, candidate("126508", "창덕궁"), candidate("264337", "창덕궁"));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        stubRegistered("126508");

        var response = service.findCandidates("11", null, null, ALL, 1, 20);

        // 확정 중복(registered)은 contentId 로만 정한다. 이름이 같은 다른 contentId 는 확정이 아니다.
        assertThat(response.items()).extracting(
                        KtoTourAreaCandidateResponse::contentId,
                        KtoTourAreaCandidateResponse::registered)
                .containsExactly(tuple("126508", true), tuple("264337", false));
        // 후보 조회는 저장을 하지 않는다.
        verify(destinationSavePersistenceService, never())
                .registerDestination(any(), any(), any(), anyList());
    }

    @Test
    void allContentTypesAreQueriedSeparatelyThenMergedDedupedAndSortedByTitle() {
        stubType(TOURIST_SPOT, candidate("126508", "경복궁"), candidate("127642", "창덕궁과 후원"));
        stubType(CULTURAL_FACILITY, candidate("126512", "광화문"),
                // 유형이 겹쳐 같은 contentId 가 두 번 와도 한 번만 남는다.
                candidate("126508", "경복궁"));
        stubType(LEPORTS, candidate("300001", "북악산 등산로"));
        stubType(SHOPPING, candidate("400001", "광장시장"));
        stubRegistered();

        var response = service.findCandidates("11", "110", null, ALL, 1, 20);

        assertThat(response.allCount()).isEqualTo(5);
        assertThat(response.items()).extracting(KtoTourAreaCandidateResponse::title)
                .containsExactly("경복궁", "광장시장", "광화문", "북악산 등산로", "창덕궁과 후원");
        for (KtoTourImportContentType type : KtoTourImportContentType.supported()) {
            verify(ktoTourService).fetchAllByArea("11", "110", type);
        }
    }

    @Test
    void aSelectedContentTypeQueriesOnlyThatType() {
        stubType(SHOPPING, candidate("400001", "광장시장"));
        stubRegistered();

        var response = service.findCandidates("11", "110", "38", ALL, 1, 20);

        assertThat(response.allCount()).isEqualTo(1);
        verify(ktoTourService).fetchAllByArea("11", "110", SHOPPING);
        verify(ktoTourService, never()).fetchAllByArea(any(), any(), eq(TOURIST_SPOT));
    }

    /**
     * 핵심 회귀: 등록완료 항목이 첫 페이지에 없어도 등록완료 탭은 비면 안 된다.
     * 필터가 페이징보다 먼저 적용돼야 한다.
     */
    @Test
    void theRegisteredFilterLooksAtEveryCandidateNotJustTheCurrentPage() {
        List<KtoTourAreaCandidateResponse> many = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            many.add(candidate("90" + String.format("%04d", index), "가나다 " + index));
        }
        // 등록완료 항목은 정렬상 맨 뒤(2페이지 이후)에 온다.
        many.add(candidate("126508", "하 경복궁"));
        stubType(TOURIST_SPOT, many.toArray(KtoTourAreaCandidateResponse[]::new));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        stubRegistered("126508");

        var registered = service.findCandidates("11", "110", null, REGISTERED, 1, 20);

        assertThat(registered.items()).extracting(KtoTourAreaCandidateResponse::contentId)
                .containsExactly("126508");
        assertThat(registered.totalCount()).isEqualTo(1);
    }

    @Test
    void countsMatchTheFilterAndPagingHappensLast() {
        stubType(TOURIST_SPOT,
                candidate("1", "가"), candidate("2", "나"), candidate("3", "다"),
                candidate("4", "라"), candidate("5", "마"));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        stubRegistered("2", "4");

        var all = service.findCandidates("11", "110", null, ALL, 1, 2);
        assertThat(all.allCount()).isEqualTo(5);
        assertThat(all.newCount()).isEqualTo(3);
        assertThat(all.registeredCount()).isEqualTo(2);
        // totalCount 는 현재 필터를 적용한 결과 수이고, 페이징 기준이다.
        assertThat(all.totalCount()).isEqualTo(5);
        assertThat(all.items()).extracting(KtoTourAreaCandidateResponse::contentId)
                .containsExactly("1", "2");

        var unregistered = service.findCandidates("11", "110", null, NEW, 1, 2);
        assertThat(unregistered.totalCount()).isEqualTo(3);
        assertThat(unregistered.newCount()).isEqualTo(3);
        assertThat(unregistered.items()).extracting(KtoTourAreaCandidateResponse::contentId)
                .containsExactly("1", "3");

        var secondPage = service.findCandidates("11", "110", null, NEW, 2, 2);
        assertThat(secondPage.items()).extracting(KtoTourAreaCandidateResponse::contentId)
                .containsExactly("5");
    }

    @Test
    void theSameConditionDoesNotHitTourApiAgainForPagingOrFilterChanges() {
        stubType(TOURIST_SPOT, candidate("1", "가"), candidate("2", "나"));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        stubRegistered("2");

        service.findCandidates("11", "110", null, NEW, 1, 20);
        service.findCandidates("11", "110", null, REGISTERED, 1, 20);
        service.findCandidates("11", "110", null, ALL, 2, 20);

        verify(ktoTourService, times(1)).fetchAllByArea("11", "110", TOURIST_SPOT);
    }

    @Test
    void selectedItemsAreRegisteredIndependentlySoOneFailureKeepsTheRest() {
        prepareDetail("126508", "창덕궁");
        prepareDetail("126509", "경복궁");
        prepareDetail("126510", "종묘");
        when(destinationService.existsTourApiDestination(any())).thenReturn(false);
        when(ktoTourImageImportService.preparePhotos(any(), any())).thenReturn(List.of());
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126508"), anyList())).thenReturn(42L);
        // 저장 직전 2차 중복 검사에 걸린 항목
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126509"), anyList()))
                .thenThrow(new DuplicateTourApiDestinationException("126509"));
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126510"), anyList()))
                .thenThrow(new IllegalStateException("insert failed"));

        KtoTourBulkImportResponse response = service.importSelected(List.of(
                item("126508"), item("126509"), item("126510")), 7L);

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.duplicateCount()).isEqualTo(1);
        assertThat(response.failureCount()).isEqualTo(1);
        assertThat(response.results()).extracting(
                        KtoTourBulkImportResponse.ItemResult::contentId,
                        KtoTourBulkImportResponse.ItemResult::status)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("126508", KtoTourBulkImportResponse.Status.SUCCESS),
                        org.assertj.core.api.Assertions.tuple("126509", KtoTourBulkImportResponse.Status.DUPLICATE),
                        org.assertj.core.api.Assertions.tuple("126510", KtoTourBulkImportResponse.Status.FAILED));
        assertThat(response.results().get(2).title()).isEqualTo("종묘");
        assertThat(response.results().get(2).message()).isNotBlank();
    }

    @Test
    void alreadyRegisteredContentIdsAreSkippedWithoutCallingTourApi() {
        when(destinationService.existsTourApiDestination("126508")).thenReturn(true);

        KtoTourBulkImportResponse response = service.importSelected(List.of(item("126508")), 7L);

        assertThat(response.duplicateCount()).isEqualTo(1);
        verify(ktoTourDetailLookupService, never()).lookup(any(), any());
        verify(destinationSavePersistenceService, never())
                .registerDestination(any(), any(), any(), anyList());
    }

    @Test
    void theSameContentIdSentTwiceIsRegisteredOnlyOnce() {
        prepareDetail("126508", "창덕궁");
        when(destinationService.existsTourApiDestination("126508")).thenReturn(false);
        when(ktoTourImageImportService.preparePhotos(any(), any())).thenReturn(List.of());
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126508"), anyList())).thenReturn(42L);

        KtoTourBulkImportResponse response = service.importSelected(
                List.of(item("126508"), item("126508")), 7L);

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results()).hasSize(1);
        verify(destinationSavePersistenceService, times(1))
                .registerDestination(any(DestinationForm.class), eq(7L), eq("126508"), anyList());
    }

    @Test
    void unmatchedRegionFailsOnlyThatItem() {
        when(destinationService.existsTourApiDestination("126508")).thenReturn(false);
        when(ktoTourDetailLookupService.lookup("126508", "12")).thenReturn(
                detail("126508", "창덕궁").withRegionMatch(KtoTourRegionMatchResponse.unmatched()));

        KtoTourBulkImportResponse response = service.importSelected(List.of(item("126508")), 7L);

        assertThat(response.failureCount()).isEqualTo(1);
        assertThat(response.results().get(0).message()).contains("지역");
        verify(destinationSavePersistenceService, never())
                .registerDestination(any(), any(), any(), anyList());
    }

    @Test
    void downloadedImagesAreCleanedUpWhenTheSaveFails() {
        prepareDetail("126508", "창덕궁");
        List<PreparedKtoPhoto> prepared = List.of(new PreparedKtoPhoto(
                "/uploads/destinations/11111111-1111-4111-8111-111111111111.jpg",
                "https://tong.visitkorea.or.kr/cms2/website/10/source.jpg",
                "126508", "창덕궁", null, true, null, "KTO_TOURAPI", "KOGL_TYPE_1"));
        when(destinationService.existsTourApiDestination("126508")).thenReturn(false);
        when(ktoTourImageImportService.preparePhotos("126508", "창덕궁")).thenReturn(prepared);
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126508"), anyList()))
                .thenThrow(new IllegalStateException("insert failed"));

        service.importSelected(List.of(item("126508")), 7L);

        verify(ktoPhotoImportService).cleanupPreparedPhotos(prepared);
    }

    @Test
    void allImageDownloadsFailButDestinationIsRegisteredWithoutPhotos() {
        prepareDetail("126508", "창덕궁");
        String imageUrl = "https://tong.visitkorea.or.kr/cms/resource/missing.jpg";
        when(ktoTourService.getBulkImportImages("126508")).thenReturn(List.of(
                new KtoTourImageCandidate("126508", "사진", imageUrl, "Type1", true)));
        KtoPhotoDownloadService downloads = mock(KtoPhotoDownloadService.class);
        when(downloads.downloadForSelection(imageUrl)).thenThrow(new KtoPhotoDownloadException());
        KtoTourImageImportService realSelection = new KtoTourImageImportService(ktoTourService, downloads);
        when(ktoTourImageImportService.preparePhotos("126508", "창덕궁"))
                .thenAnswer(invocation -> realSelection.preparePhotos("126508", "창덕궁"));
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126508"), eq(List.of()))).thenReturn(42L);

        var response = service.importSelected(List.of(item("126508")), 7L);

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.failureCount()).isZero();
        assertThat(response.results().get(0).destinationId()).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateDuplicateDetectionCleansPreparedPhotosWithoutUpdatingExistingDestination(boolean uniqueConstraint) {
        prepareDetail("126508", "창덕궁");
        List<PreparedKtoPhoto> prepared = List.of(new PreparedKtoPhoto(
                "/uploads/destinations/11111111-1111-4111-8111-111111111111.jpg",
                "https://tong.visitkorea.or.kr/cms/resource/source.jpg",
                "126508", "사진", null, true, null, "KTO_TOURAPI", "KOGL_TYPE_3"));
        when(ktoTourImageImportService.preparePhotos("126508", "창덕궁")).thenReturn(prepared);
        RuntimeException duplicate = uniqueConstraint ? new org.springframework.dao.DuplicateKeyException("duplicate")
                : new DuplicateTourApiDestinationException("126508");
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126508"), eq(prepared))).thenThrow(duplicate);

        var response = service.importSelected(List.of(item("126508")), 7L);

        assertThat(response.duplicateCount()).isEqualTo(1);
        assertThat(response.successCount()).isZero();
        verify(ktoPhotoImportService).cleanupPreparedPhotos(prepared);
        verify(destinationSavePersistenceService, never()).updateDestination(any(), any(), anyList());
    }

    /** 관리자 직접 등록 여행지와 이름·위치가 같은 후보는 '중복 확인'으로 따로 세고 미등록 탭에서 뺀다. */
    @Test
    void possibleDuplicatesAreCountedSeparatelyAndKeptOutOfTheNewTab() {
        stubType(TOURIST_SPOT, candidate("126508", "경복궁"), candidate("126509", "창덕궁"),
                candidate("126510", "새로운 여행지"));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        when(duplicateService.checkAll(anyList())).thenAnswer(invocation -> invocation
                .<List<DestinationDuplicateQuery>>getArgument(0).stream()
                .map(query -> switch (query.externalContentId()) {
                    case "126508" -> registeredCheck(11L);
                    case "126509" -> possibleCheck(12L);
                    default -> DestinationDuplicateCheck.NOT_REGISTERED;
                })
                .toList());

        var all = service.findCandidates("11", null, null, ALL, 1, 20);
        var fresh = service.findCandidates("11", null, null, NEW, 1, 20);
        var review = service.findCandidates("11", null, null, POSSIBLE_DUPLICATE, 1, 20);

        assertThat(all.registeredCount()).isEqualTo(1);
        assertThat(all.possibleDuplicateCount()).isEqualTo(1);
        assertThat(all.newCount()).isEqualTo(1);
        assertThat(all.items()).extracting(KtoTourAreaCandidateResponse::title,
                        KtoTourAreaCandidateResponse::registered,
                        candidate -> candidate.duplicate().status(),
                        candidate -> candidate.duplicate().destinationId())
                .containsExactly(
                        tuple("경복궁", true, DestinationDuplicateStatus.REGISTERED, 11L),
                        tuple("새로운 여행지", false, DestinationDuplicateStatus.NOT_REGISTERED, null),
                        tuple("창덕궁", false, DestinationDuplicateStatus.POSSIBLE_DUPLICATE, 12L));
        assertThat(fresh.items()).extracting(KtoTourAreaCandidateResponse::title).containsExactly("새로운 여행지");
        assertThat(review.items()).extracting(KtoTourAreaCandidateResponse::title).containsExactly("창덕궁");
    }

    /** 목록 판별에는 TourAPI 좌표(mapx=경도, mapy=위도)와 주소로 찾은 지역을 함께 넘긴다. */
    @Test
    void candidateCoordinatesAndAddressRegionAreSentToTheCommonCheck() {
        stubType(TOURIST_SPOT, new KtoTourAreaCandidateResponse("126508", "12", "관광지", "경복궁",
                "서울특별시 종로구 사직로 161", null, "126.9770", "37.5796", false, null));
        stubEmpty(CULTURAL_FACILITY, LEPORTS, SHOPPING);
        when(regionMatchService.match("서울특별시 종로구 사직로 161")).thenReturn(
                KtoTourRegionMatchResponse.matched(List.of(
                        new KtoTourRegionMatchResponse.RegionPathItem(7L, "대한민국"),
                        new KtoTourRegionMatchResponse.RegionPathItem(38L, "서울"),
                        new KtoTourRegionMatchResponse.RegionPathItem(235L, "종로구"))));
        stubRegistered();

        service.findCandidates("11", null, null, ALL, 1, 20);

        ArgumentCaptor<List<DestinationDuplicateQuery>> queries = ArgumentCaptor.forClass(List.class);
        verify(duplicateService).checkAll(queries.capture());
        DestinationDuplicateQuery query = queries.getValue().get(0);
        assertThat(query.sourceType()).isEqualTo(DestinationService.KTO_TOUR_API_SOURCE_TYPE);
        assertThat(query.externalContentId()).isEqualTo("126508");
        assertThat(query.names()).containsExactly("경복궁");
        assertThat(query.regionId()).isEqualTo(235L);
        assertThat(query.latitude()).isEqualByComparingTo("37.5796");
        assertThat(query.longitude()).isEqualByComparingTo("126.9770");
    }

    /** 목록 조회 뒤 다른 관리자가 같은 곳을 등록했으면 저장 직전 확인에서 막고 사진도 받지 않는다. */
    @Test
    void theFinalCheckBeforeSavingSkipsConfirmedAndUnconfirmedPossibleDuplicates() {
        prepareDetail("126508", "경복궁");
        prepareDetail("126509", "창덕궁");
        when(destinationService.existsTourApiDestination(any())).thenReturn(false);
        when(duplicateService.check(any())).thenAnswer(invocation ->
                "126508".equals(invocation.<DestinationDuplicateQuery>getArgument(0).externalContentId())
                        ? registeredCheck(11L) : possibleCheck(12L));

        KtoTourBulkImportResponse response = service.importSelected(
                List.of(item("126508"), item("126509")), 7L);

        assertThat(response.results()).extracting(
                        KtoTourBulkImportResponse.ItemResult::contentId,
                        KtoTourBulkImportResponse.ItemResult::status,
                        KtoTourBulkImportResponse.ItemResult::destinationId)
                .containsExactly(
                        tuple("126508", KtoTourBulkImportResponse.Status.DUPLICATE, 11L),
                        tuple("126509", KtoTourBulkImportResponse.Status.POSSIBLE_DUPLICATE, 12L));
        assertThat(response.possibleDuplicateCount()).isEqualTo(1);
        verify(ktoTourImageImportService, never()).preparePhotos(any(), any());
        verify(destinationSavePersistenceService, never())
                .registerDestination(any(), any(), any(), anyList());
    }

    /** 저장 직전 확인은 등록폼과 같은 이름·좌표·지역으로 하고, 관리자가 확인한 중복 확인 후보는 저장한다. */
    @Test
    void anAcknowledgedPossibleDuplicateIsSavedWithTheTourApiContentId() {
        prepareDetail("126509", "창덕궁");
        when(destinationService.existsTourApiDestination("126509")).thenReturn(false);
        when(duplicateService.check(any())).thenReturn(possibleCheck(12L));
        when(ktoTourImageImportService.preparePhotos(any(), any())).thenReturn(List.of());
        when(destinationSavePersistenceService.registerDestination(
                any(DestinationForm.class), eq(7L), eq("126509"), anyList())).thenReturn(43L);

        KtoTourBulkImportResponse response = service.importSelected(
                List.of(new KtoTourBulkImportRequest.Item("126509", "12", true)), 7L);

        assertThat(response.successCount()).isEqualTo(1);
        ArgumentCaptor<DestinationDuplicateQuery> query = ArgumentCaptor.forClass(DestinationDuplicateQuery.class);
        verify(duplicateService).check(query.capture());
        assertThat(query.getValue().externalContentId()).isEqualTo("126509");
        assertThat(query.getValue().names()).contains("창덕궁");
        assertThat(query.getValue().regionId()).isEqualTo(235L);
        assertThat(query.getValue().latitude()).isEqualByComparingTo("37.579");
    }

    private void stubRegistered(String... contentIds) {
        Set<String> registered = Set.of(contentIds);
        when(duplicateService.checkAll(anyList())).thenAnswer(invocation -> invocation
                .<List<DestinationDuplicateQuery>>getArgument(0).stream()
                .map(query -> registered.contains(query.externalContentId())
                        ? registeredCheck(1L) : DestinationDuplicateCheck.NOT_REGISTERED)
                .toList());
    }

    private DestinationDuplicateCheck registeredCheck(Long destinationId) {
        return new DestinationDuplicateCheck(DestinationDuplicateStatus.REGISTERED,
                DestinationDuplicateReason.EXTERNAL_CONTENT_ID, destinationId, "기존", null, "같은 외부 콘텐츠 ID");
    }

    private DestinationDuplicateCheck possibleCheck(Long destinationId) {
        return new DestinationDuplicateCheck(DestinationDuplicateStatus.POSSIBLE_DUPLICATE,
                DestinationDuplicateReason.NAME_AND_NEARBY, destinationId, "기존", 120, "같은 이름 · 가까운 위치 (약 120m)");
    }

    private void prepareDetail(String contentId, String title) {
        when(ktoTourDetailLookupService.lookup(contentId, "12")).thenReturn(
                detail(contentId, title).withRegionMatch(KtoTourRegionMatchResponse.matched(List.of(
                        new KtoTourRegionMatchResponse.RegionPathItem(7L, "대한민국"),
                        new KtoTourRegionMatchResponse.RegionPathItem(38L, "서울"),
                        new KtoTourRegionMatchResponse.RegionPathItem(235L, "종로구")))));
    }

    private KtoTourAutofillResponse detail(String contentId, String title) {
        return new KtoTourAutofillResponse(
                contentId, "12", title, "서울 종로구", "126.991", "37.579",
                "설명", null, null, null, "09:00~18:00", null, null);
    }

    private KtoTourBulkImportRequest.Item item(String contentId) {
        return new KtoTourBulkImportRequest.Item(contentId, "12");
    }

    private KtoTourAreaCandidateResponse candidate(String contentId, String title) {
        return new KtoTourAreaCandidateResponse(
                contentId, "12", "관광지", title, "서울 종로구", null, false);
    }

    private void stubType(KtoTourImportContentType type, KtoTourAreaCandidateResponse... candidates) {
        when(ktoTourService.fetchAllByArea(any(), any(), eq(type))).thenReturn(List.of(candidates));
    }

    private void stubEmpty(KtoTourImportContentType... types) {
        for (KtoTourImportContentType type : types) {
            stubType(type);
        }
    }
}
