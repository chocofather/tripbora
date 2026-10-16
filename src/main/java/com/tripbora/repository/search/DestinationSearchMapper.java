package com.tripbora.repository.search;

import com.tripbora.dto.DestinationSearchResultDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DestinationSearchMapper {
    List<DestinationSearchResultDto> searchDestinations(@Param("keyword") String keyword,
                                                        @Param("limit") int limit,
                                                        @Param("countryId") Long countryId);

    List<DestinationSearchResultDto> searchFilteredDestinations(@Param("keyword") String keyword,
            @Param("limit") int limit, @Param("countryId") Long countryId,
            @Param("scope") String scope, @Param("regionId") Long regionId);
}
