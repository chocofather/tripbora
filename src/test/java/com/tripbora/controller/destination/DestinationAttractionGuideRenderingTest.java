package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.model.AttractionInfo;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.model.DestinationImage;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationAttractionGuideRenderingTest {
    @Test void initialPageHasContinuationAndKeepsRepresentativeSeoUntouched() {
        var admins=new java.util.ArrayList<DestinationImage>();
        for(long i=1;i<=20;i++){var image=new DestinationImage();image.setId(i);image.setImageUrl("/admin-"+i+".jpg");admins.add(image);}
        admins.get(0).setIsMain(true);
        var reviews=new java.util.ArrayList<com.tripbora.dto.ReviewGalleryPhoto>();
        for(long i=1;i<=35;i++){var image=new com.tripbora.dto.ReviewGalleryPhoto();image.setId(i);image.setImageUrl("/uploads/comments/"+i+".jpg");image.setDestinationId(15L);image.setCommentId(i);reviews.add(image);}
        reviewPhotos=reviews;
        nextCursor="next-page";
        Model model=render("guide",admins);
        @SuppressWarnings("unchecked") var gallery=(List<com.tripbora.dto.DestinationGalleryImage>)model.getAttribute("galleryImages");
        assertThat(gallery).hasSize(30);
        assertThat(gallery.subList(0,20)).extracting(com.tripbora.dto.DestinationGalleryImage::getGalleryKey)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,20).mapToObj(id->"admin:"+id).toList());
        assertThat(gallery.subList(20,30)).extracting(com.tripbora.dto.DestinationGalleryImage::getGalleryKey)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,10).mapToObj(id->"review:"+id).toList());
        assertThat(model.getAttribute("seoImage")).isEqualTo("/admin-1.jpg");
        assertThat(model.getAttribute("images")).isEqualTo(admins);
        assertThat(model.getAttribute("galleryNextCursor")).isEqualTo("next-page");
        assertThat(model.getAttribute("galleryEndpoint")).isEqualTo("/destinations/15/gallery");
        org.mockito.Mockito.verify(destinationCommentService,org.mockito.Mockito.never()).getApprovedGalleryPhotos(any());
    }

    @Mock private DestinationService destinationService;
    @Mock private DestinationImageService destinationImageService;
    @Mock private CountryCategoryService countryCategoryService;
    @Mock private DestinationCommentService destinationCommentService;
    @Mock private ReferenceNameLocalizationService referenceNameLocalizationService;

    private DestinationController controller;
    private com.tripbora.service.destination.DestinationGalleryService galleryService;
    private List<com.tripbora.dto.ReviewGalleryPhoto> reviewPhotos=List.of();
    private String nextCursor;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.ENGLISH.getLocale());
        galleryService=org.mockito.Mockito.mock(com.tripbora.service.destination.DestinationGalleryService.class);
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService, referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)),galleryService);
        CountryCategory region = new CountryCategory();
        region.setId(101L);
        region.setRegionName("종로구");
        region.setCode("KR-11-110");
        when(countryCategoryService.getById(101L)).thenReturn(region);
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(101L));
        when(destinationService.getSimilarDestinations(15L, 4)).thenReturn(List.of());
        when(destinationService.convertToDtoWithBookmark(List.of(), null)).thenReturn(List.of());
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void localizedGuideEscapesHtmlBeforeAddingLineBreakMarkup() {
        Model model = render("<script>alert('x')</script>\nSecond line");

        assertThat(model.getAttribute("attractionGuideWithBr"))
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;<br>Second line");
    }

    @Test
    void localizedGuideNormalizesCrLfAndPreservesBlankLines() {
        Model model = render("First\r\nSecond\r\n\r\nThird\nFourth");

        assertThat(model.getAttribute("attractionGuideWithBr"))
                .isEqualTo("First<br>Second<br><br>Third<br>Fourth");
    }

    @Test
    void imageAttributionAreaIsEnabledOnlyWhenAtLeastOneImageHasMetadata() {
        assertThat(render("guide").getAttribute("hasImageAttribution")).isEqualTo(false);

        DestinationImage withoutSource = new DestinationImage();
        DestinationImage withSource = new DestinationImage();
        withSource.setSourceName("한국관광공사");

        assertThat(render("guide", List.of(withoutSource, withSource))
                .getAttribute("hasImageAttribution")).isEqualTo(true);
    }

    @Test void combinedGalleryKeepsAdminOrderIdsAttributionAndSeoWhileReviewIdsAreNamespaced() {
        var review=new com.tripbora.dto.ReviewGalleryPhoto();review.setId(7L);review.setCommentId(20L);
        review.setDestinationId(15L);review.setImageUrl("/uploads/comments/review.jpg");
        reviewPhotos=List.of(review);
        var first=new DestinationImage();first.setId(7L);first.setImageUrl("/admin-first.jpg");first.setSourceName("Official");first.setIsMain(true);
        var second=new DestinationImage();second.setId(3L);second.setImageUrl("/admin-second.jpg");
        Model model=render("guide",List.of(first,second));
        @SuppressWarnings("unchecked") var gallery=(List<com.tripbora.dto.DestinationGalleryImage>)model.getAttribute("galleryImages");
        assertThat(gallery).extracting(com.tripbora.dto.DestinationGalleryImage::getGalleryKey).containsExactly("admin:7","admin:3","review:7");
        assertThat(gallery.get(0).getSourceName()).isEqualTo("Official");
        assertThat(gallery.get(2).getReviewImageId()).isEqualTo(7L);
        assertThat(gallery.get(2).getReviewUrl()).isEqualTo("/destinations/15?commentId=20");
        assertThat(gallery.get(2).getId()).isNull();
        assertThat(model.getAttribute("images")).isEqualTo(List.of(first,second));
        assertThat(model.getAttribute("seoImage")).isEqualTo("/admin-first.jpg");
        assertThat(first.getIsMain()).isTrue();
    }

    @Test void reviewOnlyGalleryDoesNotBecomeTheSeoOrRepresentativeImage() {
        var review=new com.tripbora.dto.ReviewGalleryPhoto();review.setId(7L);review.setCommentId(20L);
        review.setDestinationId(15L);review.setImageUrl("/uploads/comments/review.jpg");
        reviewPhotos=List.of(review);
        Model model=render("guide",List.of());
        assertThat((List<?>)model.getAttribute("galleryImages")).hasSize(1);
        assertThat(model.getAttribute("seoImage")).isNull();
        assertThat(model.getAttribute("hasImageAttribution")).isEqualTo(true);
    }

    @Test void detailReloadReadsApprovalAndRemovalAgainInsteadOfCachingTheSlider() {
        var review=new com.tripbora.dto.ReviewGalleryPhoto();review.setId(7L);review.setCommentId(20L);
        review.setDestinationId(15L);review.setImageUrl("/uploads/comments/review.jpg");
        assertThat((List<?>)render("guide").getAttribute("galleryImages")).isEmpty();
        reviewPhotos=List.of(review);
        @SuppressWarnings("unchecked") var approved=(List<com.tripbora.dto.DestinationGalleryImage>)
                render("guide").getAttribute("galleryImages");
        assertThat(approved).extracting(com.tripbora.dto.DestinationGalleryImage::getGalleryKey)
                .containsExactly("review:7");
        reviewPhotos=List.of();
        assertThat((List<?>)render("guide").getAttribute("galleryImages")).isEmpty();
    }

    private Model render(String guide) {
        return render(guide, List.of());
    }

    private Model render(String guide, List<DestinationImage> images) {
        Destination destination = new Destination();
        destination.setId(15L);
        destination.setRegionId(101L);
        destination.setDescription("description");

        AttractionInfo attractionInfo = new AttractionInfo();
        attractionInfo.setDestinationId(15L);
        attractionInfo.setGuide(guide);

        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);
        dto.setAttractionInfo(attractionInfo);
        dto.setAttractionAmenities(List.of());
        dto.setImages(images);
        dto.setCategoryIds(List.of());

        when(destinationService.getPublicDestinationDetailWithInfo(
                eq(15L), any(SupportedLanguage.class))).thenReturn(dto);
        var initial=new java.util.ArrayList<com.tripbora.dto.DestinationGalleryImage>();
        images.stream().limit(30).map(com.tripbora.dto.DestinationGalleryImage::administrator).forEach(initial::add);
        reviewPhotos.stream().limit(30-initial.size()).map(com.tripbora.dto.DestinationGalleryImage::traveler).forEach(initial::add);
        when(galleryService.page(15L,"ALL",null)).thenReturn(new com.tripbora.dto.DestinationGalleryPage(initial,nextCursor,null));

        Model model = new ExtendedModelMap();
        controller.destinationDetail(15L, null, new org.springframework.mock.web.MockHttpSession(), model);
        return model;
    }
}
