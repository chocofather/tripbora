package com.tripbora.service.search;

import com.tripbora.dto.DestinationSearchResultDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.course.CourseLocationLabels;
import com.tripbora.repository.search.DestinationSearchMapper;
import com.tripbora.service.destination.DestinationImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DestinationSearchServiceImpl implements DestinationSearchService {

    private final DestinationSearchMapper destinationSearchMapper;
    private final DestinationImageService destinationImageService;
    private final CountryCategoryService countryCategoryService;
    private final ReferenceNameLocalizationService referenceNameLocalizationService;

    @Override
    public List<DestinationSearchResultDto> search(String keyword, int limit, Long countryId) {
        return localize(destinationSearchMapper.searchDestinations(keyword, Math.max(1, Math.min(limit, 20)), countryId));
    }

    @Override
    public List<DestinationSearchResultDto> search(String keyword, int limit, Long countryId, String scope, Long regionId) {
        if ((scope != null && !scope.isEmpty() && !List.of("domestic", "overseas").contains(scope))
                || (countryId != null && countryId <= 0) || (regionId != null && regionId <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 지역 조건을 확인해 주세요.");
        }
        String query = keyword == null ? "" : keyword.strip();
        boolean filtered = (scope != null && !scope.isEmpty()) || countryId != null || regionId != null;
        if (query.isEmpty() && !filtered) return List.of();
        return localize(destinationSearchMapper.searchFilteredDestinations(
                query, Math.max(1, Math.min(limit, 20)), countryId, scope, regionId));
    }

    private List<DestinationSearchResultDto> localize(List<DestinationSearchResultDto> results) {
        List<CourseStopDto> locations = results.stream().map(result -> {
            CourseStopDto stop = new CourseStopDto();
            stop.setDestinationId(result.getId());
            stop.setRegionId(result.getRegionId());
            stop.setRegionName(result.getRegionName());
            stop.setCountryId(result.getCountryId());
            stop.setCountryName(result.getCountryName());
            return stop;
        }).toList();
        CourseLocationLabels.localize(locations,
                SupportedLanguage.normalize(LocaleContextHolder.getLocale()).orElse(SupportedLanguage.KOREAN),
                countryCategoryService, referenceNameLocalizationService);
        for (int i = 0; i < results.size(); i++) results.get(i).setLocationLabel(locations.get(i).getLocationLabel());
        // 대표 이미지가 공공누리 제3유형(변경금지)이면 잘리지 않게 그린다.
        destinationImageService.markNoDerivatives(results, DestinationSearchResultDto::getThumbnailUrl,
                DestinationSearchResultDto::setImageNoDerivatives);
        return results;
    }
}
