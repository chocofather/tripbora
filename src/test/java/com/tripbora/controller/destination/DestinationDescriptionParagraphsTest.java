package com.tripbora.controller.destination;

import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationDescriptionParagraphsTest {

    @Mock
    private DestinationService destinationService;
    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private CountryCategoryService countryCategoryService;
    @Mock
    private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock
    private DestinationCommentService destinationCommentService;

    private DestinationController controller;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.KOREAN.getLocale());
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService, referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)), DestinationGalleryTestSupport.empty());

        CountryCategory region = new CountryCategory();
        region.setId(10L);
        region.setRegionName("서울");
        region.setDepth(3);
        when(countryCategoryService.getById(10L)).thenReturn(region);
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(10L));
        when(destinationService.getSimilarDestinations(7L, 4)).thenReturn(List.of());
        when(destinationService.convertToDtoWithBookmark(List.of(), null)).thenReturn(List.of());
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void joinsSingleNewlineIntoOneParagraphForExistingDescriptions() {
        assertThat(renderDescription("문장1\n문장2")).containsExactly("문장1 문장2");
    }

    @Test
    void separatesParagraphsOnlyAtBlankLines() {
        assertThat(renderDescription("문장1\n\n문장2")).containsExactly("문장1", "문장2");
    }

    @Test
    void handlesCrLfAndCollapsesMultipleBlankLinesIntoOneBoundary() {
        assertThat(renderDescription("문장1\r\n문장2\r\n\r\n\r\n문장3"))
                .containsExactly("문장1 문장2", "문장3");
    }

    @Test
    void englishCrLfDescriptionUsesTheSameParagraphFormatter() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en"));

        assertThat(renderDescription(
                "Sentence one.\r\nSentence two.\r\n\r\nSecond paragraph."))
                .containsExactly("Sentence one. Sentence two.", "Second paragraph.");
        verify(destinationService).getPublicDestinationDetailWithInfo(7L, SupportedLanguage.ENGLISH);
    }

    @Test
    void usesFallbackForNullBlankAndNewlineOnlyDescriptions() {
        assertThat(renderDescription(null)).containsExactly("-");
        assertThat(renderDescription("   ")).containsExactly("-");
        assertThat(renderDescription("\r\n\n")).containsExactly("-");
    }

    @Test
    void keepsHtmlLikeInputAsPlainTextForThymeleafToEscape() {
        assertThat(renderDescription("<script>alert('x')</script>\n안전한 설명"))
                .containsExactly("<script>alert('x')</script> 안전한 설명");
    }

    @SuppressWarnings("unchecked")
    private List<String> renderDescription(String description) {
        Destination destination = new Destination();
        destination.setId(7L);
        destination.setName("여행지");
        destination.setRegionId(10L);
        destination.setDescription(description);

        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);
        SupportedLanguage language = SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        when(destinationService.getPublicDestinationDetailWithInfo(7L, language)).thenReturn(dto);

        Model model = new ExtendedModelMap();
        controller.destinationDetail(7L, null, new org.springframework.mock.web.MockHttpSession(), model);
        return (List<String>) model.getAttribute("descriptionParagraphs");
    }
}
