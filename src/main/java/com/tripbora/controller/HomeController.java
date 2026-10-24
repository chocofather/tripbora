package com.tripbora.controller;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.EventSlideDto;
import com.tripbora.dto.RecommendDestinationDto;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.model.Event;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.seo.SeoStructuredData;
import com.tripbora.seo.SeoTextUtils;
import com.tripbora.service.event.EventLocalizationService;
import com.tripbora.service.event.EventService;
import com.tripbora.service.travelinfo.FestivalDetailService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationViewClock;
import com.tripbora.service.file.DestinationCardThumbnailService;
import com.tripbora.service.recommend.DestinationRecommendService;
import com.tripbora.service.recommend.PopularRecommendService;
import com.tripbora.service.travelinfo.TravelInfoService;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class HomeController {

    // 메인 인기 여행지: 대표 1곳 + 보조 4곳
    private static final int HOME_POPULAR_LIMIT = 5;
    // 메인 Hero: 관리자가 메인 추천으로 고른 여행정보 최대 5개
    private static final int HOME_HERO_LIMIT = 5;
    // 메인 이벤트 프로모션 배너: 메인 노출로 고른 진행 중·예정 이벤트 최대 5개
    private static final int HOME_PROMOTION_LIMIT = 5;

    private final FestivalDetailService festivalDetailService;
    private final DestinationRecommendService recommendService;
    private final PopularRecommendService popularRecommendService;
    private final DestinationCardThumbnailService cardThumbnailService;
    private final DestinationImageService destinationImageService;
    private final DestinationViewClock viewClock;
    private final TravelInfoService travelInfoService;
    private final EventService eventService;
    private final EventLocalizationService eventLocalizationService;

    public HomeController(FestivalDetailService festivalDetailService,
                          DestinationRecommendService recommendService,
                          PopularRecommendService popularRecommendService,
                          DestinationCardThumbnailService cardThumbnailService,
                          DestinationImageService destinationImageService,
                          DestinationViewClock viewClock,
                          TravelInfoService travelInfoService,
                          EventService eventService,
                          EventLocalizationService eventLocalizationService) {
     this.festivalDetailService = festivalDetailService;
     this.recommendService = recommendService;
     this.popularRecommendService = popularRecommendService;
     this.cardThumbnailService = cardThumbnailService;
     this.destinationImageService = destinationImageService;
     this.viewClock = viewClock;
     this.travelInfoService = travelInfoService;
     this.eventService = eventService;
     this.eventLocalizationService = eventLocalizationService;
    }

    @GetMapping("/")
    public String home(Model model, Authentication auth) {
        /*
          예전에는 여기서 회원 한 줄을 통째로 읽어 "user" 로 넘겼는데 home.html 이 쓰지 않는다.
          로그인 여부와 헤더 프로필 사진은 GlobalModelAttributes 가 이미 모든 화면에 넣어 준다.
        */
        SupportedLanguage language = SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        model.addAttribute("isLoggedIn", authenticatedUser(auth) != null);
        model.addAttribute("homeHeroItems", travelInfoService.getHomeHeroItems(HOME_HERO_LIMIT, language));
        model.addAttribute("homePromotionEvents", homePromotionEvents(language));
        model.addAttribute("homeFestivals", festivalDetailService.getHomeFestivals(viewClock.today(), language));
        model.addAttribute("homeLandmarks", homeLandmarks(language));
        /*
          인기 여행지 편집 영역의 데이터는 둘 중 하나만 쓴다. (섞어서 5곳을 채우지 않는다)
            A. 최근 7일(KST) 조회 기록이 있는 여행지가 5곳 → '지금 뜨는 여행지'
            B. 그보다 적으면 → 기존 '인기 여행지'
        */
        List<SeasonDestinationDto> trending = trendingDestinations(language);
        boolean popularTrending = trending.size() == HOME_POPULAR_LIMIT;
        model.addAttribute("popularTrending", popularTrending);
        model.addAttribute("popularDestinations",
                popularTrending ? trending : popularDestinations(language));
        SeoStructuredData.website(model);

        return "home";
    }

    @GetMapping("/about")
    public String about() {
        return "about";
    }

    /*
      메인 중간 이벤트 프로모션 배너. 메인 노출로 고른 진행 중·예정 이벤트를 오늘(KST) 기준으로 고른다.
      제목·설명은 이벤트 공개 화면과 같은 언어 대체를 거친다. 설명은 HTML 일 수 있으므로
      글자만 짧게 뽑는다(배너는 CSS 로 두 줄까지만 보여 준다).
    */
    private List<EventSlideDto> homePromotionEvents(SupportedLanguage language) {
        List<Event> events = eventService.getHomePromotionEvents(viewClock.today(), HOME_PROMOTION_LIMIT);
        return eventLocalizationService.localizeAll(events, language).stream()
                .map(event -> new EventSlideDto(event.getId(), event.getTitle(),
                        SeoTextUtils.summary(event.getDescription()), event.getEventImg()))
                .toList();
    }

    /*
      메인 랜드마크 아치 카드. 계절 추천 카드와 같이 원본 대신 카드 크기 썸네일을 쓴다.
      아치 사진 칸은 3:4 이다(home-converge-prototype.css 의 aspect-ratio).
    */
    private List<SeasonDestinationDto> homeLandmarks(SupportedLanguage language) {
        List<SeasonDestinationDto> landmarks = recommendService.findHomeLandmarks(language);
        destinationImageService.markNoDerivatives(landmarks, SeasonDestinationDto::getImageUrl,
                SeasonDestinationDto::setImageNoDerivatives);
        cardThumbnailService.applyCardImages(landmarks, HomeController::cardSourceUrl,
                (destination, image) -> {
                    destination.setCardImageUrl(image.src());
                    destination.setCardImageSrcset(image.srcset());
                    destination.setCardImageCoverScale(image.coverScale(3, 4));
                    destination.setSourceImageWidth(image.width());
                    destination.setSourceImageHeight(image.height());
                });
        return landmarks == null ? List.of() : landmarks;
    }

    /*
      메인 '지금 뜨는 여행지': 오늘(KST) 포함 최근 7일 조회 합계 상위 5곳 (국내 + 해외).
      지역은 랜드마크 카드처럼 상위 지역 + 지역으로 보여 주고, 카드 크기 썸네일을 쓴다.
    */
    private List<SeasonDestinationDto> trendingDestinations(SupportedLanguage language) {
        List<SeasonDestinationDto> destinations = recommendService.findTrendingDestinations(
                viewClock.today(), HOME_POPULAR_LIMIT, language);
        if (destinations == null || destinations.size() != HOME_POPULAR_LIMIT) {
            return destinations == null ? List.of() : destinations;
        }
        destinationImageService.markNoDerivatives(destinations, SeasonDestinationDto::getImageUrl,
                SeasonDestinationDto::setImageNoDerivatives);
        cardThumbnailService.applyCardImages(destinations, HomeController::cardSourceUrl,
                (destination, image) -> {
                    destination.setCardImageUrl(image.src());
                    destination.setCardImageSrcset(image.srcset());
                });
        return destinations;
    }

    /*
      메인 인기 여행지 큐레이션(fallback). 기존 국내 인기 여행지 조회(/api/popular-destinations/domestic 과 같은 기준)를
      그대로 쓰고, 공개 목록 카드와 같이 원본 대신 카드 크기 썸네일을 쓴다.
      편집 영역은 지금 뜨는 여행지와 같은 카드 모양(SeasonDestinationDto)으로 그린다. 이 조회에는 상위 지역이 없다.
    */
    private List<SeasonDestinationDto> popularDestinations(SupportedLanguage language) {
        List<RecommendDestinationDto> destinations =
                popularRecommendService.findDomesticPopular(HOME_POPULAR_LIMIT, language);
        destinationImageService.markNoDerivatives(destinations, RecommendDestinationDto::getImageUrl,
                RecommendDestinationDto::setImageNoDerivatives);
        cardThumbnailService.applyCardImages(destinations,
                destination -> destination.isImageNoDerivatives() ? null : destination.getImageUrl(),
                (destination, image) -> {
                    destination.setCardImageUrl(image.src());
                    destination.setCardImageSrcset(image.srcset());
                });
        return destinations == null ? List.of()
                : destinations.stream().map(HomeController::editorialCard).toList();
    }

    private static SeasonDestinationDto editorialCard(RecommendDestinationDto popular) {
        SeasonDestinationDto card = new SeasonDestinationDto();
        card.setId(popular.getId());
        card.setName(popular.getName());
        card.setImageUrl(popular.getImageUrl());
        card.setRegionId(popular.getRegionId());
        card.setRegionName(popular.getRegionName());
        card.setCardImageUrl(popular.getCardImageUrl());
        card.setCardImageSrcset(popular.getCardImageSrcset());
        card.setImageNoDerivatives(popular.isImageNoDerivatives());
        return card;
    }

    /**
     * 카드 썸네일을 만들 원본. 공공누리 제3유형(변경금지)은 줄이고 잘라 만든 썸네일 대신 원본을 그대로 쓴다.
     */
    private static String cardSourceUrl(SeasonDestinationDto destination) {
        return destination.isImageNoDerivatives() ? null : destination.getImageUrl();
    }

    private CustomUserDetails authenticatedUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }
        return userDetails;
    }

}
