package com.tripbora.service.course;

import com.tripbora.dto.CourseDestinationSnapshotDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.model.CourseCountry;
import com.tripbora.model.CourseDay;
import com.tripbora.model.CourseItem;
import com.tripbora.model.DestinationType;
import com.tripbora.repository.course.CourseStructureMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 기존 Course 쓰기 트랜잭션에 참여하는 Phase 2의 단일 snapshot/sync 경계. */
@Service
@RequiredArgsConstructor
public class CourseStructureService {
    private final CourseStructureMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronizeDayOne(Long courseId, Long countryId, List<Long> destinationIds) {
        Map<Long, CourseDestinationSnapshotDto> sources = mapper.findDestinationSnapshots(destinationIds)
                .stream().collect(Collectors.toMap(CourseDestinationSnapshotDto::getDestinationId,
                        Function.identity()));
        if (sources.size() != destinationIds.size()
                || !sources.keySet().containsAll(destinationIds)) {
            throw new IllegalStateException("코스 여행지 snapshot을 확인할 수 없습니다.");
        }
        CourseDay day = mapper.findDayOne(courseId);
        if (day == null) {
            day = new CourseDay();
            day.setCourseId(courseId);
            day.setDayNumber(1);
            if (mapper.insertDay(day) != 1 || day.getId() == null) {
                throw new IllegalStateException("코스 Day 저장에 실패했습니다.");
            }
        }
        // CourseService가 Course 행을 잠근 뒤 호출한다. Day ID는 보존하고 UNIQUE 순서는 비운 뒤 채운다.
        mapper.deleteDayItems(day.getId());
        for (int index = 0; index < destinationIds.size(); index++) {
            CourseItem item = snapshot(sources.get(destinationIds.get(index)));
            item.setCourseDayId(day.getId());
            item.setDisplayOrder(index + 1);
            if (mapper.insertItem(item) != 1) {
                throw new IllegalStateException("코스 Item 저장에 실패했습니다.");
            }
        }
        mapper.deleteCountries(courseId);
        CourseCountry country = new CourseCountry();
        country.setCourseId(courseId);
        country.setCountryId(countryId);
        country.setDisplayOrder(1);
        if (mapper.insertCountry(country) != 1) {
            throw new IllegalStateException("코스 국가 저장에 실패했습니다.");
        }
    }

    public List<CourseStopDto> findDayOneStops(Long courseId) {
        return mapper.findDayOneStops(courseId);
    }

    private CourseItem snapshot(CourseDestinationSnapshotDto source) {
        CourseItem item = new CourseItem();
        item.setDestinationId(source.getDestinationId());
        item.setCountryId(source.getCountryId());
        item.setPlaceName(source.getPlaceName());
        DestinationType type = source.getType();
        item.setItemType(type == DestinationType.ACCOMMODATION ? "STAY"
                : type == DestinationType.RESTAURANTS || type == DestinationType.CAFE ? "FOOD" : "PLACE");
        // Phase 1 CHECK/backfill과 동일: 한쪽 누락 또는 범위 이탈이면 둘 다 NULL.
        if (inRange(source.getLatitude(), -90, 90) && inRange(source.getLongitude(), -180, 180)) {
            item.setLatitude(source.getLatitude());
            item.setLongitude(source.getLongitude());
        }
        return item;
    }

    private boolean inRange(BigDecimal value, int minimum, int maximum) {
        return value != null && value.compareTo(BigDecimal.valueOf(minimum)) >= 0
                && value.compareTo(BigDecimal.valueOf(maximum)) <= 0;
    }
}
