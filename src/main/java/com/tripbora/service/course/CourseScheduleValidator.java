package com.tripbora.service.course;

import com.tripbora.dto.CourseDayForm;
import com.tripbora.dto.CourseItemForm;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 저장 전 일정 계약. 번호는 정규화로 숨기지 않고 클라이언트 오류를 검출한다. */
public final class CourseScheduleValidator {
    private CourseScheduleValidator() { }

    public static List<Long> validate(List<CourseDayForm> days) {
        if (days == null || days.isEmpty() || days.size() > 90) {
            throw invalid("여행 일정은 1일 이상 90일 이하로 입력해 주세요.");
        }
        LinkedHashSet<Long> destinations = new LinkedHashSet<>();
        for (int d = 0; d < days.size(); d++) {
            CourseDayForm day = days.get(d);
            if (day == null || !Objects.equals(day.getDayNumber(), d + 1)) {
                throw invalid("Day 번호는 1부터 순서대로 입력해 주세요.");
            }
            if (day.getTitle() != null && day.getTitle().length() > 100) {
                throw invalid("Day 제목은 100자 이하로 입력해 주세요.");
            }
            if (day.getItems() == null || day.getItems().isEmpty() || day.getItems().size() > 50) {
                throw invalid("Day " + (d + 1) + "에는 장소를 1곳 이상 50곳 이하로 추가해 주세요.");
            }
            for (int i = 0; i < day.getItems().size(); i++) {
                CourseItemForm item = day.getItems().get(i);
                if (item == null || !Objects.equals(item.getDisplayOrder(), i + 1)) {
                    throw invalid("장소 순서는 각 Day에서 1부터 이어져야 합니다.");
                }
                if (item.getDestinationId() == null || item.getDestinationId() <= 0) {
                    throw invalid("유효하지 않은 여행지가 포함되어 있습니다.");
                }
                if (item.getMemo() != null && item.getMemo().length() > 1000) {
                    throw invalid("장소 메모는 1000자 이하로 입력해 주세요.");
                }
                parseTime(item.getStartTime());
                if (!destinations.add(item.getDestinationId())) {
                    throw invalid("같은 여행지는 하나의 코스에 한 번만 추가할 수 있습니다.");
                }
            }
        }
        return List.copyOf(destinations);
    }

    public static LocalTime parseTime(String value) {
        if (value == null || value.isEmpty()) return null;
        if (!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw invalid("시간은 HH:mm 형식으로 입력해 주세요.");
        }
        return LocalTime.parse(value);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
