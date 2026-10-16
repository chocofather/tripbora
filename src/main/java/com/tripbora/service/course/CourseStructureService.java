package com.tripbora.service.course;

import com.tripbora.dto.CourseDestinationSnapshotDto;
import com.tripbora.dto.CourseDayForm;
import com.tripbora.dto.CourseItemForm;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Course 쓰기 트랜잭션에 참여하는 일정/snapshot 저장 경계. */
@Service
@RequiredArgsConstructor
public class CourseStructureService {
    private final CourseStructureMapper mapper;

    public List<CourseDay> findDays(Long courseId) {
        return mapper.findDays(courseId);
    }

    public List<CourseStopDto> findCourseStops(Long courseId) {
        return mapper.findCourseStops(courseId);
    }

    public List<com.tripbora.model.CountryCategory> findCountries(Long courseId) {
        return mapper.findCountries(courseId);
    }

    public List<CourseDestinationSnapshotDto> findDestinationSnapshots(List<Long> destinationIds) {
        return destinationIds.isEmpty() ? List.of() : mapper.findDestinationSnapshots(destinationIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceSchedule(Long courseId, Long countryId, List<CourseDayForm> days) {
        List<Long> destinationIds = CourseScheduleValidator.validate(days);
        Map<Long, CourseDestinationSnapshotDto> sources = findDestinationSnapshots(destinationIds)
                .stream().collect(Collectors.toMap(CourseDestinationSnapshotDto::getDestinationId,
                        Function.identity()));
        if (sources.size() != destinationIds.size() || !sources.keySet().containsAll(destinationIds)) {
            throw new IllegalStateException("코스 여행지 snapshot을 확인할 수 없습니다.");
        }
        // 모든 snapshot을 쓰기 전에 구성해 잘못된 입력이 기존 Day 삭제 뒤 발견되지 않게 한다.
        List<CourseItem> preparedItems = days.stream().flatMap(day -> day.getItems().stream())
                .map(input -> input.getDestinationId() == null ? direct(input)
                        : snapshot(sources.get(input.getDestinationId())))
                .toList();
        LinkedHashSet<Long> countryIds = new LinkedHashSet<>();
        for (CourseItem item : preparedItems) {
            if (item.getCountryId() == null || item.getCountryId() <= 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "국가 정보를 확인할 수 없는 일정이 포함되어 있습니다.");
            }
            countryIds.add(item.getCountryId());
        }
        var prepared = preparedItems.iterator();
        List<CourseDay> existing = mapper.findDays(courseId);
        Map<Long, CourseDay> existingById = existing.stream()
                .collect(Collectors.toMap(CourseDay::getId, Function.identity()));
        Set<Long> retained = new HashSet<>();
        for (CourseDayForm day : days) {
            if (day.getId() != null && (!existingById.containsKey(day.getId()) || !retained.add(day.getId()))) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "유효하지 않은 Day가 포함되어 있습니다.");
            }
        }
        // 삭제될 Day만 제거한다. 그 Day의 Item은 FK CASCADE로 같은 트랜잭션에서 제거된다.
        for (CourseDay old : existing) {
            if (!retained.contains(old.getId()) && mapper.deleteDay(courseId, old.getId()) != 1) {
                throw new IllegalStateException("코스 Day 삭제에 실패했습니다.");
            }
        }
        if (!retained.isEmpty()) {
            int maximum = existing.stream().mapToInt(CourseDay::getDayNumber).max().orElse(0);
            int offset = Math.addExact(maximum, days.size());
            Math.addExact(maximum, offset); // INT 범위를 넘는 비정상 데이터는 쓰기 전에 실패한다.
            mapper.parkDayNumbers(courseId, offset);
        }
        for (CourseDayForm form : days) {
            CourseDay day = new CourseDay();
            day.setId(form.getId());
            day.setCourseId(courseId);
            day.setDayNumber(form.getDayNumber());
            day.setTitle(form.getTitle() == null || form.getTitle().isBlank() ? null : form.getTitle());
            if (day.getId() == null) {
                if (mapper.insertDay(day) != 1 || day.getId() == null) {
                    throw new IllegalStateException("코스 Day 저장에 실패했습니다.");
                }
            } else {
                if (mapper.updateDay(day) != 1) throw new IllegalStateException("코스 Day 수정에 실패했습니다.");
                mapper.deleteDayItems(day.getId());
            }
            for (CourseItemForm input : form.getItems()) {
                CourseItem item = prepared.next();
                item.setCourseDayId(day.getId());
                item.setDisplayOrder(input.getDisplayOrder());
                item.setStartTime(CourseScheduleValidator.parseTime(input.getStartTime()));
                item.setEndTime(null);
                item.setMemo(input.getMemo() == null || input.getMemo().isBlank() ? null : input.getMemo());
                if (mapper.insertItem(item) != 1) throw new IllegalStateException("코스 Item 저장에 실패했습니다.");
            }
        }
        mapper.deleteCountries(courseId);
        int countryOrder = 1;
        for (Long itemCountryId : countryIds) {
            CourseCountry country = new CourseCountry();
            country.setCourseId(courseId);
            country.setCountryId(itemCountryId);
            country.setDisplayOrder(countryOrder++);
            if (mapper.insertCountry(country) != 1) throw new IllegalStateException("코스 국가 저장에 실패했습니다.");
        }
        if (mapper.syncLegacyCountry(courseId) != 1) throw new IllegalStateException("코스 대표 국가 동기화에 실패했습니다.");
    }

    private CourseItem direct(CourseItemForm input) {
        CourseItem item = new CourseItem();
        item.setCountryId(input.getCountryId());
        item.setItemType(input.getItemType());
        item.setPlaceName(input.getPlaceName().trim());
        item.setAddress(input.getAddress() == null || input.getAddress().isBlank() ? null : input.getAddress().trim());
        item.setLatitude(input.getLatitude());
        item.setLongitude(input.getLongitude());
        return item;
    }

    private CourseItem snapshot(CourseDestinationSnapshotDto source) {
        CourseItem item = new CourseItem();
        item.setDestinationId(source.getDestinationId());
        item.setCountryId(source.getCountryId());
        item.setPlaceName(source.getPlaceName());
        item.setItemType(itemType(source.getType()));
        // Phase 1 CHECK/backfill과 동일: 한쪽 누락 또는 범위 이탈이면 둘 다 NULL.
        if (inRange(source.getLatitude(), -90, 90) && inRange(source.getLongitude(), -180, 180)) {
            item.setLatitude(source.getLatitude());
            item.setLongitude(source.getLongitude());
        }
        return item;
    }

    public static String itemType(DestinationType type) {
        return type == DestinationType.ACCOMMODATION ? "STAY"
                : type == DestinationType.RESTAURANTS || type == DestinationType.CAFE ? "FOOD" : "PLACE";
    }

    private boolean inRange(BigDecimal value, int minimum, int maximum) {
        return value != null && value.compareTo(BigDecimal.valueOf(minimum)) >= 0
                && value.compareTo(BigDecimal.valueOf(maximum)) <= 0;
    }
}
