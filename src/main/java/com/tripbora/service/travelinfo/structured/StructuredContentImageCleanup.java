package com.tripbora.service.travelinfo.structured;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 도메인별 참조 조회와 파일 삭제를 받아 사용하는 구조화 이미지 lifecycle. DB에는 직접 접근하지 않는다. */
@Slf4j
public final class StructuredContentImageCleanup {
    private StructuredContentImageCleanup() {
    }

    /** 잘못된 저장 JSON은 정리하지 않는다. 전체 문서가 검증된 경우에만 후보를 만든다. */
    public static Set<String> imageUrls(StructuredContentService service, String json, StructuredImagePolicy policy) {
        return service.collectImageUrls(service.readStored(json, policy));
    }

    public static Set<String> removedImages(Collection<String> previous, Collection<String> current,
                                             StructuredImagePolicy policy) {
        Set<String> removed = new LinkedHashSet<>(previous);
        removed.removeAll(current);
        removed.removeIf(url -> !policy.allows(url));
        return removed;
    }

    /**
     * 도메인 DB 쓰기가 끝난 뒤 호출한다. 참조가 없는 후보만 고르고 commit 후 삭제한다.
     * rollback이면 아무것도 지우지 않으며, 트랜잭션 밖에서는 즉시 정리한다.
     */
    public static void afterCommit(Collection<String> candidates, StructuredImagePolicy policy,
                                   Predicate<String> unreferenced, Consumer<String> deleteImage) {
        Set<String> images = new LinkedHashSet<>();
        for (String url : candidates) {
            if (policy.allows(url) && unreferenced.test(url)) {
                images.add(url);
            }
        }
        if (images.isEmpty()) {
            return;
        }
        Runnable cleanup = () -> {
            for (String url : images) {
                try {
                    deleteImage.accept(url);
                } catch (RuntimeException exception) {
                    log.warn("구조화 본문 이미지 파일을 정리하지 못했습니다: {}", url, exception);
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }
}
