package com.tripbora.service.translation;

import com.tripbora.model.translation.ContentTranslationCache;
import com.tripbora.model.translation.SharedTranslationCache;
import com.tripbora.repository.translation.ContentTranslationCacheMapper;
import com.tripbora.repository.translation.SharedTranslationCacheMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentTranslationService {
    private final TranslationSourceRegistry sourceRegistry;
    private final ContentTranslationCacheMapper cacheMapper;
    private final SharedTranslationCacheMapper sharedCacheMapper;
    private final MachineTranslationClient translationClient;
    private final TranslationRateLimiter rateLimiter;
    private final TranslationProperties properties;
    private final TranslationCacheFinalizer cacheFinalizer;
    private final TranslationUsageReservationGate usageReservation;
    private final TranslationProviderMetadata providerMetadata;
    private final Clock clock = Clock.systemUTC();

    public ContentTranslationResponse translate(TranslatableContentType type,
                                                Long contentId,
                                                String targetLanguage,
                                                String ipAddress,
                                                Long userId) {
        return translate(type, contentId, "content", targetLanguage, ipAddress, userId);
    }

    public ContentTranslationResponse translate(TranslatableContentType type,
                                                Long contentId,
                                                String sourceField,
                                                String targetLanguage,
                                                String ipAddress,
                                                Long userId) {
        rateLimiter.checkRequest(ipAddress, userId);
        TranslationSourceSnapshot source = sourceRegistry.get(type).findVisible(contentId, sourceField)
                .orElseThrow(TranslationNotFoundException::new);
        if (source.text() == null) throw new TranslationNotFoundException();
        if (type != TranslatableContentType.USER_POST
                && type != TranslatableContentType.COURSE
                && source.text().codePointCount(0, source.text().length())
                > properties.maxCommentCharacters()) {
            throw new TranslationTooLongException();
        }
        boolean mixed = !"text/html".equals(source.mimeType())
                && MixedLanguageSupport.shouldTranslate(source.text(), targetLanguage);
        if (!mixed && !TranslationVisibility.shouldOffer(source.sourceLanguage(), targetLanguage)) {
            return ContentTranslationResponse.ready(
                    source.text(), source.sourceLanguage(), targetLanguage, true);
        }

        byte[] sourceHash = sourceHash(source.text());
        ContentTranslationCache cached = findContentCache(source, targetLanguage);
        if (isReadyFor(cached, sourceHash)) {
            return contentCacheResponse(source, targetLanguage, cached);
        }

        Instant now = clock.instant();
        SharedTranslationCache shared = findSharedCache(source, targetLanguage, sourceHash);
        if (isSharedReady(shared)) {
            return reuseShared(source, targetLanguage, sourceHash, shared, now);
        }
        if (isSharedWaiting(shared, now)) {
            return processing(source, targetLanguage, retryAfterSeconds(shared, now));
        }

        String contentLeaseToken = UUID.randomUUID().toString();
        if (!tryAcquireContentLease(
                source, targetLanguage, sourceHash, contentLeaseToken, now)) {
            ContentTranslationCache current = findContentCache(source, targetLanguage);
            if (isReadyFor(current, sourceHash)) {
                return contentCacheResponse(source, targetLanguage, current);
            }
            shared = findSharedCache(source, targetLanguage, sourceHash);
            if (isSharedReady(shared)) {
                return reuseShared(source, targetLanguage, sourceHash, shared, clock.instant());
            }
            long retryAfter = shared == null
                    ? retryAfterSeconds(current, now) : retryAfterSeconds(shared, now);
            return processing(source, targetLanguage, retryAfter);
        }

        shared = findSharedCache(source, targetLanguage, sourceHash);
        if (isSharedReady(shared)) {
            return reuseShared(source, targetLanguage, sourceHash, shared, clock.instant());
        }
        if (isSharedWaiting(shared, now)) {
            return processing(source, targetLanguage, retryAfterSeconds(shared, now));
        }

        String sharedLeaseToken = UUID.randomUUID().toString();
        if (!tryAcquireSharedLease(
                source, targetLanguage, sourceHash, sharedLeaseToken, now)) {
            SharedTranslationCache current = findSharedCache(source, targetLanguage, sourceHash);
            if (isSharedReady(current)) {
                return reuseShared(source, targetLanguage, sourceHash, current, clock.instant());
            }
            return processing(source, targetLanguage, retryAfterSeconds(current, now));
        }

        try {
            rateLimiter.checkExternalCall(ipAddress, userId);
        } catch (TranslationRateLimitException e) {
            releaseSharedLease(source, targetLanguage, sourceHash, sharedLeaseToken);
            markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    now.plusSeconds(e.retryAfterSeconds()), now);
            throw e;
        }

        try {
            translationClient.prepare();
        } catch (RuntimeException e) {
            markBothFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    sharedLeaseToken, now.plus(properties.failureRetryAfter()), now);
            throw providerException(e);
        }

        try {
            usageReservation.reserve(source.text(), ipAddress, userId);
        } catch (TranslationDailyLimitException | TranslationMonthlyLimitException e) {
            releaseSharedLease(source, targetLanguage, sourceHash, sharedLeaseToken);
            markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    now.plus(properties.failureRetryAfter()), now);
            throw e;
        } catch (RuntimeException e) {
            releaseSharedLease(source, targetLanguage, sourceHash, sharedLeaseToken);
            markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    now.plus(properties.failureRetryAfter()), now);
            throw new MachineTranslationException("번역 사용량 확인에 실패했습니다.", e);
        }

        MachineTranslation translated;
        try {
            translated = translationClient.translate(
                    source.text(), mixed ? MixedLanguageSupport.sourceHint(source.text(), targetLanguage, source.sourceLanguage())
                            : source.sourceLanguage(), targetLanguage, source.mimeType());
            if (mixed && (translated.translatedText() == null || translated.translatedText().isBlank()
                    || unchangedMixed(source,targetLanguage,translated.translatedText()))) {
                throw new MachineTranslationException("혼합 문장의 번역 결과를 확인할 수 없습니다.");
            }
        } catch (RuntimeException e) {
            Instant failedAt = clock.instant();
            markBothFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    sharedLeaseToken, failedAt.plus(properties.failureRetryAfter()), failedAt);
            throw providerException(e);
        }

        Timestamp completedAt = Timestamp.from(clock.instant());
        var sharedKey = SharedTranslationKey.forSource(source,sourceHash,providerMetadata);
        int sharedReady = sharedCacheMapper.markReady(
                sharedKey.hash(), source.sourceLanguage(), targetLanguage,
                providerMetadata.provider(), sharedKey.profile(), sharedLeaseToken,
                translated.translatedText(), translated.detectedSourceLanguage(), completedAt);
        if (sharedReady != 1) {
            SharedTranslationCache current = findSharedCache(source, targetLanguage, sourceHash);
            if (isSharedReady(current)) {
                return reuseShared(source, targetLanguage, sourceHash, current, clock.instant());
            }
            markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    clock.instant(), clock.instant());
            throw new TranslationStaleException();
        }

        TranslationFinalization finalization = cacheFinalizer.markReady(
                type, contentId, source.sourceField(), targetLanguage, sourceHash,
                source.sourceLanguage(), contentLeaseToken, translated, completedAt);
        if (finalization.status() != TranslationFinalization.Status.READY) {
            markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken,
                    clock.instant(), clock.instant());
            if (finalization.status() == TranslationFinalization.Status.NOT_FOUND) {
                throw new TranslationNotFoundException();
            }
            throw new TranslationStaleException();
        }
        TranslationSourceSnapshot currentSource = finalization.source();
        return ContentTranslationResponse.ready(
                translated.translatedText(), providerSourceLanguage(currentSource, translated),
                targetLanguage, false);
    }

    private ContentTranslationCache findContentCache(TranslationSourceSnapshot source,
                                                     String targetLanguage) {
        var cached = cacheMapper.find(source.contentType().name(), source.contentId(),
                source.sourceField(), targetLanguage);
        if (cached != null && "READY".equals(cached.getStatus()) && unchangedMixed(source, targetLanguage, cached.getTranslatedText())) {
            cacheMapper.invalidateUnchangedReady(source.contentType().name(),source.contentId(),source.sourceField(),
                    targetLanguage,sourceHash(source.text()),cached.getTranslatedText());
            return null;
        }
        return cached;
    }

    private SharedTranslationCache findSharedCache(TranslationSourceSnapshot source,
                                                   String targetLanguage,
                                                   byte[] sourceHash) {
        var key = SharedTranslationKey.forSource(source,sourceHash,providerMetadata);
        var cached = readSharedCache(source,targetLanguage,key);
        if (cached != null || !key.normalized()) return cached;
        // 기존 캐시는 삭제하지 않는다. 완전 일치 READY는 정규화 키의 별칭으로도 재사용한다.
        var legacy = readSharedCache(source,targetLanguage,
                new SharedTranslationKey(sourceHash,providerMetadata.profileFor(source.mimeType()),false));
        if (legacy == null && !Arrays.equals(sourceHash,key.hash())) {
            // 이전 버전에 공백이 정리된 원문으로 저장된 캐시도 정확한 hash 조회로 찾는다.
            legacy = readSharedCache(source,targetLanguage,
                    new SharedTranslationKey(key.hash(),providerMetadata.profileFor(source.mimeType()),false));
        }
        if (!isSharedReady(legacy)) return legacy;
        var alias = new SharedTranslationCache();
        alias.setSourceHash(key.hash()); alias.setSourceLanguage(source.sourceLanguage());
        alias.setTargetLanguage(targetLanguage); alias.setProvider(providerMetadata.provider());
        alias.setTranslationProfile(key.profile()); alias.setTranslatedText(legacy.getTranslatedText());
        alias.setDetectedSourceLanguage(legacy.getDetectedSourceLanguage());
        alias.setCreatedAt(Timestamp.from(clock.instant())); alias.setUpdatedAt(alias.getCreatedAt());
        sharedCacheMapper.insertReadyIfAbsent(alias);
        // INSERT IGNORE는 다른 요청의 활성 lease를 덮어쓰지 않는다.
        var current = readSharedCache(source,targetLanguage,key);
        return current == null ? legacy : current;
    }

    private SharedTranslationCache readSharedCache(TranslationSourceSnapshot source,
                                                   String targetLanguage, SharedTranslationKey key) {
        var cached = sharedCacheMapper.find(
                key.hash(), source.sourceLanguage(), targetLanguage,
                providerMetadata.provider(), key.profile());
        if (cached != null && "READY".equals(cached.getStatus()) && unchangedMixed(source,targetLanguage,cached.getTranslatedText())) {
            sharedCacheMapper.invalidateUnchangedReady(key.hash(),source.sourceLanguage(),targetLanguage,
                    providerMetadata.provider(),key.profile(),cached.getTranslatedText());
            return null;
        }
        return cached;
    }

    private boolean unchangedMixed(TranslationSourceSnapshot source, String targetLanguage, String translatedText) {
        if ("text/html".equals(source.mimeType()) || translatedText == null
                || !MixedLanguageSupport.shouldTranslate(source.text(),targetLanguage)) return false;
        var original = SharedTranslationTextNormalizer.normalize(source.text(),source.mimeType());
        var translated = SharedTranslationTextNormalizer.normalize(translatedText,source.mimeType());
        return source.text().strip().equals(translatedText.strip())
                || (original.isPresent() && original.equals(translated));
    }

    private ContentTranslationResponse contentCacheResponse(
            TranslationSourceSnapshot source,
            String targetLanguage,
            ContentTranslationCache cache) {
        return ContentTranslationResponse.ready(
                cache.getTranslatedText(), effectiveSourceLanguage(source, cache),
                targetLanguage, true);
    }

    private ContentTranslationResponse reuseShared(
            TranslationSourceSnapshot source,
            String targetLanguage,
            byte[] sourceHash,
            SharedTranslationCache shared,
            Instant now) {
        MachineTranslation translation = new MachineTranslation(
                shared.getTranslatedText(), shared.getDetectedSourceLanguage());
        TranslationFinalization finalization = cacheFinalizer.upsertReady(
                source.contentType(), source.contentId(), source.sourceField(), targetLanguage,
                sourceHash, source.sourceLanguage(), shared.getProvider(), translation,
                Timestamp.from(now));
        if (finalization.status() == TranslationFinalization.Status.NOT_FOUND) {
            throw new TranslationNotFoundException();
        }
        if (finalization.status() != TranslationFinalization.Status.READY) {
            throw new TranslationStaleException();
        }
        return ContentTranslationResponse.ready(
                shared.getTranslatedText(), providerSourceLanguage(finalization.source(), translation),
                targetLanguage, true);
    }

    private ContentTranslationResponse processing(TranslationSourceSnapshot source,
                                                  String targetLanguage,
                                                  long retryAfterSeconds) {
        return ContentTranslationResponse.processing(
                source.sourceLanguage(), targetLanguage, retryAfterSeconds);
    }

    private boolean tryAcquireContentLease(TranslationSourceSnapshot source,
                                           String targetLanguage,
                                           byte[] sourceHash,
                                           String leaseToken,
                                           Instant now) {
        ContentTranslationCache cache = new ContentTranslationCache();
        cache.setContentType(source.contentType().name());
        cache.setContentId(source.contentId());
        cache.setSourceField(source.sourceField());
        cache.setTargetLanguage(targetLanguage);
        cache.setSourceHash(sourceHash);
        cache.setProvider(providerMetadata.provider());
        cache.setLeaseToken(leaseToken);
        cache.setLeaseExpiresAt(Timestamp.from(now.plus(properties.leaseDuration())));
        cache.setCreatedAt(Timestamp.from(now));
        cache.setUpdatedAt(Timestamp.from(now));
        if (cacheMapper.insertProcessing(cache) == 1) return true;
        return cacheMapper.tryClaim(
                source.contentType().name(), source.contentId(), source.sourceField(), targetLanguage,
                sourceHash, leaseToken, Timestamp.from(now),
                Timestamp.from(now.plus(properties.leaseDuration()))) == 1;
    }

    private boolean tryAcquireSharedLease(TranslationSourceSnapshot source,
                                          String targetLanguage,
                                          byte[] sourceHash,
                                          String leaseToken,
                                          Instant now) {
        var key = SharedTranslationKey.forSource(source,sourceHash,providerMetadata);
        SharedTranslationCache cache = new SharedTranslationCache();
        cache.setSourceHash(key.hash());
        cache.setSourceLanguage(source.sourceLanguage());
        cache.setTargetLanguage(targetLanguage);
        cache.setProvider(providerMetadata.provider());
        cache.setTranslationProfile(key.profile());
        cache.setLeaseToken(leaseToken);
        cache.setLeaseExpiresAt(Timestamp.from(now.plus(properties.leaseDuration())));
        cache.setCreatedAt(Timestamp.from(now));
        cache.setUpdatedAt(Timestamp.from(now));
        if (sharedCacheMapper.insertProcessing(cache) == 1) return true;
        return sharedCacheMapper.tryClaim(
                key.hash(), source.sourceLanguage(), targetLanguage,
                providerMetadata.provider(), key.profile(), leaseToken,
                Timestamp.from(now), Timestamp.from(now.plus(properties.leaseDuration()))) == 1;
    }

    private void markBothFailed(TranslationSourceSnapshot source,
                                String targetLanguage,
                                byte[] sourceHash,
                                String contentLeaseToken,
                                String sharedLeaseToken,
                                Instant retryAfter,
                                Instant now) {
        var key = SharedTranslationKey.forSource(source,sourceHash,providerMetadata);
        sharedCacheMapper.markFailed(
                key.hash(), source.sourceLanguage(), targetLanguage,
                providerMetadata.provider(), key.profile(), sharedLeaseToken,
                Timestamp.from(retryAfter), Timestamp.from(now));
        markContentFailed(source, targetLanguage, sourceHash, contentLeaseToken, retryAfter, now);
    }

    private void markContentFailed(TranslationSourceSnapshot source,
                                   String targetLanguage,
                                   byte[] sourceHash,
                                   String leaseToken,
                                   Instant retryAfter,
                                   Instant now) {
        cacheMapper.markFailed(
                source.contentType().name(), source.contentId(), source.sourceField(), targetLanguage,
                sourceHash, leaseToken, Timestamp.from(retryAfter), Timestamp.from(now));
    }

    private void releaseSharedLease(TranslationSourceSnapshot source,
                                    String targetLanguage,
                                    byte[] sourceHash,
                                    String sharedLeaseToken) {
        var key = SharedTranslationKey.forSource(source,sourceHash,providerMetadata);
        sharedCacheMapper.deleteProcessing(
                key.hash(), source.sourceLanguage(), targetLanguage,
                providerMetadata.provider(), key.profile(), sharedLeaseToken);
    }

    private boolean isReadyFor(ContentTranslationCache cache, byte[] sourceHash) {
        return cache != null
                && "READY".equals(cache.getStatus())
                && cache.getTranslatedText() != null
                && Arrays.equals(cache.getSourceHash(), sourceHash);
    }

    private boolean isSharedReady(SharedTranslationCache cache) {
        return cache != null
                && "READY".equals(cache.getStatus())
                && cache.getTranslatedText() != null;
    }

    private boolean isSharedWaiting(SharedTranslationCache cache, Instant now) {
        if (cache == null) return false;
        if ("PROCESSING".equals(cache.getStatus())) {
            return cache.getLeaseExpiresAt() != null
                    && cache.getLeaseExpiresAt().toInstant().isAfter(now);
        }
        if ("FAILED".equals(cache.getStatus())) {
            return cache.getRetryAfter() != null
                    && cache.getRetryAfter().toInstant().isAfter(now);
        }
        return false;
    }

    private long retryAfterSeconds(ContentTranslationCache cache, Instant now) {
        Timestamp retryAt = cache == null ? null
                : "FAILED".equals(cache.getStatus()) ? cache.getRetryAfter() : cache.getLeaseExpiresAt();
        return retryAfterSeconds(retryAt, now);
    }

    private long retryAfterSeconds(SharedTranslationCache cache, Instant now) {
        Timestamp retryAt = cache == null ? null
                : "FAILED".equals(cache.getStatus()) ? cache.getRetryAfter() : cache.getLeaseExpiresAt();
        return retryAfterSeconds(retryAt, now);
    }

    private long retryAfterSeconds(Timestamp retryAt, Instant now) {
        if (retryAt == null) return 1L;
        long millis = Duration.between(now, retryAt.toInstant()).toMillis();
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private String effectiveSourceLanguage(TranslationSourceSnapshot source,
                                           ContentTranslationCache cache) {
        return cache.getDetectedSourceLanguage() == null
                ? source.sourceLanguage() : cache.getDetectedSourceLanguage();
    }

    private String providerSourceLanguage(TranslationSourceSnapshot source,
                                          MachineTranslation translated) {
        return translated.detectedSourceLanguage() == null
                ? source.sourceLanguage() : translated.detectedSourceLanguage();
    }

    private MachineTranslationException providerException(RuntimeException exception) {
        if (exception instanceof MachineTranslationException machineTranslationException) {
            return machineTranslationException;
        }
        return new MachineTranslationException("번역 provider 요청에 실패했습니다.", exception);
    }

    static byte[] sourceHash(String sourceText) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(sourceText.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
