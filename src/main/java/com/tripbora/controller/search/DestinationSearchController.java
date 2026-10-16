package com.tripbora.controller.search;

import com.tripbora.dto.DestinationSearchResultDto;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.CountryCategory;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.search.DestinationSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class DestinationSearchController {
    private final DestinationSearchService destinationSearchService;
    private final CountryCategoryService countryCategoryService;
    private final ReferenceNameLocalizationService referenceNameLocalizationService;

    @GetMapping
    public List<DestinationSearchResultDto> searchDestinations(
            @RequestParam("q") String keyword,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long countryId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long regionId) {
        // size는 검색결과 개수 제한 (기본 20)
        return destinationSearchService.search(keyword, size, countryId, scope, regionId);
    }

    @GetMapping("/regions")
    public List<CountryCategory> searchRegions(@RequestParam Long countryId) {
        if (countryCategoryService.getCourseCountries().stream().noneMatch(country -> countryId.equals(country.getId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var regions = countryCategoryService.getRegionsByParentId(countryId).stream()
                .filter(region -> Integer.valueOf(1).equals(region.getIsVisible())).toList();
        var language = SupportedLanguage.normalize(LocaleContextHolder.getLocale()).orElse(SupportedLanguage.KOREAN);
        var names = referenceNameLocalizationService.localizeCountryCategories(regions, language);
        return regions.stream().map(region -> {
            var option = new CountryCategory();
            option.setId(region.getId()); option.setRegionName(names.getOrDefault(region.getId(), region.getRegionName()));
            return option;
        }).toList();
    }
}
