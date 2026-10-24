package com.tripbora.service.travelinfo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.dto.kto.KtoFestivalAdditionalImage;
import com.tripbora.model.FestivalInfo;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.InfoImage;
import com.tripbora.model.InfoPeriod;
import com.tripbora.model.TravelInfo;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import com.tripbora.service.kto.KtoDownloadedFestivalImage;
import com.tripbora.service.kto.KtoFestivalImageDownloadService;
import com.tripbora.service.kto.KtoFestivalService;
import com.tripbora.service.kto.KtoPhotoDownloadException;
import com.tripbora.dto.kto.KtoFestivalImageDetail;
import com.tripbora.service.post.PostContentSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FestivalRegistrationServiceTest {
    @Test
    void previewValidationRejectsOverlongForeignTitleAndFestivalAddressBeforeAnyImageWork() {
        FestivalCreateForm form = validForm();
        allowFestivalCategory();
        var title = form.getTranslations().stream().filter(slot -> "en".equals(slot.getLanguageCode())).findFirst().orElseThrow();
        title.setTitle("x".repeat(256));
        assertThatThrownBy(() -> service.validateForCreate(form, 7L)).isInstanceOf(FestivalValidationException.class);
        title.setTitle("English festival");
        form.getFestivalInfoTranslations().stream().filter(slot -> "en".equals(slot.getLanguageCode()))
                .findFirst().orElseThrow().setAddress("x".repeat(501));
        assertThatThrownBy(() -> service.validateForCreate(form, 7L)).isInstanceOf(FestivalValidationException.class);
        verifyNoInteractions(ktoFestivalService, festivalImageDownloadService, travelInfoMapper);
    }

    @Mock
    private TravelInfoMapper travelInfoMapper;
    @Mock
    private FestivalInfoMapper festivalInfoMapper;
    @Mock
    private InfoCategoryMapper infoCategoryMapper;
    @Mock
    private KtoFestivalService ktoFestivalService;
    @Mock
    private KtoFestivalImageDownloadService festivalImageDownloadService;
    @Mock
    private TravelInfoService travelInfoService;
    @Mock
    private FestivalInfoService festivalInfoService;

    private FestivalRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new FestivalRegistrationService(
                travelInfoMapper, festivalInfoMapper, infoCategoryMapper, new PostContentSanitizer(),
                ktoFestivalService, festivalImageDownloadService, travelInfoService,
                festivalInfoService);
    }

    @Test
    void preflightChecksLengthsWithoutImageOrPersistenceSideEffects() {
        allowFestivalCategory();
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("12345");
        service.validateForCreate(form, 7L);
        verify(festivalInfoMapper).countOccurrence("KTO_TOURAPI", "12345", 2026);
        form.setAddress("a".repeat(501));
        assertThatThrownBy(() -> service.validateForCreate(form, 7L))
                .isInstanceOf(FestivalValidationException.class);
        verifyNoInteractions(ktoFestivalService, festivalImageDownloadService, travelInfoMapper);
        verify(festivalInfoMapper, never()).insert(any());
    }

    @Test
    void strictRegistrationRequiresContentIdAndThumbnailSelectionBeforeImageLookup() {
        allowFestivalCategory();
        FestivalCreateForm form = validForm();
        assertThatThrownBy(() -> service.createWithRequiredThumbnail(form, 7L))
                .isInstanceOf(FestivalValidationException.class);
        form.setKtoFestivalContentId("12345");
        assertThatThrownBy(() -> service.createWithRequiredThumbnail(form, 7L))
                .isInstanceOf(FestivalValidationException.class);
        verifyNoInteractions(ktoFestivalService, festivalImageDownloadService, travelInfoMapper);
    }

    @Test
    void strictSelectedPosterDownloadFailureRollsBackAndDeletesOtherDownloadedImagesBeforeInsert() {
        allowFestivalCategory();
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("12345");
        form.setKtoThumbnailImageSelection("DETAIL:poster");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg";
        String posterUrl = "https://tong.visitkorea.or.kr/cms/resource/35/poster.jpg";
        KtoDownloadedFestivalImage main = downloaded("main.jpg", mainUrl);
        when(ktoFestivalService.getImageDetail("12345"))
                .thenReturn(imageDetail("12345", mainUrl, "Type1"));
        when(ktoFestivalService.getAdditionalImages("12345")).thenReturn(List.of(
                additionalImage("12345", posterUrl, "포스터", "poster", "Type1")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(main);
        when(festivalImageDownloadService.download(posterUrl)).thenThrow(new KtoPhotoDownloadException("실패"));
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        assertThatThrownBy(() -> transactionalProxy(service, transactionManager)
                .createWithRequiredThumbnail(form, 7L)).isInstanceOf(FestivalValidationException.class);

        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
        verify(festivalImageDownloadService).deleteDownloadedFestivalImage(main);
        verifyNoInteractions(travelInfoMapper);
        verify(festivalInfoMapper, never()).insert(any());
    }

    @Test
    void manualRegistrationStoresThreeTablesWithFestivalAndAdminSourceFixedByServer() {
        allowFestivalCategory();
        generateTravelInfoId(41L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        FestivalCreateForm form = validForm();

        assertThat(service.create(form, 7L).festivalId()).isEqualTo(41L);

        ArgumentCaptor<TravelInfo> travelInfoCaptor = ArgumentCaptor.forClass(TravelInfo.class);
        ArgumentCaptor<InfoPeriod> periodCaptor = ArgumentCaptor.forClass(InfoPeriod.class);
        ArgumentCaptor<FestivalInfo> festivalInfoCaptor = ArgumentCaptor.forClass(FestivalInfo.class);
        InOrder order = inOrder(travelInfoMapper, festivalInfoMapper);
        order.verify(travelInfoMapper).insertTravelInfo(travelInfoCaptor.capture());
        order.verify(travelInfoMapper).insertPeriod(periodCaptor.capture());
        order.verify(festivalInfoMapper).insert(festivalInfoCaptor.capture());

        assertThat(travelInfoCaptor.getValue()).satisfies(info -> {
            assertThat(info.getTitle()).isEqualTo("서울 빛 축제");
            assertThat(info.getContent()).isEqualTo("<p>행사 소개</p>");
            assertThat(info.getScope()).isEqualTo(TravelInfoScope.DOMESTIC);
            assertThat(info.getContentType()).isEqualTo(TravelInfoContentType.FESTIVAL);
            assertThat(info.getCategoryId()).isEqualTo(5L);
            assertThat(info.getViews()).isZero();
            assertThat(info.getUserId()).isEqualTo(7L);
        });
        assertThat(periodCaptor.getValue()).satisfies(period -> {
            assertThat(period.getInfoId()).isEqualTo(41L);
            assertThat(period.getStartDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(period.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        });
        assertThat(festivalInfoCaptor.getValue()).satisfies(info -> {
            assertThat(info.getInfoId()).isEqualTo(41L);
            assertThat(info.getEventPlace()).isEqualTo("광화문광장");
            assertThat(info.getSourceType()).isEqualTo("ADMIN");
            assertThat(info.getExternalContentId()).isNull();
        });
    }

    @Test
    void tourApiRegistrationUsesKtoSourceAndBlocksDuplicateExternalContentId() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId(" 12345 ");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "12345", 2026))
                .thenReturn(1);

        assertThatThrownBy(() -> service.create(form, 7L))
                .isInstanceOf(FestivalValidationException.class)
                .hasMessage("이미 등록된 해당 연도의 TourAPI 축제·행사입니다.");

        verify(festivalInfoMapper).countOccurrence("KTO_TOURAPI", "12345", 2026);
        verify(travelInfoMapper, never()).insertTravelInfo(any());
        verify(festivalInfoMapper, never()).insert(any());
    }

    @Test
    void theSameTourApiFestivalCanBeRegisteredAgainForAnotherYear() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId(" 506534 ");
        // 2027 개최분. 2026 개최분이 남아 있어도 다른 개최분이라 등록된다.
        form.setStartDate(LocalDate.of(2027, 7, 24));
        form.setEndDate(LocalDate.of(2027, 8, 9));
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "506534", 2027)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("506534"))
                .thenReturn(imageDetail("506534", null, "Type1"));
        generateTravelInfoId(43L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);

        assertThat(service.create(form, 7L).festivalId()).isEqualTo(43L);

        ArgumentCaptor<FestivalInfo> captor = ArgumentCaptor.forClass(FestivalInfo.class);
        verify(festivalInfoMapper).insert(captor.capture());
        assertThat(captor.getValue().getExternalContentId()).isEqualTo("506534");
        assertThat(captor.getValue().getEventYear()).isEqualTo(2027);
        // 2026 개최분을 찾아보지 않는다. 연도가 다르면 애초에 다른 개최분이다.
        verify(festivalInfoMapper, never()).countOccurrence("KTO_TOURAPI", "506534", 2026);
    }

    @Test
    void everyRegistrationStoresTheEventYearTakenFromTheStartDate() {
        FestivalCreateForm form = validForm();
        allowFestivalCategory();
        generateTravelInfoId(44L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);

        service.create(form, 7L);

        ArgumentCaptor<FestivalInfo> captor = ArgumentCaptor.forClass(FestivalInfo.class);
        verify(festivalInfoMapper).insert(captor.capture());
        // ADMIN 등록도 개최연도를 갖는다. contentId 가 없으니 중복 조회는 하지 않는다.
        assertThat(captor.getValue().getEventYear()).isEqualTo(2026);
        assertThat(captor.getValue().getSourceType()).isEqualTo("ADMIN");
        assertThat(captor.getValue().getExternalContentId()).isNull();
        verify(festivalInfoMapper, never()).countOccurrence(any(), any(), any());
    }

    @Test
    void tourApiRegistrationStoresServerDerivedSourceAndExternalContentId() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId(" 12345 ");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "12345", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("12345")).thenReturn(imageDetail("12345", null, "Type1"));
        generateTravelInfoId(42L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);

        assertThat(service.create(form, 7L).festivalId()).isEqualTo(42L);

        ArgumentCaptor<FestivalInfo> captor = ArgumentCaptor.forClass(FestivalInfo.class);
        verify(festivalInfoMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceType()).isEqualTo("KTO_TOURAPI");
        assertThat(captor.getValue().getExternalContentId()).isEqualTo("12345");
    }

    @Test
    void logsRefetchedTourApiCopyrightDivisionCodeWithContentIdOnly() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("2648460");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "2648460", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("2648460"))
                .thenReturn(imageDetail("2648460", null, "Type3"));
        generateTravelInfoId(43L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        Logger logger = (Logger) LoggerFactory.getLogger(FestivalRegistrationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.create(form, 7L);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("contentId=2648460", "cpyrhtDivCd=Type3")
                            .doesNotContain("http", "serviceKey", "response");
                });
    }

    @Test
    void type3TourApiImageUsesRefetchedDetailAndStoresCompleteInfoImageMetadata() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("2648460");
        String serverVerifiedUrl = "https://tong.visitkorea.or.kr/cms2/website/75/gyeongbokgung.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "2648460", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("2648460"))
                .thenReturn(imageDetail("2648460", serverVerifiedUrl, "Type3"));
        when(festivalImageDownloadService.download(serverVerifiedUrl)).thenReturn(new KtoDownloadedFestivalImage(
                "/uploads/travel-info/festivals/generated.jpg", serverVerifiedUrl, "image/jpeg", 1024));
        generateTravelInfoId(44L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        FestivalRegistrationResult result = service.create(form, 7L);

        assertThat(result.festivalId()).isEqualTo(44L);
        assertThat(result.imageWarning()).isNull();
        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper).insertInfoImage(captor.capture());
        assertThat(captor.getValue()).satisfies(image -> {
            assertThat(image.getImageUrl()).isEqualTo("/uploads/travel-info/festivals/generated.jpg");
            assertThat(image.getIsMain()).isTrue();
            assertThat(image.getOrderIndex()).isEqualTo(1);
            assertThat(image.getSourceType()).isEqualTo("KTO_TOURAPI");
            assertThat(image.getSourceName()).isEqualTo("한국관광공사");
            assertThat(image.getExternalContentId()).isEqualTo("2648460");
            assertThat(image.getSourceTitle()).isEqualTo("경복궁 별빛야행");
            assertThat(image.getSourceImageUrl()).isEqualTo(serverVerifiedUrl);
            assertThat(image.getLicenseType()).isEqualTo("KOGL_TYPE_3");
            assertThat(image.getLicenseCheckedAt()).isNotNull();
            assertThat(image.getInfoId()).isEqualTo(44L);
        });
        verify(festivalImageDownloadService).download(serverVerifiedUrl);
    }

    @Test
    void selectedMainImageIsStoredAsBothMainAndThumbnail() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("thumbnail-main");
        form.setKtoThumbnailImageSelection("MAIN");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/main-thumbnail.jpg";
        String additionalUrl = "https://tong.visitkorea.or.kr/cms/resource/35/additional-thumbnail.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "thumbnail-main", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-main"))
                .thenReturn(imageDetail("thumbnail-main", mainUrl, "Type1"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-main")).thenReturn(List.of(
                additionalImage("thumbnail-main", additionalUrl, "추가 이미지", "poster-1", "Type3")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(downloaded("main-thumbnail.jpg", mainUrl));
        when(festivalImageDownloadService.download(additionalUrl)).thenReturn(downloaded("additional-thumbnail.jpg", additionalUrl));
        generateTravelInfoId(61L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        service.create(form, 7L);

        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper, org.mockito.Mockito.times(2)).insertInfoImage(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InfoImage::getSourceImageUrl, InfoImage::getIsMain, InfoImage::getIsThumbnail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(mainUrl, true, true),
                        org.assertj.core.groups.Tuple.tuple(additionalUrl, false, false));
    }

    @Test
    void selectedDetailImageIsOnlyThumbnailAndKeepsMainImageRole() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("thumbnail-detail");
        form.setKtoThumbnailImageSelection("DETAIL:poster-2");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/main-hero.jpg";
        String posterUrl = "https://tong.visitkorea.or.kr/cms/resource/35/official-poster.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "thumbnail-detail", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-detail"))
                .thenReturn(imageDetail("thumbnail-detail", mainUrl, "Type3"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-detail")).thenReturn(List.of(
                additionalImage("thumbnail-detail", posterUrl, "공식 포스터", "poster-2", "Type1")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(downloaded("main-hero.jpg", mainUrl));
        when(festivalImageDownloadService.download(posterUrl)).thenReturn(downloaded("poster.jpg", posterUrl));
        generateTravelInfoId(62L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        service.createWithRequiredThumbnail(form, 7L);

        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper, org.mockito.Mockito.times(2)).insertInfoImage(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InfoImage::getSourceImageUrl, InfoImage::getIsMain, InfoImage::getIsThumbnail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(mainUrl, true, false),
                        org.assertj.core.groups.Tuple.tuple(posterUrl, false, true));
        verify(travelInfoService).saveTranslations(62L, "서울 빛 축제", "<p>행사 소개</p>", form.getTranslations());
        verify(festivalInfoService).saveTranslations(org.mockito.ArgumentMatchers.eq(62L), any(),
                org.mockito.ArgumentMatchers.eq(form.getFestivalInfoTranslations()));
    }

    @Test
    void invalidOrUnsupportedThumbnailSelectionIsRejectedBeforeFestivalInsert() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("thumbnail-validation");
        form.setKtoThumbnailImageSelection("DETAIL:type2");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "thumbnail-validation", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-validation"))
                .thenReturn(imageDetail("thumbnail-validation",
                        "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg", "Type1"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-validation")).thenReturn(List.of(
                additionalImage("thumbnail-validation",
                        "https://tong.visitkorea.or.kr/cms/resource/35/type2.jpg", "미지원", "type2", "Type2")));

        assertThatThrownBy(() -> service.create(form, 7L))
                .isInstanceOf(FestivalValidationException.class)
                .hasMessage("선택한 목록 썸네일 이미지를 다시 확인해 주세요.");

        verify(travelInfoMapper, never()).insertTravelInfo(any());
        verify(festivalImageDownloadService, never()).download(any());
    }

    @Test
    void arbitraryThumbnailUrlAndOtherFestivalSerialAreRejectedBeforeFestivalInsert() {
        FestivalCreateForm arbitraryUrl = validForm();
        arbitraryUrl.setKtoFestivalContentId("thumbnail-client-input");
        arbitraryUrl.setKtoThumbnailImageSelection("https://attacker.example/image.jpg");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence(
                "KTO_TOURAPI", "thumbnail-client-input", 2026)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-client-input"))
                .thenReturn(imageDetail("thumbnail-client-input",
                        "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg", "Type1"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-client-input")).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(arbitraryUrl, 7L))
                .isInstanceOf(FestivalValidationException.class)
                .hasMessage("선택한 목록 썸네일 이미지를 다시 확인해 주세요.");

        FestivalCreateForm otherContent = validForm();
        otherContent.setKtoFestivalContentId("thumbnail-client-input");
        otherContent.setKtoThumbnailImageSelection("DETAIL:other-festival-image");
        when(ktoFestivalService.getAdditionalImages("thumbnail-client-input")).thenReturn(List.of(
                additionalImage("other-content-id",
                        "https://tong.visitkorea.or.kr/cms/resource/35/other.jpg",
                        "다른 축제 이미지", "other-festival-image", "Type1")));

        assertThatThrownBy(() -> service.create(otherContent, 7L))
                .isInstanceOf(FestivalValidationException.class)
                .hasMessage("선택한 목록 썸네일 이미지를 다시 확인해 주세요.");

        verify(travelInfoMapper, never()).insertTravelInfo(any());
        verify(festivalImageDownloadService, never()).download(any());
    }

    @Test
    void missingThumbnailSerialIsRejectedBeforeFestivalInsert() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("thumbnail-missing-serial");
        form.setKtoThumbnailImageSelection("DETAIL:missing");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence(
                "KTO_TOURAPI", "thumbnail-missing-serial", 2026)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-missing-serial"))
                .thenReturn(imageDetail("thumbnail-missing-serial",
                        "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg", "Type1"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-missing-serial")).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(form, 7L))
                .isInstanceOf(FestivalValidationException.class)
                .hasMessage("선택한 목록 썸네일 이미지를 다시 확인해 주세요.");

        verify(travelInfoMapper, never()).insertTravelInfo(any());
    }

    @Test
    void failedSelectedThumbnailKeepsFestivalRegistrationAndMainFallback() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("thumbnail-download-failure");
        form.setKtoThumbnailImageSelection("DETAIL:poster-failure");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/fallback-main.jpg";
        String posterUrl = "https://tong.visitkorea.or.kr/cms/resource/35/fallback-poster.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence(
                "KTO_TOURAPI", "thumbnail-download-failure", 2026)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("thumbnail-download-failure"))
                .thenReturn(imageDetail("thumbnail-download-failure", mainUrl, "Type1"));
        when(ktoFestivalService.getAdditionalImages("thumbnail-download-failure")).thenReturn(List.of(
                additionalImage("thumbnail-download-failure", posterUrl, "포스터", "poster-failure", "Type3")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(downloaded("fallback-main.jpg", mainUrl));
        when(festivalImageDownloadService.download(posterUrl)).thenThrow(new KtoPhotoDownloadException());
        generateTravelInfoId(63L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        FestivalRegistrationResult result = service.create(form, 7L);

        assertThat(result.festivalId()).isEqualTo(63L);
        assertThat(result.imageWarning()).contains("선택한 목록 썸네일");
        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper).insertInfoImage(captor.capture());
        assertThat(captor.getValue()).satisfies(image -> {
            assertThat(image.getIsMain()).isTrue();
            assertThat(image.getIsThumbnail()).isFalse();
        });
    }

    @Test
    void storesOnlyLicensedUniqueAdditionalImagesInSuccessfulDownloadOrder() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("2648460");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg";
        String type1Url = "https://tong.visitkorea.or.kr/cms/resource/35/additional-1.jpg";
        String unsupportedUrl = "https://tong.visitkorea.or.kr/cms/resource/35/additional-2.jpg";
        String failedUrl = "https://tong.visitkorea.or.kr/cms/resource/35/additional-3.jpg";
        String type3Url = "https://tong.visitkorea.or.kr/cms/resource/35/additional-4.png";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "2648460", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("2648460"))
                .thenReturn(imageDetail("2648460", mainUrl, "Type3"));
        when(ktoFestivalService.getAdditionalImages("2648460")).thenReturn(List.of(
                additionalImage(mainUrl, "대표 중복", "Type3"),
                additionalImage(type1Url, "행사장 전경", "Type1"),
                additionalImage(type1Url, "동일 URL 중복", "Type1"),
                additionalImage(unsupportedUrl, "미지원 저작권", "Type2"),
                additionalImage(failedUrl, "다운로드 실패", "Type3"),
                additionalImage(type3Url, "야간 공연", "Type3")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(downloaded("main.jpg", mainUrl));
        when(festivalImageDownloadService.download(type1Url)).thenReturn(downloaded("additional-1.jpg", type1Url));
        when(festivalImageDownloadService.download(failedUrl)).thenThrow(new KtoPhotoDownloadException());
        when(festivalImageDownloadService.download(type3Url)).thenReturn(downloaded("additional-4.png", type3Url));
        generateTravelInfoId(49L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        FestivalRegistrationResult result = service.create(form, 7L);

        assertThat(result.festivalId()).isEqualTo(49L);
        assertThat(result.imageWarning()).contains("일부");
        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper, org.mockito.Mockito.times(3)).insertInfoImage(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InfoImage::getSourceImageUrl, InfoImage::getIsMain, InfoImage::getIsThumbnail,
                        InfoImage::getOrderIndex, InfoImage::getLicenseType, InfoImage::getSourceTitle)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(mainUrl, true, false, 1, "KOGL_TYPE_3", "경복궁 별빛야행"),
                        org.assertj.core.groups.Tuple.tuple(type1Url, false, false, 2, "KOGL_TYPE_1", "행사장 전경"),
                        org.assertj.core.groups.Tuple.tuple(type3Url, false, false, 3, "KOGL_TYPE_3", "야간 공연"));
        verify(festivalImageDownloadService, never()).download(unsupportedUrl);
    }

    @Test
    void detailImageCanStoreSameUrlOnceWhenUnsupportedRepresentativeWasNotStored() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("same-url-fallback");
        String sameUrl = "https://tong.visitkorea.or.kr/cms/resource/35/same.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence(
                "KTO_TOURAPI", "same-url-fallback", 2026)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("same-url-fallback"))
                .thenReturn(imageDetail("same-url-fallback", sameUrl, "Type2"));
        when(ktoFestivalService.getAdditionalImages("same-url-fallback"))
                .thenReturn(List.of(additionalImage(
                        "same-url-fallback", sameUrl, "이미지별 라이선스 확인", "Type1")));
        when(festivalImageDownloadService.download(sameUrl)).thenReturn(downloaded("same.jpg", sameUrl));
        generateTravelInfoId(51L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        service.create(form, 7L);

        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper).insertInfoImage(captor.capture());
        assertThat(captor.getValue()).satisfies(image -> {
            assertThat(image.getSourceImageUrl()).isEqualTo(sameUrl);
            assertThat(image.getIsMain()).isFalse();
            assertThat(image.getOrderIndex()).isEqualTo(2);
            assertThat(image.getLicenseType()).isEqualTo("KOGL_TYPE_1");
        });
    }

    @Test
    void rejectedAdditionalImageDoesNotBlockLaterLicensedDuplicate() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("duplicate-retry");
        String unsupportedFirstUrl = "https://tong.visitkorea.or.kr/cms/resource/35/license-retry.jpg";
        String failedFirstUrl = "https://tong.visitkorea.or.kr/cms/resource/35/download-retry.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence(
                "KTO_TOURAPI", "duplicate-retry", 2026)).thenReturn(0);
        when(ktoFestivalService.getImageDetail("duplicate-retry"))
                .thenReturn(imageDetail("duplicate-retry", null, null));
        when(ktoFestivalService.getAdditionalImages("duplicate-retry")).thenReturn(List.of(
                additionalImage("duplicate-retry", unsupportedFirstUrl, "미지원 선행", "Type2"),
                additionalImage("duplicate-retry", unsupportedFirstUrl, "유효 라이선스", "Type1"),
                additionalImage("duplicate-retry", failedFirstUrl, "다운로드 실패 선행", "Type3"),
                additionalImage("duplicate-retry", failedFirstUrl, "다운로드 재시도", "Type3")));
        when(festivalImageDownloadService.download(unsupportedFirstUrl))
                .thenReturn(downloaded("license-retry.jpg", unsupportedFirstUrl));
        when(festivalImageDownloadService.download(failedFirstUrl))
                .thenThrow(new KtoPhotoDownloadException())
                .thenReturn(downloaded("download-retry.jpg", failedFirstUrl));
        generateTravelInfoId(52L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);

        FestivalRegistrationResult result = service.create(form, 7L);

        assertThat(result.imageWarning()).contains("일부");
        ArgumentCaptor<InfoImage> captor = ArgumentCaptor.forClass(InfoImage.class);
        verify(travelInfoMapper, org.mockito.Mockito.times(2)).insertInfoImage(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InfoImage::getSourceImageUrl, InfoImage::getOrderIndex,
                        InfoImage::getLicenseType, InfoImage::getSourceTitle)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                unsupportedFirstUrl, 2, "KOGL_TYPE_1", "유효 라이선스"),
                        org.assertj.core.groups.Tuple.tuple(
                                failedFirstUrl, 3, "KOGL_TYPE_3", "다운로드 재시도"));
    }

    @Test
    void unsupportedLicenseOrMissingFirstImageKeepsFestivalRegistrationWithoutAutoImage() {
        FestivalCreateForm unsupported = validForm();
        unsupported.setKtoFestivalContentId("unsupported");
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "unsupported", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("unsupported"))
                .thenReturn(imageDetail("unsupported", "https://tong.visitkorea.or.kr/cms2/website/75/image.jpg", "Type2"));
        generateTravelInfoId(45L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);

        FestivalRegistrationResult unsupportedResult = service.create(unsupported, 7L);

        assertThat(unsupportedResult.imageWarning()).contains("저작권 유형");
        verify(travelInfoMapper, never()).insertInfoImage(any());
        verify(festivalImageDownloadService, never()).download(any());

        FestivalCreateForm noImage = validForm();
        noImage.setKtoFestivalContentId("no-image");
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "no-image", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("no-image"))
                .thenReturn(imageDetail("no-image", null, "Type1"));
        generateTravelInfoId(46L);

        FestivalRegistrationResult noImageResult = service.create(noImage, 7L);

        assertThat(noImageResult.imageWarning()).isNull();
        verify(festivalImageDownloadService, never()).download(any());
    }

    @Test
    void imageDownloadFailureDoesNotCancelTourApiFestivalRegistration() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("image-failure");
        String url = "https://tong.visitkorea.or.kr/cms2/website/75/image.jpg";
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "image-failure", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("image-failure"))
                .thenReturn(imageDetail("image-failure", url, "Type1"));
        when(festivalImageDownloadService.download(url)).thenThrow(new KtoPhotoDownloadException());
        generateTravelInfoId(47L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);

        FestivalRegistrationResult result = service.create(form, 7L);

        assertThat(result.festivalId()).isEqualTo(47L);
        assertThat(result.imageWarning()).contains("대표이미지");
        verify(travelInfoMapper, never()).insertInfoImage(any());
    }

    @Test
    void validatesFestivalCategoryRequiredFieldsAndDateOrderBeforeInsert() {
        FestivalCreateForm form = validForm();
        form.setTitle("   ");
        assertFieldError(form, "title");

        form = validForm();
        form.setScope(null);
        assertFieldError(form, "scope");

        form = validForm();
        form.setStartDate(null);
        assertFieldError(form, "startDate");

        form = validForm();
        form.setEndDate(LocalDate.of(2026, 11, 30));
        assertFieldError(form, "endDate");

        form = validForm();
        InfoCategory general = festivalCategory();
        general.setContentType(TravelInfoContentType.GENERAL);
        when(infoCategoryMapper.findById(5L)).thenReturn(general);
        assertFieldError(form, "categoryId");

        verifyNoInteractions(travelInfoMapper);
    }

    @Test
    void mapperFailureBetweenTravelInfoAndFestivalInfoRollsBackTransaction() {
        allowFestivalCategory();
        generateTravelInfoId(41L);
        when(travelInfoMapper.insertPeriod(any())).thenThrow(new IllegalStateException("period failure"));
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        FestivalRegistrationService transactionalService = transactionalProxy(service, transactionManager);

        assertThatThrownBy(() -> transactionalService.create(validForm(), 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("period failure");

        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
        verify(festivalInfoMapper, never()).insert(any());
    }

    @Test
    void rollbackDeletesOnlyNewFestivalImageAfterDatabaseFailure() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("rollback-image");
        String url = "https://tong.visitkorea.or.kr/cms2/website/75/image.jpg";
        KtoDownloadedFestivalImage downloaded = new KtoDownloadedFestivalImage(
                "/uploads/travel-info/festivals/rollback.jpg", url, "image/jpeg", 1024);
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "rollback-image", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("rollback-image"))
                .thenReturn(imageDetail("rollback-image", url, "Type1"));
        when(festivalImageDownloadService.download(url)).thenReturn(downloaded);
        generateTravelInfoId(48L);
        when(travelInfoMapper.insertPeriod(any())).thenThrow(new IllegalStateException("period failure"));
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        assertThatThrownBy(() -> transactionalProxy(service, transactionManager).create(form, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("period failure");

        assertThat(transactionManager.rolledBack).isTrue();
        verify(festivalImageDownloadService).deleteDownloadedFestivalImage(downloaded);
    }

    @Test
    void rollbackDeletesEveryNewlyDownloadedFestivalImage() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("rollback-gallery");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/main.jpg";
        String additionalUrl = "https://tong.visitkorea.or.kr/cms/resource/35/additional.jpg";
        KtoDownloadedFestivalImage main = downloaded("rollback-main.jpg", mainUrl);
        KtoDownloadedFestivalImage additional = downloaded("rollback-additional.jpg", additionalUrl);
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "rollback-gallery", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("rollback-gallery"))
                .thenReturn(imageDetail("rollback-gallery", mainUrl, "Type1"));
        when(ktoFestivalService.getAdditionalImages("rollback-gallery"))
                .thenReturn(List.of(additionalImage("rollback-gallery", additionalUrl, "추가", "Type3")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(main);
        when(festivalImageDownloadService.download(additionalUrl)).thenReturn(additional);
        generateTravelInfoId(50L);
        when(travelInfoMapper.insertPeriod(any())).thenThrow(new IllegalStateException("period failure"));
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        assertThatThrownBy(() -> transactionalProxy(service, transactionManager).create(form, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("period failure");

        verify(festivalImageDownloadService).deleteDownloadedFestivalImage(main);
        verify(festivalImageDownloadService).deleteDownloadedFestivalImage(additional);
    }

    @Test
    void committedRegistrationKeepsEveryStoredFestivalImageFile() {
        FestivalCreateForm form = validForm();
        form.setKtoFestivalContentId("commit-gallery");
        String mainUrl = "https://tong.visitkorea.or.kr/cms/resource/35/commit-main.jpg";
        String additionalUrl = "https://tong.visitkorea.or.kr/cms/resource/35/commit-additional.jpg";
        KtoDownloadedFestivalImage main = downloaded("commit-main.jpg", mainUrl);
        KtoDownloadedFestivalImage additional = downloaded("commit-additional.jpg", additionalUrl);
        allowFestivalCategory();
        when(festivalInfoMapper.countOccurrence("KTO_TOURAPI", "commit-gallery", 2026))
                .thenReturn(0);
        when(ktoFestivalService.getImageDetail("commit-gallery"))
                .thenReturn(imageDetail("commit-gallery", mainUrl, "Type1"));
        when(ktoFestivalService.getAdditionalImages("commit-gallery"))
                .thenReturn(List.of(additionalImage("commit-gallery", additionalUrl, "추가", "Type3")));
        when(festivalImageDownloadService.download(mainUrl)).thenReturn(main);
        when(festivalImageDownloadService.download(additionalUrl)).thenReturn(additional);
        generateTravelInfoId(52L);
        when(travelInfoMapper.insertPeriod(any())).thenReturn(1);
        when(festivalInfoMapper.insert(any())).thenReturn(1);
        when(travelInfoMapper.insertInfoImage(any())).thenReturn(1);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();

        FestivalRegistrationResult result = transactionalProxy(service, transactionManager).create(form, 7L);

        assertThat(result.festivalId()).isEqualTo(52L);
        assertThat(transactionManager.committed).isTrue();
        assertThat(transactionManager.rolledBack).isFalse();
        verify(festivalImageDownloadService, never()).deleteDownloadedFestivalImage(any(KtoDownloadedFestivalImage.class));
    }

    private void assertFieldError(FestivalCreateForm form, String field) {
        assertThatThrownBy(() -> service.create(form, 7L))
                .isInstanceOfSatisfying(FestivalValidationException.class,
                        exception -> assertThat(exception.getField()).isEqualTo(field));
    }

    private FestivalRegistrationService transactionalProxy(FestivalRegistrationService target,
                                                              RecordingTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (FestivalRegistrationService) proxyFactory.getProxy();
    }

    private void allowFestivalCategory() {
        when(infoCategoryMapper.findById(5L)).thenReturn(festivalCategory());
    }

    private InfoCategory festivalCategory() {
        InfoCategory category = new InfoCategory();
        category.setId(5L);
        category.setName("축제");
        category.setContentType(TravelInfoContentType.FESTIVAL);
        category.setIsVisible(true);
        return category;
    }

    private KtoFestivalImageDetail imageDetail(String contentId, String firstImage, String copyrightCode) {
        return new KtoFestivalImageDetail(contentId, "경복궁 별빛야행", firstImage, copyrightCode);
    }

    private KtoFestivalAdditionalImage additionalImage(String url, String name, String copyrightCode) {
        return additionalImage("2648460", url, name, copyrightCode);
    }

    private KtoFestivalAdditionalImage additionalImage(String contentId, String url,
                                                        String name, String copyrightCode) {
        return new KtoFestivalAdditionalImage(contentId, name, url, "serial", copyrightCode);
    }

    private KtoFestivalAdditionalImage additionalImage(String contentId, String url,
                                                        String name, String serialNumber,
                                                        String copyrightCode) {
        return new KtoFestivalAdditionalImage(contentId, name, url, serialNumber, copyrightCode);
    }

    private KtoDownloadedFestivalImage downloaded(String fileName, String sourceUrl) {
        return new KtoDownloadedFestivalImage(
                "/uploads/travel-info/festivals/" + fileName, sourceUrl, "image/jpeg", 1024);
    }

    private void generateTravelInfoId(Long id) {
        doAnswer(invocation -> {
            invocation.getArgument(0, TravelInfo.class).setId(id);
            return 1;
        }).when(travelInfoMapper).insertTravelInfo(any());
    }

    private FestivalCreateForm validForm() {
        FestivalCreateForm form = new FestivalCreateForm();
        form.setTitle("서울 빛 축제");
        form.setContent("<p>행사 소개</p>");
        form.setScope(TravelInfoScope.DOMESTIC);
        form.setCategoryId(5L);
        form.setStartDate(LocalDate.of(2026, 12, 1));
        form.setEndDate(LocalDate.of(2026, 12, 31));
        form.setEventPlace("광화문광장");
        form.setAddress("서울 종로구");
        return form;
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;
        private boolean rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
