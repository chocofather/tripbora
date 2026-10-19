package com.tripbora.service.travelplan;

import com.tripbora.dto.TravelPlanFinalDetailDto;
import com.tripbora.dto.TravelPlanFinalListItemDto;
import com.tripbora.model.TravelPlanFinalItem;
import com.tripbora.model.TravelPlanFinalItemAlternative;
import com.tripbora.model.TravelPlanFinalSnapshot;
import com.tripbora.repository.travelplan.TravelPlanFinalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 완료된 여행 읽기.
 *
 * <p>최종본은 한번 만들어지면 바뀌지 않는다. 여기서는 읽기만 한다.
 * 일정·참여자·기간은 최종본을 기준으로 읽는다.
 * 스냅샷에 컬럼이 없는 DAY 제목만 확정 후 수정이 차단된 원본 DAY에서 보완한다.
 *
 * <p>볼 수 있는 사람은 완료 시점에 그 여행에 있던 사람뿐이다.
 * 방장이든 아니든 같은 최종본을 본다.
 */
@Service
@RequiredArgsConstructor
public class TravelPlanFinalReadService {

    private final TravelPlanFinalMapper travelPlanFinalMapper;

    /**
     * 내가 함께했던 완료된 여행 목록 한 쪽. 최근에 끝난 것부터 온다.
     *
     * <p>쪽 크기만큼만 DB 에서 끊어 온다. 전부 읽어 온 뒤 자르지 않는다.
     */
    @Transactional(readOnly = true)
    public List<TravelPlanFinalListItemDto> getCompletedPlans(Long userId, int offset, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        List<TravelPlanFinalListItemDto> plans =
                travelPlanFinalMapper.findSnapshotsByUserId(userId, Math.max(offset, 0), limit);
        return plans == null ? List.of() : plans;
    }

    /**
     * 내가 함께했던 완료된 여행 수. 목록 탭 숫자와 쪽수 계산에 쓴다.
     * 내 목록에서 지운 것은 목록에 나오지 않으므로 여기서도 세지 않는다.
     */
    @Transactional(readOnly = true)
    public int countCompletedPlans(Long userId) {
        if (userId == null) {
            return 0;
        }
        return travelPlanFinalMapper.countSnapshotsByUserId(userId);
    }

    /**
     * 완료된 여행 한 벌.
     *
     * <p>그 여행에 함께했던 사람이 아니면 최종본의 존재 자체를 알리지 않는다
     * (다른 방 조회와 같은 관례로 404).
     */
    @Transactional(readOnly = true)
    public TravelPlanFinalDetailDto getCompletedPlanDetail(Long userId, Long travelPlanId) {
        if (userId == null || travelPlanId == null) {
            throw finalPlanNotFound();
        }
        // 볼 자격은 이 조회 하나로 확인된다. 함께하지 않았으면 여기서 비어 온다.
        TravelPlanFinalSnapshot snapshot =
                travelPlanFinalMapper.findSnapshotByPlanAndUser(travelPlanId, userId);
        if (snapshot == null) {
            throw finalPlanNotFound();
        }

        Long snapshotId = snapshot.getId();
        return new TravelPlanFinalDetailDto(
                snapshot,
                travelPlanFinalMapper.findMembersBySnapshotId(snapshotId),
                travelPlanFinalMapper.findDaysBySnapshotId(snapshotId),
                // 날짜·일정 수만큼 조회가 나가지 않도록 한 번에 읽어 묶는다.
                groupBy(travelPlanFinalMapper.findItemsBySnapshotId(snapshotId),
                        TravelPlanFinalItem::getFinalDayId),
                groupBy(travelPlanFinalMapper.findAlternativesBySnapshotId(snapshotId),
                        TravelPlanFinalItemAlternative::getFinalItemId));
    }

    /** 읽어 온 순서를 그대로 지키며 묶는다. 완료 시점의 차례가 화면에 그대로 나온다. */
    private <T> Map<Long, List<T>> groupBy(List<T> rows, Function<T, Long> key) {
        return rows == null
                ? Map.of()
                : rows.stream().collect(Collectors.groupingBy(
                        key, LinkedHashMap::new, Collectors.toList()));
    }

    private ResponseStatusException finalPlanNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "완료된 여행을 찾을 수 없습니다.");
    }
}
