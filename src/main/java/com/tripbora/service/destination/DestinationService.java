package com.tripbora.service.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.AdminDestinationDataStatusCounts;
import com.tripbora.dto.AdminDestinationListItemDto;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.dto.DestinationDto;
import com.tripbora.dto.DestinationForm;
import com.tripbora.dto.DestinationTranslationForm;
import com.tripbora.model.BookmarkTargetType;
import com.tripbora.model.Destination;
import com.tripbora.model.DestinationImage;
import com.tripbora.model.DestinationSeason;
import com.tripbora.model.DestinationTranslation;
import com.tripbora.model.DestinationTranslationSource;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.destination.DestinationTranslationSourceMapper;
import com.tripbora.repository.destination.DestinationViewDailyMapper;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.info.*;
import com.tripbora.service.wikidata.WikidataRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DestinationService {

    private final DestinationMapper destinationMapper;
    private final DestinationImageService destinationImageService;
    private final BookmarkMapper bookmarkMapper;
    private final AmenityService amenityService;
    private final DestinationCommentService destinationCommentService;
    /** 여행지가 빠진 코스의 STOP 번호를 다시 매기는 일만 맡긴다. */
    private final CourseService courseService;


    // 추가 정보
    private final AccommodationInfoService accommodationInfoService;
    private final AttractionInfoService attractionInfoService;
    private final RestaurantInfoService restaurantInfoService;
    private final ActivityInfoService activityInfoService;
    private final ShopInfoService shopInfoService;

    /** 여행지 번역 일괄 조회와 언어 대체 규칙은 코스 STOP 과 함께 쓰도록 떼어 두었다. */
    private final DestinationLocalizationService destinationLocalizationService;
    @Autowired private DestinationTranslationSourceMapper translationSourceMapper;
    /** 상세 조회의 일별 집계(지금 뜨는 여행지). 날짜는 Asia/Seoul 기준이다. */
    @Autowired private DestinationViewDailyMapper viewDailyMapper;
    @Autowired private DestinationViewClock viewClock;

    @Value("${custom.upload-path}")
    private String uploadPath;


    /** 관리자가 화면에서 직접 입력한 여행지. 등록 출처는 ADMIN 이다. */
    public static final String ADMIN_SOURCE_TYPE = "ADMIN";
    /** TourAPI 에서 가져온 여행지. contentId 와 짝을 이뤄 중복 판정 기준이 된다. */
    public static final String KTO_TOUR_API_SOURCE_TYPE = "KTO_TOURAPI";
    public static final String WIKIDATA_SOURCE_TYPE = "WIKIDATA";

    public Long registerDestination(DestinationForm form, Long userId) {
        return registerDestination(form, userId, null);
    }

    /**
     * @param externalContentId TourAPI contentId. null 이면 관리자 직접 등록(ADMIN)으로 저장한다.
     */
    public Long registerDestination(DestinationForm form, Long userId, String externalContentId) {
        return registerDestination(form, userId,
                externalContentId == null ? ADMIN_SOURCE_TYPE : KTO_TOUR_API_SOURCE_TYPE,
                externalContentId);
    }

    public Long registerDestination(DestinationForm form, Long userId, String sourceType,
                                    String externalContentId) {
        // 잘못된 대표값이면 어떤 DB 작업도 시작하지 않는다. KTO·Wikidata 처럼 값이 없으면 가장 작은 ID가 대표다.
        Long mainCategoryId = resolveMainCategoryId(form.getCategoryIds(), form.getMainCategoryId());
        Destination destination = new Destination();
        destination.setLatitude(form.getLatitude());
        destination.setLongitude(form.getLongitude());
        destination.setGooglePlaceId(form.getGooglePlaceId());
        destination.setSeason(DestinationSeason.valueOf(form.getSeason()));
        destination.setUserID(userId);
        destination.setViews(0);
        destination.setRegionId(form.getRegionId());

        destination.setCreatedAt(new java.sql.Timestamp(System.currentTimeMillis()));

        destination.setType(form.getType());
        destination.setSourceType(sourceType);
        destination.setExternalContentId(externalContentId);

        destinationMapper.insertDestination(destination);
        Long destinationId = destination.getId();

        switch (form.getType()) {
            case ATTRACTION:
                if (form.getAttractionInfo() != null) {
                    form.getAttractionInfo().setDestinationId(destinationId);
                    attractionInfoService.save(form.getAttractionInfo());
                    // 한국어 입력은 원본이자 ko 번역이 되고, 나머지 언어는 슬롯 값으로 저장된다.
                    attractionInfoService.saveTranslations(destinationId,
                            form.getAttractionInfo(), form.getAttractionInfoTranslations());
                }
                break;
            case ACCOMMODATION:
                if (form.getAccommodationInfo() != null) {
                    form.getAccommodationInfo().setDestinationId(destinationId);
                    accommodationInfoService.save(form.getAccommodationInfo());
                    // 한국어 입력은 원본이자 ko 번역이 되고, 나머지 언어는 슬롯 값으로 저장된다.
                    accommodationInfoService.saveTranslations(destinationId,
                            form.getAccommodationInfo(), form.getAccommodationInfoTranslations());
                }
                break;
            case RESTAURANTS:
            case CAFE:
                if (form.getRestaurantInfo() != null) {
                    form.getRestaurantInfo().setDestinationId(destinationId);
                    restaurantInfoService.save(form.getRestaurantInfo());
                    // 한국어 입력은 원본이자 ko 번역이 되고, 나머지 언어는 슬롯 값으로 저장된다.
                    restaurantInfoService.saveTranslations(destinationId,
                            form.getRestaurantInfo(), form.getRestaurantInfoTranslations());
                }
                break;
            case ACTIVITY:
                if (form.getActivityInfo() != null) {
                    form.getActivityInfo().setDestinationId(destinationId);
                    activityInfoService.save(form.getActivityInfo());
                    // 한국어 입력은 원본이자 ko 번역이 되고, 나머지 언어는 슬롯 값으로 저장된다.
                    activityInfoService.saveTranslations(destinationId,
                            form.getActivityInfo(), form.getActivityInfoTranslations());
                }
                break;
            case SHOP:
                if (form.getShopInfo() != null) {
                    form.getShopInfo().setDestinationId(destinationId);
                    shopInfoService.save(form.getShopInfo());
                    // 한국어 입력은 원본이자 ko 번역이 되고, 나머지 언어는 슬롯 값으로 저장된다.
                    shopInfoService.saveTranslations(destinationId,
                            form.getShopInfo(), form.getShopInfoTranslations());
                }
                break;
        }


        for (DestinationTranslationForm transForm : form.getTranslations()) {
            // 한국어는 원본이라 늘 남기고, 나머지 언어는 값이 있을 때만 줄을 만든다.
            if (!keepsTranslation(transForm)) {
                continue;
            }
            DestinationTranslation trans = new DestinationTranslation();
            trans.setDestinationId(destinationId);
            trans.setLanguageCode(transForm.getLanguageCode());
            trans.setName(transForm.getName());
            trans.setDescription(transForm.getDescription());
            trans.setShortDescription(transForm.getShortDescription());
            destinationMapper.insertTranslation(trans);
        }

        for (Long categoryId : form.getCategoryIds()) {
            destinationMapper.insertDestinationCategory(destinationId, categoryId);
        }
        saveMainCategory(destinationId, null, mainCategoryId);
        // === 편의시설 체크박스 값 저장 ===
        switch (form.getType()) {
            case ATTRACTION:
                amenityService.insertAttractionAmenities(destinationId, form.getAttractionAmenityIds());
                break;
            case ACCOMMODATION:
                amenityService.insertAccommodationAmenities(destinationId, form.getAccommodationAmenityIds());
                break;
            case RESTAURANTS:
            case CAFE:
                amenityService.insertRestaurantAmenities(destinationId, form.getRestaurantAmenityIds());
                break;
            case ACTIVITY:
                amenityService.insertActivityAmenities(destinationId, form.getActivityAmenityIds());
                break;
            case SHOP:
                amenityService.insertShopAmenities(destinationId, form.getShopAmenityIds());
                break;
        }

        destinationImageService.saveImages(
                destinationId,
                form.getImages(),
                form.isMain(),
                form.isSlide(),
                form.getImageSourceNames(),
                form.getImagePhotographers(),
                form.getImageLicenseTypes(),
                form.getImageLicenseDetails(),
                form.getImageSourceUrls(),
                form.getImageCommonSourceUrls(),
                form.getImageWorkPageUrls());
        return destinationId;
    }

    /** TourAPI contentId 로만 본 중복 여부. 여행지명은 보지 않는다. */
    public boolean existsTourApiDestination(String externalContentId) {
        if (externalContentId == null || externalContentId.isBlank()) {
            return false;
        }
        return destinationMapper.countByExternalContentId(
                KTO_TOUR_API_SOURCE_TYPE, externalContentId.strip()) > 0;
    }

    public Long findTourApiDestinationId(String externalContentId) {
        if (externalContentId == null || externalContentId.isBlank()) {
            return null;
        }
        return destinationMapper.findIdByExternalContentId(KTO_TOUR_API_SOURCE_TYPE, externalContentId.strip());
    }

    public Long findWikidataDestinationId(String qid) {
        return destinationMapper.findIdByExternalContentId(WIKIDATA_SOURCE_TYPE, qid);
    }

    // 후보 목록의 등록 여부 표시는 DestinationDuplicateService(외부 ID·Place ID·이름·위치 공통 판별)가 맡는다.

    public List<Destination> getDomesticDestinations() {
        return destinationMapper.findDomestic();
    }

    public List<Destination> getAllDestinationsWithRegion() {
        return destinationMapper.findAllWithRegion();
    }

    public List<String> getImageUrlsByDestinationId(Long id) {
        List<DestinationImage> images = destinationMapper.findImagesByDestinationId(id);
        return images.stream()
                .map(DestinationImage::getImageUrl)
                .collect(Collectors.toList());
    }

    public DestinationDetailDto getDestinationDetailWithInfo(Long id) {
        return getDestinationDetailWithInfo(id, SupportedLanguage.KOREAN, false);
    }

    public DestinationDetailDto getDestinationDetailWithInfo(Long id,
                                                              SupportedLanguage requestedLanguage) {
        return getDestinationDetailWithInfo(id, requestedLanguage, true);
    }

    private DestinationDetailDto getDestinationDetailWithInfo(Long id,
                                                               SupportedLanguage requestedLanguage,
                                                               boolean localizeSubtypeInfo) {
        Destination destination = destinationMapper.findDestinationDetail(id);
        if (destination == null) return null;

        List<DestinationTranslation> translations =
                destinationMapper.findTranslationsByDestinationId(id);
        if (translations == null || translations.isEmpty()) return null;

        applyLocalizedContent(destination, translations,
                requestedLanguage == null ? SupportedLanguage.KOREAN : requestedLanguage);

        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);

        // 이미지 리스트
        dto.setImages(destinationMapper.findImagesByDestinationId(id));
        dto.setCategoryIds(destinationMapper.findCategoryIdsByDestinationId(id));
        dto.setMainCategoryId(destinationMapper.findMainCategoryId(id));

        // 타입별 상세정보 및 amenity 리스트 셋팅
        switch (destination.getType()) {
            case ATTRACTION:
                dto.setAttractionInfo(localizeSubtypeInfo
                        ? attractionInfoService.findLocalizedByDestinationId(id, requestedLanguage)
                        : attractionInfoService.findByDestinationId(id));
                dto.setAttractionAmenities(
                        amenityService.getAttractionAmenities(id, requestedLanguage));
                break;
            case ACCOMMODATION:
                dto.setAccommodationInfo(localizeSubtypeInfo
                        ? accommodationInfoService.findLocalizedByDestinationId(id, requestedLanguage)
                        : accommodationInfoService.findByDestinationId(id));
                dto.setAccommodationAmenities(
                        amenityService.getAccommodationAmenities(id, requestedLanguage));
                break;
            case RESTAURANTS:
            case CAFE:
                dto.setRestaurantInfo(localizeSubtypeInfo
                        ? restaurantInfoService.findLocalizedByDestinationId(id, requestedLanguage)
                        : restaurantInfoService.findByDestinationId(id));
                dto.setRestaurantAmenities(
                        amenityService.getRestaurantAmenities(id, requestedLanguage));
                break;
            case ACTIVITY:
                dto.setActivityInfo(localizeSubtypeInfo
                        ? activityInfoService.findLocalizedByDestinationId(id, requestedLanguage)
                        : activityInfoService.findByDestinationId(id));
                dto.setActivityAmenities(
                        amenityService.getActivityAmenities(id, requestedLanguage));
                break;
            case SHOP:
                dto.setShopInfo(localizeSubtypeInfo
                        ? shopInfoService.findLocalizedByDestinationId(id, requestedLanguage)
                        : shopInfoService.findByDestinationId(id));
                dto.setShopAmenities(amenityService.getShopAmenities(id, requestedLanguage));
                break;
        }
        return dto;
    }

    private void applyLocalizedContent(Destination destination,
                                       List<DestinationTranslation> translations,
                                       SupportedLanguage requestedLanguage) {
        List<DestinationTranslation> ordered = orderedTranslations(translations);
        DestinationTranslation requested = translationFor(
                ordered, requestedLanguage.getLanguageTag());
        DestinationTranslation korean = translationFor(
                ordered, SupportedLanguage.KOREAN.getLanguageTag());

        destination.setName(localizedField(
                DestinationTranslation::getName, requested, korean, ordered));
        destination.setShortDescription(localizedField(
                DestinationTranslation::getShortDescription, requested, korean, ordered));
        destination.setDescription(localizedField(
                DestinationTranslation::getDescription, requested, korean, ordered));
    }

    /**
     * 그 언어 줄을 남길지 정한다.
     *
     * <p>한국어는 원본이라 늘 남기고, 나머지 언어는 이름·간단 설명·상세 설명 중
     * 하나라도 실제 값이 있어야 남긴다. 공백만 있는 값은 없는 것으로 본다.
     * (유형별 상세정보 번역과 같은 규칙)
     */
    private boolean keepsTranslation(DestinationTranslationForm form) {
        if (form == null || form.getLanguageCode() == null) {
            return false;
        }
        if (SupportedLanguage.KOREAN.getLanguageTag().equals(form.getLanguageCode())) {
            return true;
        }
        return hasText(form.getName())
                || hasText(form.getShortDescription())
                || hasText(form.getDescription());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<DestinationTranslation> orderedTranslations(
            Collection<DestinationTranslation> translations) {
        return destinationLocalizationService.orderedTranslations(translations);
    }

    private DestinationTranslation translationFor(List<DestinationTranslation> translations,
                                                  String languageTag) {
        return destinationLocalizationService.translationFor(translations, languageTag);
    }

    private String localizedField(Function<DestinationTranslation, String> field,
                                  DestinationTranslation requested,
                                  DestinationTranslation korean,
                                  List<DestinationTranslation> ordered) {
        return destinationLocalizationService.localizedField(field, requested, korean, ordered);
    }

    // 상세 페이지 여행지 추천
    public List<Destination> getSimilarDestinations(Long destinationId, int limit) {
        Destination destination = destinationMapper.findById(destinationId);
        if (destination == null) return Collections.emptyList();

        Long regionId = destination.getRegionId();
        List<Long> categoryIds = destinationMapper.findCategoryIdsByDestinationId(destinationId);

        if (categoryIds == null || categoryIds.isEmpty()) return Collections.emptyList();

        return destinationMapper.findSimilarDestinations(regionId, categoryIds, destinationId, limit);
    }




    public List<Destination> findAll() {
        return destinationMapper.findAllWithRegion();
    }

    public List<Destination> findAllWithRegion() {
        return destinationMapper.findAllWithRegion();
    }

    public Destination findById(Long id) {
        return destinationMapper.findById(id);
    }

    /** 공개 상태만 갱신한다. 본문·이미지·번역·기존 코스/계획 참조에는 손대지 않는다. */
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public boolean setPublicVisible(Long id, boolean publicVisible) {
        if (id == null || id <= 0) throw new IllegalArgumentException("여행지 ID가 올바르지 않습니다.");
        if (destinationMapper.updateVisibility(id, publicVisible) == 0) {
            // 동일 상태로 재요청한 경우에도 대상이 존재하면 성공이다.
            if (destinationMapper.findById(id) == null) throw new DestinationNotFoundException();
        }
        return publicVisible;
    }

    // ✅ 추가: 이미지 리스트만 따로 불러올 수 있도록
    public List<DestinationImage> getImagesByDestinationId(Long id) {
        return destinationMapper.findImagesByDestinationId(id);
    }

    public List<Destination> getDestinationsByCityId(Long cityId) {
        return destinationMapper.findByCountryCategoryId(cityId);
    }

    /** 관리자 여행지 목록 한 쪽. 지역·분류·데이터 상태·검색·정렬은 모두 DB 조회 단계에서 적용한다. */
    public List<AdminDestinationListItemDto> getAdminDestinationPage(List<Long> regionIds, String destinationType,
                                                                     String dataStatus, String keyword, String sort,
                                                                     long offset, int size) {
        return getAdminDestinationPage(regionIds, destinationType, dataStatus, keyword, sort, offset, size, null);
    }

    public List<AdminDestinationListItemDto> getAdminDestinationPage(List<Long> regionIds, String destinationType,
            String dataStatus, String keyword, String sort, long offset, int size, Boolean publicVisible) {
        if (regionIds == null || regionIds.isEmpty()) {
            return List.of();
        }
        DestinationSearchKeyword search = DestinationSearchKeyword.of(keyword);
        return destinationMapper.findAdminDestinationPage(regionIds, destinationType, dataStatus,
                search.namePattern(), search.chosungPattern(), sort, offset, size, publicVisible);
    }

    public int countAdminDestinations(List<Long> regionIds, String destinationType, String dataStatus,
                                      String keyword) {
        return countAdminDestinations(regionIds, destinationType, dataStatus, keyword, null);
    }

    public int countAdminDestinations(List<Long> regionIds, String destinationType, String dataStatus,
                                      String keyword, Boolean publicVisible) {
        if (regionIds == null || regionIds.isEmpty()) {
            return 0;
        }
        DestinationSearchKeyword search = DestinationSearchKeyword.of(keyword);
        return destinationMapper.countAdminDestinations(regionIds, destinationType, dataStatus,
                search.namePattern(), search.chosungPattern(), publicVisible);
    }

    /** 관리자 목록 상단 데이터 상태별 건수. 데이터 상태 필터만 빼고 지금 목록 조건을 그대로 쓴다. */
    public AdminDestinationDataStatusCounts getAdminDestinationDataStatusCounts(List<Long> regionIds,
                                                                                String destinationType,
                                                                                String keyword) {
        return getAdminDestinationDataStatusCounts(regionIds, destinationType, keyword, null);
    }

    public AdminDestinationDataStatusCounts getAdminDestinationDataStatusCounts(List<Long> regionIds,
            String destinationType, String keyword, Boolean publicVisible) {
        if (regionIds == null || regionIds.isEmpty()) {
            return new AdminDestinationDataStatusCounts();
        }
        DestinationSearchKeyword search = DestinationSearchKeyword.of(keyword);
        AdminDestinationDataStatusCounts counts = destinationMapper.countAdminDestinationDataStatus(
                regionIds, destinationType, search.namePattern(), search.chosungPattern(), publicVisible);
        return counts != null ? counts : new AdminDestinationDataStatusCounts();
    }


    // 조회수 증가
    public void incrementViewCount(Long id) {
        destinationMapper.incrementViewCount(id);
    }

    /**
     * 여행지 상세 조회 한 번을 기록한다.
     * 누적 조회수(destinations.views)는 매번 +1 하고, 일별 집계(destination_view_daily)는
     * firstViewOn 이 '오늘(KST) 처음 본 여행지'라고 답할 때만 +1 한다. 두 쓰기는 한 트랜잭션이다.
     * 세션 중복 판정은 웹 계층이 firstViewOn 으로 넘긴다. (이 서비스와 Mapper 는 세션을 모른다)
     */
    @Transactional
    public void recordDetailView(Long destinationId, Predicate<LocalDate> firstViewOn) {
        incrementViewCount(destinationId);
        LocalDate today = viewClock.today();
        if (firstViewOn.test(today)) {
            viewDailyMapper.incrementDailyView(destinationId, today);
        }
    }

    // 여행지 삭제

    @Transactional
    public void deleteById(Long id) {
        List<DestinationImage> images = destinationMapper.findImagesByDestinationId(id);
        List<String> imageUrls = images == null
                ? List.of()
                : images.stream()
                .map(DestinationImage::getImageUrl)
                .toList();
        // 댓글 row 가 지워지기 전에 정리 대상 사진 URL 을 모아 둔다
        List<String> commentImageUrls = destinationCommentService.findAllCommentImageUrls(id);
        /*
          이 여행지를 담고 있는 코스도 지우기 전에 받아 둔다.
          여행지가 사라지면 연결 행이 FK CASCADE 로 함께 없어져,
          그다음에는 어느 코스가 영향을 받았는지 알아낼 방법이 없다.
        */
        List<Long> affectedCourseIds = courseService.getCourseIdsContainingDestination(id);

        // 연관 테이블 먼저 삭제
        // 북마크는 target_type/target_id 구조라 FK cascade 대상이 아니므로 직접 지운다
        bookmarkMapper.deleteByTarget(BookmarkTargetType.DESTINATION.name(), id);
        destinationMapper.deleteImagesByDestinationId(id);
        destinationMapper.deleteTranslationsByDestinationId(id);
        destinationMapper.deleteCommentsByDestinationId(id);
        destinationMapper.deleteDestinationCategoriesByDestinationId(id); // 이 줄 추가됨

        // 마지막으로 본체 삭제
        destinationMapper.deleteById(id);

        /*
          방금 CASCADE 로 STOP 하나가 빠진 코스들의 번호를 메꾼다.
          그대로 두면 남은 STOP 이 예전 번호를 들고 있어 "STOP 2" 하나만 남는다.

          같은 트랜잭션 안이라 여기서 실패하면 여행지 삭제까지 함께 되돌아간다.
          여행지만 지워지고 번호는 깨진 채로 남는 절반의 성공을 만들지 않는다.
        */
        courseService.resequenceStops(affectedCourseIds);

        destinationImageService.deleteFilesAfterCommit(imageUrls);
        destinationCommentService.deleteImageFilesAfterCommit(commentImageUrls);
    }

    /** 관리자 목록에서 한 번에 지울 수 있는 최대 여행지 수. 목록 한 쪽(30건)과 같다. */
    public static final int ADMIN_BULK_DELETE_LIMIT = 30;

    /**
     * 관리자 목록에서 고른 여행지 여러 건을 한 번에 지운다.
     *
     * <p>하나라도 이미 없는 여행지면 아무것도 지우지 않는다. 실제 삭제는 단건 삭제({@link #deleteById})를 차례로 적용해
     * 연관 데이터 정리·코스 STOP 재정렬·커밋 후 파일 정리 규칙을 그대로 따른다.
     * 전체가 한 트랜잭션이라 중간에 실패하면 앞서 지운 여행지까지 되돌아가고, 파일도 커밋된 뒤에만 지워진다.
     *
     * @return 지운 여행지 수
     */
    @Transactional
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("삭제할 여행지를 선택해 주세요.");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("잘못된 여행지가 선택되었습니다. 새로고침한 뒤 다시 선택해 주세요.");
        }
        Set<Long> targetIds = new LinkedHashSet<>(ids);
        if (targetIds.size() > ADMIN_BULK_DELETE_LIMIT) {
            throw new IllegalArgumentException("한 번에 최대 " + ADMIN_BULK_DELETE_LIMIT + "건까지 삭제할 수 있습니다.");
        }
        if (targetIds.stream().anyMatch(id -> destinationMapper.findById(id) == null)) {
            throw new IllegalArgumentException(
                    "선택한 여행지 중 이미 삭제된 것이 있습니다. 새로고침한 뒤 다시 선택해 주세요.");
        }
        targetIds.forEach(this::deleteById);
        return targetIds.size();
    }

    public List<DestinationDto> convertToDtoWithBookmark(List<Destination> destinations, Long userId) {
        return convertToDtoWithBookmark(destinations, userId, null, Map.of());
    }

    public List<DestinationDto> convertToLocalizedDtoWithBookmark(
            List<Destination> destinations,
            Long userId,
            SupportedLanguage requestedLanguage,
            Map<Long, String> localizedRegionNames) {
        List<Destination> available = destinations == null ? List.of() : destinations;
        List<Long> destinationIds = available.stream()
                .map(Destination::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<DestinationTranslation> translations = destinationIds.isEmpty()
                ? List.of()
                : destinationMapper.findTranslationsByDestinationIds(destinationIds);
        Map<Long, List<DestinationTranslation>> translationsByDestinationId =
                orderedTranslations(translations).stream()
                        .filter(translation -> translation.getDestinationId() != null)
                        .collect(Collectors.groupingBy(
                                DestinationTranslation::getDestinationId,
                                LinkedHashMap::new,
                                Collectors.toList()));

        return convertToDtoWithBookmark(available, userId,
                requestedLanguage == null ? SupportedLanguage.KOREAN : requestedLanguage,
                localizedRegionNames == null ? Map.of() : localizedRegionNames,
                translationsByDestinationId);
    }

    public Map<Long, DestinationTranslation> resolveLocalizedContentByDestinationIds(
            Collection<Long> destinationIds,
            SupportedLanguage requestedLanguage) {
        return destinationLocalizationService.resolveLocalizedContentByDestinationIds(
                destinationIds, requestedLanguage);
    }

    private List<DestinationDto> convertToDtoWithBookmark(
            List<Destination> destinations,
            Long userId,
            SupportedLanguage requestedLanguage,
            Map<Long, String> localizedRegionNames) {
        return convertToDtoWithBookmark(destinations, userId, requestedLanguage,
                localizedRegionNames, Map.of());
    }

    private List<DestinationDto> convertToDtoWithBookmark(
            List<Destination> destinations,
            Long userId,
            SupportedLanguage requestedLanguage,
            Map<Long, String> localizedRegionNames,
            Map<Long, List<DestinationTranslation>> translationsByDestinationId) {
        List<Destination> available = destinations == null ? List.of() : destinations;
        // 현재 페이지에 표시하는 여행지만 북마크 상태와 댓글 수를 읽는다.
        List<Long> ids = available.stream()
                .map(Destination::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> bookmarkedIds = (userId != null && !ids.isEmpty())
                ? bookmarkMapper.findBookmarkedTargetIds(userId, "DESTINATION", ids)
                : Collections.emptySet();

        // 여행지별 댓글 수를 한 번에 조회
        Map<Long, Integer> commentCountMap = destinationCommentService.countCommentsByDestinationIds(ids);

        return available.stream()
                .map(dest -> {
                    List<DestinationTranslation> translations =
                            translationsByDestinationId.getOrDefault(dest.getId(), List.of());
                    DestinationTranslation requested = requestedLanguage == null
                            ? null
                            : translationFor(translations, requestedLanguage.getLanguageTag());
                    DestinationTranslation korean = requestedLanguage == null
                            ? null
                            : translationFor(translations,
                            SupportedLanguage.KOREAN.getLanguageTag());
                    DestinationDto dto = new DestinationDto();
                    dto.setId(dest.getId());
                    dto.setName(requestedLanguage == null
                            ? dest.getName()
                            : localizedField(DestinationTranslation::getName,
                            requested, korean, translations, dest.getName()));
                    dto.setThumbnailPath(dest.getThumbnailPath());
                    dto.setRegionName(localizedRegionNames.getOrDefault(
                            dest.getRegionId(), dest.getRegionName()));
                    dto.setShortDescription(requestedLanguage == null
                            ? dest.getShortDescription()
                            : localizedField(DestinationTranslation::getShortDescription,
                            requested, korean, translations, dest.getShortDescription()));
                    dto.setBookmarked(bookmarkedIds.contains(dest.getId()));

                    dto.setCommentCount(commentCountMap.getOrDefault(dest.getId(), 0));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private String localizedField(Function<DestinationTranslation, String> field,
                                  DestinationTranslation requested,
                                  DestinationTranslation korean,
                                  List<DestinationTranslation> ordered,
                                  String baseValue) {
        String localized = localizedField(field, requested, korean, ordered);
        return localized == null ? baseValue : localized;
    }

    /**
     * 저장할 대표 카테고리. 선택한 카테고리 중 하나여야 한다.
     *
     * @return 요청한 대표, 요청이 없으면 선택한 것 중 가장 작은 ID, 선택이 없으면 null
     * @throws InvalidMainCategoryException 선택하지 않은 카테고리를 대표로 보낸 경우
     */
    public static Long resolveMainCategoryId(List<Long> categoryIds, Long requestedMainCategoryId) {
        List<Long> selected = categoryIds == null ? List.of()
                : categoryIds.stream().filter(Objects::nonNull).toList();
        if (requestedMainCategoryId != null) {
            if (!selected.contains(requestedMainCategoryId)) {
                throw new InvalidMainCategoryException();
            }
            return requestedMainCategoryId;
        }
        return selected.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * 연결을 모두 맞춘 뒤 대표 표시만 바꾼다. 기존 대표를 먼저 지워야 여행지당 대표 1개 UNIQUE 와 부딪히지 않는다.
     *
     * @param current 연결을 맞춘 뒤의 현재 대표(새로 등록한 여행지는 null)
     */
    private void saveMainCategory(Long destinationId, Long current, Long mainCategoryId) {
        if (Objects.equals(current, mainCategoryId)) {
            return;
        }
        if (current != null) {
            destinationMapper.clearMainCategory(destinationId);
        }
        if (mainCategoryId != null && destinationMapper.markMainCategory(destinationId, mainCategoryId) != 1) {
            // 방금 맞춘 연결에 없는 대표 → 트랜잭션을 되돌린다.
            throw new IllegalStateException("Main category link is missing: destinationId="
                    + destinationId + ", categoryId=" + mainCategoryId);
        }
    }

    @Transactional
    public void updateDestination(Long destinationId, DestinationForm form) {
        // 1. 기본 정보 update
        // 이미 삭제된 여행지의 stale 수정 폼이면 어떤 DB 작업도 시작하지 않는다.
        Destination destination = destinationMapper.findById(destinationId);
        if (destination == null) {
            throw new DestinationNotFoundException();
        }
        Long mainCategoryId = resolveMainCategoryId(form.getCategoryIds(), form.getMainCategoryId());
        destination.setLatitude(form.getLatitude());
        destination.setLongitude(form.getLongitude());
        destination.setGooglePlaceId(form.getGooglePlaceId());
        destination.setSeason(DestinationSeason.valueOf(form.getSeason()));
        destination.setRegionId(form.getRegionId());
        destination.setType(form.getType());
        destinationMapper.updateDestination(destination);

        // 2. 타입별 상세 정보 update (각 InfoService에 update 메서드 필요)
        switch (form.getType()) {
            case ATTRACTION:
                if (form.getAttractionInfo() != null) {
                    form.getAttractionInfo().setDestinationId(destinationId);
                    attractionInfoService.update(form.getAttractionInfo());
                    attractionInfoService.saveTranslations(destinationId,
                            form.getAttractionInfo(), form.getAttractionInfoTranslations());
                }
                break;
            case ACCOMMODATION:
                if (form.getAccommodationInfo() != null) {
                    form.getAccommodationInfo().setDestinationId(destinationId);
                    accommodationInfoService.update(form.getAccommodationInfo());
                    accommodationInfoService.saveTranslations(destinationId,
                            form.getAccommodationInfo(), form.getAccommodationInfoTranslations());
                }
                break;
            case RESTAURANTS:
            case CAFE:
                if (form.getRestaurantInfo() != null) {
                    form.getRestaurantInfo().setDestinationId(destinationId);
                    restaurantInfoService.update(form.getRestaurantInfo());
                    restaurantInfoService.saveTranslations(destinationId,
                            form.getRestaurantInfo(), form.getRestaurantInfoTranslations());
                }
                break;
            case ACTIVITY:
                if (form.getActivityInfo() != null) {
                    form.getActivityInfo().setDestinationId(destinationId);
                    activityInfoService.update(form.getActivityInfo());
                    activityInfoService.saveTranslations(destinationId,
                            form.getActivityInfo(), form.getActivityInfoTranslations());
                }
                break;
            case SHOP:
                if (form.getShopInfo() != null) {
                    form.getShopInfo().setDestinationId(destinationId);
                    shopInfoService.update(form.getShopInfo());
                    shopInfoService.saveTranslations(destinationId,
                            form.getShopInfo(), form.getShopInfoTranslations());
                }
                break;
        }

        // 3. 번역(Translation) update or insert
        List<DestinationTranslation> existingTranslations = destinationMapper.findTranslationsByDestinationId(destinationId);
        for (DestinationTranslationForm transForm : form.getTranslations()) {
            DestinationTranslation match = existingTranslations.stream()
                    .filter(t -> t.getLanguageCode().equals(transForm.getLanguageCode()))
                    .findFirst().orElse(null);

            // 값이 하나도 없는 번역 언어는 남기지 않는다. 있던 줄이면 그 언어만 지운다.
            if (!keepsTranslation(transForm)) {
                if (match != null) {
                    destinationMapper.deleteTranslation(destinationId, transForm.getLanguageCode());
                }
                continue;
            }

            if (match != null) {
                // UPDATE
                match.setName(transForm.getName());
                match.setDescription(transForm.getDescription());
                match.setShortDescription(transForm.getShortDescription());
                destinationMapper.updateTranslation(match);
                DestinationTranslationSource source = translationSourceMapper == null ? null
                        : translationSourceMapper.findByTranslationId(match.getId());
                if (source != null) {
                    if (!hasText(transForm.getDescription())
                            || form.getWikipediaUnlinkLanguages().contains(transForm.getLanguageCode())) {
                        translationSourceMapper.deleteByTranslationId(match.getId());
                    } else {
                        translationSourceMapper.updateModified(match.getId(), !Arrays.equals(
                                WikidataRegistrationService.sha256(transForm.getDescription()),
                                source.getOriginalContentSha256()));
                    }
                }
            } else {
                // INSERT
                DestinationTranslation trans = new DestinationTranslation();
                trans.setDestinationId(destinationId);
                trans.setLanguageCode(transForm.getLanguageCode());
                trans.setName(transForm.getName());
                trans.setDescription(transForm.getDescription());
                trans.setShortDescription(transForm.getShortDescription());
                destinationMapper.insertTranslation(trans);
            }
        }

        // 4. 카테고리 부분 수정(차집합 기반)
        List<Long> beforeIds = destinationMapper.findCategoryIdsByDestinationId(destinationId);
        List<Long> afterIds = form.getCategoryIds();

        for (Long id : afterIds) {
            if (!beforeIds.contains(id)) {
                destinationMapper.insertDestinationCategory(destinationId, id);
            }
        }
        for (Long id : beforeIds) {
            if (!afterIds.contains(id)) {
                destinationMapper.deleteDestinationCategory(destinationId, id);
            }
        }
        // 대표는 연결 행의 표시만 바꾼다(대표만 바뀌면 위 연결 INSERT/DELETE 는 일어나지 않는다).
        saveMainCategory(destinationId, destinationMapper.findMainCategoryId(destinationId), mainCategoryId);

        // 5. 편의시설(amenity) 부분도 위와 동일(amenityService에서 비교 후 갱신)
        switch (form.getType()) {
            case ATTRACTION:
                amenityService.updateAttractionAmenities(destinationId, form.getAttractionAmenityIds());
                break;
            case ACCOMMODATION:
                amenityService.updateAccommodationAmenities(destinationId, form.getAccommodationAmenityIds());
                break;
            case RESTAURANTS:
            case CAFE:
                amenityService.updateRestaurantAmenities(destinationId, form.getRestaurantAmenityIds());
                break;
            case ACTIVITY:
                amenityService.updateActivityAmenities(destinationId, form.getActivityAmenityIds());
                break;
            case SHOP:
                amenityService.updateShopAmenities(destinationId, form.getShopAmenityIds());
                break;
        }
    }

    public List<DestinationTranslation> getTranslationsByDestinationId(Long destinationId) {
        return destinationMapper.findTranslationsByDestinationId(destinationId);
    }

    public Map<String, DestinationTranslationSource> getWikipediaSourcesByDestinationId(Long destinationId) {
        Map<String, DestinationTranslationSource> sources = new LinkedHashMap<>();
        List<DestinationTranslation> translations = destinationMapper.findTranslationsByDestinationId(destinationId);
        if (translations == null) return sources;
        for (DestinationTranslation translation : translations) {
            DestinationTranslationSource source = translationSourceMapper == null ? null
                    : translationSourceMapper.findByTranslationId(translation.getId());
            if (source != null) sources.put(translation.getLanguageCode(), source);
        }
        return sources;
    }

    public DestinationTranslationSource findDisplayedWikipediaSource(Long destinationId,
            SupportedLanguage requestedLanguage, String displayedDescription) {
        if (!hasText(displayedDescription)) return null;
        List<DestinationTranslation> ordered = orderedTranslations(
                destinationMapper.findTranslationsByDestinationId(destinationId));
        DestinationTranslation requested = translationFor(ordered, requestedLanguage.getLanguageTag());
        DestinationTranslation korean = translationFor(ordered, SupportedLanguage.KOREAN.getLanguageTag());
        List<DestinationTranslation> candidates = new ArrayList<>();
        if (requested != null) candidates.add(requested);
        if (korean != null && !candidates.contains(korean)) candidates.add(korean);
        for (DestinationTranslation translation : ordered) {
            if (!candidates.contains(translation)) candidates.add(translation);
        }
        for (DestinationTranslation translation : candidates) {
            if (hasText(translation.getDescription())) {
                return displayedDescription.equals(translation.getDescription())
                        ? translationSourceMapper.findByTranslationId(translation.getId()) : null;
            }
        }
        return null;
    }

    // 페이징
    // 국내 전체 여행지 페이징 (대한민국=7 기준)
    public List<Destination> getDomesticDestinationsPaged(int offset, int size, String sort) {
        return destinationMapper.findDomesticPaged(offset, size, sort);
    }
    public int countDomesticDestinations() {
        return destinationMapper.countDomestic();
    }

    //  (공통) 특정 루트 지역(rootRegionId) 이하 전체(계층 포함) 페이징
    public List<Destination> getDestinationsByRootRegionPaged(Long rootRegionId, int offset, int size, String sort) {
        List<Long> regionIds = destinationMapper.findAllRegionIdsUnder(rootRegionId);
        return regionIds.isEmpty()
                ? Collections.emptyList()
                : destinationMapper.findByRegionIdsPaged(regionIds, null, offset, size, sort);
    }

    //  (공통) 특정 루트 지역(rootRegionId) 이하 전체(계층 포함) 카운트
    public int countDestinationsByRootRegion(Long rootRegionId) {
        List<Long> regionIds = destinationMapper.findAllRegionIdsUnder(rootRegionId);
        return regionIds.isEmpty()
                ? 0
                : destinationMapper.countByRegionIds(regionIds, null);
    }


    // 지역별 여행지 페이징
    /**
     * 지역 목록 한 쪽.
     *
     * @param categoryIds 비어 있지 않으면 그중 하나라도 등록된 여행지만(대표만이 아니라 모든 카테고리 연결 기준)
     */
    public List<Destination> getDestinationsByRegionIdsPaged(List<Long> regionIds, List<Long> categoryIds,
                                                             int offset, int size, String sort) {
        return destinationMapper.findByRegionIdsPaged(regionIds, categoryIds, offset, size, sort);
    }
    public int countDestinationsByRegionIds(List<Long> regionIds, List<Long> categoryIds) {
        return destinationMapper.countByRegionIds(regionIds, categoryIds);
    }

    /**
     * 목록 카테고리 필터의 선택지: 카테고리 ID → 지역 범위에서 그 카테고리가 등록된 여행지 수.
     * 지역 범위 여행지에 등록된 카테고리와, 고른 카테고리 중 실제로 있는 것(0곳이어도)만 들어 있다.
     */
    public Map<Long, Integer> getCategoryFilterCounts(List<Long> regionIds, List<Long> selectedCategoryIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (regionIds == null || regionIds.isEmpty()) {
            return counts;
        }
        for (var row : destinationMapper.findCategoryFilterCounts(regionIds, selectedCategoryIds)) {
            if (row != null && row.getCategoryId() != null) {
                counts.put(row.getCategoryId(), row.getDestinationCount());
            }
        }
        return counts;
    }




}
