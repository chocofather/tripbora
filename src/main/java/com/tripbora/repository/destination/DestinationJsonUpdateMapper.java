package com.tripbora.repository.destination;

import com.tripbora.model.DestinationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** All identifiers are literal XML allowlists; values are always bound parameters. */
@Mapper
public interface DestinationJsonUpdateMapper {
    List<Map<String, Object>> findExternalTargets(@Param("sourceType") String sourceType, @Param("contentIds") List<String> contentIds);
    List<Map<String, Object>> findBases(@Param("ids") List<Long> ids);
    List<Map<String, Object>> findTranslationsBatch(@Param("ids") List<Long> ids);
    List<Map<String, Object>> findCategoriesBatch(@Param("ids") List<Long> ids);
    List<Map<String, Object>> findInfoBatch(@Param("ids") List<Long> ids, @Param("type") DestinationType type);
    List<Map<String, Object>> findInfoTranslationsBatch(@Param("ids") List<Long> ids, @Param("type") DestinationType type);
    List<Map<String, Object>> findAmenitiesBatch(@Param("ids") List<Long> ids, @Param("type") DestinationType type);
    Long lockDestination(@Param("id") Long id);
    Map<String, Object> findBase(@Param("id") Long id, @Param("lock") boolean lock);
    List<Map<String, Object>> findTranslations(@Param("id") Long id, @Param("lock") boolean lock);
    Map<String, Object> findInfo(@Param("id") Long id, @Param("type") DestinationType type, @Param("lock") boolean lock);
    List<Map<String, Object>> findInfoTranslations(@Param("id") Long id, @Param("type") DestinationType type, @Param("lock") boolean lock);
    List<Map<String, Object>> findCategories(@Param("id") Long id, @Param("lock") boolean lock);
    List<Map<String, Object>> findAmenities(@Param("id") Long id, @Param("type") DestinationType type, @Param("lock") boolean lock);
    void updateBaseField(@Param("id") Long id, @Param("field") String field, @Param("value") Object value);
    void insertTranslation(@Param("id") Long id, @Param("language") String language);
    void updateTranslationField(@Param("id") Long id, @Param("language") String language, @Param("field") String field, @Param("value") Object value);
    void insertInfo(@Param("id") Long id, @Param("type") DestinationType type);
    void updateInfoField(@Param("id") Long id, @Param("type") DestinationType type, @Param("field") String field, @Param("value") Object value);
    void insertInfoTranslation(@Param("id") Long id, @Param("type") DestinationType type, @Param("language") String language);
    void updateInfoTranslationField(@Param("id") Long id, @Param("type") DestinationType type, @Param("language") String language, @Param("field") String field, @Param("value") Object value);
    void deleteAmenity(@Param("id") Long id, @Param("type") DestinationType type, @Param("amenityId") Integer amenityId);
    void insertAmenity(@Param("id") Long id, @Param("type") DestinationType type, @Param("amenityId") Integer amenityId);
}
