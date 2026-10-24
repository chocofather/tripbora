package com.tripbora.service.travelinfo;

import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.FestivalInfo;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.InfoImage;
import com.tripbora.model.InfoPeriod;
import com.tripbora.model.TravelInfo;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import com.tripbora.dto.kto.KtoFestivalAdditionalImage;
import com.tripbora.dto.kto.KtoFestivalImageDetail;
import com.tripbora.service.kto.InvalidKtoPhotoUrlException;
import com.tripbora.service.kto.KtoDownloadedFestivalImage;
import com.tripbora.service.kto.KtoFestivalImageDownloadService;
import com.tripbora.service.kto.KtoFestivalImageLicense;
import com.tripbora.service.kto.KtoFestivalService;
import com.tripbora.service.kto.KtoPhotoDownloadException;
import com.tripbora.service.kto.KtoTourApiException;
import com.tripbora.service.post.PostContentSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class FestivalRegistrationService {

    private static final Set<String> TRANSLATION_CODES = SupportedLanguage.all().stream()
            .filter(language -> language != SupportedLanguage.KOREAN)
            .map(SupportedLanguage::getLanguageTag)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final String ADMIN_SOURCE_TYPE = "ADMIN";
    private static final String KTO_SOURCE_TYPE = "KTO_TOURAPI";
    private static final String KTO_SOURCE_NAME = "한국관광공사";
    private static final String UNSUPPORTED_LICENSE_WARNING =
            "TourAPI 대표이미지의 저작권 유형을 확인할 수 없어 이미지는 저장하지 않았습니다.";
    private static final String IMAGE_DOWNLOAD_WARNING =
            "TourAPI 대표이미지를 저장하지 못해 이미지 없이 등록했습니다.";
    private static final String ADDITIONAL_IMAGE_WARNING =
            "일부 TourAPI 추가이미지는 저작권을 확인할 수 없거나 다운로드하지 못해 건너뛰었습니다.";
    private static final String THUMBNAIL_DOWNLOAD_WARNING =
            "선택한 목록 썸네일 이미지를 저장하지 못해 대표이미지를 사용합니다.";
    private static final String INVALID_THUMBNAIL_SELECTION_MESSAGE =
            "선택한 목록 썸네일 이미지를 다시 확인해 주세요.";

    private final TravelInfoMapper travelInfoMapper;
    private final FestivalInfoMapper festivalInfoMapper;
    private final InfoCategoryMapper infoCategoryMapper;
    private final PostContentSanitizer postContentSanitizer;
    private final KtoFestivalService ktoFestivalService;
    private final KtoFestivalImageDownloadService festivalImageDownloadService;
    private final TravelInfoService travelInfoService;
    private final FestivalInfoService festivalInfoService;

    public FestivalRegistrationService(TravelInfoMapper travelInfoMapper,
                                       FestivalInfoMapper festivalInfoMapper,
                                       InfoCategoryMapper infoCategoryMapper,
                                       PostContentSanitizer postContentSanitizer,
                                       KtoFestivalService ktoFestivalService,
                                       KtoFestivalImageDownloadService festivalImageDownloadService,
                                       TravelInfoService travelInfoService,
                                       FestivalInfoService festivalInfoService) {
        this.travelInfoMapper = travelInfoMapper;
        this.festivalInfoMapper = festivalInfoMapper;
        this.infoCategoryMapper = infoCategoryMapper;
        this.postContentSanitizer = postContentSanitizer;
        this.ktoFestivalService = ktoFestivalService;
        this.festivalImageDownloadService = festivalImageDownloadService;
        this.travelInfoService = travelInfoService;
        this.festivalInfoService = festivalInfoService;
    }

    @Transactional
    public FestivalRegistrationResult create(FestivalCreateForm form, Long userId) {
        return createInternal(form, userId, false);
    }

    @Transactional
    public FestivalRegistrationResult createWithRequiredThumbnail(FestivalCreateForm form, Long userId) {
        return createInternal(form, userId, true);
    }

    public void validateForCreate(FestivalCreateForm form, Long userId) {
        prepareCreate(form, userId);
    }

    private ValidatedCreate prepareCreate(FestivalCreateForm form, Long userId) {
        if (form == null) {
            throw new FestivalValidationException(null, "축제·행사 정보를 입력해 주세요.");
        }
        if (userId == null) {
            throw new FestivalValidationException(null, "작성자 정보를 확인할 수 없습니다.");
        }

        String title = requiredText(form.getTitle(), "title", "제목을 입력해 주세요.", 255);
        if (form.getScope() == null) {
            throw new FestivalValidationException("scope", "여행 범위를 선택해 주세요.");
        }
        if (form.getStartDate() == null) {
            throw new FestivalValidationException("startDate", "행사 시작일을 입력해 주세요.");
        }
        if (form.getEndDate() == null) {
            throw new FestivalValidationException("endDate", "행사 종료일을 입력해 주세요.");
        }
        if (form.getEndDate().isBefore(form.getStartDate())) {
            throw new FestivalValidationException("endDate", "행사 종료일은 시작일보다 빠를 수 없습니다.");
        }
        InfoCategory category = validateFestivalCategory(form.getCategoryId());

        String externalContentId = optionalText(form.getKtoFestivalContentId(), "ktoFestivalContentId", 100);
        String thumbnailSelection = optionalText(form.getKtoThumbnailImageSelection(),
                "ktoThumbnailImageSelection", 100);
        if (externalContentId == null && thumbnailSelection != null) {
            throw new FestivalValidationException("ktoThumbnailImageSelection",
                    "TourAPI 축제 이미지에서만 목록 썸네일을 선택할 수 있습니다.");
        }
        String sourceType = externalContentId == null ? ADMIN_SOURCE_TYPE : KTO_SOURCE_TYPE;
        // 개최연도는 화면 입력이 아니라 언제나 행사 시작일에서 계산한다.
        int eventYear = form.getStartDate().getYear();
        // 같은 축제라도 개최연도가 다르면 다른 개최분이라 새로 등록된다.
        if (externalContentId != null
                && festivalInfoMapper.countOccurrence(sourceType, externalContentId, eventYear) > 0) {
            throw duplicateTourApiFestival();
        }

        validateDetailFields(form);
        return new ValidatedCreate(title, category, externalContentId, thumbnailSelection, sourceType, eventYear);
    }

    private FestivalRegistrationResult createInternal(FestivalCreateForm form, Long userId,
                                                        boolean requiredThumbnail) {
        ValidatedCreate validated = prepareCreate(form, userId);
        String title = validated.title();
        InfoCategory category = validated.category();
        String externalContentId = validated.externalContentId();
        String thumbnailSelection = validated.thumbnailSelection();
        String sourceType = validated.sourceType();
        int eventYear = validated.eventYear();
        if (requiredThumbnail && externalContentId == null) {
            throw new FestivalValidationException("ktoFestivalContentId", "TourAPI 축제를 선택해 주세요.");
        }
        if (requiredThumbnail && thumbnailSelection == null) {
            throw invalidThumbnailSelection();
        }
        PreparedFestivalImages preparedImages = prepareTourApiImages(
                externalContentId, title, thumbnailSelection);
        boolean lifecycleRegistered = registerRollbackCleanup(preparedImages.downloadedImages());

        try {
            if (requiredThumbnail && preparedImages.images().stream()
                    .noneMatch(image -> image.thumbnail() && image.downloadedImage() != null
                            && optionalText(image.downloadedImage().localImageUrl()) != null)) {
                throw invalidThumbnailSelection();
            }
            TravelInfo travelInfo = new TravelInfo();
            travelInfo.setTitle(title);
            String baseContent = postContentSanitizer.sanitize(form.getContent());
            travelInfo.setContent(baseContent);
            travelInfo.setScope(form.getScope());
            travelInfo.setContentType(TravelInfoContentType.FESTIVAL);
            travelInfo.setCategoryId(category.getId());
            travelInfo.setViews(0);
            travelInfo.setUserId(userId);
            requireSingleRow(travelInfoMapper.insertTravelInfo(travelInfo), "축제·행사 기본정보를 저장하지 못했습니다.");
            if (travelInfo.getId() == null) {
                throw new IllegalStateException("생성된 축제·행사 ID를 확인할 수 없습니다.");
            }
            // base 저장과 같은 트랜잭션에서 번역까지 끝낸다.
            // 저장 규칙(ko 동기화 / 외국어 insert·update·delete)은 여행정보 서비스가 갖고 있다.
            travelInfoService.saveTranslations(
                    travelInfo.getId(), title, baseContent, form.getTranslations());

            InfoPeriod period = new InfoPeriod();
            period.setInfoId(travelInfo.getId());
            period.setStartDate(form.getStartDate());
            period.setEndDate(form.getEndDate());
            requireSingleRow(travelInfoMapper.insertPeriod(period), "축제·행사 기간을 저장하지 못했습니다.");

            FestivalInfo festivalInfo = new FestivalInfo();
            festivalInfo.setInfoId(travelInfo.getId());
            festivalInfo.setEventPlace(optionalText(form.getEventPlace(), "eventPlace", 255));
            festivalInfo.setAddress(optionalText(form.getAddress(), "address", 500));
            festivalInfo.setPlayTime(optionalText(form.getPlayTime(), "playTime", 500));
            festivalInfo.setUseTime(optionalText(form.getUseTime()));
            festivalInfo.setSponsor1(optionalText(form.getSponsor1(), "sponsor1", 255));
            festivalInfo.setSponsor1Tel(optionalText(form.getSponsor1Tel(), "sponsor1Tel", 100));
            festivalInfo.setSponsor2(optionalText(form.getSponsor2(), "sponsor2", 255));
            festivalInfo.setSponsor2Tel(optionalText(form.getSponsor2Tel(), "sponsor2Tel", 100));
            festivalInfo.setContactTel(optionalText(form.getContactTel(), "contactTel", 100));
            festivalInfo.setHomepageUrl(optionalText(form.getHomepageUrl(), "homepageUrl", 1000));
            festivalInfo.setSourceType(sourceType);
            festivalInfo.setExternalContentId(externalContentId);
            festivalInfo.setEventYear(eventYear);
            try {
                requireSingleRow(festivalInfoMapper.insert(festivalInfo), "축제·행사 상세정보를 저장하지 못했습니다.");
            } catch (DuplicateKeyException exception) {
                if (externalContentId != null) {
                    throw duplicateTourApiFestival();
                }
                throw exception;
            }
            // 행사 상세정보 번역도 같은 트랜잭션에서 저장한다. ko 는 base 여섯 칸으로 맞춰진다.
            festivalInfoService.saveTranslations(travelInfo.getId(), festivalInfo,
                    form.getFestivalInfoTranslations());

            for (PreparedFestivalImage preparedImage : preparedImages.images()) {
                insertTourApiImage(travelInfo.getId(), externalContentId, preparedImage);
            }
            return new FestivalRegistrationResult(travelInfo.getId(), preparedImages.warning());
        } catch (RuntimeException exception) {
            if (!lifecycleRegistered) {
                deleteDownloadedImagesSafely(preparedImages.downloadedImages());
            }
            throw exception;
        }
    }

    private void validateDetailFields(FestivalCreateForm form) {
        optionalText(form.getEventPlace(), "eventPlace", 255);
        optionalText(form.getAddress(), "address", 500);
        optionalText(form.getPlayTime(), "playTime", 500);
        optionalText(form.getSponsor1(), "sponsor1", 255);
        optionalText(form.getSponsor1Tel(), "sponsor1Tel", 100);
        optionalText(form.getSponsor2(), "sponsor2", 255);
        optionalText(form.getSponsor2Tel(), "sponsor2Tel", 100);
        optionalText(form.getContactTel(), "contactTel", 100);
        optionalText(form.getHomepageUrl(), "homepageUrl", 1000);
        if (form.getStartDate().getYear() < 1900 || form.getStartDate().getYear() > 2999) {
            throw new FestivalValidationException("startDate", "개최 시작 연도는 1900~2999년이어야 합니다.");
        }
        // 번역도 같은 VARCHAR 제약을 가진다. 자동입력 미리보기에서 실패 원인을 먼저 알린다.
        if (form.getTranslations() != null) {
            for (int index = 0; index < form.getTranslations().size(); index++) {
                var translation = form.getTranslations().get(index);
                if (translation == null || translation.getLanguageCode() == null
                        || !TRANSLATION_CODES.contains(translation.getLanguageCode())) continue;
                optionalText(translation.getTitle(), "translations[" + index + "].title", 255);
            }
        }
        if (form.getFestivalInfoTranslations() != null) {
            for (int index = 0; index < form.getFestivalInfoTranslations().size(); index++) {
                var translation = form.getFestivalInfoTranslations().get(index);
                if (translation == null || translation.getLanguageCode() == null
                        || !TRANSLATION_CODES.contains(translation.getLanguageCode())) continue;
                String prefix = "festivalInfoTranslations[" + index + "].";
                optionalText(translation.getEventPlace(), prefix + "eventPlace", 255);
                optionalText(translation.getAddress(), prefix + "address", 500);
                optionalText(translation.getPlayTime(), prefix + "playTime", 500);
                optionalText(translation.getSponsor1(), prefix + "sponsor1", 255);
                optionalText(translation.getSponsor2(), prefix + "sponsor2", 255);
            }
        }
    }

    private record ValidatedCreate(String title, InfoCategory category, String externalContentId,
                                   String thumbnailSelection, String sourceType, int eventYear) {
    }

    private PreparedFestivalImages prepareTourApiImages(String externalContentId,
                                                         String fallbackTitle,
                                                         String thumbnailSelection) {
        if (externalContentId == null) {
            return PreparedFestivalImages.none();
        }

        KtoFestivalImageDetail detail;
        try {
            detail = ktoFestivalService.getImageDetail(externalContentId);
        } catch (KtoTourApiException exception) {
            throw new FestivalValidationException("ktoFestivalContentId", "TourAPI 축제 정보를 다시 확인하지 못했습니다.");
        }
        if (detail == null || !externalContentId.equals(optionalText(detail.contentId()))) {
            throw new FestivalValidationException("ktoFestivalContentId", "TourAPI 축제 정보를 다시 확인하지 못했습니다.");
        }
        String copyrightDivisionCode = optionalText(detail.copyrightDivisionCode());
        log.info("TourAPI 축제 대표이미지 저작권 코드 확인: contentId={}, cpyrhtDivCd={}",
                externalContentId, copyrightDivisionCode);
        List<String> warnings = new ArrayList<>();
        String sourceTitle = firstNonBlank(optionalText(detail.title()), fallbackTitle);
        List<KtoFestivalAdditionalImage> additionalImages = null;
        try {
            additionalImages = ktoFestivalService.getAdditionalImages(externalContentId);
        } catch (KtoTourApiException exception) {
            if (isDetailThumbnailSelection(thumbnailSelection)) {
                throw invalidThumbnailSelection();
            }
            warnings.add(ADDITIONAL_IMAGE_WARNING);
        }

        SelectedThumbnail selectedThumbnail = resolveThumbnailSelection(
                externalContentId, thumbnailSelection, detail, additionalImages);
        List<PreparedFestivalImage> images = new ArrayList<>();
        Set<String> sourceImageUrls = new LinkedHashSet<>();

        String mainImageUrl = optionalText(detail.firstImage());
        if (mainImageUrl != null) {
            KtoFestivalImageLicense license = KtoFestivalImageLicense
                    .fromCopyrightDivisionCode(copyrightDivisionCode)
                    .orElse(null);
            if (license == null) {
                warnings.add(UNSUPPORTED_LICENSE_WARNING);
            } else {
                try {
                    KtoDownloadedFestivalImage downloaded = festivalImageDownloadService.download(mainImageUrl);
                    images.add(new PreparedFestivalImage(downloaded, license, sourceTitle, true, 1,
                            selectedThumbnail != null && selectedThumbnail.sourceImageUrl().equals(mainImageUrl)));
                    sourceImageUrls.add(mainImageUrl);
                } catch (InvalidKtoPhotoUrlException | KtoPhotoDownloadException exception) {
                    warnings.add(IMAGE_DOWNLOAD_WARNING);
                }
            }
        }

        if (additionalImages == null) {
            addThumbnailFallbackWarningIfNeeded(selectedThumbnail, images, warnings);
            return new PreparedFestivalImages(images, warningText(warnings));
        }

        int orderIndex = 2;
        for (KtoFestivalAdditionalImage additionalImage : additionalImages) {
            if (additionalImage == null
                    || !externalContentId.equals(optionalText(additionalImage.contentId()))) {
                warnings.add(ADDITIONAL_IMAGE_WARNING);
                continue;
            }
            String sourceImageUrl = optionalText(additionalImage.originalImageUrl());
            if (sourceImageUrl == null || sourceImageUrls.contains(sourceImageUrl)) {
                continue;
            }
            KtoFestivalImageLicense license = KtoFestivalImageLicense
                    .fromCopyrightDivisionCode(additionalImage.copyrightDivisionCode())
                    .orElse(null);
            if (license == null) {
                warnings.add(ADDITIONAL_IMAGE_WARNING);
                continue;
            }
            try {
                KtoDownloadedFestivalImage downloaded = festivalImageDownloadService.download(sourceImageUrl);
                sourceImageUrls.add(sourceImageUrl);
                images.add(new PreparedFestivalImage(
                        downloaded,
                        license,
                        firstNonBlank(optionalText(additionalImage.imageName()), sourceTitle),
                        false,
                        orderIndex++,
                        selectedThumbnail != null && selectedThumbnail.sourceImageUrl().equals(sourceImageUrl)));
            } catch (InvalidKtoPhotoUrlException | KtoPhotoDownloadException exception) {
                warnings.add(ADDITIONAL_IMAGE_WARNING);
            }
        }
        addThumbnailFallbackWarningIfNeeded(selectedThumbnail, images, warnings);
        return new PreparedFestivalImages(images, warningText(warnings));
    }

    private void insertTourApiImage(Long infoId, String externalContentId, PreparedFestivalImage preparedImage) {
        KtoDownloadedFestivalImage downloaded = preparedImage.downloadedImage();
        InfoImage image = new InfoImage();
        image.setImageUrl(downloaded.localImageUrl());
        image.setIsMain(preparedImage.main());
        image.setOrderIndex(preparedImage.orderIndex());
        image.setSourceType(KTO_SOURCE_TYPE);
        image.setSourceName(KTO_SOURCE_NAME);
        image.setExternalContentId(externalContentId);
        image.setSourceTitle(preparedImage.sourceTitle());
        image.setSourceImageUrl(downloaded.sourceImageUrl());
        image.setLicenseType(preparedImage.license().name());
        image.setLicenseCheckedAt(new Timestamp(System.currentTimeMillis()));
        image.setIsThumbnail(preparedImage.thumbnail());
        image.setInfoId(infoId);
        requireSingleRow(travelInfoMapper.insertInfoImage(image), "축제·행사 이미지를 저장하지 못했습니다.");
    }

    private boolean registerRollbackCleanup(List<KtoDownloadedFestivalImage> downloadedImages) {
        if (downloadedImages.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteDownloadedImagesSafely(downloadedImages);
                }
            }
        });
        return true;
    }

    private void deleteDownloadedImagesSafely(List<KtoDownloadedFestivalImage> downloadedImages) {
        for (KtoDownloadedFestivalImage downloadedImage : downloadedImages) {
            deleteDownloadedImageSafely(downloadedImage);
        }
    }

    private void deleteDownloadedImageSafely(KtoDownloadedFestivalImage downloadedImage) {
        if (downloadedImage == null) {
            return;
        }
        try {
            festivalImageDownloadService.deleteDownloadedFestivalImage(downloadedImage);
        } catch (RuntimeException exception) {
            log.warn("축제 TourAPI 이미지 파일을 정리하지 못했습니다.");
        }
    }

    private String warningText(List<String> warnings) {
        return warnings.stream().distinct().reduce((first, second) -> first + " " + second).orElse(null);
    }

    private InfoCategory validateFestivalCategory(Long categoryId) {
        if (categoryId == null) {
            throw new FestivalValidationException("categoryId", "축제 분류를 선택해 주세요.");
        }
        InfoCategory category = infoCategoryMapper.findById(categoryId);
        if (category == null || category.getContentType() != TravelInfoContentType.FESTIVAL) {
            throw new FestivalValidationException("categoryId", "축제·행사 분류만 선택할 수 있습니다.");
        }
        return category;
    }

    private String requiredText(String value, String field, String message, int maxLength) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new FestivalValidationException(field, message);
        }
        if (normalized.length() > maxLength) {
            throw new FestivalValidationException(field, maxLength + "자 이내로 입력해 주세요.");
        }
        return normalized;
    }

    private String optionalText(String value, String field, int maxLength) {
        String normalized = optionalText(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new FestivalValidationException(field, maxLength + "자 이내로 입력해 주세요.");
        }
        return normalized;
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireSingleRow(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    /** 다른 연도 개최분은 막지 않는다. 같은 축제의 같은 해 개최분만 중복이다. */
    private FestivalValidationException duplicateTourApiFestival() {
        return new FestivalValidationException("ktoFestivalContentId",
                "이미 등록된 해당 연도의 TourAPI 축제·행사입니다.");
    }

    private SelectedThumbnail resolveThumbnailSelection(String externalContentId,
                                                        String thumbnailSelection,
                                                        KtoFestivalImageDetail detail,
                                                        List<KtoFestivalAdditionalImage> additionalImages) {
        if (thumbnailSelection == null) {
            return null;
        }
        if ("MAIN".equals(thumbnailSelection)) {
            String mainImageUrl = optionalText(detail.firstImage());
            if (mainImageUrl == null || KtoFestivalImageLicense
                    .fromCopyrightDivisionCode(detail.copyrightDivisionCode()).isEmpty()) {
                throw invalidThumbnailSelection();
            }
            return new SelectedThumbnail(mainImageUrl);
        }
        if (!isDetailThumbnailSelection(thumbnailSelection) || additionalImages == null) {
            throw invalidThumbnailSelection();
        }
        String serialNumber = thumbnailSelection.substring("DETAIL:".length());
        for (KtoFestivalAdditionalImage image : additionalImages) {
            if (image == null
                    || !externalContentId.equals(optionalText(image.contentId()))
                    || !serialNumber.equals(optionalText(image.serialNumber()))) {
                continue;
            }
            String sourceImageUrl = optionalText(image.originalImageUrl());
            if (sourceImageUrl == null || KtoFestivalImageLicense
                    .fromCopyrightDivisionCode(image.copyrightDivisionCode()).isEmpty()) {
                break;
            }
            return new SelectedThumbnail(sourceImageUrl);
        }
        throw invalidThumbnailSelection();
    }

    private boolean isDetailThumbnailSelection(String thumbnailSelection) {
        return thumbnailSelection != null
                && thumbnailSelection.startsWith("DETAIL:")
                && thumbnailSelection.length() > "DETAIL:".length();
    }

    private void addThumbnailFallbackWarningIfNeeded(SelectedThumbnail selectedThumbnail,
                                                      List<PreparedFestivalImage> images,
                                                      List<String> warnings) {
        if (selectedThumbnail != null && images.stream().noneMatch(PreparedFestivalImage::thumbnail)) {
            warnings.add(THUMBNAIL_DOWNLOAD_WARNING);
        }
    }

    private FestivalValidationException invalidThumbnailSelection() {
        return new FestivalValidationException("ktoThumbnailImageSelection", INVALID_THUMBNAIL_SELECTION_MESSAGE);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record PreparedFestivalImage(
            KtoDownloadedFestivalImage downloadedImage,
            KtoFestivalImageLicense license,
            String sourceTitle,
            boolean main,
            int orderIndex,
            boolean thumbnail
    ) {
    }

    private record SelectedThumbnail(String sourceImageUrl) {
    }

    private record PreparedFestivalImages(
            List<PreparedFestivalImage> images,
            String warning
    ) {
        private PreparedFestivalImages {
            images = List.copyOf(images);
        }

        private static PreparedFestivalImages none() {
            return new PreparedFestivalImages(List.of(), null);
        }

        private List<KtoDownloadedFestivalImage> downloadedImages() {
            return images.stream().map(PreparedFestivalImage::downloadedImage).toList();
        }
    }
}
