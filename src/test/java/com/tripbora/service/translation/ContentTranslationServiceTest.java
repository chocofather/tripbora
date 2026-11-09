package com.tripbora.service.translation;

import com.tripbora.model.translation.ContentTranslationCache;
import com.tripbora.repository.translation.ContentTranslationCacheMapper;
import com.tripbora.repository.translation.SharedTranslationCacheMapper;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentTranslationServiceTest {
    private static final Timestamp UPDATED_AT = Timestamp.from(Instant.parse("2026-09-09T01:00:00Z"));

    @Test
    void readyHashMatchReturnsCacheWithoutCallingProvider() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        MachineTranslationClient client = mock(MachineTranslationClient.class);
        CountingUsageReservationGate monthlyUsage = new CountingUsageReservationGate();
        ContentTranslationCache cached = cache("원문 댓글", "READY", "Translated once");
        when(mapper.find("DESTINATION_COMMENT", 7L, "content", "en")).thenReturn(cached);

        ContentTranslationResponse response = service(reader, mapper, client, monthlyUsage)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", 3L);

        assertThat(response.translatedText()).isEqualTo("Translated once");
        assertThat(response.cached()).isTrue();
        assertThat(monthlyUsage.calls).isZero();
        verify(client, never()).translate(anyString(), anyString(), anyString());
    }

    @Test
    void undeterminedSourceNeverCallsCacheOrExternalProvider() {
        MutableSourceReader reader = new MutableSourceReader(source("OK", "und"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        MachineTranslationClient client = mock(MachineTranslationClient.class);

        ContentTranslationResponse response = service(reader, mapper, client)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null);

        assertThat(response.translatedText()).isEqualTo("OK");
        verify(mapper, never()).find(anyString(), any(), anyString(), anyString());
        verify(client, never()).translate(anyString(), anyString(), anyString());
    }

    @Test
    void cacheMissCallsProviderAndStoresReadyResult() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        MachineTranslationClient client = (text, source, target) ->
                new MachineTranslation("Translated comment", "ko");
        CountingUsageReservationGate monthlyUsage = new CountingUsageReservationGate();
        when(mapper.insertProcessing(any())).thenReturn(1);
        when(mapper.markReady(anyString(), eq(7L), eq("content"), eq("en"), any(), anyString(),
                eq("Translated comment"), eq("ko"), any())).thenReturn(1);

        ContentTranslationResponse response = service(reader, mapper, client, monthlyUsage)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null);

        assertThat(response.translatedText()).isEqualTo("Translated comment");
        assertThat(response.cached()).isFalse();
        assertThat(monthlyUsage.calls).isEqualTo(1);
        assertThat(monthlyUsage.characters).isEqualTo(5L);
        assertThat(monthlyUsage.lastIpAddress).isEqualTo("127.0.0.1");
        assertThat(monthlyUsage.lastUserId).isNull();
        verify(mapper).markReady(anyString(), eq(7L), eq("content"), eq("en"), any(), anyString(),
                eq("Translated comment"), eq("ko"), any());
    }

    @Test
    void changedSourceHashClaimsAndReplacesOldCache() {
        MutableSourceReader reader = new MutableSourceReader(source("수정된 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.find("DESTINATION_COMMENT", 7L, "content", "en"))
                .thenReturn(cache("이전 댓글", "READY", "Old translation"));
        when(mapper.insertProcessing(any())).thenReturn(0);
        when(mapper.tryClaim(anyString(), eq(7L), eq("content"), eq("en"), any(), anyString(), any(), any()))
                .thenReturn(1);
        when(mapper.markReady(anyString(), eq(7L), eq("content"), eq("en"), any(), anyString(),
                eq("New translation"), eq("ko"), any())).thenReturn(1);

        ContentTranslationResponse response = service(reader, mapper,
                (text, source, target) -> new MachineTranslation("New translation", "ko"))
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null);

        assertThat(response.translatedText()).isEqualTo("New translation");
        verify(mapper).tryClaim(anyString(), eq(7L), eq("content"), eq("en"), any(), anyString(), any(), any());
    }

    @Test
    void sourceChangedDuringProviderCallIsNeverStoredReady() {
        MutableSourceReader reader = new MutableSourceReader(source("처음 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        MachineTranslationClient client = (text, source, target) -> {
            reader.current.set(source("번역 중 수정된 댓글", "ko"));
            return new MachineTranslation("Stale translation", "ko");
        };

        assertThatThrownBy(() -> service(reader, mapper, client)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null))
                .isInstanceOf(TranslationStaleException.class);

        verify(mapper, never()).markReady(anyString(), any(), anyString(), anyString(), any(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void hiddenOrDeletedSourceReturnsNotFoundBeforeCacheLookup() {
        MutableSourceReader reader = new MutableSourceReader(null);
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        MachineTranslationClient client = mock(MachineTranslationClient.class);

        assertThatThrownBy(() -> service(reader, mapper, client)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null))
                .isInstanceOf(TranslationNotFoundException.class);

        verify(mapper, never()).find(anyString(), any(), anyString(), anyString());
        verify(client, never()).translate(anyString(), anyString(), anyString());
    }

    @Test
    void providerFailureMarksLeaseFailedAndLeavesCommentFlowIndependent() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        MachineTranslationClient client = (text, source, target) -> {
            throw new MachineTranslationException("provider unavailable");
        };
        CountingUsageReservationGate monthlyUsage = new CountingUsageReservationGate();

        assertThatThrownBy(() -> service(reader, mapper, client, monthlyUsage)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null))
                .isInstanceOf(MachineTranslationException.class);

        assertThat(monthlyUsage.calls).isEqualTo(1);
        assertThat(monthlyUsage.characters).isEqualTo(5L);
        verify(mapper).markFailed(anyString(), eq(7L), eq("content"), eq("en"), any(),
                anyString(), any(), any());
    }

    @Test
    void providerPreparationFailureDoesNotReserveMonthlyUsage() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        MachineTranslationClient client = new MachineTranslationClient() {
            @Override
            public void prepare() {
                throw new MachineTranslationException("provider unavailable");
            }

            @Override
            public MachineTranslation translate(String sourceText, String sourceLanguage, String targetLanguage) {
                throw new AssertionError("provider 호출까지 진행되면 안 됩니다.");
            }
        };
        CountingUsageReservationGate monthlyUsage = new CountingUsageReservationGate();

        assertThatThrownBy(() -> service(reader, mapper, client, monthlyUsage)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null))
                .isInstanceOf(MachineTranslationException.class);

        assertThat(monthlyUsage.calls).isZero();
        verify(mapper).markFailed(anyString(), eq(7L), eq("content"), eq("en"), any(),
                anyString(), any(), any());
    }

    @Test
    void monthlyLimitRejectionNeverCallsTheProvider() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        MachineTranslationClient client = mock(MachineTranslationClient.class);
        TranslationUsageReservationGate monthlyUsage = (sourceText, ipAddress, userId) -> {
            throw new TranslationMonthlyLimitException();
        };

        assertThatThrownBy(() -> service(reader, mapper, client, monthlyUsage)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", null))
                .isInstanceOf(TranslationMonthlyLimitException.class);

        verify(client, never()).translate(anyString(), anyString(), anyString());
        verify(mapper).markFailed(anyString(), eq(7L), eq("content"), eq("en"), any(),
                anyString(), any(), any());
    }

    @Test
    void dailyLimitRejectionNeverCallsTheProvider() {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        MachineTranslationClient client = mock(MachineTranslationClient.class);
        TranslationUsageReservationGate usageReservation = (sourceText, ipAddress, userId) -> {
            throw new TranslationDailyLimitException();
        };

        assertThatThrownBy(() -> service(reader, mapper, client, usageReservation)
                .translate(TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "127.0.0.1", 42L))
                .isInstanceOf(TranslationDailyLimitException.class);

        verify(client, never()).translate(anyString(), anyString(), anyString());
        verify(mapper).markFailed(anyString(), eq(7L), eq("content"), eq("en"), any(),
                anyString(), any(), any());
    }

    @Test
    void concurrentFirstRequestsMakeOnlyOneProviderCall() throws Exception {
        MutableSourceReader reader = new MutableSourceReader(source("원문 댓글", "ko"));
        InMemoryCacheMapper mapper = new InMemoryCacheMapper();
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        MachineTranslationClient client = (text, source, target) -> {
            providerCalls.incrementAndGet();
            providerStarted.countDown();
            try {
                releaseProvider.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new MachineTranslation("Translated", "ko");
        };
        CountingUsageReservationGate monthlyUsage = new CountingUsageReservationGate();
        ContentTranslationService service = service(reader, mapper, client, monthlyUsage);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.translate(
                    TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "10.0.0.1", null));
            assertThat(providerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.translate(
                    TranslatableContentType.DESTINATION_COMMENT, 7L, "en", "10.0.0.2", null));
            ContentTranslationResponse waiting = second.get(2, TimeUnit.SECONDS);
            releaseProvider.countDown();
            ContentTranslationResponse ready = first.get(2, TimeUnit.SECONDS);

            assertThat(waiting.status()).isEqualTo("PROCESSING");
            assertThat(ready.status()).isEqualTo("READY");
            assertThat(providerCalls).hasValue(1);
            assertThat(monthlyUsage.calls).isEqualTo(1);
            assertThat(monthlyUsage.characters).isEqualTo(5L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postCommentUsesTheSharedCacheLeaseHashAndCostReservationFlow() {
        MutableSourceReader reader = new MutableSourceReader(
                source(TranslatableContentType.POST_COMMENT, "첫 게시글 댓글", "ko"));
        InMemoryCacheMapper mapper = new InMemoryCacheMapper();
        AtomicInteger providerCalls = new AtomicInteger();
        MachineTranslationClient client = (text, source, target) -> {
            providerCalls.incrementAndGet();
            return new MachineTranslation("translated: " + text, "ko");
        };
        CountingUsageReservationGate usageReservation = new CountingUsageReservationGate();
        ContentTranslationService service = service(reader, mapper, client, usageReservation);

        ContentTranslationResponse miss = service.translate(
                TranslatableContentType.POST_COMMENT, 7L, "en", "203.0.113.9", 42L);
        ContentTranslationResponse hit = service.translate(
                TranslatableContentType.POST_COMMENT, 7L, "en", "203.0.113.9", 42L);
        reader.current.set(source(
                TranslatableContentType.POST_COMMENT, "수정된 게시글 댓글", "ko"));
        ContentTranslationResponse changed = service.translate(
                TranslatableContentType.POST_COMMENT, 7L, "en", "203.0.113.9", 42L);

        assertThat(miss.cached()).isFalse();
        assertThat(hit.cached()).isTrue();
        assertThat(changed.cached()).isFalse();
        assertThat(changed.translatedText()).isEqualTo("translated: 수정된 게시글 댓글");
        assertThat(providerCalls).hasValue(2);
        assertThat(usageReservation.calls).isEqualTo(2);
        assertThat(usageReservation.lastUserId).isEqualTo(42L);
    }

    @Test
    void courseCommentUsesContentCacheAndInvalidatesItAfterTheSourceHashChanges() {
        MutableSourceReader reader = new MutableSourceReader(
                source(TranslatableContentType.COURSE_COMMENT, "첫 코스 댓글", "ko"));
        InMemoryCacheMapper mapper = new InMemoryCacheMapper();
        AtomicInteger providerCalls = new AtomicInteger();
        MachineTranslationClient client = (text, source, target) -> {
            providerCalls.incrementAndGet();
            return new MachineTranslation("translated: " + text, "ko");
        };
        CountingUsageReservationGate usageReservation = new CountingUsageReservationGate();
        ContentTranslationService service = service(reader, mapper, client, usageReservation);

        ContentTranslationResponse miss = service.translate(
                TranslatableContentType.COURSE_COMMENT, 7L, "en", "203.0.113.9", 42L);
        ContentTranslationResponse hit = service.translate(
                TranslatableContentType.COURSE_COMMENT, 7L, "en", "203.0.113.9", 42L);
        reader.current.set(source(
                TranslatableContentType.COURSE_COMMENT, "수정된 코스 댓글", "ko"));
        ContentTranslationResponse changed = service.translate(
                TranslatableContentType.COURSE_COMMENT, 7L, "en", "203.0.113.9", 42L);

        assertThat(miss.cached()).isFalse();
        assertThat(hit.cached()).isTrue();
        assertThat(changed.cached()).isFalse();
        assertThat(changed.translatedText()).isEqualTo("translated: 수정된 코스 댓글");
        assertThat(providerCalls).hasValue(2);
        assertThat(usageReservation.calls).isEqualTo(2);
    }

    @Test
    void htmlSourceUsesGoogleHtmlModeWhileExistingSourcesStayPlainText() {
        TranslationSourceSnapshot html = new TranslationSourceSnapshot(
                TranslatableContentType.USER_POST, 7L, "content", "<p>Hello world</p>",
                "en", UPDATED_AT, "text/html");
        MutableSourceReader reader = new MutableSourceReader(html);
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        when(mapper.markReady(anyString(), eq(7L), eq("content"), eq("ko"), any(), anyString(),
                eq("<p>안녕하세요</p>"), eq("en"), any())).thenReturn(1);
        AtomicReference<String> requestedMimeType = new AtomicReference<>();
        MachineTranslationClient client = new MachineTranslationClient() {
            @Override
            public MachineTranslation translate(
                    String sourceText, String sourceLanguage, String targetLanguage) {
                throw new AssertionError("MIME 정보 없는 번역 경로를 사용하면 안 됩니다.");
            }

            @Override
            public MachineTranslation translate(
                    String sourceText, String sourceLanguage, String targetLanguage, String mimeType) {
                requestedMimeType.set(mimeType);
                return new MachineTranslation("<p>안녕하세요</p>", "en");
            }
        };

        service(reader, mapper, client).translate(
                TranslatableContentType.USER_POST, 7L, "content", "ko", null, null);

        assertThat(requestedMimeType).hasValue("text/html");
    }

    @Test
    void userPostBodyBeyondCommentLengthStillUsesTheExistingDailyUsageGate() {
        String html = "<p>" + "English travel article content ".repeat(80) + "</p>";
        MutableSourceReader reader = new MutableSourceReader(new TranslationSourceSnapshot(
                TranslatableContentType.USER_POST, 7L, "content", html,
                "en", UPDATED_AT, "text/html"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        when(mapper.markReady(anyString(), eq(7L), eq("content"), eq("ko"), any(), anyString(),
                eq("<p>번역 본문</p>"), eq("en"), any())).thenReturn(1);
        CountingUsageReservationGate usage = new CountingUsageReservationGate();

        ContentTranslationResponse response = service(reader, mapper,
                (text, source, target) -> new MachineTranslation("<p>번역 본문</p>", "en"), usage)
                .translate(TranslatableContentType.USER_POST, 7L, "content", "ko", null, 7L);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(usage.calls).isEqualTo(1);
        assertThat(usage.characters).isEqualTo(html.codePointCount(0, html.length()));
    }

    @Test
    void courseBodyBeyondCommentLengthStillUsesTheExistingDailyUsageGate() {
        String html = "<p>" + "English course description content ".repeat(80) + "</p>";
        MutableSourceReader reader = new MutableSourceReader(new TranslationSourceSnapshot(
                TranslatableContentType.COURSE, 7L, "content", html,
                "en", UPDATED_AT, "text/html"));
        ContentTranslationCacheMapper mapper = mock(ContentTranslationCacheMapper.class);
        when(mapper.insertProcessing(any())).thenReturn(1);
        when(mapper.markReady(anyString(), eq(7L), eq("content"), eq("ko"), any(), anyString(),
                eq("<p>번역 코스</p>"), eq("en"), any())).thenReturn(1);
        CountingUsageReservationGate usage = new CountingUsageReservationGate();

        ContentTranslationResponse response = service(reader, mapper,
                (text, source, target) -> new MachineTranslation("<p>번역 코스</p>", "en"), usage)
                .translate(TranslatableContentType.COURSE, 7L, "content", "ko", null, 7L);

        assertThat(response.status()).isEqualTo("READY");
        assertThat(usage.calls).isEqualTo(1);
        assertThat(usage.characters).isEqualTo(html.codePointCount(0, html.length()));
    }

    private ContentTranslationService service(TranslationSourceReader reader,
                                              ContentTranslationCacheMapper mapper,
                                              MachineTranslationClient client) {
        return service(reader, mapper, client, (sourceText, ipAddress, userId) -> { });
    }

    private ContentTranslationService service(TranslationSourceReader reader,
                                              ContentTranslationCacheMapper mapper,
                                              MachineTranslationClient client,
                                              TranslationUsageReservationGate usageReservation) {
        TranslationProperties properties = properties();
        TranslationSourceRegistry registry = new TranslationSourceRegistry(List.of(reader));
        SharedTranslationCacheMapper sharedCacheMapper = mock(SharedTranslationCacheMapper.class);
        when(sharedCacheMapper.insertProcessing(any())).thenReturn(1);
        when(sharedCacheMapper.markReady(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any())).thenReturn(1);
        return new ContentTranslationService(
                registry, mapper, sharedCacheMapper, client,
                new InMemoryTranslationRateLimiter(properties), properties,
                new TranslationCacheFinalizer(registry, mapper), usageReservation,
                new TranslationProviderMetadata("google-v3-general-v1"));
    }

    private TranslationProperties properties() {
        return new TranslationProperties(
                2000, Duration.ofSeconds(30), Duration.ofSeconds(30),
                100, 100, 100, Duration.ofMinutes(1), true, 400_000L,
                20_000L, 5_000L);
    }

    private TranslationSourceSnapshot source(String text, String language) {
        return source(TranslatableContentType.DESTINATION_COMMENT, text, language);
    }

    private TranslationSourceSnapshot source(TranslatableContentType type,
                                             String text,
                                             String language) {
        return new TranslationSourceSnapshot(
                type, 7L, "content", text, language, UPDATED_AT);
    }

    private ContentTranslationCache cache(String source, String status, String translated) {
        ContentTranslationCache cache = new ContentTranslationCache();
        cache.setContentType("DESTINATION_COMMENT");
        cache.setContentId(7L);
        cache.setSourceField("content");
        cache.setTargetLanguage("en");
        cache.setSourceHash(ContentTranslationService.sourceHash(source));
        cache.setStatus(status);
        cache.setTranslatedText(translated);
        return cache;
    }

    private static final class MutableSourceReader implements TranslationSourceReader {
        private final AtomicReference<TranslationSourceSnapshot> current;

        private MutableSourceReader(TranslationSourceSnapshot source) {
            this.current = new AtomicReference<>(source);
        }

        @Override
        public TranslatableContentType contentType() {
            TranslationSourceSnapshot source = current.get();
            return source == null
                    ? TranslatableContentType.DESTINATION_COMMENT
                    : source.contentType();
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisible(Long contentId) {
            return Optional.ofNullable(current.get());
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisibleForUpdate(Long contentId) {
            return Optional.ofNullable(current.get());
        }
    }

    private static final class InMemoryCacheMapper implements ContentTranslationCacheMapper {
        @Override public synchronized int invalidateUnchangedReady(String type,Long id,String field,String target,byte[] hash,String text) {
            if(value==null || !"READY".equals(value.getStatus()) || !Arrays.equals(hash,value.getSourceHash()) || !text.equals(value.getTranslatedText())) return 0;
            value.setStatus("FAILED");value.setTranslatedText(null);value.setRetryAfter(null);return 1;
        }
        private ContentTranslationCache value;

        @Override
        public synchronized ContentTranslationCache find(String contentType, Long contentId,
                                                         String sourceField, String targetLanguage) {
            return value;
        }

        @Override
        public synchronized int insertProcessing(ContentTranslationCache cache) {
            if (value != null) return 0;
            value = cache;
            value.setStatus("PROCESSING");
            return 1;
        }

        @Override
        public synchronized int tryClaim(String contentType, Long contentId, String sourceField,
                                         String targetLanguage, byte[] sourceHash, String leaseToken,
                                         Timestamp now, Timestamp leaseExpiresAt) {
            if (value == null || Arrays.equals(value.getSourceHash(), sourceHash)) return 0;
            value.setContentType(contentType);
            value.setContentId(contentId);
            value.setSourceField(sourceField);
            value.setTargetLanguage(targetLanguage);
            value.setSourceHash(sourceHash);
            value.setLeaseToken(leaseToken);
            value.setLeaseExpiresAt(leaseExpiresAt);
            value.setStatus("PROCESSING");
            return 1;
        }

        @Override
        public synchronized int markReady(String contentType, Long contentId, String sourceField,
                                          String targetLanguage, byte[] sourceHash, String leaseToken,
                                          String translatedText, String detectedSourceLanguage,
                                          Timestamp now) {
            if (value == null || !leaseToken.equals(value.getLeaseToken())) return 0;
            value.setTranslatedText(translatedText);
            value.setDetectedSourceLanguage(detectedSourceLanguage);
            value.setStatus("READY");
            return 1;
        }

        @Override
        public synchronized int markFailed(String contentType, Long contentId, String sourceField,
                                           String targetLanguage, byte[] sourceHash, String leaseToken,
                                           Timestamp retryAfter, Timestamp now) {
            if (value != null) value.setStatus("FAILED");
            return value == null ? 0 : 1;
        }

        @Override
        public synchronized int upsertReady(ContentTranslationCache cache) {
            value = cache;
            value.setStatus("READY");
            return 1;
        }

        @Override
        public synchronized List<ContentTranslationCache> findReadyAfter(Long afterId, int limit) {
            if (value == null || value.getId() == null || value.getId() <= afterId
                    || !"READY".equals(value.getStatus())) {
                return List.of();
            }
            return List.of(value);
        }
    }

    private static final class CountingUsageReservationGate implements TranslationUsageReservationGate {
        private int calls;
        private long characters;
        private String lastIpAddress;
        private Long lastUserId;

        @Override
        public synchronized void reserve(String sourceText, String ipAddress, Long userId) {
            calls++;
            characters += sourceText.codePointCount(0, sourceText.length());
            lastIpAddress = ipAddress;
            lastUserId = userId;
        }
    }
}
