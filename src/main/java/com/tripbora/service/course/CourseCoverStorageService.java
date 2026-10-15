package com.tripbora.service.course;

import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.file.UnsupportedImageFormatException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 파일 URL은 서버에서만 만든다. 기존 cover는 소유 Course의 DB 값에서만 받는다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseCoverStorageService {
    private final FileUploadService files;

    public String save(MultipartFile image, Long userId) {
        if (image == null || image.isEmpty()) return null;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Course 대표 이미지 저장에는 트랜잭션이 필요합니다.");
        }
        String url;
        try {
            url = files.saveFile(image, directory(userId));
        } catch (UnsupportedImageFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) delete(url, userId);
            }
        });
        return url;
    }

    public void removeAfterCommit(String previousUrl, Long userId) {
        if (previousUrl == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Course 대표 이미지 정리에는 트랜잭션이 필요합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(previousUrl, userId);
            }
        });
    }

    private void delete(String url, Long userId) {
        try {
            // UUID 파일명, 정확한 사용자별 cover namespace와 루트 포함 여부를 공통 서비스가 검증한다.
            files.deleteSavedFile(url, directory(userId));
        } catch (RuntimeException error) {
            log.warn("Course 대표 이미지 파일 정리 실패", error);
        }
    }

    private String directory(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("사용자 확인이 필요합니다.");
        return "course/cover/" + userId;
    }
}
