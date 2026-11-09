package com.tripbora.service.translation;

import com.tripbora.model.translation.ContentTranslationCache;
import com.tripbora.model.translation.SharedTranslationCache;
import com.tripbora.repository.translation.ContentTranslationCacheMapper;
import com.tripbora.repository.translation.SharedTranslationCacheMapper;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedTranslationDeduplicationTest {
    @Test void legacyCanonicalExactResultCanServeFirstWhitespaceVariantWithoutCallingProvider() {
        var old=new SharedTranslationCache();old.setSourceHash(ContentTranslationService.sourceHash("The food was delicious."));old.setSourceLanguage("en");old.setTargetLanguage("ko");old.setProvider("GOOGLE");old.setTranslationProfile("google-v3-general-v1");old.setTranslatedText("기존 번역");
        var shared=new FakeSharedCacheMapper();shared.insertReadyIfAbsent(old);
        var calls=new AtomicInteger();var usage=new CountingUsageGate();
        var svc=service(registry(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"  The\tfood  was delicious. ","en"))),new FakeContentCacheMapper(),shared,countingClient(calls,r->new MachineTranslation("새 번역",null)),usage,new CountingRateLimiter(),"google-v3-general-v1");
        assertThat(svc.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).translatedText()).isEqualTo("기존 번역");
        assertThat(calls).hasValue(0);assertThat(usage.calls).isZero();
    }
    @Test void legacyExactReadyIsPreservedAndPromotedWithoutProviderOrUsage() {
        String raw="  The food was delicious.  ";
        var old=new SharedTranslationCache();old.setSourceHash(ContentTranslationService.sourceHash(raw));old.setSourceLanguage("en");old.setTargetLanguage("ko");old.setProvider("GOOGLE");old.setTranslationProfile("google-v3-general-v1");old.setTranslatedText("음식이 맛있었습니다.");
        var shared=new FakeSharedCacheMapper();shared.insertReadyIfAbsent(old);
        var calls=new AtomicInteger();var usage=new CountingUsageGate();
        var svc=service(registry(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,raw,"en"),2L,source(TranslatableContentType.POST_COMMENT,2L,"The\tfood was delicious.","en"))),new FakeContentCacheMapper(),shared,
                countingClient(calls,r->new MachineTranslation("새 번역",null)),usage,new CountingRateLimiter(),"google-v3-general-v1");
        assertThat(svc.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).translatedText()).isEqualTo("음식이 맛있었습니다.");
        assertThat(svc.translate(TranslatableContentType.POST_COMMENT,2L,"ko","ip",2L).translatedText()).isEqualTo("음식이 맛있었습니다.");
        assertThat(calls).hasValue(0);assertThat(usage.calls).isZero();
        assertThat(shared.find(old.getSourceHash(),"en","ko","GOOGLE","google-v3-general-v1").getStatus()).isEqualTo("READY");
        assertThat(shared.find(ContentTranslationService.sourceHash("The food was delicious."),"en","ko","GOOGLE","google-v3-general-v1-ws-v1").getStatus()).isEqualTo("READY");
    }
    @Test void legacyActiveLeaseAndFailureCooldownAreNotBypassed() {
        for(String status:List.of("PROCESSING","FAILED")) {
            String raw="The food was delicious.";
            var old=new SharedTranslationCache();old.setSourceHash(ContentTranslationService.sourceHash(raw));old.setSourceLanguage("en");old.setTargetLanguage("ko");old.setProvider("GOOGLE");old.setTranslationProfile("google-v3-general-v1");old.setLeaseToken("legacy-owner");old.setLeaseExpiresAt(Timestamp.from(Instant.now().plusSeconds(30)));old.setRetryAfter(old.getLeaseExpiresAt());
            var shared=new FakeSharedCacheMapper();shared.insertProcessing(old);old.setStatus(status);
            var calls=new AtomicInteger();var usage=new CountingUsageGate();
            var svc=service(registry(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,raw,"en"))),new FakeContentCacheMapper(),shared,countingClient(calls,r->new MachineTranslation("번역",null)),usage,new CountingRateLimiter(),"google-v3-general-v1");
            assertThat(svc.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).status()).isEqualTo("PROCESSING");
            assertThat(calls).hasValue(0);assertThat(usage.calls).isZero();assertThat(old.getLeaseToken()).isEqualTo("legacy-owner");
        }
    }
    @Test void crLfIsSharedButSingleNewlinesParagraphsAndWordBoundariesStaySeparate() {
        String[] text={"The food was delicious.\r\nThe staff were kind.","The food was delicious.\nThe staff were kind.",
                "The food was delicious. The staff were kind.","The food was delicious.\n\nThe staff were kind.","now here","nowhere"};
        Map<Long,TranslationSourceSnapshot> sources=new ConcurrentHashMap<>();
        for(int i=0;i<text.length;i++)sources.put((long)i+1,source(TranslatableContentType.DESTINATION_COMMENT,(long)i+1,text[i],"en"));
        var ctx=context(sources);
        for(long id=1;id<=text.length;id++)ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,id,"ko","ip",id);
        assertThat(ctx.providerCalls()).hasValue(5);assertThat(ctx.usage().calls).isEqualTo(5);
    }
    @Test void normalizedConcurrentVariantsHaveOneLeaseOwnerAndOneUsageReservation() throws Exception {
        var ctx=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"The food was delicious.","en"),
                2L,source(TranslatableContentType.POST_COMMENT,2L,"  The\tfood  was delicious.  ","en")),true,false);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L));
            assertThat(ctx.providerStarted().await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(ctx.service().translate(TranslatableContentType.POST_COMMENT,2L,"ko","ip",2L).status()).isEqualTo("PROCESSING");
            ctx.releaseProvider().countDown();assertThat(first.get(2,TimeUnit.SECONDS).status()).isEqualTo("READY");
            assertThat(ctx.service().translate(TranslatableContentType.POST_COMMENT,2L,"ko","ip",2L).cached()).isTrue();
            assertThat(ctx.providerCalls()).hasValue(1);assertThat(ctx.usage().calls).isEqualTo(1);
        } finally {ctx.releaseProvider().countDown();executor.shutdownNow();}
    }
    @Test void normalizedFailureBacksOffAcrossDifferentRawTexts() {
        var ctx=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"The food was delicious.","en"),
                2L,source(TranslatableContentType.DESTINATION_COMMENT,2L,"The\tfood  was delicious.","en")),false,true);
        assertThatThrownBy(()->ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L)).isInstanceOf(MachineTranslationException.class);
        assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,2L,"ko","ip",2L).status()).isEqualTo("PROCESSING");
        assertThat(ctx.providerCalls()).hasValue(1);assertThat(ctx.usage().calls).isEqualTo(1);
    }
    @Test void originalTextAndUsageLengthAreNeverNormalizedBeforeProvider() {
        String raw="  The\tfood  was delicious.  ";var calls=new AtomicInteger();var usage=new CountingUsageGate();
        var svc=service(registry(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,raw,"en"))),new FakeContentCacheMapper(),new FakeSharedCacheMapper(),
                countingClient(calls,r->{assertThat(r.text()).isEqualTo(raw);return new MachineTranslation("번역 결과",null);}),usage,new CountingRateLimiter(),"google-v3-general-v1");
        svc.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        assertThat(calls).hasValue(1);assertThat(usage.calls).isEqualTo(1);assertThat(usage.lastText).isEqualTo(raw);
    }
    @Test void koreanEnglishJapaneseChineseAndMixedProseReuseOnlyWhitespaceVariants() {
        for(String[] row:new String[][]{{"정말 맛있는 음식입니다.","ko","en"},{"The food was delicious.","en","ko"},
                {"とても 美味しいお店です。","ja","en"},{"这里 食物非常好吃。","zh-CN","en"},
                {"這裡 食物非常好吃。","zh-TW","en"},{"정말 맛있어요. Very good!","ko","ja"}}) {
            var ctx=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,row[0],row[1]),
                    2L,source(TranslatableContentType.POST_COMMENT,2L,"  "+row[0].replace(" ","\t  ")+"  ",row[1])));
            ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,row[2],"ip",1L);
            assertThat(ctx.service().translate(TranslatableContentType.POST_COMMENT,2L,row[2],"ip",2L).cached()).isTrue();
            assertThat(ctx.providerCalls()).hasValue(1);assertThat(ctx.usage().calls).isEqualTo(1);
        }
    }
    @Test void whitespaceOnlyMixedProviderResultDoesNotBecomeNormalizedReady() {
        String raw="정말  맛있어요. Very  good!";
        var calls=new AtomicInteger();var usage=new CountingUsageGate();
        var svc=service(registry(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,raw,"ko"),
                        2L,source(TranslatableContentType.POST_COMMENT,2L,"정말 맛있어요. Very good!","ko"))),
                new FakeContentCacheMapper(),new FakeSharedCacheMapper(),countingClient(calls,r->new MachineTranslation("정말 맛있어요. Very good!",null)),usage,new CountingRateLimiter(),"google-v3-general-v1");
        assertThatThrownBy(()->svc.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L)).isInstanceOf(MachineTranslationException.class);
        assertThat(svc.translate(TranslatableContentType.POST_COMMENT,2L,"ko","ip",2L).status()).isEqualTo("PROCESSING");
        assertThat(calls).hasValue(1);assertThat(usage.calls).isEqualTo(1);
    }
    @Test void whitespaceEditRevalidatesRawHashButSemanticEditNeedsNewTranslation() {
        Map<Long,TranslationSourceSnapshot> sources=new ConcurrentHashMap<>();
        sources.put(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"The food was delicious.","en"));
        var ctx=context(sources);
        ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        String changed="  The\tfood  was delicious.  ";
        sources.put(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,changed,"en"));
        assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).cached()).isTrue();
        assertThat(ctx.contentCaches().find("DESTINATION_COMMENT",1L,"content","ko").getSourceHash()).containsExactly(ContentTranslationService.sourceHash(changed));
        assertThat(ctx.providerCalls()).hasValue(1);
        sources.put(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"The food was bad.","en"));
        assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).cached()).isFalse();
        assertThat(ctx.providerCalls()).hasValue(2);assertThat(ctx.usage().calls).isEqualTo(2);
    }
    @Test void proseWhitespaceVariantsShareOneProviderCallAndKeepEachRawContentHash() {
        Map<Long,TranslationSourceSnapshot> sources=new ConcurrentHashMap<>();
        String[] variants={"The food was delicious.","  The food was delicious.  ","The  food was delicious.","The\tfood was delicious."};
        for(int i=0;i<variants.length;i++)sources.put((long)i+1,source(TranslatableContentType.DESTINATION_COMMENT,(long)i+1,variants[i],"en"));
        var ctx=context(sources);
        for(long id=1;id<=variants.length;id++) {
            var result=ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,id,"ko","ip",id);
            assertThat(result.cached()).isEqualTo(id!=1);
            assertThat(ctx.contentCaches().find("DESTINATION_COMMENT",id,"content","ko").getSourceHash())
                    .containsExactly(ContentTranslationService.sourceHash(variants[(int)id-1]));
        }
        assertThat(ctx.providerCalls()).hasValue(1);assertThat(ctx.usage().calls).isEqualTo(1);
    }
    @Test void pureKoreanRepeatedRequestsNeverCallProviderOrReserveUsage() {
        var ctx=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,"정말 맛있고 직원분이 친절합니다.","ko")));
        for(int i=0;i<3;i++)ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        assertThat(ctx.providerCalls()).hasValue(0);assertThat(ctx.usage().calls).isZero();
    }
    @Test void mixedQuotaFailuresUseExistingDailyAndMonthlyGuardsBeforeProvider() {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        for(RuntimeException failure:List.of(new TranslationDailyLimitException(),new TranslationMonthlyLimitException())) {
            var sourceMap=Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko"));
            var registry=registry(sourceMap);var content=new FakeContentCacheMapper();var shared=new FakeSharedCacheMapper();var calls=new AtomicInteger();var reservations=new AtomicInteger();
            var service=new ContentTranslationService(registry,content,shared,countingClient(calls,r->new MachineTranslation("번역",null)),new CountingRateLimiter(),properties(),new TranslationCacheFinalizer(registry,content),
                    (s,ip,id)->{reservations.incrementAndGet();throw failure;},new TranslationProviderMetadata("google-v3-general-v1"));
            assertThatThrownBy(()->service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L)).isSameAs(failure);
            assertThat(service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).status()).isEqualTo("PROCESSING");
            assertThat(calls).hasValue(0);assertThat(reservations).hasValue(1);
        }
    }
    @Test void mixedTextIsSharedAcrossContentTypesAndEditsDoNotReturnTheOldResult() {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        Map<Long,TranslationSourceSnapshot> sources=new ConcurrentHashMap<>();
        sources.put(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko"));
        sources.put(2L,source(TranslatableContentType.POST_COMMENT,2L,text,"ko"));
        var ctx=context(sources);
        ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        assertThat(ctx.service().translate(TranslatableContentType.POST_COMMENT,2L,"ko","ip",2L).cached()).isTrue();
        assertThat(ctx.providerCalls()).hasValue(1);
        sources.put(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text+" The staff were very kind.","ko"));
        assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).translatedText()).endsWith("The staff were very kind.");
        assertThat(ctx.providerCalls()).hasValue(2);
        sources.remove(1L);
        assertThatThrownBy(()->ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L)).isInstanceOf(TranslationNotFoundException.class);
        assertThat(ctx.providerCalls()).hasValue(2);
    }
    @Test void mixedRequestsUseOneWholeTextProviderCallForEachExpectedLanguagePair() {
        String[][] rows={{"정말 좋은 식당입니다. The food was very delicious.","ko","ko","en"},
                {"정말 좋은 식당입니다. The food was very delicious.","ko","ja","und"},
                {"とても美味しいお店です。 The food was very delicious.","ja","ko","und"},
                {"とても美味しいお店です。 The food was very delicious.","ja","en","ja"},
                {"这里的食物非常好吃 정말 좋은 식당입니다.","zh-CN","en","und"}};
        for(var row:rows) {
            var sources=Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,row[0],row[1]));
            var calls=new AtomicInteger();var usage=new CountingUsageGate();
            var service=service(registry(sources),new FakeContentCacheMapper(),new FakeSharedCacheMapper(),
                    countingClient(calls,request->{assertThat(request.text()).isEqualTo(row[0]);assertThat(request.source()).isEqualTo(row[3]);return new MachineTranslation("번역 결과",null);}),usage,new CountingRateLimiter(),"google-v3-general-v1");
            assertThat(service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,row[2],"ip",1L).translatedText()).isEqualTo("번역 결과");
            service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,row[2],"ip",1L);
            assertThat(calls).hasValue(1);assertThat(usage.calls).isEqualTo(1);
        }
    }
    @Test void legacyUnchangedContentAndSharedEntriesAreRefreshedWithSameSourceHash() {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        var content=new FakeContentCacheMapper();var shared=new FakeSharedCacheMapper();var calls=new AtomicInteger();
        var source=source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko");
        var bad=new ContentTranslationCache();bad.setContentType("DESTINATION_COMMENT");bad.setContentId(1L);bad.setSourceField("content");bad.setTargetLanguage("ko");bad.setSourceHash(ContentTranslationService.sourceHash(text));bad.setTranslatedText(text);content.upsertReady(bad);
        var old=new SharedTranslationCache();old.setSourceHash(ContentTranslationService.sourceHash(text));old.setSourceLanguage("ko");old.setTargetLanguage("ko");old.setProvider("GOOGLE");old.setTranslationProfile("google-v3-general-v1");old.setTranslatedText(text);shared.insertReadyIfAbsent(old);
        var service=service(registry(Map.of(1L,source)),content,shared,countingClient(calls,r->new MachineTranslation("정말 좋은 식당이며 음식이 아주 맛있었습니다.",null)),new CountingUsageGate(),new CountingRateLimiter(),"google-v3-general-v1");
        assertThat(service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).translatedText()).doesNotContain("The food");
        assertThat(service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).cached()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(content.find("DESTINATION_COMMENT",1L,"content","ko").getSourceHash()).containsExactly(ContentTranslationService.sourceHash(text));
    }
    @Test void unchangedProviderResultBacksOffRatherThanCachingSuccessOrRetrying() {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        var calls=new AtomicInteger();var sources=Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko"));
        var service=service(registry(sources),new FakeContentCacheMapper(),new FakeSharedCacheMapper(),
                countingClient(calls,r->new MachineTranslation(r.text(),null)),new CountingUsageGate(),new CountingRateLimiter(),"google-v3-general-v1");
        assertThatThrownBy(()->service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L)).isInstanceOf(MachineTranslationException.class);
        for(int i=0;i<5;i++)assertThat(service.translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L).status()).isEqualTo("PROCESSING");
        assertThat(calls).hasValue(1);
    }
    @Test void concurrentMixedRequestsForSameAndDifferentReviewsHaveOneProviderOwner() throws Exception {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        var ctx=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko"),2L,source(TranslatableContentType.DESTINATION_COMMENT,2L,text,"ko")),true,false);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L));
            assertThat(ctx.providerStarted().await(2,TimeUnit.SECONDS)).isTrue();
            for(long id:new long[]{1,2})assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,id,"ko","ip",id).status()).isEqualTo("PROCESSING");
            ctx.releaseProvider().countDown();assertThat(first.get(2,TimeUnit.SECONDS).status()).isEqualTo("READY");
            assertThat(ctx.service().translate(TranslatableContentType.DESTINATION_COMMENT,2L,"ko","ip",2L).cached()).isTrue();
            assertThat(ctx.providerCalls()).hasValue(1);assertThat(ctx.usage().calls).isEqualTo(1);
        } finally {ctx.releaseProvider().countDown();executor.shutdownNow();}
    }
    @Test void mixedKoreanReviewIsTranslatedEvenWhenTargetMatchesDominantLanguageAndThenCached() {
        String text="정말 좋은 식당입니다. The food was very delicious.";
        var context=context(Map.of(1L,source(TranslatableContentType.DESTINATION_COMMENT,1L,text,"ko"),
                2L,source(TranslatableContentType.DESTINATION_COMMENT,2L,text,"ko")));
        var first=context.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        var repeat=context.service().translate(TranslatableContentType.DESTINATION_COMMENT,1L,"ko","ip",1L);
        var shared=context.service().translate(TranslatableContentType.DESTINATION_COMMENT,2L,"ko","ip",2L);
        assertThat(first.cached()).isFalse();assertThat(repeat.cached()).isTrue();assertThat(shared.cached()).isTrue();
        assertThat(first.translatedText()).isEqualTo("ko:"+text);
        assertThat(context.providerCalls()).hasValue(1);assertThat(context.usage().calls).isEqualTo(1);
    }
    private static final Timestamp VERSION =
            Timestamp.from(Instant.parse("2026-09-09T01:00:00Z"));

    @Test
    void exactTextAcrossContentIdsCallsProviderAndUsageOnlyOnce() {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.DESTINATION_COMMENT, 200L, "hello", "en")));

        ContentTranslationResponse first = context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        ContentTranslationResponse second = context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 200L, "ko", "10.0.0.2", 9L);

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.translatedText()).isEqualTo("ko:hello");
        assertThat(context.providerCalls()).hasValue(1);
        assertThat(context.usage().calls).isEqualTo(1);
        assertThat(context.rateLimiter().externalCalls).isEqualTo(1);
        assertThat(context.contentCaches().find(
                "DESTINATION_COMMENT", 200L, "content", "ko").getStatus()).isEqualTo("READY");
    }

    @Test
    void sharedHitReplacesAnExistingProcessingContentCache() {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.DESTINATION_COMMENT, 200L, "hello", "en")));
        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        ContentTranslationCache processing = new ContentTranslationCache();
        processing.setContentType("DESTINATION_COMMENT");
        processing.setContentId(200L);
        processing.setSourceField("content");
        processing.setTargetLanguage("ko");
        processing.setSourceHash(ContentTranslationService.sourceHash("hello"));
        processing.setLeaseToken("00000000-0000-0000-0000-000000000001");
        processing.setLeaseExpiresAt(Timestamp.from(Instant.now().plusSeconds(30)));
        context.contentCaches().insertProcessing(processing);

        ContentTranslationResponse response = context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 200L, "ko", "10.0.0.2", 9L);

        assertThat(response.cached()).isTrue();
        assertThat(context.contentCaches().find(
                "DESTINATION_COMMENT", 200L, "content", "ko").getStatus()).isEqualTo("READY");
        assertThat(context.providerCalls()).hasValue(1);
    }

    @Test
    void exactTextIsSharedAcrossDestinationAndPostComments() {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.POST_COMMENT, 200L, "hello", "en")));

        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        ContentTranslationResponse post = context.service().translate(
                TranslatableContentType.POST_COMMENT, 200L, "ko", "10.0.0.2", 99L);

        assertThat(post.cached()).isTrue();
        assertThat(post.translatedText()).isEqualTo("ko:hello");
        assertThat(context.providerCalls()).hasValue(1);
        assertThat(context.usage().calls).isEqualTo(1);
    }

    @Test
    void exactTextIsSharedFromPostToCourseWithoutAdditionalProviderOrUsageCalls() {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.POST_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.COURSE_COMMENT, 200L, "hello", "en")));

        context.service().translate(
                TranslatableContentType.POST_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        ContentTranslationResponse course = context.service().translate(
                TranslatableContentType.COURSE_COMMENT, 200L, "ko", "10.0.0.2", 99L);

        assertThat(course.cached()).isTrue();
        assertThat(course.translatedText()).isEqualTo("ko:hello");
        assertThat(context.providerCalls()).hasValue(1);
        assertThat(context.usage().calls).isEqualTo(1);
        assertThat(context.rateLimiter().externalCalls).isEqualTo(1);
    }

    @Test
    void whitespaceIsSharedButCasePunctuationAndDifferentTextRemainExact() {
        TestContext context = context(Map.of(
                1L, source(TranslatableContentType.DESTINATION_COMMENT, 1L, "hello", "en"),
                2L, source(TranslatableContentType.DESTINATION_COMMENT, 2L, "Hello", "en"),
                3L, source(TranslatableContentType.DESTINATION_COMMENT, 3L, "hello ", "en"),
                4L, source(TranslatableContentType.DESTINATION_COMMENT, 4L, "hello!", "en"),
                5L, source(TranslatableContentType.DESTINATION_COMMENT, 5L, "hello mr.gpt", "en"),
                6L, source(TranslatableContentType.DESTINATION_COMMENT, 6L, "hello mr.mj", "en")));

        for (long id = 1L; id <= 6L; id++) {
            context.service().translate(
                    TranslatableContentType.DESTINATION_COMMENT, id, "ko", "10.0.0." + id, id);
        }

        assertThat(context.providerCalls()).hasValue(5);
        assertThat(context.usage().calls).isEqualTo(5);
    }

    @Test
    void targetLanguageAndTranslationProfileArePartOfTheSharedKey() {
        Map<Long, TranslationSourceSnapshot> sources = Map.of(
                1L, source(TranslatableContentType.DESTINATION_COMMENT, 1L, "hello", "en"),
                2L, source(TranslatableContentType.DESTINATION_COMMENT, 2L, "hello", "en"),
                3L, source(TranslatableContentType.DESTINATION_COMMENT, 3L, "hello", "en"));
        FakeContentCacheMapper contentCaches = new FakeContentCacheMapper();
        FakeSharedCacheMapper sharedCaches = new FakeSharedCacheMapper();
        CountingUsageGate usage = new CountingUsageGate();
        CountingRateLimiter rateLimiter = new CountingRateLimiter();
        AtomicInteger calls = new AtomicInteger();
        MachineTranslationClient client = countingClient(calls,
                request -> new MachineTranslation(request.target() + ":" + request.text(), "en"));
        TranslationSourceRegistry registry = registry(sources);
        ContentTranslationService profileV1 = service(
                registry, contentCaches, sharedCaches, client, usage, rateLimiter, "google-v3-general-v1");
        ContentTranslationService profileV2 = service(
                registry, contentCaches, sharedCaches, client, usage, rateLimiter, "google-v3-general-v2");

        profileV1.translate(TranslatableContentType.DESTINATION_COMMENT, 1L, "ko", "10.0.0.1", 1L);
        profileV1.translate(TranslatableContentType.DESTINATION_COMMENT, 2L, "ja", "10.0.0.2", 2L);
        profileV2.translate(TranslatableContentType.DESTINATION_COMMENT, 3L, "ko", "10.0.0.3", 3L);

        assertThat(calls).hasValue(3);
        assertThat(usage.calls).isEqualTo(3);
    }

    @Test
    void sourceLanguageIsPartOfTheSharedKey() {
        TestContext context = context(Map.of(
                1L, source(TranslatableContentType.DESTINATION_COMMENT, 1L, "hello", "en"),
                2L, source(TranslatableContentType.DESTINATION_COMMENT, 2L, "hello", "ja")));

        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 1L, "ko", "10.0.0.1", 1L);
        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 2L, "ko", "10.0.0.2", 2L);

        assertThat(context.providerCalls()).hasValue(2);
        assertThat(context.usage().calls).isEqualTo(2);
    }

    @Test
    void concurrentExactRequestsForDifferentContentIdsHaveOneGlobalOwner() throws Exception {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.DESTINATION_COMMENT, 200L, "hello", "en")),
                true, false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> context.service().translate(
                    TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L));
            assertThat(context.providerStarted().await(2, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> context.service().translate(
                    TranslatableContentType.DESTINATION_COMMENT, 200L, "ko", "10.0.0.2", 9L));
            ContentTranslationResponse waiting = second.get(2, TimeUnit.SECONDS);
            assertThat(waiting.status()).isEqualTo("PROCESSING");

            context.releaseProvider().countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo("READY");
            assertThat(context.service().translate(
                    TranslatableContentType.DESTINATION_COMMENT, 200L, "ko", "10.0.0.2", 9L)
                    .translatedText()).isEqualTo("ko:hello");
            assertThat(context.providerCalls()).hasValue(1);
            assertThat(context.usage().calls).isEqualTo(1);
        } finally {
            context.releaseProvider().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedExactTranslationBacksOffGloballyWithoutAnotherProviderCall() {
        TestContext context = context(Map.of(
                100L, source(TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"),
                200L, source(TranslatableContentType.POST_COMMENT, 200L, "hello", "en")),
                false, true);

        assertThatThrownBy(() -> context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L))
                .isInstanceOf(MachineTranslationException.class);
        ContentTranslationResponse retry = context.service().translate(
                TranslatableContentType.POST_COMMENT, 200L, "ko", "10.0.0.2", 9L);

        assertThat(retry.status()).isEqualTo("PROCESSING");
        assertThat(retry.retryAfterSeconds()).isGreaterThan(0L);
        assertThat(context.providerCalls()).hasValue(1);
        assertThat(context.usage().calls).isEqualTo(1);
    }

    @Test
    void changedSourceNeverUsesTheOldSharedResult() {
        Map<Long, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(100L, source(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"));
        TestContext context = context(sources);

        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        sources.put(100L, source(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "hello!", "en"));
        ContentTranslationResponse changed = context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);

        assertThat(changed.translatedText()).isEqualTo("ko:hello!");
        assertThat(context.providerCalls()).hasValue(2);
    }

    @Test
    void hiddenContentCannotExposeAnExistingSharedResult() {
        Map<Long, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(100L, source(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "hello", "en"));
        sources.put(200L, source(
                TranslatableContentType.DESTINATION_COMMENT, 200L, "hello", "en"));
        TestContext context = context(sources);
        context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        sources.remove(200L);

        assertThatThrownBy(() -> context.service().translate(
                TranslatableContentType.DESTINATION_COMMENT, 200L, "ko", "10.0.0.2", 9L))
                .isInstanceOf(TranslationNotFoundException.class);
        assertThat(context.providerCalls()).hasValue(1);
    }

    @Test
    void unavailableCourseCommentCannotExposeAnExistingSharedResult() {
        Map<Long, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(100L, source(
                TranslatableContentType.POST_COMMENT, 100L, "hello", "en"));
        sources.put(200L, source(
                TranslatableContentType.COURSE_COMMENT, 200L, "hello", "en"));
        TestContext context = context(sources);
        context.service().translate(
                TranslatableContentType.POST_COMMENT, 100L, "ko", "10.0.0.1", 7L);
        sources.remove(200L);

        assertThatThrownBy(() -> context.service().translate(
                TranslatableContentType.COURSE_COMMENT, 200L, "ko", "10.0.0.2", 9L))
                .isInstanceOf(TranslationNotFoundException.class);
        assertThat(context.providerCalls()).hasValue(1);
        assertThat(context.usage().calls).isEqualTo(1);
    }

    @Test
    void userPostTitleAndHtmlBodyReuseExactSharedEntriesWithoutAdditionalCost() {
        Map<FieldSourceKey, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(new FieldSourceKey(1L, "title"),
                postSource(1L, "title", "Best places in Seoul", "en", "text/plain"));
        sources.put(new FieldSourceKey(2L, "title"),
                postSource(2L, "title", "Best places in Seoul", "en", "text/plain"));
        sources.put(new FieldSourceKey(1L, "content"),
                postSource(1L, "content", "<p>Same English body</p>", "en", "text/html"));
        sources.put(new FieldSourceKey(2L, "content"),
                postSource(2L, "content", "<p>Same English body</p>", "en", "text/html"));
        TestContext context = fieldContext(sources);

        context.service().translate(
                TranslatableContentType.USER_POST, 1L, "title", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse titleHit = context.service().translate(
                TranslatableContentType.USER_POST, 2L, "title", "ko", "10.0.0.2", 2L);
        context.service().translate(
                TranslatableContentType.USER_POST, 1L, "content", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse contentHit = context.service().translate(
                TranslatableContentType.USER_POST, 2L, "content", "ko", "10.0.0.2", 2L);

        assertThat(titleHit.cached()).isTrue();
        assertThat(contentHit.cached()).isTrue();
        assertThat(context.providerCalls()).hasValue(2);
        assertThat(context.usage().calls).isEqualTo(2);
        assertThat(context.rateLimiter().externalCalls).isEqualTo(2);
    }

    @Test
    void changingOneUserPostFieldKeepsTheOtherFieldContentCacheReady() {
        Map<FieldSourceKey, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(new FieldSourceKey(1L, "title"),
                postSource(1L, "title", "Original title", "en", "text/plain"));
        sources.put(new FieldSourceKey(1L, "content"),
                postSource(1L, "content", "<p>Original body</p>", "en", "text/html"));
        TestContext context = fieldContext(sources);
        context.service().translate(
                TranslatableContentType.USER_POST, 1L, "title", "ko", "10.0.0.1", 1L);
        context.service().translate(
                TranslatableContentType.USER_POST, 1L, "content", "ko", "10.0.0.1", 1L);

        sources.put(new FieldSourceKey(1L, "title"),
                postSource(1L, "title", "Changed title", "en", "text/plain"));
        ContentTranslationResponse titleMiss = context.service().translate(
                TranslatableContentType.USER_POST, 1L, "title", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse unchangedContent = context.service().translate(
                TranslatableContentType.USER_POST, 1L, "content", "ko", "10.0.0.1", 1L);

        assertThat(titleMiss.cached()).isFalse();
        assertThat(unchangedContent.cached()).isTrue();
        assertThat(context.providerCalls()).hasValue(3);

        sources.put(new FieldSourceKey(1L, "content"),
                postSource(1L, "content", "<p>Changed body</p>", "en", "text/html"));
        ContentTranslationResponse unchangedTitle = context.service().translate(
                TranslatableContentType.USER_POST, 1L, "title", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse contentMiss = context.service().translate(
                TranslatableContentType.USER_POST, 1L, "content", "ko", "10.0.0.1", 1L);

        assertThat(unchangedTitle.cached()).isTrue();
        assertThat(contentMiss.cached()).isFalse();
        assertThat(context.providerCalls()).hasValue(4);
    }

    @Test
    void courseTitleAndHtmlContentReuseExactSharedEntriesWithoutAdditionalCost() {
        Map<FieldSourceKey, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(new FieldSourceKey(1L, "title"), fieldSource(
                TranslatableContentType.COURSE, 1L, "title",
                "Seoul One Day Trip", "en", "text/plain"));
        sources.put(new FieldSourceKey(2L, "title"), fieldSource(
                TranslatableContentType.COURSE, 2L, "title",
                "Seoul One Day Trip", "en", "text/plain"));
        sources.put(new FieldSourceKey(1L, "content"), fieldSource(
                TranslatableContentType.COURSE, 1L, "content",
                "<p>Same course description</p>", "en", "text/html"));
        sources.put(new FieldSourceKey(2L, "content"), fieldSource(
                TranslatableContentType.COURSE, 2L, "content",
                "<p>Same course description</p>", "en", "text/html"));
        TestContext context = fieldContext(TranslatableContentType.COURSE, sources);

        context.service().translate(TranslatableContentType.COURSE, 1L,
                "title", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse titleHit = context.service().translate(
                TranslatableContentType.COURSE, 2L,
                "title", "ko", "10.0.0.2", 2L);
        context.service().translate(TranslatableContentType.COURSE, 1L,
                "content", "ko", "10.0.0.1", 1L);
        ContentTranslationResponse contentHit = context.service().translate(
                TranslatableContentType.COURSE, 2L,
                "content", "ko", "10.0.0.2", 2L);

        assertThat(titleHit.cached()).isTrue();
        assertThat(contentHit.cached()).isTrue();
        assertThat(context.providerCalls()).hasValue(2);
        assertThat(context.usage().calls).isEqualTo(2);
        assertThat(context.rateLimiter().externalCalls).isEqualTo(2);
    }

    @Test
    void changingOneCourseFieldKeepsTheOtherFieldContentCacheReady() {
        Map<FieldSourceKey, TranslationSourceSnapshot> sources = new ConcurrentHashMap<>();
        sources.put(new FieldSourceKey(1L, "title"), fieldSource(
                TranslatableContentType.COURSE, 1L, "title",
                "Original course", "en", "text/plain"));
        sources.put(new FieldSourceKey(1L, "content"), fieldSource(
                TranslatableContentType.COURSE, 1L, "content",
                "<p>Original course body</p>", "en", "text/html"));
        TestContext context = fieldContext(TranslatableContentType.COURSE, sources);
        context.service().translate(TranslatableContentType.COURSE, 1L,
                "title", "ko", null, 1L);
        context.service().translate(TranslatableContentType.COURSE, 1L,
                "content", "ko", null, 1L);

        sources.put(new FieldSourceKey(1L, "title"), fieldSource(
                TranslatableContentType.COURSE, 1L, "title",
                "Changed course", "en", "text/plain"));
        ContentTranslationResponse titleMiss = context.service().translate(
                TranslatableContentType.COURSE, 1L, "title", "ko", null, 1L);
        ContentTranslationResponse contentHit = context.service().translate(
                TranslatableContentType.COURSE, 1L, "content", "ko", null, 1L);

        assertThat(titleMiss.cached()).isFalse();
        assertThat(contentHit.cached()).isTrue();
        assertThat(context.providerCalls()).hasValue(3);

        sources.put(new FieldSourceKey(1L, "content"), fieldSource(
                TranslatableContentType.COURSE, 1L, "content",
                "<p>Changed course body</p>", "en", "text/html"));
        ContentTranslationResponse titleHit = context.service().translate(
                TranslatableContentType.COURSE, 1L, "title", "ko", null, 1L);
        ContentTranslationResponse contentMiss = context.service().translate(
                TranslatableContentType.COURSE, 1L, "content", "ko", null, 1L);

        assertThat(titleHit.cached()).isTrue();
        assertThat(contentMiss.cached()).isFalse();
        assertThat(context.providerCalls()).hasValue(4);
    }

    private TestContext context(Map<Long, TranslationSourceSnapshot> sources) {
        return context(sources, false, false);
    }

    private TestContext context(Map<Long, TranslationSourceSnapshot> sources,
                                boolean blockProvider,
                                boolean failProvider) {
        FakeContentCacheMapper contentCaches = new FakeContentCacheMapper();
        FakeSharedCacheMapper sharedCaches = new FakeSharedCacheMapper();
        CountingUsageGate usage = new CountingUsageGate();
        CountingRateLimiter rateLimiter = new CountingRateLimiter();
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        MachineTranslationClient client = countingClient(providerCalls, request -> {
            providerStarted.countDown();
            if (blockProvider) {
                try {
                    releaseProvider.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failProvider) throw new MachineTranslationException("provider unavailable");
            return new MachineTranslation(request.target() + ":" + request.text(), request.source());
        });
        TranslationSourceRegistry registry = registry(sources);
        ContentTranslationService service = service(
                registry, contentCaches, sharedCaches, client, usage, rateLimiter,
                "google-v3-general-v1");
        return new TestContext(service, contentCaches, providerCalls, usage, rateLimiter,
                providerStarted, releaseProvider);
    }

    private TestContext fieldContext(Map<FieldSourceKey, TranslationSourceSnapshot> sources) {
        return fieldContext(TranslatableContentType.USER_POST, sources);
    }

    private TestContext fieldContext(TranslatableContentType type,
                                     Map<FieldSourceKey, TranslationSourceSnapshot> sources) {
        FakeContentCacheMapper contentCaches = new FakeContentCacheMapper();
        FakeSharedCacheMapper sharedCaches = new FakeSharedCacheMapper();
        CountingUsageGate usage = new CountingUsageGate();
        CountingRateLimiter rateLimiter = new CountingRateLimiter();
        AtomicInteger providerCalls = new AtomicInteger();
        MachineTranslationClient client = countingClient(providerCalls,
                request -> new MachineTranslation(request.target() + ":" + request.text(), request.source()));
        TranslationSourceRegistry registry = new TranslationSourceRegistry(
                List.of(new FieldSnapshotReader(type, sources)));
        ContentTranslationService service = service(
                registry, contentCaches, sharedCaches, client, usage, rateLimiter,
                "google-v3-general-v1");
        return new TestContext(service, contentCaches, providerCalls, usage, rateLimiter,
                new CountDownLatch(0), new CountDownLatch(0));
    }

    private ContentTranslationService service(
            TranslationSourceRegistry registry,
            FakeContentCacheMapper contentCaches,
            FakeSharedCacheMapper sharedCaches,
            MachineTranslationClient client,
            CountingUsageGate usage,
            CountingRateLimiter rateLimiter,
            String profile) {
        return new ContentTranslationService(
                registry, contentCaches, sharedCaches, client, rateLimiter, properties(),
                new TranslationCacheFinalizer(registry, contentCaches), usage,
                new TranslationProviderMetadata(profile));
    }

    private TranslationSourceRegistry registry(Map<Long, TranslationSourceSnapshot> sources) {
        return new TranslationSourceRegistry(List.of(
                new SnapshotReader(TranslatableContentType.DESTINATION_COMMENT, sources),
                new SnapshotReader(TranslatableContentType.POST_COMMENT, sources),
                new SnapshotReader(TranslatableContentType.COURSE_COMMENT, sources)));
    }

    private MachineTranslationClient countingClient(
            AtomicInteger calls,
            Function<TranslationRequest, MachineTranslation> translator) {
        return (text, source, target) -> {
            calls.incrementAndGet();
            return translator.apply(new TranslationRequest(text, source, target));
        };
    }

    private TranslationProperties properties() {
        return new TranslationProperties(
                2_000, Duration.ofSeconds(30), Duration.ofSeconds(30),
                1_000, 1_000, 1_000, Duration.ofMinutes(1), true, 400_000L,
                20_000L, 5_000L);
    }

    private static TranslationSourceSnapshot source(
            TranslatableContentType type, Long id, String text, String language) {
        return new TranslationSourceSnapshot(type, id, "content", text, language, VERSION);
    }

    private static TranslationSourceSnapshot postSource(
            Long id, String field, String text, String language, String mimeType) {
        return fieldSource(TranslatableContentType.USER_POST,
                id, field, text, language, mimeType);
    }

    private static TranslationSourceSnapshot fieldSource(
            TranslatableContentType type, Long id, String field,
            String text, String language, String mimeType) {
        return new TranslationSourceSnapshot(type, id, field, text, language, VERSION, mimeType);
    }

    private record TranslationRequest(String text, String source, String target) { }

    private record FieldSourceKey(Long id, String field) { }

    private record TestContext(
            ContentTranslationService service,
            FakeContentCacheMapper contentCaches,
            AtomicInteger providerCalls,
            CountingUsageGate usage,
            CountingRateLimiter rateLimiter,
            CountDownLatch providerStarted,
            CountDownLatch releaseProvider) { }

    private static final class SnapshotReader implements TranslationSourceReader {
        private final TranslatableContentType type;
        private final Map<Long, TranslationSourceSnapshot> sources;

        private SnapshotReader(TranslatableContentType type,
                               Map<Long, TranslationSourceSnapshot> sources) {
            this.type = type;
            this.sources = sources;
        }

        @Override
        public TranslatableContentType contentType() {
            return type;
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisible(Long contentId) {
            return Optional.ofNullable(sources.get(contentId)).filter(source -> source.contentType() == type);
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisibleForUpdate(Long contentId) {
            return findVisible(contentId);
        }
    }

    private static final class FieldSnapshotReader implements TranslationSourceReader {
        private final TranslatableContentType type;
        private final Map<FieldSourceKey, TranslationSourceSnapshot> sources;

        private FieldSnapshotReader(TranslatableContentType type,
                                    Map<FieldSourceKey, TranslationSourceSnapshot> sources) {
            this.type = type;
            this.sources = sources;
        }

        @Override
        public TranslatableContentType contentType() {
            return type;
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisible(Long contentId) {
            return findVisible(contentId, "content");
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisible(Long contentId, String sourceField) {
            return Optional.ofNullable(sources.get(new FieldSourceKey(contentId, sourceField)));
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisibleForUpdate(Long contentId) {
            return findVisibleForUpdate(contentId, "content");
        }

        @Override
        public Optional<TranslationSourceSnapshot> findVisibleForUpdate(
                Long contentId, String sourceField) {
            return findVisible(contentId, sourceField);
        }
    }

    private static final class CountingUsageGate implements TranslationUsageReservationGate {
        private int calls;
        private String lastText;

        @Override
        public synchronized void reserve(String sourceText, String ipAddress, Long userId) {
            calls++;
            lastText=sourceText;
        }
    }

    private static final class CountingRateLimiter implements TranslationRateLimiter {
        private int requestCalls;
        private int externalCalls;

        @Override
        public synchronized void checkRequest(String ipAddress, Long userId) {
            requestCalls++;
        }

        @Override
        public synchronized void checkExternalCall(String ipAddress, Long userId) {
            externalCalls++;
        }
    }

    private record ContentKey(String type, Long id, String field, String target) { }

    private static final class FakeContentCacheMapper implements ContentTranslationCacheMapper {
        private final Map<ContentKey, ContentTranslationCache> caches = new ConcurrentHashMap<>();
        @Override public synchronized int invalidateUnchangedReady(String type, Long id, String field, String target, byte[] hash, String text) {
            var cache=find(type,id,field,target);
            if(cache==null || !"READY".equals(cache.getStatus()) || !Arrays.equals(hash,cache.getSourceHash()) || !text.equals(cache.getTranslatedText())) return 0;
            cache.setStatus("FAILED");cache.setTranslatedText(null);cache.setRetryAfter(null);return 1;
        }

        @Override
        public synchronized ContentTranslationCache find(
                String contentType, Long contentId, String sourceField, String targetLanguage) {
            return caches.get(new ContentKey(contentType, contentId, sourceField, targetLanguage));
        }

        @Override
        public synchronized int insertProcessing(ContentTranslationCache cache) {
            ContentKey key = key(cache);
            if (caches.containsKey(key)) return 0;
            cache.setStatus("PROCESSING");
            caches.put(key, cache);
            return 1;
        }

        @Override
        public synchronized int tryClaim(
                String contentType, Long contentId, String sourceField, String targetLanguage,
                byte[] sourceHash, String leaseToken, Timestamp now, Timestamp leaseExpiresAt) {
            ContentTranslationCache current = find(contentType, contentId, sourceField, targetLanguage);
            if (current == null) return 0;
            boolean changed = !Arrays.equals(current.getSourceHash(), sourceHash);
            boolean expired = "PROCESSING".equals(current.getStatus())
                    && (current.getLeaseExpiresAt() == null
                    || !current.getLeaseExpiresAt().after(now));
            boolean retryable = "FAILED".equals(current.getStatus())
                    && (current.getRetryAfter() == null || !current.getRetryAfter().after(now));
            if (!changed && !expired && !retryable) return 0;
            current.setSourceHash(sourceHash);
            current.setTranslatedText(null);
            current.setDetectedSourceLanguage(null);
            current.setStatus("PROCESSING");
            current.setLeaseToken(leaseToken);
            current.setLeaseExpiresAt(leaseExpiresAt);
            current.setRetryAfter(null);
            return 1;
        }

        @Override
        public synchronized int markReady(
                String contentType, Long contentId, String sourceField, String targetLanguage,
                byte[] sourceHash, String leaseToken, String translatedText,
                String detectedSourceLanguage, Timestamp now) {
            ContentTranslationCache current = find(contentType, contentId, sourceField, targetLanguage);
            if (current == null || !Arrays.equals(current.getSourceHash(), sourceHash)
                    || !"PROCESSING".equals(current.getStatus())
                    || !leaseToken.equals(current.getLeaseToken())) return 0;
            current.setTranslatedText(translatedText);
            current.setDetectedSourceLanguage(detectedSourceLanguage);
            current.setStatus("READY");
            current.setLeaseToken(null);
            return 1;
        }

        @Override
        public synchronized int markFailed(
                String contentType, Long contentId, String sourceField, String targetLanguage,
                byte[] sourceHash, String leaseToken, Timestamp retryAfter, Timestamp now) {
            ContentTranslationCache current = find(contentType, contentId, sourceField, targetLanguage);
            if (current == null || !leaseToken.equals(current.getLeaseToken())) return 0;
            current.setStatus("FAILED");
            current.setTranslatedText(null);
            current.setLeaseToken(null);
            current.setRetryAfter(retryAfter);
            return 1;
        }

        @Override
        public synchronized int upsertReady(ContentTranslationCache cache) {
            cache.setStatus("READY");
            cache.setLeaseToken(null);
            ContentTranslationCache previous = caches.put(key(cache), cache);
            return previous == null ? 1 : 2;
        }

        @Override
        public synchronized List<ContentTranslationCache> findReadyAfter(Long afterId, int limit) {
            return caches.values().stream()
                    .filter(cache -> "READY".equals(cache.getStatus()))
                    .filter(cache -> cache.getId() != null && cache.getId() > afterId)
                    .sorted(Comparator.comparing(ContentTranslationCache::getId))
                    .limit(limit)
                    .toList();
        }

        private ContentKey key(ContentTranslationCache cache) {
            return new ContentKey(cache.getContentType(), cache.getContentId(),
                    cache.getSourceField(), cache.getTargetLanguage());
        }
    }

    private record SharedKey(
            String hash, String source, String target, String provider, String profile) { }

    private static final class FakeSharedCacheMapper implements SharedTranslationCacheMapper {
        private final Map<SharedKey, SharedTranslationCache> caches = new ConcurrentHashMap<>();
        @Override public synchronized int invalidateUnchangedReady(byte[] hash,String source,String target,String provider,String profile,String text) {
            var cache=find(hash,source,target,provider,profile);
            if(cache==null || !"READY".equals(cache.getStatus()) || !text.equals(cache.getTranslatedText())) return 0;
            cache.setStatus("FAILED");cache.setTranslatedText(null);cache.setRetryAfter(null);return 1;
        }

        @Override
        public synchronized SharedTranslationCache find(
                byte[] sourceHash, String sourceLanguage, String targetLanguage,
                String provider, String translationProfile) {
            return caches.get(key(sourceHash, sourceLanguage, targetLanguage, provider, translationProfile));
        }

        @Override
        public synchronized int insertProcessing(SharedTranslationCache cache) {
            SharedKey key = key(cache);
            if (caches.containsKey(key)) return 0;
            cache.setStatus("PROCESSING");
            caches.put(key, cache);
            return 1;
        }

        @Override
        public synchronized int tryClaim(
                byte[] sourceHash, String sourceLanguage, String targetLanguage,
                String provider, String translationProfile, String leaseToken,
                Timestamp now, Timestamp leaseExpiresAt) {
            SharedTranslationCache current = find(
                    sourceHash, sourceLanguage, targetLanguage, provider, translationProfile);
            if (current == null) return 0;
            boolean expired = "PROCESSING".equals(current.getStatus())
                    && (current.getLeaseExpiresAt() == null
                    || !current.getLeaseExpiresAt().after(now));
            boolean retryable = "FAILED".equals(current.getStatus())
                    && (current.getRetryAfter() == null || !current.getRetryAfter().after(now));
            if (!expired && !retryable) return 0;
            current.setStatus("PROCESSING");
            current.setLeaseToken(leaseToken);
            current.setLeaseExpiresAt(leaseExpiresAt);
            current.setRetryAfter(null);
            return 1;
        }

        @Override
        public synchronized int markReady(
                byte[] sourceHash, String sourceLanguage, String targetLanguage,
                String provider, String translationProfile, String leaseToken,
                String translatedText, String detectedSourceLanguage, Timestamp now) {
            SharedTranslationCache current = find(
                    sourceHash, sourceLanguage, targetLanguage, provider, translationProfile);
            if (current == null || !"PROCESSING".equals(current.getStatus())
                    || !leaseToken.equals(current.getLeaseToken())) return 0;
            current.setTranslatedText(translatedText);
            current.setDetectedSourceLanguage(detectedSourceLanguage);
            current.setStatus("READY");
            current.setLeaseToken(null);
            return 1;
        }

        @Override
        public synchronized int markFailed(
                byte[] sourceHash, String sourceLanguage, String targetLanguage,
                String provider, String translationProfile, String leaseToken,
                Timestamp retryAfter, Timestamp now) {
            SharedTranslationCache current = find(
                    sourceHash, sourceLanguage, targetLanguage, provider, translationProfile);
            if (current == null || !leaseToken.equals(current.getLeaseToken())) return 0;
            current.setStatus("FAILED");
            current.setTranslatedText(null);
            current.setLeaseToken(null);
            current.setRetryAfter(retryAfter);
            return 1;
        }

        @Override
        public synchronized int deleteProcessing(
                byte[] sourceHash, String sourceLanguage, String targetLanguage,
                String provider, String translationProfile, String leaseToken) {
            SharedKey key = key(sourceHash, sourceLanguage, targetLanguage, provider, translationProfile);
            SharedTranslationCache current = caches.get(key);
            if (current == null || !"PROCESSING".equals(current.getStatus())
                    || !leaseToken.equals(current.getLeaseToken())) return 0;
            caches.remove(key);
            return 1;
        }

        @Override
        public synchronized int insertReadyIfAbsent(SharedTranslationCache cache) {
            SharedKey key = key(cache);
            if (caches.containsKey(key)) return 0;
            cache.setStatus("READY");
            caches.put(key, cache);
            return 1;
        }

        private SharedKey key(SharedTranslationCache cache) {
            return key(cache.getSourceHash(), cache.getSourceLanguage(), cache.getTargetLanguage(),
                    cache.getProvider(), cache.getTranslationProfile());
        }

        private SharedKey key(byte[] sourceHash, String sourceLanguage, String targetLanguage,
                              String provider, String translationProfile) {
            return new SharedKey(Base64.getEncoder().encodeToString(sourceHash),
                    sourceLanguage, targetLanguage, provider, translationProfile);
        }
    }
}
