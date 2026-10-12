package com.tripbora.controller.recommend;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.file.DestinationCardThumbnailService;
import com.tripbora.service.recommend.DestinationRecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/season-destinations")
@RequiredArgsConstructor
public class DestinationRecommendController {

    private final DestinationRecommendService recommendService;
    private final DestinationCardThumbnailService cardThumbnailService;
    private final DestinationImageService destinationImageService;

    // 1) 계절 탭별 여행지 추천. 탭(테마 카테고리 이름)이 있으면 카테고리만으로 고른다.
    @GetMapping
    public List<SeasonDestinationDto> getSeasonDestinations(
            @RequestParam String season,
            @RequestParam(required = false) String categoryName,
            @RequestParam(defaultValue = "5") int limit,
            Locale locale
    ) {
        SupportedLanguage requestedLanguage = SupportedLanguage.fromLocale(locale)
                .orElse(SupportedLanguage.KOREAN);
        List<SeasonDestinationDto> destinations = categoryName != null && !categoryName.isBlank()
                ? recommendService.findByCurationCategoryName(categoryName.trim(), limit, requestedLanguage)
                : recommendService.findBySeason(season, limit, requestedLanguage);
        // 카드는 원본 대신 카드 크기 썸네일을 쓴다. (공개 여행지 목록 카드와 같은 규칙)
        // 공공누리 제3유형(변경금지)은 줄이고 잘라 만든 썸네일 대신 원본을 쓴다.
        destinationImageService.markNoDerivatives(destinations, SeasonDestinationDto::getImageUrl,
                SeasonDestinationDto::setImageNoDerivatives);
        return cardThumbnailService.applyCardImages(destinations,
                destination -> destination.isImageNoDerivatives() ? null : destination.getImageUrl(),
                (destination, image) -> {
                    destination.setCardImageUrl(image.src());
                    destination.setCardImageSrcset(image.srcset());
                });
    }
}
