package com.tripbora.repository.translation;

import com.tripbora.model.translation.SharedTranslationCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface SharedTranslationCacheMapper {
    int invalidateUnchangedReady(@Param("sourceHash") byte[] sourceHash, @Param("sourceLanguage") String sourceLanguage,
            @Param("targetLanguage") String targetLanguage, @Param("provider") String provider,
            @Param("translationProfile") String translationProfile, @Param("translatedText") String translatedText);
    SharedTranslationCache find(@Param("sourceHash") byte[] sourceHash,
                                @Param("sourceLanguage") String sourceLanguage,
                                @Param("targetLanguage") String targetLanguage,
                                @Param("provider") String provider,
                                @Param("translationProfile") String translationProfile);

    int insertProcessing(SharedTranslationCache cache);

    int tryClaim(@Param("sourceHash") byte[] sourceHash,
                 @Param("sourceLanguage") String sourceLanguage,
                 @Param("targetLanguage") String targetLanguage,
                 @Param("provider") String provider,
                 @Param("translationProfile") String translationProfile,
                 @Param("leaseToken") String leaseToken,
                 @Param("now") Timestamp now,
                 @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    int markReady(@Param("sourceHash") byte[] sourceHash,
                  @Param("sourceLanguage") String sourceLanguage,
                  @Param("targetLanguage") String targetLanguage,
                  @Param("provider") String provider,
                  @Param("translationProfile") String translationProfile,
                  @Param("leaseToken") String leaseToken,
                  @Param("translatedText") String translatedText,
                  @Param("detectedSourceLanguage") String detectedSourceLanguage,
                  @Param("now") Timestamp now);

    int markFailed(@Param("sourceHash") byte[] sourceHash,
                   @Param("sourceLanguage") String sourceLanguage,
                   @Param("targetLanguage") String targetLanguage,
                   @Param("provider") String provider,
                   @Param("translationProfile") String translationProfile,
                   @Param("leaseToken") String leaseToken,
                   @Param("retryAfter") Timestamp retryAfter,
                   @Param("now") Timestamp now);

    int deleteProcessing(@Param("sourceHash") byte[] sourceHash,
                         @Param("sourceLanguage") String sourceLanguage,
                         @Param("targetLanguage") String targetLanguage,
                         @Param("provider") String provider,
                         @Param("translationProfile") String translationProfile,
                         @Param("leaseToken") String leaseToken);

    int insertReadyIfAbsent(SharedTranslationCache cache);
}
