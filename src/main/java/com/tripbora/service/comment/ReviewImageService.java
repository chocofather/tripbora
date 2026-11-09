package com.tripbora.service.comment;

import com.tripbora.dto.ReviewImageDto;
import com.tripbora.dto.ReviewImageAdminDto;
import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.policy.GalleryConsentService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 리뷰 공개와 별개인, 향후 상세 갤러리 이용 동의 및 심사. */
@Service
@RequiredArgsConstructor
public class ReviewImageService {
    public static final String CONSENT_VERSION = "review-gallery-20261030-v1";
    private static final Set<String> STATUSES = Set.of("NONE", "PENDING", "APPROVED", "REJECTED", "REVOKED");
    private final DestinationCommentImageMapper images;
    private final DestinationCommentMapper comments;
    private final UserMapper users;
    private final FileUploadService files;
    private GalleryConsentService galleryConsentService;

    @Autowired
    public void setGalleryConsentService(GalleryConsentService galleryConsentService) {
        this.galleryConsentService = galleryConsentService;
    }

    /** 본문과 첨부를 한 트랜잭션에서 변경한다. 취소/실패 시 기존 첨부는 그대로 남는다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void edit(Long commentId, Long userId, String content, List<Long> removeImageIds,
                     List<MultipartFile> uploads) {
        edit(commentId, userId, content, removeImageIds, uploads, null, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void edit(Long commentId, Long userId, String content, List<Long> removeImageIds,
                     List<MultipartFile> uploads, Integer rating, boolean ratingSpecified) {
        edit(commentId, userId, content, removeImageIds, uploads, rating, ratingSpecified, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void edit(Long commentId, Long userId, String content, List<Long> removeImageIds,
                     List<MultipartFile> uploads, Integer rating, boolean ratingSpecified, Long photoUploadPolicyVersionId) {
        DestinationComment comment = owned(commentId, userId, true);
        new DestinationReviewPolicy(comments, images).edit(comment, rating, ratingSpecified, content);
        List<DestinationCommentImage> existing = images.findByCommentIdForUpdate(commentId);
        Set<Long> removed = new HashSet<>(removeImageIds == null ? List.of() : removeImageIds);
        if (removed.contains(null) || (removeImageIds != null && removed.size() != removeImageIds.size())
                || !existing.stream().map(DestinationCommentImage::getId).toList().containsAll(removed))
            throw error(HttpStatus.BAD_REQUEST, "삭제할 사진의 댓글 소속을 확인해 주세요.");
        List<MultipartFile> incoming = uploads == null ? List.of() : uploads.stream()
                .filter(Objects::nonNull).filter(f -> !f.isEmpty()).toList();
        if (existing.size() - removed.size() + incoming.size() > DestinationCommentService.MAX_COMMENT_IMAGES)
            throw error(HttpStatus.BAD_REQUEST, "사진은 댓글당 최대 5장까지 첨부할 수 있습니다.");
        GalleryConsentService.UploadLicenseGrant license = incoming.isEmpty() ? null : uploadLicense(userId, photoUploadPolicyVersionId);
        incoming.forEach(file -> validateDimensions(files, file));
        List<String> written = new ArrayList<>();
        rollbackCleanup(written, files);
        try {
            for (DestinationCommentImage image : existing) {
                if (removed.contains(image.getId())) {
                    if (images.deleteById(image.getId()) != 1) throw error(HttpStatus.CONFLICT, "사진이 변경되었습니다.");
                    deleteAfterCommit(image.getImageUrl());
                }
            }
            Set<Integer> used = new HashSet<>();
            existing.stream().filter(i -> !removed.contains(i.getId())).forEach(i -> used.add(i.getDisplayOrder()));
            for (MultipartFile file : incoming) {
                String url = files.saveFile(file, "comments");
                written.add(url);
                int slot = 1;
                while (used.contains(slot)) slot++;
                used.add(slot);
                DestinationCommentImage image = new DestinationCommentImage();
                image.setCommentId(commentId);
                image.setImageUrl(url);
                image.setDisplayOrder(slot);
                galleryConsentService.applyToNewImage(image, license);
                if (images.insert(image) != 1) throw new IllegalStateException("사진 저장 실패");
            }
            comment.setContent(content);
            comment.setSourceLanguage(new LocalContentLanguageDetector().detect(content).code());
            comment.setUpdatedAt(new java.sql.Timestamp(System.currentTimeMillis()));
            comments.updateContent(comment);
        } catch (RuntimeException failure) {
            written.forEach(url -> deleteQuietly(files, url));
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public List<ReviewImageAdminDto> myPhotos(Long userId, int page) {
        if (userId == null) throw error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return images.findOwnerPhotos(userId, Math.min(Math.max(page, 0), 100000) * 20, 20);
    }

    @Transactional
    public List<ReviewImageDto> ownerImages(Long commentId, Long userId) {
        owned(commentId, userId, false);
        return images.findByCommentIdForUpdate(commentId).stream()
                .map(i -> new ReviewImageDto(i.getId(), i.getImageUrl(), i.getGalleryStatus(), i.getReviewReason())).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void add(Long commentId, Long userId, List<MultipartFile> uploads) {
        add(commentId, userId, uploads, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void add(Long commentId, Long userId, List<MultipartFile> uploads, Long photoUploadPolicyVersionId) {
        owned(commentId, userId, true);
        List<MultipartFile> incoming = uploads == null ? List.of() : uploads.stream()
                .filter(Objects::nonNull).filter(f -> !f.isEmpty()).toList();
        List<DestinationCommentImage> existing = images.findByCommentIdForUpdate(commentId);
        if (incoming.isEmpty() || existing.size() + incoming.size() > DestinationCommentService.MAX_COMMENT_IMAGES) {
            throw error(HttpStatus.BAD_REQUEST, "사진은 댓글당 최대 5장까지 첨부할 수 있습니다.");
        }
        GalleryConsentService.UploadLicenseGrant license = uploadLicense(userId, photoUploadPolicyVersionId);
        Set<Integer> used = new HashSet<>();
        existing.forEach(i -> used.add(i.getDisplayOrder()));
        List<String> written = new ArrayList<>();
        rollbackCleanup(written, files);
        try {
            for (MultipartFile file : incoming) {
                validateDimensions(files, file);
                String url = files.saveFile(file, "comments");
                written.add(url);
                int slot = 1;
                while (used.contains(slot)) slot++;
                if (slot > DestinationCommentService.MAX_COMMENT_IMAGES) throw error(HttpStatus.CONFLICT, "사진 목록이 변경되었습니다.");
                used.add(slot);
                DestinationCommentImage image = new DestinationCommentImage();
                image.setCommentId(commentId);
                image.setImageUrl(url);
                image.setDisplayOrder(slot);
                galleryConsentService.applyToNewImage(image, license);
                if (images.insert(image) != 1) throw new IllegalStateException("사진 저장 실패");
            }
        } catch (RuntimeException failure) {
            written.forEach(url -> deleteQuietly(files, url));
            throw failure;
        }
    }

    private GalleryConsentService.UploadLicenseGrant uploadLicense(Long userId, Long expectedVersionId) {
        if (galleryConsentService == null)
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "사진 업로드 이용조건을 확인할 수 없습니다.");
        return galleryConsentService.requireUploadLicense(userId, expectedVersionId);
    }

    @Transactional
    public void remove(Long commentId, Long imageId, Long userId) {
        owned(commentId, userId, false);
        DestinationCommentImage image = attached(commentId, imageId);
        if (images.deleteById(imageId) != 1) throw error(HttpStatus.CONFLICT, "사진이 변경되었습니다.");
        deleteAfterCommit(image.getImageUrl());
    }

    private void deleteAfterCommit(String url) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deleteIfUnreferenced(url); }
            });
        } else deleteIfUnreferenced(url);
    }

    private void deleteIfUnreferenced(String url) {
        try {
            if (!images.isReferenced(url)) files.deleteCommentFile(url);
        } catch (RuntimeException failure) { /* DB/파일 확인 실패는 주기적 대조에서 재처리한다. */ }
    }

    @Transactional
    public void consent(Long commentId, Long imageId, Long userId, boolean consent, boolean rightsConfirmed) {
        if (galleryConsentService != null) throw error(HttpStatus.GONE, "갤러리 활용 동의는 마이페이지에서 한 번만 설정해 주세요.");
        owned(commentId, userId, consent);
        DestinationCommentImage image = attached(commentId, imageId);
        if (consent && !rightsConfirmed) throw error(HttpStatus.BAD_REQUEST, "촬영 또는 적법한 사용 권한을 확인해 주세요.");
        // 이미 유효한 동의는 심사 결과를 초기화하지 않는다. 철회 후 재동의만 새 심사다.
        if (consent && image.getGalleryConsentAt() != null && image.getGalleryConsentWithdrawnAt() == null
                && !Set.of("NONE", "REVOKED").contains(image.getGalleryStatus())) return;
        images.updateConsent(imageId, consent, CONSENT_VERSION);
    }

    @Transactional(readOnly = true)
    public List<ReviewImageAdminDto> list(String status, int page) {
        if (!STATUSES.contains(status)) throw error(HttpStatus.BAD_REQUEST, "지원하지 않는 상태입니다.");
        return images.listForAdmin(status, Math.min(Math.max(page, 0), 100000) * 20, 20);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void review(Long imageId, Long adminId, String action, String reason) {
        User admin = adminId == null ? null : users.findById(adminId);
        if (admin == null || admin.getUserRole() != UserRole.ADMIN || admin.getStatus() != UserStatus.ACTIVE)
            throw error(HttpStatus.FORBIDDEN, "관리자만 심사할 수 있습니다.");
        DestinationCommentImage located = images.findById(imageId);
        if (located == null) throw error(HttpStatus.NOT_FOUND, "사진을 찾을 수 없습니다.");
        DestinationComment hint = comments.findById(located.getCommentId());
        if (hint == null) throw error(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다.");
        User owner = hint.getUserId() == null ? null : images.lockUser(hint.getUserId());
        Destination destination = images.lockDestination(hint.getDestinationId());
        DestinationComment comment = images.lockComment(hint.getId());
        if (comment == null) throw error(HttpStatus.CONFLICT, "댓글이 삭제되었습니다.");
        if (!Objects.equals(comment.getUserId(), hint.getUserId()) || !Objects.equals(comment.getDestinationId(), hint.getDestinationId()))
            throw error(HttpStatus.CONFLICT, "사진 소속이 변경되었습니다.");
        DestinationCommentImage image = attached(comment.getId(), imageId);
        if (Set.of("APPROVE", "KEEP").contains(action == null ? "" : action)) {
            if (galleryConsentService != null) galleryConsentService.requirePhotoValid(imageId);
            else if (!images.photoRights(imageId))
                throw error(HttpStatus.CONFLICT, "사진의 원본 동의 근거 또는 공개 상태가 변경되었습니다.");
        }
        String note = reason == null ? "" : reason.strip();
        if (note.length() > 500) throw error(HttpStatus.BAD_REQUEST, "사유는 500자 이하로 입력해 주세요.");
        String status;
        if ("KEEP".equals(action)) {
            if (!"APPROVED".equals(image.getGalleryStatus()) || owner==null || owner.getStatus()!=UserStatus.ACTIVE
                    || destination==null || !destination.isPublicVisible() || Boolean.TRUE.equals(comment.getDeleted()) || comment.isModerated()
                    || comment.getParentCommentId()!=null)
                throw error(HttpStatus.CONFLICT,"대표 사진의 동의 또는 공개 상태가 변경되었습니다.");
            return;
        }
        switch (action == null ? "" : action) {
            case "APPROVE" -> {
                if (!"PENDING".equals(image.getGalleryStatus())
                        || owner == null || owner.getStatus() != UserStatus.ACTIVE
                        || destination == null || !destination.isPublicVisible() || Boolean.TRUE.equals(comment.getDeleted())
                        || comment.isModerated() || comment.getParentCommentId()!=null)
                    throw error(HttpStatus.CONFLICT, "동의 또는 공개 상태가 변경되어 심사할 수 없습니다.");
                status = "APPROVED";
            }
            case "REJECT" -> {
                if (!"PENDING".equals(image.getGalleryStatus()))
                    throw error(HttpStatus.CONFLICT, "대기 사진만 반려할 수 있습니다.");
                if (note.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "반려 사유를 입력해 주세요.");
                status = "REJECTED";
            }
            case "REVOKE" -> {
                if (!"APPROVED".equals(image.getGalleryStatus())) throw error(HttpStatus.CONFLICT, "승인된 사진만 취소할 수 있습니다.");
                status = "REVOKED";
            }
            default -> throw error(HttpStatus.BAD_REQUEST, "지원하지 않는 심사입니다.");
        }
        images.review(imageId, status, adminId, note.isEmpty() ? null : note);
    }

    private DestinationComment owned(Long commentId, Long userId, boolean requirePublic) {
        DestinationComment hint = comments.findById(commentId);
        if (hint == null) throw error(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다.");
        if (userId == null || !userId.equals(hint.getUserId())) throw error(HttpStatus.FORBIDDEN, "본인 사진만 관리할 수 있습니다.");
        User user = images.lockUser(userId);
        Destination destination = images.lockDestination(hint.getDestinationId());
        DestinationComment comment = images.lockComment(commentId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) throw error(HttpStatus.FORBIDDEN, "활성 회원만 관리할 수 있습니다.");
        if (comment == null || !userId.equals(comment.getUserId())) throw error(HttpStatus.CONFLICT, "댓글이 변경되었습니다.");
        if (requirePublic && (Boolean.TRUE.equals(comment.getDeleted()) || destination == null || !destination.isPublicVisible()))
            throw error(HttpStatus.CONFLICT, "공개 댓글에서만 사진을 추가하거나 동의할 수 있습니다.");
        return comment;
    }

    private DestinationCommentImage attached(Long commentId, Long imageId) {
        return images.findByCommentIdForUpdate(commentId).stream().filter(i -> Objects.equals(i.getId(), imageId))
                .findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "댓글에 속한 사진을 찾을 수 없습니다."));
    }

    static void validateDimensions(FileUploadService files, MultipartFile file) {
        var image = files.validatedGeneralImageDetails(file);
        if (Math.max(image.width(), image.height()) > 20000 || (long) image.width() * image.height() > 40000000L)
            throw error(HttpStatus.BAD_REQUEST, "사진은 한 변 20000px, 4000만 화소 이하여야 합니다.");
    }

    static void rollbackCleanup(List<String> written, FileUploadService files) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) written.forEach(url -> deleteQuietly(files, url));
                }
            });
        }
    }

    private static void deleteQuietly(FileUploadService files, String url) {
        try { files.deleteCommentFile(url); }
        catch (RuntimeException failure) { /* 주기적 미참조 파일 정리에서 재시도한다. */ }
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
