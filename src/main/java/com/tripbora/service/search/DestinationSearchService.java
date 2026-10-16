package com.tripbora.service.search;

import com.tripbora.dto.DestinationSearchResultDto;

import java.util.List;

public interface DestinationSearchService {
    List<DestinationSearchResultDto> search(String keyword, int limit, Long countryId);
    List<DestinationSearchResultDto> search(String keyword, int limit, Long countryId, String scope, Long regionId);

}
