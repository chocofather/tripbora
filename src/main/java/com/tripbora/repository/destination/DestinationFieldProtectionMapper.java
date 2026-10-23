package com.tripbora.repository.destination;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Collection;
import java.util.Map;

@Mapper
public interface DestinationFieldProtectionMapper {
    List<String> findFieldPaths(@Param("destinationId") Long destinationId);
    List<Map<String, Object>> findProtectedFieldsBatch(@Param("ids") Collection<Long> ids);

    void insert(@Param("destinationId") Long destinationId,
                @Param("fieldPath") String fieldPath,
                @Param("userId") Long userId);
}
