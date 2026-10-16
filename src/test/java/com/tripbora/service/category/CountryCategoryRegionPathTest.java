package com.tripbora.service.category;

import com.tripbora.service.file.FileUploadService;
import com.tripbora.model.CountryCategory;
import com.tripbora.repository.category.CountryCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryCategoryRegionPathTest {

    @Mock
    private CountryCategoryMapper mapper;

    private CountryCategoryService service;

    @BeforeEach
    void setUp() {
        service = new CountryCategoryService(
                mapper, mock(FileUploadService.class), new CountryCategoryCache());
    }

    @Test
    void regionPathIsReturnedFromRootToSelectedRegion() {
        CountryCategory korea = region(7L, "대한민국", null);
        CountryCategory seoul = region(38L, "서울", 7L);
        CountryCategory jongno = region(235L, "종로구", 38L);
        when(mapper.selectById(235L)).thenReturn(jongno);
        when(mapper.selectById(38L)).thenReturn(seoul);
        when(mapper.selectById(7L)).thenReturn(korea);

        assertThat(service.getRegionPath(235L))
                .extracting(CountryCategory::getId)
                .containsExactly(7L, 38L, 235L);
    }

    @Test
    void pathsReuseCachedCommonAncestorsAcrossDifferentCourseItems() {
        when(mapper.selectById(7L)).thenReturn(region(7L, "대한민국", null));
        when(mapper.selectById(38L)).thenReturn(region(38L, "서울", 7L));
        when(mapper.selectById(235L)).thenReturn(region(235L, "종로구", 38L));
        when(mapper.selectById(236L)).thenReturn(region(236L, "강서구", 38L));
        service.getRegionPath(235L);
        service.getRegionPath(236L);
        service.getRegionPath(235L);
        verify(mapper).selectById(7L);
        verify(mapper).selectById(38L);
        verify(mapper).selectById(235L);
        verify(mapper).selectById(236L);
    }

    @Test
    void regionPathIsEmptyWhenRegionIsMissing() {
        assertThat(service.getRegionPath(null)).isEmpty();

        when(mapper.selectById(999L)).thenReturn(null);
        assertThat(service.getRegionPath(999L)).isEmpty();
    }

    @Test
    void regionPathIsEmptyWhenParentChainLoops() {
        CountryCategory first = region(11L, "A", 12L);
        CountryCategory second = region(12L, "B", 11L);
        lenient().when(mapper.selectById(11L)).thenReturn(first);
        lenient().when(mapper.selectById(12L)).thenReturn(second);

        assertThat(service.getRegionPath(11L)).isEmpty();
    }

    @Test
    void descendantRegionIdsAreLoadedByOneMapperQueryIncludingTheRoot() {
        when(mapper.findAllRegionIdsUnder(7L)).thenReturn(List.of(7L, 38L, 235L));

        assertThat(service.getAllRegionIdsUnder(7L))
                .containsExactly(7L, 38L, 235L);
        verify(mapper).findAllRegionIdsUnder(7L);
        verify(mapper, never()).selectByParentId(anyLong());
    }

    private CountryCategory region(Long id, String regionName, Long parentId) {
        CountryCategory category = new CountryCategory();
        category.setId(id);
        category.setRegionName(regionName);
        category.setParentId(parentId);
        return category;
    }
}
