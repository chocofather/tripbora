package com.tripbora.service.category;

import com.tripbora.model.CountryCategory;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.service.file.FileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CountryCategoryService {

    /** 지역 아이콘을 두는 곳. 예전에 올린 아이콘과 같은 자리라 기존 경로가 그대로 열린다. */
    static final String ICON_DIRECTORY = "icons";

    private final CountryCategoryMapper mapper;
    /** 이미지 검증과 저장은 다른 업로드와 한 벌을 쓴다. 여기에 따로 만들지 않는다. */
    private final FileUploadService fileUploadService;
    /**
     * 요청 사이에도 들고 있는 기준 데이터.
     *
     * <p>여러 번 불리고 좀처럼 바뀌지 않는 조회만 골라 담는다. 무작위로 고르는 조회와
     * 어쩌다 한 번 부르는 조회는 담지 않는다 — 담아 봐야 이득이 없고, 무작위는 뜻이 바뀐다.
     */
    private final CountryCategoryCache cache;

    // 1. depth1(최상위): 대륙 or 대한민국만
    public List<CountryCategory> getRootRegions() {
        return cache.readList(CountryCategoryCache.Key.of("depth1"), mapper::selectDepth1);
    }

    // 2. 특정 부모의 하위 지역 리스트
    public List<CountryCategory> getRegionsByParentId(Long parentId) {
        return mapper.selectByParentId(parentId);
    }

    // 3. 특정 depth 전체 리스트
    public List<CountryCategory> getRegionsByDepth(int depth) {
        // parentId 없이 전체를 뽑을 때는 null로
        return findByDepth(depth, null);
    }

    public List<CountryCategory> getCourseCountries() {
        return cache.readList(
                CountryCategoryCache.Key.of("courseCountries"), mapper::selectCourseCountries);
    }

    // 4. 카테고리 ID로 조회
    public CountryCategory getById(Long id) {
        return cache.read(CountryCategoryCache.Key.of("byId", id), () -> mapper.selectById(id));
    }

    /** depth(+parent) 목록. 여러 메서드가 같은 조합을 나눠 쓰므로 한 자리에 모은다. */
    private List<CountryCategory> findByDepth(int depth, Long parentId) {
        return cache.readList(CountryCategoryCache.Key.of("byDepth", depth, parentId),
                () -> mapper.findByDepth(depth, parentId));
    }

    // 4-1. 최상위부터 해당 지역까지의 경로 (수정 화면 지역 select 복원용)
    public List<CountryCategory> getRegionPath(Long regionId) {
        LinkedList<CountryCategory> path = new LinkedList<>();
        Set<Long> visitedIds = new HashSet<>();

        Long currentId = regionId;
        while (currentId != null) {
            if (!visitedIds.add(currentId)) {
                return List.of();
            }
            CountryCategory region = getById(currentId);
            if (region == null) {
                return List.of();
            }
            path.addFirst(region);
            currentId = region.getParentId();
        }
        return path;
    }

    /**
     * 5. 아이콘 저장/업데이트
     *
     * <p>저장 위치가 {@code /uploads/icons/**} 라 올린 파일이 같은 origin 에서 그대로 공개된다.
     * 그래서 올린 이름과 클라이언트가 말한 형식은 쓰지 않고, 다른 이미지 업로드와 <b>같은</b>
     * 검증({@link FileUploadService#saveFile(MultipartFile, String)})을 지나게 한다.
     * 실제로 펼쳐지는 JPEG/PNG/WEBP 만 통과하고 파일 이름은 서버가 정한 UUID 다 —
     * HTML·SVG·XML 처럼 브라우저가 실행하는 내용은 들어올 수 없다.
     *
     * <p>지역 아이콘에 SVG 가 필요하지 않으므로 이 경로에서는 받지 않는다.
     * (편의시설 아이콘만 별도의 엄격한 SVG 검사를 거쳐 허용한다)
     *
     * @throws com.tripbora.service.file.UnsupportedImageFormatException
     *         이미지가 아니거나 허용 형식이 아닐 때. 파일은 저장되지 않는다.
     */
    public void saveIcon(Long id, MultipartFile file) {
        if (file == null || file.isEmpty()) return;

        String iconPath = fileUploadService.saveFile(file, ICON_DIRECTORY);
        mapper.updateIconPath(id, iconPath);
        // 아이콘 경로가 바뀌었다. 들고 있던 지역을 버려야 바뀐 아이콘이 바로 보인다.
        cache.invalidate();
    }

    // 6. 특정 지역의 모든 하위 지역(자손까지) ID 반환
    public List<Long> getAllRegionIdsUnder(Long parentId) {
        // 재귀 질의라 이 표에서 가장 비싸고, 여행지 목록이 한 요청에 여러 번 부른다.
        return cache.readList(CountryCategoryCache.Key.of("subtree", parentId),
                () -> mapper.findAllRegionIdsUnder(parentId));
    }

    // 7. 특정 parent에서 depth까지의 지역 반환 (예: parent=서울, depth=4 -> 구)
    public List<CountryCategory> getSubregions(Long parentId, int depth) {
        return cache.readList(CountryCategoryCache.Key.of("subregions", parentId, depth),
                () -> mapper.selectByParentIdAndDepth(parentId, depth));
    }

    // 8. [국내] 현재 계층에서 최상위에 놓인 실제 국가 root id 반환
    public List<Long> getDomesticRootIds() {
        return getRootCountries().stream()
                .map(CountryCategory::getId)
                .toList();
    }

    // 9. [해외] 대륙 루트 id 복수 반환
    public List<Long> getOverseasRootIds() {
        Set<Long> domesticRootIds = new HashSet<>(getDomesticRootIds());
        return findByDepth(1, null).stream()
                .filter(category -> !domesticRootIds.contains(category.getId()))
                .map(CountryCategory::getId)
                .toList();
    }

    // 10. depth1 전체(대륙/대한민국) 반환
    public List<CountryCategory> getContinentRoots() {
        // parentId 조건 없이 전체 depth=1 뽑으려면 null 넘겨야 함
        return findByDepth(1, null);
    }
    // depth + parentId 조합으로 리스트
    public List<CountryCategory> getRegionsByDepthAndParent(int depth, Long parentId) {
        return findByDepth(depth, parentId);
    }

    // 현재 구조상 최상위에 놓인 실제 국가(대한민국) 뽑기
    public Long getKoreaRootId() {
        return getRootCountries().stream()
                .map(CountryCategory::getId)
                .findFirst()
                .orElse(null);
    }

    // region_id로 code 반환
    public String getCodeById(Long regionId) {
        return mapper.getCodeById(regionId);
    }

    public List<CountryCategory> getOverseasContinentRegions() {
        Set<Long> domesticRootIds = new HashSet<>(getDomesticRootIds());
        return findByDepth(1, null).stream()
                .filter(category -> !domesticRootIds.contains(category.getId()))
                .toList();
    }

    private List<CountryCategory> getRootCountries() {
        return getCourseCountries().stream()
                .filter(country -> country.getParentId() == null)
                .toList();
    }

    public List<CountryCategory> getRegionsByIds(List<Long> ids) {
        return mapper.selectByIds(ids);
    }

    public List<CountryCategory> findRandomOverseasCountries(int limit) {
        return mapper.findRandomOverseasCountries(limit);
    }
}
