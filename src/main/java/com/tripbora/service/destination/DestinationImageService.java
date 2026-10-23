package com.tripbora.service.destination;

import com.tripbora.model.DestinationImage;
import com.tripbora.model.DestinationImageLicenseType;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.file.UnsupportedImageFormatException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.net.URI;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

@RequiredArgsConstructor
@Service
@Slf4j
public class DestinationImageService {

    private final DestinationMapper destinationMapper;
    private final FileUploadService fileUploadService;

    @Value("${custom.upload-path}")
    private String uploadDir;

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           Integer mainIdx,
                           Integer[] slideIdx) {
        saveImages(destId, files, mainIdx, slideIdx, null, null, null);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           Integer mainIdx,
                           Integer[] slideIdx,
                           String[] sourceNames,
                           String[] licenseTypes,
                           String[] sourceUrls) {
        saveImages(destId, files, mainIdx, slideIdx,
                sourceNames, null, licenseTypes, null, sourceUrls);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           Integer mainIdx,
                           Integer[] slideIdx,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] sourceUrls) {
        saveImages(destId, files, mainIdx, slideIdx,
                sourceNames, photographers, licenseTypes, null, sourceUrls);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           Integer mainIdx,
                           Integer[] slideIdx,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] licenseDetails,
                           String[] sourceUrls) {
        saveImages(destId, files, mainIdx, slideIdx, sourceNames, photographers,
                licenseTypes, licenseDetails, sourceUrls, null, null);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           Integer mainIdx,
                           Integer[] slideIdx,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] licenseDetails,
                           String[] sourceUrls,
                           String[] commonSourceUrls,
                           String[] workPageUrls) {
        if (files == null || files.length == 0) return;

        List<DestinationImage> existingImages = destinationMapper.findImagesByDestinationId(destId);
        int orderIndex = nextOrderIndex(existingImages);
        int uploadIndex = 0;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;

            boolean isMain = mainIdx != null && mainIdx == uploadIndex;
            int finalIdx = uploadIndex;
            boolean isSlide = slideIdx != null &&
                    Arrays.stream(slideIdx).anyMatch(i -> i == finalIdx);
            storeUploadedFile(destId, file,
                    metadataValue(sourceNames, uploadIndex),
                    metadataValue(photographers, uploadIndex),
                    metadataValue(licenseTypes, uploadIndex),
                    metadataValue(licenseDetails, uploadIndex),
                    metadataValue(sourceUrls, uploadIndex),
                    metadataValue(commonSourceUrls, uploadIndex),
                    metadataValue(workPageUrls, uploadIndex),
                    isMain, isSlide, orderIndex++);
            uploadIndex++;
        }
    }

    /**
     * 관리 화면에서 사진을 한 장씩 나눠 올릴 때의 한 장.
     *
     * <p>여러 장을 한 요청에 담는 {@link #saveImages} 와 같은 검증·파일 저장·출처 저장을 쓴다.
     * 한 장이 한 트랜잭션이라, 여러 장 중 한 장이 실패해도 앞서 저장된 사진과 그 출처는 그대로 남는다.
     * 대표·슬라이드는 지정하지 않는다(업로드 뒤 관리 카드에서 정한다). 순서는 기존 사진 다음이다.
     *
     * @return 저장된 이미지 번호
     */
    @Transactional
    public Long saveUploadedImage(Long destId, MultipartFile file,
                                  String sourceName, String photographer, String licenseType,
                                  String licenseDetail, String sourceUrl,
                                  String commonSourceUrl, String workPageUrl) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImageFormatException("이미지 파일을 선택해 주세요.");
        }
        List<DestinationImage> existingImages = destinationMapper.findImagesByDestinationId(destId);
        return storeUploadedFile(destId, file,
                metadataValue(sourceName), metadataValue(photographer), metadataValue(licenseType),
                metadataValue(licenseDetail), metadataValue(sourceUrl),
                metadataValue(commonSourceUrl), metadataValue(workPageUrl),
                false, false, nextOrderIndex(existingImages)).getId();
    }

    /** 올라온 사진 한 장을 검증·저장하고 출처와 함께 등록한다. 트랜잭션이 되돌려지면 저장한 파일도 지운다. */
    private DestinationImage storeUploadedFile(Long destId, MultipartFile file,
                                               String sourceName, String photographer,
                                               String licenseType, String licenseDetail,
                                               String sourceUrl, String commonSourceUrl,
                                               String workPageUrl,
                                               boolean isMain, boolean isSlide, int orderIndex) {
        // ✅ 실제 이미지 검증 후 저장하고 URL 경로 반환
        String imageUrl = fileUploadService.saveDestinationImage(file);
        registerRollbackCleanup(imageUrl);

        DestinationImage img = new DestinationImage();
        img.setImageUrl(imageUrl);
        img.setSourceName(sourceName);
        img.setPhotographer(photographer);
        img.setLicenseType(licenseType);
        img.setLicenseDetail(licenseDetail);
        img.setSourceUrl(sourceUrl);
        img.setCommonSourceUrl(verifiedPageUrl(commonSourceUrl));
        img.setWorkPageUrl(verifiedPageUrl(workPageUrl));
        img.setIsSlide(isSlide);

        insertImage(destId, img, isMain, orderIndex);
        return img;
    }

    @Transactional
    public void saveImages(Long destId, List<DestinationImage> images) {
        if (images == null || images.isEmpty()) return;

        List<DestinationImage> existingImages = destinationMapper.findImagesByDestinationId(destId);
        int orderIndex = nextOrderIndex(existingImages);
        for (DestinationImage image : images) {
            if (image == null) continue;
            insertImage(destId, image, Boolean.TRUE.equals(image.getIsMain()), orderIndex++);
        }
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           boolean main,
                           boolean slide) {
        saveImages(destId, files, main, slide, null, null, null);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           boolean main,
                           boolean slide,
                           String[] sourceNames,
                           String[] licenseTypes,
                           String[] sourceUrls) {
        saveImages(destId, files, main, slide, sourceNames, null, licenseTypes, null, sourceUrls);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           boolean main,
                           boolean slide,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] sourceUrls) {
        saveImages(destId, files, main, slide,
                sourceNames, photographers, licenseTypes, null, sourceUrls);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           boolean main,
                           boolean slide,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] licenseDetails,
                           String[] sourceUrls) {
        saveImages(destId, files, main, slide, sourceNames, photographers,
                licenseTypes, licenseDetails, sourceUrls, null, null);
    }

    @Transactional
    public void saveImages(Long destId,
                           MultipartFile[] files,
                           boolean main,
                           boolean slide,
                           String[] sourceNames,
                           String[] photographers,
                           String[] licenseTypes,
                           String[] licenseDetails,
                           String[] sourceUrls,
                           String[] commonSourceUrls,
                           String[] workPageUrls) {
        Integer mainIdx = main ? 0 : null;
        Integer[] slideIdx = slide ? allUploadIndexes(files) : new Integer[0];
        saveImages(destId, files, mainIdx, slideIdx,
                sourceNames, photographers, licenseTypes, licenseDetails, sourceUrls,
                commonSourceUrls, workPageUrls);
    }

    public List<DestinationImage> getImages(Long destId) {
        return destinationMapper.findImagesByDestinationId(destId);
    }

    @Transactional
    public void setMainImage(Long destinationId, Long imageId) {
        requireDestinationImage(destinationId, imageId);
        destinationMapper.clearMainImagesByDestinationId(destinationId);
        destinationMapper.setMainImage(imageId);
    }

    /** 파일·대표 지정·출처는 변경하지 않고 해당 대표 이미지의 목록 구도만 저장한다. */
    @Transactional
    public void saveCardPosition(Long destinationId, Long imageId, Integer x, Integer y) {
        if (x == null || y == null || x < 0 || x > 100 || y < 0 || y > 100) {
            throw new IllegalArgumentException("가로·세로 위치는 0~100 사이의 정수여야 합니다.");
        }
        DestinationImage image = requireDestinationImage(destinationId, imageId);
        if (!Boolean.TRUE.equals(image.getIsMain())
                || destinationMapper.updateImageCardPosition(destinationId, imageId, x, y) != 1) {
            throw new IllegalArgumentException("대표 이미지가 변경되었거나 삭제되었습니다. 새로고침 후 다시 조절해 주세요.");
        }
    }

    @Transactional
    public void toggleSlideImage(Long destinationId, Long imageId) {
        DestinationImage image = requireDestinationImage(destinationId, imageId);
        destinationMapper.updateImageSlide(imageId, !Boolean.TRUE.equals(image.getIsSlide()));
    }

    @Transactional
    public void updateImageMetadata(Long destinationId,
                                    Long imageId,
                                    String sourceName,
                                    String photographer,
                                    String licenseType,
                                    String sourceUrl) {
        updateImageMetadata(destinationId, imageId, sourceName, photographer,
                licenseType, null, sourceUrl);
    }

    @Transactional
    public void updateImageMetadataAndPages(Long destinationId, Long imageId,
                                            String sourceName, String photographer,
                                            String licenseType, String licenseDetail,
                                            String sourceUrl, String commonSourceUrl,
                                            String workPageUrl) {
        DestinationImage image = requireDestinationImage(destinationId, imageId);
        updateImageMetadata(destinationId, imageId, sourceName, photographer,
                licenseType, licenseDetail, sourceUrl);
        String common = commonSourceUrl == null ? image.getCommonSourceUrl()
                : verifiedPageUrl(metadataValue(commonSourceUrl));
        String work = workPageUrl == null ? image.getWorkPageUrl()
                : verifiedPageUrl(metadataValue(workPageUrl));
        if (Boolean.TRUE.equals(image.getSourceRecordPresent()) || common != null || work != null) {
            destinationMapper.upsertImageSourcePages(imageId, common, work);
        }
    }

    public record BulkSourceResult(int appliedImageCount, int skippedFieldCount,
                                   List<Long> manualReviewImageIds) { }

    private static final Set<String> BULK_FIELDS = Set.of(
            "sourceName", "photographer", "licenseType", "licenseDetail", "commonSourceUrl");

    @Transactional
    public BulkSourceResult applyBulkSource(Long destinationId, List<Long> imageIds,
                                            String sourceName, String photographer,
                                            String licenseType, String licenseDetail,
                                            String commonSourceUrl, Set<String> overwriteFields,
                                            boolean licenseConfirmed, boolean overwriteConfirmed) {
        if (imageIds == null || imageIds.isEmpty() || imageIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("적용할 사진을 선택해 주세요.");
        }
        Set<String> overwrite = overwriteFields == null ? Set.of() : Set.copyOf(overwriteFields);
        if (!BULK_FIELDS.containsAll(overwrite) || (!overwrite.isEmpty() && !overwriteConfirmed)) {
            throw new IllegalArgumentException("덮어쓸 항목을 선택하고 변경을 확인해 주세요.");
        }
        Map<String, String> values = Map.ofEntries(
                Map.entry("sourceName", metadataValue(sourceName) == null ? "" : metadataValue(sourceName)),
                Map.entry("photographer", metadataValue(photographer) == null ? "" : metadataValue(photographer)),
                Map.entry("licenseType", metadataValue(licenseType) == null ? "" : metadataValue(licenseType)),
                Map.entry("licenseDetail", metadataValue(licenseDetail) == null ? "" : metadataValue(licenseDetail)),
                Map.entry("commonSourceUrl", metadataValue(commonSourceUrl) == null ? "" : metadataValue(commonSourceUrl)));
        if (values.values().stream().allMatch(String::isEmpty)) {
            throw new IllegalArgumentException("적용할 공통 출처 정보를 입력해 주세요.");
        }
        if ((!values.get("licenseType").isEmpty() || !values.get("licenseDetail").isEmpty())
                && !licenseConfirmed) {
            throw new IllegalArgumentException("선택한 모든 사진의 라이선스가 동일한지 확인해 주세요.");
        }
        if (values.get("sourceName").length() > 100 || values.get("photographer").length() > 100
                || values.get("licenseType").length() > 50 || values.get("licenseDetail").length() > 255
                || values.get("commonSourceUrl").length() > 2000) {
            throw new IllegalArgumentException("공통 출처 입력 길이를 확인해 주세요.");
        }
        verifiedPageUrl(values.get("commonSourceUrl").isEmpty() ? null : values.get("commonSourceUrl"));

        int applied = 0;
        int skipped = 0;
        List<Long> manualReview = new ArrayList<>();
        for (Long imageId : new LinkedHashSet<>(imageIds)) {
            DestinationImage image = requireEditableSourceImage(destinationId, imageId);
            Set<String> changed = new LinkedHashSet<>();
            int skippedForImage = 0;
            for (String field : BULK_FIELDS) {
                String value = values.get(field);
                if (value.isEmpty()) continue;
                String current = bulkFieldValue(image, field);
                if (current != null && !current.isBlank() && !overwrite.contains(field)) {
                    if (!current.equals(value)) skippedForImage++;
                    continue;
                }
                if (!value.equals(current)) {
                    setBulkField(image, field, value);
                    changed.add(field);
                }
            }
            if (skippedForImage > 0) manualReview.add(imageId);
            skipped += skippedForImage;
            if (changed.isEmpty()) continue;
            Set<String> legacyFields = new LinkedHashSet<>(changed);
            legacyFields.remove("commonSourceUrl");
            if (!legacyFields.isEmpty()) destinationMapper.updateBulkImageLegacy(image, legacyFields);
            if (Boolean.TRUE.equals(image.getSourceRecordPresent())) {
                destinationMapper.updateBulkImageSource(image, changed);
            } else {
                destinationMapper.insertImageSource(image);
            }
            applied++;
        }
        return new BulkSourceResult(applied, skipped, List.copyOf(manualReview));
    }

    private String bulkFieldValue(DestinationImage image, String field) {
        return switch (field) {
            case "sourceName" -> image.getSourceName();
            case "photographer" -> image.getPhotographer();
            case "licenseType" -> image.getLicenseType();
            case "licenseDetail" -> image.getLicenseDetail();
            case "commonSourceUrl" -> image.getCommonSourceUrl();
            default -> throw new IllegalArgumentException("알 수 없는 출처 항목입니다.");
        };
    }

    private void setBulkField(DestinationImage image, String field, String value) {
        switch (field) {
            case "sourceName" -> image.setSourceName(value);
            case "photographer" -> image.setPhotographer(value);
            case "licenseType" -> image.setLicenseType(value);
            case "licenseDetail" -> image.setLicenseDetail(value);
            case "commonSourceUrl" -> image.setCommonSourceUrl(value);
            default -> throw new IllegalArgumentException("알 수 없는 출처 항목입니다.");
        }
    }

    private String verifiedPageUrl(String value) {
        if (value == null) return null;
        if (value.length() > 2000) throw new InvalidSourceUrlException("출처 URL이 너무 깁니다.");
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null) return value;
        } catch (IllegalArgumentException ignored) {
            // 아래에서 관리자에게 유효한 URL을 요청한다.
        }
        throw new InvalidSourceUrlException("출처 URL은 http 또는 https 주소여야 합니다.");
    }

    public static class InvalidSourceUrlException extends IllegalArgumentException {
        public InvalidSourceUrlException(String message) { super(message); }
    }

    @Transactional
    public void updateImageMetadata(Long destinationId,
                                    Long imageId,
                                    String sourceName,
                                    String photographer,
                                    String licenseType,
                                    String licenseDetail,
                                    String sourceUrl) {
        DestinationImage image = requireEditableSourceImage(destinationId, imageId);
        String normalizedSourceName = metadataValue(sourceName);
        String normalizedPhotographer = metadataValue(photographer);
        String normalizedLicenseType = metadataValue(licenseType);
        String normalizedLicenseDetail = metadataValue(licenseDetail);
        String normalizedSourceUrl = metadataValue(sourceUrl);
        destinationMapper.updateImageMetadata(
                imageId,
                normalizedSourceName,
                normalizedPhotographer,
                normalizedLicenseType,
                normalizedLicenseDetail,
                normalizedSourceUrl);
        if (Boolean.TRUE.equals(image.getSourceRecordPresent())) {
            destinationMapper.upsertImageSourceMetadata(
                    imageId, normalizedSourceName, normalizedPhotographer,
                    normalizedLicenseType, normalizedLicenseDetail, normalizedSourceUrl);
        } else {
            // 백필 전 사진을 처음 수정하는 경우, 화면에서 수정하지 않는 원본 식별자도 보존한다.
            image.setSourceName(normalizedSourceName);
            image.setPhotographer(normalizedPhotographer);
            image.setLicenseType(normalizedLicenseType);
            image.setLicenseDetail(normalizedLicenseDetail);
            image.setSourceUrl(normalizedSourceUrl);
            if (hasSourceMetadata(image)) {
                destinationMapper.insertImageSource(image);
            }
        }
    }

    /**
     * 관리 화면에서 정한 사진 순서를 한 번에 저장한다.
     *
     * <p>요청은 이 여행지의 사진 전부를 원하는 순서대로 담아야 한다. 다른 여행지 사진, 중복, 빠진 사진이 있으면
     * 아무것도 바꾸지 않는다(그 사이 다른 곳에서 사진이 추가·삭제된 경우도 여기서 걸린다).
     * 순서는 기존 규칙대로 0부터 매기며, 대표 이미지·슬라이드 지정은 건드리지 않는다.
     */
    @Transactional
    public void saveImageOrder(Long destinationId, List<Long> orderedImageIds) {
        if (orderedImageIds == null || orderedImageIds.isEmpty()) {
            throw new InvalidImageOrderException("저장할 사진 순서가 없습니다.");
        }
        if (orderedImageIds.stream().anyMatch(java.util.Objects::isNull)
                || new LinkedHashSet<>(orderedImageIds).size() != orderedImageIds.size()) {
            throw new InvalidImageOrderException("같은 사진이 두 번 들어 있거나 비어 있는 항목이 있습니다.");
        }
        List<DestinationImage> images = destinationMapper.findImagesByDestinationId(destinationId);
        Set<Long> currentIds = new LinkedHashSet<>();
        images.forEach(image -> currentIds.add(image.getId()));
        if (!currentIds.equals(new LinkedHashSet<>(orderedImageIds))) {
            throw new InvalidImageOrderException(
                    "이 여행지의 사진 목록이 바뀌었습니다. 새로고침한 뒤 다시 순서를 정해 주세요.");
        }
        Map<Long, Integer> previousOrder = new java.util.HashMap<>();
        images.forEach(image -> previousOrder.put(image.getId(), image.getOrderIndex()));
        for (int orderIndex = 0; orderIndex < orderedImageIds.size(); orderIndex++) {
            Long imageId = orderedImageIds.get(orderIndex);
            if (!Integer.valueOf(orderIndex).equals(previousOrder.get(imageId))) {
                destinationMapper.updateImageOrder(imageId, orderIndex);
            }
        }
    }

    /** 사진 순서 요청이 이 여행지의 현재 사진 목록과 맞지 않는다. */
    public static class InvalidImageOrderException extends IllegalArgumentException {
        public InvalidImageOrderException(String message) { super(message); }
    }

    @Transactional
    public void deleteImage(Long destinationId, Long imageId) {
        DestinationImage image = requireDestinationImage(destinationId, imageId);
        destinationMapper.deleteImageById(imageId);

        List<DestinationImage> remainingImages = destinationMapper
                .findImagesByDestinationId(image.getDestinationId());
        reorderImages(remainingImages);

        if (Boolean.TRUE.equals(image.getIsMain()) && !remainingImages.isEmpty()) {
            destinationMapper.clearMainImagesByDestinationId(image.getDestinationId());
            destinationMapper.setMainImage(remainingImages.get(0).getId());
        }

        deleteFilesAfterCommit(Collections.singletonList(image.getImageUrl()));
    }

    /**
     * 관리 화면에서 고른 사진 여러 장을 한 번에 지운다.
     *
     * <p>하나라도 이 여행지의 사진이 아니면(이미 지워졌거나 다른 여행지 사진) 아무것도 지우지 않는다.
     * 실제 삭제는 단건 삭제를 차례로 적용해 순서 재정렬·대표 이미지 승계(남은 첫 사진)·커밋 후 파일 정리 규칙을 그대로 따른다.
     *
     * @return 지운 사진 수
     */
    @Transactional
    public int deleteImages(Long destinationId, List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty() || imageIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("삭제할 사진을 선택해 주세요.");
        }
        Set<Long> targetIds = new LinkedHashSet<>(imageIds);
        Set<Long> currentIds = new LinkedHashSet<>();
        destinationMapper.findImagesByDestinationId(destinationId)
                .forEach(image -> currentIds.add(image.getId()));
        if (!currentIds.containsAll(targetIds)) {
            throw new IllegalArgumentException(
                    "선택한 사진 중 이미 삭제되었거나 이 여행지의 사진이 아닌 것이 있습니다. 새로고침한 뒤 다시 선택해 주세요.");
        }
        targetIds.forEach(imageId -> deleteImage(destinationId, imageId));
        return targetIds.size();
    }

    public void deleteFilesAfterCommit(List<String> imageUrls) {
        List<String> managedImageUrls = imageUrls == null
                ? List.of()
                : imageUrls.stream()
                .filter(imageUrl -> imageUrl != null && !imageUrl.isBlank())
                .distinct()
                .toList();
        if (managedImageUrls.isEmpty()) {
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFilesSafely(managedImageUrls);
                }
            });
            return;
        }

        deleteFilesSafely(managedImageUrls);
    }

    private int nextOrderIndex(List<DestinationImage> images) {
        return images == null || images.isEmpty()
                ? 0
                : images.stream()
                .map(DestinationImage::getOrderIndex)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }

    private String metadataValue(String[] values, int index) {
        return values == null || index < 0 || index >= values.length
                ? null
                : metadataValue(values[index]);
    }

    private String metadataValue(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private DestinationImage requireDestinationImage(Long destinationId, Long imageId) {
        DestinationImage image = destinationMapper.findImageById(imageId);
        if (image == null || !java.util.Objects.equals(destinationId, image.getDestinationId())) {
            throw new IllegalArgumentException("여행지 이미지를 찾을 수 없습니다.");
        }
        return image;
    }

    /**
     * Commons 사진 출처는 원본에서 재검증한 값, Pixabay 사진 출처는 저장 때 검색 응답에서 기록한 값이라
     * 관리자 출처 수정·일괄 적용 대상에서 뺀다.
     */
    private DestinationImage requireEditableSourceImage(Long destinationId, Long imageId) {
        DestinationImage image = requireDestinationImage(destinationId, imageId);
        if (image.isCommonsImage()) {
            throw new IllegalArgumentException(
                    "Wikimedia Commons 사진의 출처는 원본에서 검증한 값이라 수정할 수 없습니다.");
        }
        if (image.isPixabayImage()) {
            throw new IllegalArgumentException(
                    "Pixabay 사진의 출처는 저장할 때 Pixabay에서 받은 값이라 수정할 수 없습니다.");
        }
        return image;
    }

    private void insertImage(Long destId,
                             DestinationImage image,
                             boolean isMain,
                             int orderIndex) {
        if (isMain) {
            destinationMapper.clearMainImagesByDestinationId(destId);
        }
        image.setDestinationId(destId);
        image.setIsMain(isMain);
        if (image.getIsSlide() == null) {
            image.setIsSlide(false);
        }
        image.setOrderIndex(orderIndex);
        destinationMapper.insertImage(image);
        if (hasSourceMetadata(image)) {
            if (image.getId() == null) {
                throw new IllegalStateException("저장된 여행지 이미지 ID를 확인할 수 없습니다.");
            }
            destinationMapper.insertImageSource(image);
        }
    }

    private boolean hasSourceMetadata(DestinationImage image) {
        return image.getSourceName() != null
                || image.getExternalContentId() != null
                || image.getSourceTitle() != null
                || image.getPhotographer() != null
                || image.getLicenseType() != null
                || image.getLicenseDetail() != null
                || image.getSourceUrl() != null
                || image.getCommonSourceUrl() != null
                || image.getWorkPageUrl() != null
                || image.getSourceImageUrl() != null
                || image.getLicenseCheckedAt() != null;
    }

    private Integer[] allUploadIndexes(MultipartFile[] files) {
        if (files == null || files.length == 0) return new Integer[0];

        int nonEmptyCount = (int) Arrays.stream(files)
                .filter(file -> file != null && !file.isEmpty())
                .count();
        Integer[] indexes = new Integer[nonEmptyCount];
        for (int i = 0; i < nonEmptyCount; i++) {
            indexes[i] = i;
        }
        return indexes;
    }

    private void reorderImages(List<DestinationImage> images) {
        List<DestinationImage> remainingImages = images == null
                ? Collections.emptyList()
                : images;
        for (int orderIndex = 0; orderIndex < remainingImages.size(); orderIndex++) {
            destinationMapper.updateImageOrder(remainingImages.get(orderIndex).getId(), orderIndex);
        }
    }

    private void registerRollbackCleanup(String imageUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    fileUploadService.deleteDestinationFile(imageUrl);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("롤백된 신규 여행지 이미지 파일을 정리하지 못했습니다. (원인: {})",
                            cleanupFailure.getClass().getSimpleName());
                }
            }
        });
    }

    private void deleteFilesSafely(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            try {
                fileUploadService.deleteDestinationFile(imageUrl);
            } catch (RuntimeException cleanupFailure) {
                log.warn("여행지 이미지 파일을 정리하지 못했습니다. (원인: {})",
                        cleanupFailure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 주어진 이미지 주소 중 공공누리 제3유형(변경금지) 사진의 주소. 비어 있는 주소는 무시한다.
     *
     * <p>변경금지 사진은 목록·카드에서도 잘라 보이지 않게 원본 비율로 그리고(contain),
     * 카드용으로 줄이고 잘라 만든 썸네일 파일 대신 원본을 쓴다.
     * 화면에 실제로 그리는 이미지 주소를 기준으로 목록마다 한 번만 조회한다.</p>
     */
    public Set<String> noDerivativeImageUrls(Collection<String> imageUrls) {
        if (imageUrls == null) {
            return Set.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String imageUrl : imageUrls) {
            if (imageUrl != null && !imageUrl.isBlank()) {
                candidates.add(imageUrl);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(destinationMapper.findImageUrlsByLicenseType(
                candidates, DestinationImageLicenseType.KOGL_TYPE_3.name()));
    }

    /**
     * 목록의 변경금지 사진에 표시를 남긴다. 카드 썸네일을 채우기 전에 불러야 원본을 쓸 수 있다.
     *
     * @param imageUrl 카드가 그리는 원본 이미지 주소를 꺼내는 함수
     * @param mark     변경금지 사진일 때만 불린다
     */
    public <T> void markNoDerivatives(List<T> items, Function<T, String> imageUrl,
                                      BiConsumer<T, Boolean> mark) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> noDerivatives = noDerivativeImageUrls(items.stream()
                .filter(Objects::nonNull)
                .map(imageUrl)
                .toList());
        if (noDerivatives.isEmpty()) {
            return;
        }
        for (T item : items) {
            // 불변 Set 은 null 을 물으면 예외를 낸다. 사진 없는 카드는 그냥 넘긴다.
            String url = item == null ? null : imageUrl.apply(item);
            if (url != null && noDerivatives.contains(url)) {
                mark.accept(item, true);
            }
        }
    }
}
