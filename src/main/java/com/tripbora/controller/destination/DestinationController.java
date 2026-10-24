package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.dto.DestinationDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.model.DestinationImage;
import com.tripbora.seo.SeoModel;
import com.tripbora.seo.SeoStructuredData;
import com.tripbora.seo.SeoTextUtils;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.file.DestinationCardThumbnailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import jakarta.servlet.http.HttpServletRequest; // Spring Boot 3.x
import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class DestinationController {

    /** 여행지 목록 기본 페이지 크기. 목록 카드가 4열이라 12개가 3줄로 떨어진다. */
    private static final String DEFAULT_PAGE_SIZE = "12";

    /** Maps Embed API 기본 확대 수준 */
    private static final int EMBED_MAP_ZOOM = 15;

    /** 해외 지도(Maps Embed API) 키. 환경변수 GOOGLE_MAPS_API_KEY 로만 주입한다. */
    @Value("${GOOGLE_MAPS_API_KEY:}")
    private String googleMapsApiKey;

    private final DestinationService destinationService;
    private final DestinationImageService destinationImageService;
    private final CountryCategoryService countryCategoryService;
    private final DestinationCommentService destinationCommentService;
    private final ReferenceNameLocalizationService referenceNameLocalizationService;
    private final DestinationCardThumbnailService cardThumbnailService;

    // 공통 리스트: type=domestic or overseas, region(도시, 국가 등) id
    @GetMapping("/destinations")
    public String destinationList(
            @RequestParam(value = "type", defaultValue = "domestic") String type,
            @RequestParam(value = "region", required = false) Long regionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "sort", defaultValue = "default") String sort,
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest request,
            Model model) {

        Long userId = (userDetails != null) ? userDetails.getId() : null;
        size = Math.max(1, Math.min(size, 100));
        sort = DestinationPagination.sort(sort);
        int totalCount;
        List<Destination> rawList;

        List<CountryCategory> cities;
        Long selectedCityId = null;
        List<CountryCategory> subregions = null;
        String selectedCityName = null;

        // [1] 상단 region 케러셀/서브카테고리 분기
        if ("domestic".equals(type)) {
            // 국내: rootId = 7
            final Long rootId = 7L;
            if (regionId == null) {
                cities = countryCategoryService.getSubregions(rootId, 3);
            } else {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 3) {
                    // 시/도 클릭 (서울 등)
                    cities = countryCategoryService.getSubregions(rootId, 3);


                    selectedCityId = region.getId();
                    // 구/군(하위) 있는지 체크
                    subregions = countryCategoryService.getSubregions(region.getId(), 4);
                } else if (region.getDepth() == 4) {
                    // 구/군 클릭
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    cities = countryCategoryService.getSubregions(rootId, 3);
                    selectedCityId = parent.getId();
                    // 구/군 형제들
                    subregions = countryCategoryService.getSubregions(parent.getId(), 4);
                } else {
                    cities = countryCategoryService.getSubregions(rootId, 3);
                }
            }
        } else {
            // 해외
            if (regionId == null) {
                // 대륙 리스트 (대한민국 제외)
                List<Long> overseasRootIds = countryCategoryService.getOverseasRootIds();
                cities = overseasRootIds.stream()
                        .map(countryCategoryService::getById)
                        .toList();
            } else {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 1) {
                    // 대륙 클릭 → 국가 리스트
                    cities = countryCategoryService.getSubregions(regionId, 2);

                    selectedCityId = region.getId();
                    subregions = null;


                } else if (region.getDepth() == 2) {
                    // 국가 클릭
                    List<CountryCategory> childCities = countryCategoryService.getSubregions(regionId, 3);
                    if (!childCities.isEmpty()) {
                        // 하위 도시 있음: cities=형제국가, subregions=하위도시
                        cities = countryCategoryService.getSubregions(region.getParentId(), 2);


                        selectedCityId = region.getId();
                        subregions = childCities;
                    } else {
                        // 하위 도시 없음: cities=형제국가, subregions=null
                        cities = countryCategoryService.getSubregions(region.getParentId(), 2);


                        selectedCityId = region.getId();
                        subregions = null;
                    }
                } else if (region.getDepth() == 3) {
                    // 도시 클릭: cities=해당 국가 모든 도시, subregions=형제 도시들
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    cities = countryCategoryService.getSubregions(parent.getId(), 3);

                    selectedCityId = region.getId();
                    subregions = countryCategoryService.getSubregions(parent.getId(), 3);
                } else {
                    cities = List.of();
                }
            }
        }

        model.addAttribute("cities", cities);
        model.addAttribute("type", type);
        model.addAttribute("selectedCityId", selectedCityId != null ? selectedCityId : regionId);

        // [2] subregions와 선택값 세팅(위에서 다 처리, 중복 없음)
        model.addAttribute("subregions", subregions);
        model.addAttribute("selectedSubregionId", regionId);

        // [3] 선택 지역명, 여행지 리스트
        if (regionId != null) {
            CountryCategory selectedCity = countryCategoryService.getById(regionId);
            selectedCityName = selectedCity.getRegionName();
            model.addAttribute("selectedCityName", selectedCityName);

            List<Long> regionIds = countryCategoryService.getAllRegionIdsUnder(regionId);
            List<Long> categoryIds = applyCategoryFilter(category,regionIds, model);
            totalCount = destinationService.countDestinationsByRegionIds(regionIds, categoryIds);
            page = DestinationPagination.page(page, totalCount, size);
            rawList = destinationService.getDestinationsByRegionIdsPaged(regionIds, categoryIds, (page - 1) * size, size, sort);

        } else {
            List<Long> rootRegionIds = "overseas".equals(type)
                    ? countryCategoryService.getOverseasRootIds()
                    : countryCategoryService.getDomesticRootIds();
            List<Long> allRegionIds = rootRegionIds.stream()
                    .flatMap(id -> countryCategoryService.getAllRegionIdsUnder(id).stream())
                    .toList();
            List<Long> categoryIds = applyCategoryFilter(category,allRegionIds, model);
            totalCount = destinationService.countDestinationsByRegionIds(allRegionIds, categoryIds);
            page = DestinationPagination.page(page, totalCount, size);
            rawList = destinationService.getDestinationsByRegionIdsPaged(allRegionIds, categoryIds, (page - 1) * size, size, sort);

            model.addAttribute("selectedCityName", null);
        }

        DestinationListLocalization localization = localizeDestinationList(
                cities, subregions, rawList, regionId, selectedCityName, userId);
        List<DestinationDto> destinations = localization.destinations();
        selectedCityName = localization.selectedRegionName();
        int totalPages = (int) Math.ceil((double) totalCount / size);
        addPagination(model, page, totalPages, regionId);

        model.addAttribute("destinations", destinations);
        model.addAttribute("regionDisplayNames", localization.regionDisplayNames());
        model.addAttribute("selectedCityName", selectedCityName);
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);
        // 주소로 바로 연 정렬(새로고침·공유)도 정렬 버튼에 그대로 표시한다.
        model.addAttribute("sort", sort);

        Map<String, Object> canonicalParameters = SeoModel.parameters();
        canonicalParameters.put("type", "overseas".equals(type) ? type : null);
        canonicalParameters.put("region", regionId);
        canonicalParameters.put("page", page);
        model.addAttribute("seoCanonicalPath",
                SeoModel.canonicalPath("/destinations", canonicalParameters));

        return "destination/list";

    }



    // 여행지 상세 (국내/해외 구분 없이 동일)
    @GetMapping("/destinations/{id}")
    public String destinationDetail(@PathVariable Long id,
                                    @AuthenticationPrincipal CustomUserDetails userDetails,
                                    HttpSession session,
                                    Model model) {
        Long userId = (userDetails != null) ? userDetails.getId() : null;

        // 1. 여행지 + 타입별 상세 + amenity + 이미지 전부
        // 삭제됐거나 없는 여행지는 정상적인 404 로 응답한다.
        SupportedLanguage requestedLanguage = SupportedLanguage
                .fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        DestinationDetailDto dto = destinationService.getDestinationDetailWithInfo(
                id, requestedLanguage);
        boolean administrator = userDetails != null && userDetails.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (dto == null || dto.getDestination() == null
                || (!dto.getDestination().isPublicVisible() && !administrator)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행지를 찾을 수 없습니다.");
        }

        // ✅ 조회수 증가: 누적 조회수는 매번, 일별 집계(지금 뜨는 여행지)는 이 세션에서 KST 하루 1회만 센다.
        destinationService.recordDetailView(id,
                today -> DestinationDailyViewSession.markFirstView(session, id, today));

        model.addAttribute("destination", dto.getDestination());
        model.addAttribute("images", dto.getImages());
        model.addAttribute("hasImageAttribution", dto.getImages() != null
                && dto.getImages().stream().anyMatch(DestinationImage::isAttributionPresent));

        model.addAttribute("descriptionParagraphs", descriptionParagraphs(dto.getDestination().getDescription()));
        model.addAttribute("wikipediaSource", destinationService.findDisplayedWikipediaSource(
                id, requestedLanguage, dto.getDestination().getDescription()));

        // 2. 타입별 추가 정보
        if (dto.getAccommodationInfo() != null) {
            model.addAttribute("accommodationInfo", dto.getAccommodationInfo());
            model.addAttribute("accommodationAmenities", dto.getAccommodationAmenities());
        }
        if (dto.getRestaurantInfo() != null) {
            model.addAttribute("restaurantInfo", dto.getRestaurantInfo());
            model.addAttribute("restaurantAmenities", dto.getRestaurantAmenities());
        }
        if (dto.getAttractionInfo() != null) {
            model.addAttribute("attractionInfo", dto.getAttractionInfo());
            model.addAttribute("attractionAmenities", dto.getAttractionAmenities());
            String guide = dto.getAttractionInfo().getGuide();
            if (guide != null && !guide.isBlank()) {
                String normalizedGuide = guide.replace("\r\n", "\n").replace('\r', '\n');
                String sanitized = HtmlUtils.htmlEscape(normalizedGuide).replace("\n", "<br>");
                model.addAttribute("attractionGuideWithBr", sanitized);
            } else {
                model.addAttribute("attractionGuideWithBr", "-");
            }
        }
        if (dto.getActivityInfo() != null) {
            model.addAttribute("activityInfo", dto.getActivityInfo());
            model.addAttribute("activityAmenities", dto.getActivityAmenities());
        }
        if (dto.getShopInfo() != null) {
            model.addAttribute("shopInfo", dto.getShopInfo());
            model.addAttribute("shopAmenities", dto.getShopAmenities());
        }

        // 지역/부모지역/카테고리/댓글/비슷한 여행지 등은 기존대로
        CountryCategory region = countryCategoryService.getById(dto.getDestination().getRegionId());
        model.addAttribute("regionId", region.getId());
        // 목록으로 돌아가는 링크(/destinations?type=..&region=..)가 쓰는 값.
        // 비어 있으면 목록이 지역 필터를 적용하지 못한다.
        model.addAttribute("type", resolveRegionType(region));

        CountryCategory parentRegion = null;
        if (region.getParentId() != null) {
            parentRegion = countryCategoryService.getById(region.getParentId());
            model.addAttribute("regionPathId", parentRegion.getId());
        } else {
            model.addAttribute("regionPathId", null);
        }

        // 관리자가 등록한 도시·시/군/구는 code 를 비워 둔다. 그때는 부모(국가·시/도)의 code 로 국가를 정한다.
        String code = region.getCode() != null ? region.getCode()
                : parentRegion != null ? parentRegion.getCode() : null;
        String countryCode = code != null ? code.split("-")[0] : null;
        model.addAttribute("countryCode", countryCode);

        List<CountryCategory> detailRegions = new ArrayList<>();
        detailRegions.add(region);
        if (parentRegion != null) {
            detailRegions.add(parentRegion);
        }
        Map<Long, String> localizedRegionNames =
                referenceNameLocalizationService.localizeCountryCategories(
                        detailRegions, requestedLanguage);
        model.addAttribute("regionName", localizedDisplayName(
                localizedRegionNames, region.getId(), region.getRegionName()));
        model.addAttribute("regionPath", parentRegion == null ? null : localizedDisplayName(
                localizedRegionNames, parentRegion.getId(), parentRegion.getRegionName()));

        // 해외 지도는 Maps Embed API iframe 으로 표시한다. 만들 수 없으면 null → 지도 영역만 감춘다.
        model.addAttribute("overseasMapEmbedUrl",
                buildOverseasMapEmbedUrl(countryCode, dto.getDestination()));
        model.addAttribute("overseasMapLinkUrl",
                buildOverseasMapLinkUrl(countryCode, dto.getDestination()));

        List<Long> categoryIds = dto.getCategoryIds() == null ? List.of() : dto.getCategoryIds();
        Map<Long, String> localizedCategoryNames =
                referenceNameLocalizationService.localizeCategories(categoryIds, requestedLanguage);
        // 대표 카테고리 하나만 보여준다. 대표가 없는 기존 데이터는 가장 작은 ID(MIN(category_id))를 쓴다.
        Long mainCategoryId = dto.getMainCategoryId();
        Long categoryId = categoryIds.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing((Long candidate) -> !candidate.equals(mainCategoryId))
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .filter(candidate -> localizedDisplayName(localizedCategoryNames, candidate, null) != null)
                .findFirst()
                .orElse(null);
        model.addAttribute("categoryName", categoryId == null ? null
                : localizedDisplayName(localizedCategoryNames, categoryId, null));
        // 배지를 누르면 이 카테고리가 선택된 목록(같은 국내/해외 전체 지역)으로 간다.
        model.addAttribute("categoryId", categoryId);

        int commentCount = destinationCommentService.getCommentCountByDestinationId(id);
        model.addAttribute("commentCount", commentCount);

        List<Destination> similarEntities = destinationService.getSimilarDestinations(id, 4);
        List<DestinationDto> similarDtos = destinationService.convertToDtoWithBookmark(similarEntities, userId);
        destinationImageService.markNoDerivatives(similarDtos, DestinationDto::getThumbnailPath,
                DestinationDto::setImageNoDerivatives);
        model.addAttribute("similarDestinations", similarDtos);

        String seoImage = dto.getImages() == null ? null : dto.getImages().stream()
                .map(image -> image.getImageUrl())
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
        SeoModel.apply(model,
                dto.getDestination().getName() + " | TripBora",
                SeoTextUtils.firstNonBlank(dto.getDestination().getShortDescription(),
                        dto.getDestination().getDescription()),
                "/destinations/" + id,
                SeoTextUtils.firstNonBlank(dto.getDestination().getThumbnailPath(), seoImage),
                "article");
        SeoStructuredData.place(model, "/destinations/" + id,
                dto.getDestination().getName(),
                SeoTextUtils.firstNonBlank(dto.getDestination().getShortDescription(),
                        dto.getDestination().getDescription()),
                SeoTextUtils.firstNonBlank(dto.getDestination().getThumbnailPath(), seoImage),
                null,
                dto.getDestination().getLatitude(), dto.getDestination().getLongitude());

        return "destination/detail";
    }

    private String localizedDisplayName(Map<Long, String> localizedNames,
                                        Long id,
                                        String baseName) {
        if (localizedNames != null) {
            String localizedName = localizedNames.get(id);
            if (localizedName != null && !localizedName.isBlank()) {
                return localizedName;
            }
        }
        return baseName;
    }

    private List<String> descriptionParagraphs(String description) {
        if (description == null || description.isBlank()) {
            return List.of("-");
        }

        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        String normalized = description.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            String text = line.strip();
            if (text.isEmpty()) {
                if (!paragraph.isEmpty()) {
                    paragraphs.add(paragraph.toString());
                    paragraph.setLength(0);
                }
                continue;
            }
            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(text);
        }
        if (!paragraph.isEmpty()) {
            paragraphs.add(paragraph.toString());
        }
        return paragraphs.isEmpty() ? List.of("-") : paragraphs;
    }

    /**
     * 해외 여행지의 Maps Embed API URL. 아래 중 하나라도 없으면 null 을 반환해
     * 잘못된 iframe 대신 지도 영역을 감추게 한다.
     * - 해외 여행지가 아님(국내는 Kakao 지도 유지)
     * - GOOGLE_MAPS_API_KEY 환경변수 미설정
     * - 좌표 미입력
     */
    private String buildOverseasMapEmbedUrl(String countryCode, Destination destination) {
        if (googleMapsApiKey == null || googleMapsApiKey.isBlank()) {
            return null;
        }
        if (countryCode == null || "KR".equals(countryCode)) {
            return null;
        }
        String key = URLEncoder.encode(googleMapsApiKey, StandardCharsets.UTF_8);
        String position = overseasPosition(countryCode, destination);
        String placeId = trimmedPlaceId(destination);

        // 1) Place ID 가 있으면 place 모드로 해당 장소를 정확히 지정한다. (마커 표시)
        //    텍스트 검색은 다른 장소가 선택될 수 있어 쓰지 않는다.
        if (placeId != null) {
            String url = "https://www.google.com/maps/embed/v1/place"
                    + "?key=" + key
                    + "&q=" + URLEncoder.encode("place_id:" + placeId, StandardCharsets.UTF_8);
            if (position != null) {
                url += "&center=" + URLEncoder.encode(position, StandardCharsets.UTF_8)
                        + "&zoom=" + EMBED_MAP_ZOOM;
            }
            return url;
        }

        // 2) Place ID 가 없으면 장소를 추측하지 않고 좌표 중심 view 모드만 보여준다.
        if (position == null) {
            return null;
        }
        return "https://www.google.com/maps/embed/v1/view"
                + "?key=" + key
                + "&center=" + URLEncoder.encode(position, StandardCharsets.UTF_8)
                + "&zoom=" + EMBED_MAP_ZOOM;
    }

    /** 입력되지 않았거나 공백뿐이면 null. */
    private String trimmedPlaceId(Destination destination) {
        String placeId = destination.getGooglePlaceId();
        if (placeId == null || placeId.isBlank()) {
            return null;
        }
        return placeId.trim();
    }

    /** "Google 지도에서 크게 보기" 링크. API 키 없이도 열 수 있어 좌표만 있으면 만든다. */
    private String buildOverseasMapLinkUrl(String countryCode, Destination destination) {
        String position = overseasPosition(countryCode, destination);
        if (position == null) {
            return null;
        }
        String url = "https://www.google.com/maps/search/?api=1"
                + "&query=" + URLEncoder.encode(position, StandardCharsets.UTF_8);
        // Place ID 가 있으면 같은 장소로 정확히 열리게 한다. (query 는 필수라 좌표를 유지)
        String placeId = trimmedPlaceId(destination);
        if (placeId != null) {
            url += "&query_place_id=" + URLEncoder.encode(placeId, StandardCharsets.UTF_8);
        }
        return url;
    }

    /** 해외 여행지의 "위도,경도" 문자열. 국내이거나 좌표가 없으면 null. */
    private String overseasPosition(String countryCode, Destination destination) {
        if (countryCode == null || "KR".equals(countryCode)) {
            return null;
        }
        BigDecimal latitude = destination.getLatitude();
        BigDecimal longitude = destination.getLongitude();
        if (latitude == null || longitude == null) {
            return null;
        }
        return latitude.toPlainString() + "," + longitude.toPlainString();
    }

    /**
     * 지역이 속한 최상위 루트까지 거슬러 올라가 목록의 type 값(domestic/overseas)을 정한다.
     * 지역 ID 는 하드코딩하지 않고 CountryCategoryService 로 판별한다.
     */
    private String resolveRegionType(CountryCategory region) {
        CountryCategory root = region;
        while (root != null && root.getParentId() != null) {
            root = countryCategoryService.getById(root.getParentId());
        }
        if (root == null) {
            return "domestic";
        }
        return countryCategoryService.getDomesticRootIds().contains(root.getId())
                ? "domestic"
                : "overseas";
    }

    @GetMapping("/destinations/fragment")
    public String regionFragment(
            @RequestParam(value = "type", defaultValue = "domestic") String type,
            @RequestParam(value = "region", required = false) Long regionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "sort", defaultValue = "default") String sort,
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Long userId = (userDetails != null) ? userDetails.getId() : null;
        size = Math.max(1, Math.min(size, 100));
        sort = DestinationPagination.sort(sort);
        int totalCount;
        List<Destination> rawList;

        List<CountryCategory> cities = null;
        List<CountryCategory> subregions = null;
        Long selectedSubregionId = null;
        Long selectedCityId = null;
        String selectedCityName = null;

        if ("domestic".equals(type)) {
            final Long rootId = 7L;
            if (regionId == null) {
                cities = countryCategoryService.getSubregions(rootId, 3);
            } else {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 3) {
                    cities = countryCategoryService.getSubregions(rootId, 3);
                    selectedCityId = region.getId();
                    subregions = countryCategoryService.getSubregions(region.getId(), 4);
                } else if (region.getDepth() == 4) {
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    cities = countryCategoryService.getSubregions(rootId, 3);
                    selectedCityId = parent.getId();
                    subregions = countryCategoryService.getSubregions(parent.getId(), 4);
                    selectedSubregionId = regionId;
                } else {
                    cities = countryCategoryService.getSubregions(rootId, 3);
                }
            }
        } else { // overseas
            if (regionId == null) {
                List<Long> overseasRootIds = countryCategoryService.getOverseasRootIds();
                cities = overseasRootIds.stream()
                        .map(countryCategoryService::getById)
                        .toList();
            } else {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 1) {
                    cities = countryCategoryService.getSubregions(regionId, 2);
                    selectedCityId = region.getId();
                } else if (region.getDepth() == 2) {
                    List<CountryCategory> childCities = countryCategoryService.getSubregions(regionId, 3);
                    if (!childCities.isEmpty()) {
                        cities = countryCategoryService.getSubregions(region.getParentId(), 2);
                        selectedCityId = region.getId();
                        subregions = childCities;
                    } else {
                        cities = countryCategoryService.getSubregions(region.getParentId(), 2);
                        selectedCityId = region.getId();
                        subregions = null;
                    }
                } else if (region.getDepth() == 3) {
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    cities = countryCategoryService.getSubregions(parent.getId(), 3);
                    selectedCityId = region.getId();
                    subregions = countryCategoryService.getSubregions(parent.getId(), 3);
                    // 해외 도시도 국내 구/군과 같이 고른 도시를 하위 지역 pill 에 표시한다
                    selectedSubregionId = regionId;
                }
            }
        }

        // 여행지 리스트 추출 (공통)
        List<Long> regionIds;
        if (regionId != null) {
            regionIds = countryCategoryService.getAllRegionIdsUnder(regionId);
            CountryCategory selectedRegion = countryCategoryService.getById(regionId);
            selectedCityName = selectedRegion.getRegionName();
        } else {
            List<Long> rootRegionIds = "overseas".equals(type)
                    ? countryCategoryService.getOverseasRootIds()
                    : countryCategoryService.getDomesticRootIds();
            regionIds = rootRegionIds.stream()
                    .flatMap(id -> countryCategoryService.getAllRegionIdsUnder(id).stream())
                    .toList();
            selectedCityName = null;
        }
        List<Long> categoryIds = applyCategoryFilter(category,regionIds, model);
        totalCount = destinationService.countDestinationsByRegionIds(regionIds, categoryIds);
        page = DestinationPagination.page(page, totalCount, size);
        rawList = destinationService.getDestinationsByRegionIdsPaged(regionIds, categoryIds, (page - 1) * size, size, sort);

        DestinationListLocalization localization = localizeDestinationList(
                cities, subregions, rawList, regionId, selectedCityName, userId);
        List<DestinationDto> destinations = localization.destinations();
        selectedCityName = localization.selectedRegionName();
        int totalPages = (int) Math.ceil((double) totalCount / size);
        addPagination(model, page, totalPages, regionId);

        model.addAttribute("cities", cities);
        model.addAttribute("destinations", destinations);
        model.addAttribute("regionDisplayNames", localization.regionDisplayNames());
        model.addAttribute("subregions", subregions);
        model.addAttribute("selectedSubregionId", selectedSubregionId);
        model.addAttribute("selectedCityId", selectedCityId);
        model.addAttribute("selectedCityName", selectedCityName);
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("sort", sort);
        model.addAttribute("type", type);

        // ★ regionFragment를 리턴 (region-bar + 리스트 + subregion 전체)
        return "destination/fragment :: regionFragment";
    }


    @GetMapping("/destinations/list-fragment")
    public String destinationListFragment(
            @RequestParam(value = "type", defaultValue = "domestic") String type,
            @RequestParam(value = "region", required = false) Long regionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "sort", defaultValue = "default") String sort,
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Long userId = (userDetails != null) ? userDetails.getId() : null;
        size = Math.max(1, Math.min(size, 100));
        sort = DestinationPagination.sort(sort);
        int totalCount;
        List<Destination> rawList;

        List<CountryCategory> subregions = null;
        Long selectedSubregionId = null;
        Long selectedCityId = null;
        String selectedCityName = null;

        if ("domestic".equals(type)) {
            final Long rootId = 7L;
            if (regionId != null) {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 3) {
                    selectedCityId = region.getId();
                    subregions = countryCategoryService.getSubregions(region.getId(), 4);
                } else if (region.getDepth() == 4) {
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    selectedCityId = parent.getId();
                    subregions = countryCategoryService.getSubregions(parent.getId(), 4);
                    selectedSubregionId = regionId;
                }
            }
        } else { // overseas
            if (regionId != null) {
                CountryCategory region = countryCategoryService.getById(regionId);
                if (region.getDepth() == 2) {
                    List<CountryCategory> childCities = countryCategoryService.getSubregions(regionId, 3);
                    if (!childCities.isEmpty()) {
                        selectedCityId = region.getId();
                        subregions = childCities;
                    }
                } else if (region.getDepth() == 3) {
                    CountryCategory parent = countryCategoryService.getById(region.getParentId());
                    selectedCityId = region.getId();
                    subregions = countryCategoryService.getSubregions(parent.getId(), 3);
                    // 정렬·페이지를 바꿔도 고른 해외 도시 pill 표시가 풀리지 않게 한다
                    selectedSubregionId = regionId;
                }
            }
        }

        // 여행지 리스트 추출 (공통)
        List<Long> regionIds;
        if (regionId != null) {
            regionIds = countryCategoryService.getAllRegionIdsUnder(regionId);
            CountryCategory selectedRegion = countryCategoryService.getById(regionId);
            selectedCityName = selectedRegion.getRegionName();
        } else {
            List<Long> rootRegionIds = "overseas".equals(type)
                    ? countryCategoryService.getOverseasRootIds()
                    : countryCategoryService.getDomesticRootIds();
            regionIds = rootRegionIds.stream()
                    .flatMap(id -> countryCategoryService.getAllRegionIdsUnder(id).stream())
                    .toList();
            selectedCityName = null;
        }
        List<Long> categoryIds = applyCategoryFilter(category,regionIds, model);
        totalCount = destinationService.countDestinationsByRegionIds(regionIds, categoryIds);
        page = DestinationPagination.page(page, totalCount, size);
        rawList = destinationService.getDestinationsByRegionIdsPaged(regionIds, categoryIds, (page - 1) * size, size, sort);

        DestinationListLocalization localization = localizeDestinationList(
                null, subregions, rawList, regionId, selectedCityName, userId);
        List<DestinationDto> destinations = localization.destinations();
        selectedCityName = localization.selectedRegionName();
        int totalPages = (int) Math.ceil((double) totalCount / size);
        addPagination(model, page, totalPages, regionId);

        // cities 필요 없음!
        model.addAttribute("destinations", destinations);
        model.addAttribute("regionDisplayNames", localization.regionDisplayNames());
        model.addAttribute("subregions", subregions);
        model.addAttribute("selectedSubregionId", selectedSubregionId);
        model.addAttribute("selectedCityId", selectedCityId);
        model.addAttribute("selectedCityName", selectedCityName);
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("sort", sort);
        model.addAttribute("type", type);

        // ★ destinationList만 리턴 (region-bar 없음)
        return "destination/fragment :: destinationList";
    }

    /**
     * 기존 category 주소는 유지한다. 실제 카테고리가 아니면 전체로 본다.
     *
     * @return 목록 조회에 쓸 카테고리 ID(0개 또는 1개). 비어 있으면 카테고리 조건 없음(전체)
     */
    private List<Long> applyCategoryFilter(String categoryValue, List<Long> regionIds, Model model) {
        Long requested = parseCategoryId(categoryValue);
        if (requested == null) {
            model.addAttribute("selectedCategoryId", null);
            return List.of();
        }
        Map<Long, Integer> counts = destinationService.getCategoryFilterCounts(regionIds, List.of(requested));
        SupportedLanguage requestedLanguage = SupportedLanguage
                .fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        Map<Long, String> names = !counts.containsKey(requested) ? Map.of()
                : referenceNameLocalizationService.localizeCategories(List.of(requested), requestedLanguage);
        Long selectedId = requested != null && counts.containsKey(requested) && names.get(requested) != null
                ? requested : null;

        model.addAttribute("selectedCategoryId", selectedId);
        return selectedId == null ? List.of() : List.of(selectedId);
    }

    /**
     * 주소의 category 값(하나). 숫자가 아니거나 0 이하면 전체로 본다.
     * 예전 다중 선택 주소(category=3&category=5)는 스프링이 "3,5" 로 이어 주므로 그중 첫 번째 올바른 값만 쓴다.
     */
    private static Long parseCategoryId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String part : value.split(",")) {
            try {
                long id = Long.parseLong(part.strip());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // 잘못된 값은 목록 요청을 실패시키지 않고 무시한다.
            }
        }
        return null;
    }

    private DestinationListLocalization localizeDestinationList(
            List<CountryCategory> cities,
            List<CountryCategory> subregions,
            List<Destination> destinations,
            Long selectedRegionId,
            String selectedRegionBaseName,
            Long userId) {
        SupportedLanguage requestedLanguage = SupportedLanguage
                .fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        Map<Long, String> baseNames = new LinkedHashMap<>();
        collectRegionBaseNames(baseNames, cities);
        collectRegionBaseNames(baseNames, subregions);
        if (destinations != null) {
            for (Destination destination : destinations) {
                if (destination != null && destination.getRegionId() != null) {
                    baseNames.putIfAbsent(destination.getRegionId(), destination.getRegionName());
                }
            }
        }
        if (selectedRegionId != null) {
            baseNames.putIfAbsent(selectedRegionId, selectedRegionBaseName);
        }
        // 국내 여행지 카드의 광역지역, 해외 여행지 카드의 국가 이름도 같은 요청 언어로 바꾼다.
        Map<Long, CountryCategory> domesticProvinces =
                topRegionsByRegionId(destinations, countryCategoryService.getDomesticRootIds());
        Map<Long, CountryCategory> overseasCountries =
                topRegionsByRegionId(destinations, countryCategoryService.getOverseasRootIds());
        domesticProvinces.values().forEach(province ->
                baseNames.putIfAbsent(province.getId(), province.getRegionName()));
        overseasCountries.values().forEach(country ->
                baseNames.putIfAbsent(country.getId(), country.getRegionName()));

        Map<Long, String> localizedRegionNames =
                referenceNameLocalizationService.localizeCountryCategoryNames(
                        baseNames, requestedLanguage);
        String selectedRegionName = selectedRegionId == null
                ? null
                : localizedDisplayName(localizedRegionNames,
                selectedRegionId, selectedRegionBaseName);
        List<DestinationDto> localizedDestinations =
                destinationService.convertToLocalizedDtoWithBookmark(
                        destinations, userId, requestedLanguage, localizedRegionNames);
        prefixTopRegions(localizedDestinations, destinations, domesticProvinces, localizedRegionNames);
        prefixTopRegions(localizedDestinations, destinations, overseasCountries, localizedRegionNames);
        // 목록 카드는 원본 대신 카드 크기 썸네일을 쓴다. (메인 추천 카드와 같은 규칙)
        // 카드 사진 칸은 4:3 이다(destination.css 의 aspect-ratio).
        // 공공누리 제3유형(변경금지)은 줄이고 잘라 만든 썸네일 대신 원본을 쓴다.
        destinationImageService.markNoDerivatives(localizedDestinations, DestinationDto::getThumbnailPath,
                DestinationDto::setImageNoDerivatives);
        cardThumbnailService.applyCardImages(localizedDestinations,
                destination -> destination.isImageNoDerivatives() ? null : destination.getThumbnailPath(),
                (destination, image) -> {
                    destination.setCardImageUrl(image.src());
                    destination.setCardImageSrcset(image.srcset());
                    destination.setCardImageCoverScale(image.coverScale(4, 3));
                });
        return new DestinationListLocalization(
                localizedDestinations, localizedRegionNames, selectedRegionName);
    }

    /**
     * 여행지 지역 → 그 지역이 속한, 주어진 루트 바로 아래 지역.
     * 국내 루트면 광역지역(예: 강서구 → 서울), 해외 대륙 루트면 국가(예: 파리 → 프랑스)다.
     * 국내/해외 여부와 계층은 country_categories 부모 관계로만 판단한다(이름으로 추측하지 않는다).
     * 지역 자신이 그 단계(광역지역·국가)이거나 다른 루트 아래 지역이면 담지 않는다.
     */
    private Map<Long, CountryCategory> topRegionsByRegionId(List<Destination> destinations,
                                                            List<Long> rootIds) {
        Map<Long, CountryCategory> topRegions = new LinkedHashMap<>();
        if (destinations == null || destinations.isEmpty() || rootIds == null || rootIds.isEmpty()) {
            return topRegions;
        }
        Set<Long> rootIdSet = new HashSet<>(rootIds);
        Set<Long> checkedRegionIds = new HashSet<>();
        for (Destination destination : destinations) {
            Long regionId = destination == null ? null : destination.getRegionId();
            if (regionId == null || !checkedRegionIds.add(regionId)) {
                continue;
            }
            CountryCategory topRegion = topRegionOf(regionId, rootIdSet);
            if (topRegion != null) {
                topRegions.put(regionId, topRegion);
            }
        }
        return topRegions;
    }

    private CountryCategory topRegionOf(Long regionId, Set<Long> rootIds) {
        Set<Long> visitedIds = new HashSet<>();
        CountryCategory current = countryCategoryService.getById(regionId);
        while (current != null && current.getParentId() != null && visitedIds.add(current.getId())) {
            if (rootIds.contains(current.getParentId())) {
                return regionId.equals(current.getId()) ? null : current;
            }
            current = countryCategoryService.getById(current.getParentId());
        }
        return null;
    }

    /**
     * 여행지 카드 지역을 "상위 지역 + 최하위 지역"으로 바꾼다.
     * 국내는 광역지역(예: 서울 강서구), 해외는 국가(예: 프랑스 파리)를 붙인다.
     * 번역된 두 이름이 같으면(예: 싱가포르) 한 번만 보인다.
     */
    private void prefixTopRegions(List<DestinationDto> localizedDestinations,
                                  List<Destination> destinations,
                                  Map<Long, CountryCategory> topRegions,
                                  Map<Long, String> localizedRegionNames) {
        if (localizedDestinations == null || destinations == null || topRegions.isEmpty()) {
            return;
        }
        Map<Long, Long> regionIdByDestinationId = new HashMap<>();
        for (Destination destination : destinations) {
            if (destination != null && destination.getId() != null && destination.getRegionId() != null) {
                regionIdByDestinationId.put(destination.getId(), destination.getRegionId());
            }
        }
        for (DestinationDto dto : localizedDestinations) {
            CountryCategory topRegion = topRegions.get(regionIdByDestinationId.get(dto.getId()));
            if (topRegion == null || dto.getRegionName() == null || dto.getRegionName().isBlank()) {
                continue;
            }
            String topRegionName = localizedDisplayName(
                    localizedRegionNames, topRegion.getId(), topRegion.getRegionName());
            if (topRegionName == null || topRegionName.isBlank()
                    || topRegionName.strip().equals(dto.getRegionName().strip())) {
                continue;
            }
            dto.setRegionName(topRegionName + " " + dto.getRegionName());
        }
    }

    private void collectRegionBaseNames(Map<Long, String> baseNames,
                                        List<CountryCategory> regions) {
        if (regions == null) {
            return;
        }
        for (CountryCategory region : regions) {
            if (region != null && region.getId() != null) {
                baseNames.putIfAbsent(region.getId(), region.getRegionName());
            }
        }
    }

    private static void addPagination(Model model, int page, int totalPages, Long regionId) {
        model.addAttribute("listRegionId", regionId);
        model.addAttribute("paginationWindows", List.of(
                DestinationPagination.numbers(page, totalPages, 7),
                DestinationPagination.numbers(page, totalPages, 3)));
    }

    private record DestinationListLocalization(
            List<DestinationDto> destinations,
            Map<Long, String> regionDisplayNames,
            String selectedRegionName) {
    }
}
