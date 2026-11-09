package com.tripbora.repository.translation;

import com.tripbora.model.translation.ContentTranslationCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface ContentTranslationCacheMapper {
    int invalidateUnchangedReady(@Param("contentType") String contentType, @Param("contentId") Long contentId,
            @Param("sourceField") String sourceField, @Param("targetLanguage") String targetLanguage,
            @Param("sourceHash") byte[] sourceHash, @Param("translatedText") String translatedText);
    ContentTranslationCache find(@Param("contentType") String contentType,
                                 @Param("contentId") Long contentId,
                                 @Param("sourceField") String sourceField,
                                 @Param("targetLanguage") String targetLanguage);

    int insertProcessing(ContentTranslationCache cache);

    int tryClaim(@Param("contentType") String contentType,
                 @Param("contentId") Long contentId,
                 @Param("sourceField") String sourceField,
                 @Param("targetLanguage") String targetLanguage,
                 @Param("sourceHash") byte[] sourceHash,
                 @Param("leaseToken") String leaseToken,
                 @Param("now") Timestamp now,
                 @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    int markReady(@Param("contentType") String contentType,
                  @Param("contentId") Long contentId,
                  @Param("sourceField") String sourceField,
                  @Param("targetLanguage") String targetLanguage,
                  @Param("sourceHash") byte[] sourceHash,
                  @Param("leaseToken") String leaseToken,
                  @Param("translatedText") String translatedText,
                  @Param("detectedSourceLanguage") String detectedSourceLanguage,
                  @Param("now") Timestamp now);

    int markFailed(@Param("contentType") String contentType,
                   @Param("contentId") Long contentId,
                   @Param("sourceField") String sourceField,
                   @Param("targetLanguage") String targetLanguage,
                   @Param("sourceHash") byte[] sourceHash,
                   @Param("leaseToken") String leaseToken,
                   @Param("retryAfter") Timestamp retryAfter,
                   @Param("now") Timestamp now);

    int upsertReady(ContentTranslationCache cache);

    List<ContentTranslationCache> findReadyAfter(@Param("afterId") Long afterId,
                                                 @Param("limit") int limit);
}
