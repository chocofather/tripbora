package com.tripbora.service.course;

import com.tripbora.dto.CourseDayForm;
import com.tripbora.dto.CourseItemForm;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CourseScheduleValidationTest {
    @Test
    void acceptsOvernightAndIndependentTimesForDifferentDestinations() {
        CourseDayForm first = day(1, 2);
        first.getItems().get(0).setStartTime("23:00");
        first.getItems().get(0).setEndTime("01:00");
        first.getItems().get(1).setEndTime("10:30");
        assertThat(CourseScheduleValidator.validate(List.of(first, day(2, 1))))
                .containsExactly(101L, 102L, 201L);
    }

    @Test
    void rejectsDuplicateDestinationWithinTheSameDay() {
        CourseDayForm day = day(1, 2);
        day.getItems().get(1).setDestinationId(101L);
        assertThatThrownBy(() -> CourseScheduleValidator.validate(List.of(day)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("같은 여행지");
    }

    @Test
    void rejectsDuplicateDestinationAcrossDays() {
        CourseDayForm second = day(2, 1);
        second.getItems().get(0).setDestinationId(101L);
        assertThatThrownBy(() -> CourseScheduleValidator.validate(List.of(day(1, 1), second)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("같은 여행지");
    }

    @Test
    void acceptsNoTimeAndStartOnlyAndEndOnly() {
        CourseDayForm day = day(1, 3);
        day.getItems().get(1).setStartTime("09:00");
        day.getItems().get(2).setEndTime("10:30");
        assertThat(CourseScheduleValidator.validate(List.of(day))).containsExactly(101L, 102L, 103L);
    }

    @Test
    void rejectsMissingOrTooManyDays() {
        rejected(null);
        rejected(List.of());
        List<CourseDayForm> days = new ArrayList<>();
        for (int n = 1; n <= 91; n++) days.add(day(n, 1));
        rejected(days);
    }

    @Test
    void acceptsMaximumDayAndItemCounts() {
        List<CourseDayForm> days = new ArrayList<>();
        for (int n = 1; n <= 90; n++) days.add(day(n, 50));
        assertThat(CourseScheduleValidator.validate(days)).hasSize(4500).doesNotHaveDuplicates();
    }

    @Test
    void rejectsEmptyDayAndTooManyItems() {
        rejected(List.of(day(1, 0)));
        rejected(List.of(day(1, 1), day(2, 0)));
        rejected(List.of(day(1, 51)));
        CourseDayForm day = day(1, 1);
        day.setItems(null);
        rejected(List.of(day));
    }

    @Test
    void rejectsNonSequentialDayAndItemOrdersAndInvalidIds() {
        rejected(List.of(day(1, 1), day(3, 1)));
        CourseDayForm day = day(1, 1);
        day.getItems().get(0).setDisplayOrder(2);
        rejected(List.of(day));
        day.getItems().get(0).setDisplayOrder(1);
        day.getItems().get(0).setDestinationId(null);
        rejected(List.of(day));
        day.getItems().get(0).setDestinationId(-1L);
        rejected(List.of(day));
    }

    @Test
    void validatesDayTitleMemoAndTimeWithoutSilentlyNormalizingMalformedTimes() {
        CourseDayForm day = day(1, 1);
        day.setTitle("가".repeat(101));
        rejected(List.of(day));
        day.setTitle("가".repeat(100));
        day.getItems().get(0).setMemo("메".repeat(1001));
        rejected(List.of(day));
        day.getItems().get(0).setMemo("메".repeat(1000));
        for (String time : List.of("24:00", "9:00", "12:60", "09:00:01", "00:00garbage")) {
            day.getItems().get(0).setStartTime(time);
            rejected(List.of(day));
        }
        day.getItems().get(0).setStartTime("");
        assertThat(CourseScheduleValidator.validate(List.of(day))).containsExactly(101L);
    }

    private void rejected(List<CourseDayForm> days) {
        assertThatThrownBy(() -> CourseScheduleValidator.validate(days))
                .isInstanceOf(ResponseStatusException.class);
    }

    private CourseDayForm day(int number, int itemCount) {
        CourseDayForm day = new CourseDayForm();
        day.setDayNumber(number);
        day.setItems(new ArrayList<>());
        for (int order = 1; order <= itemCount; order++) {
            CourseItemForm item = new CourseItemForm();
            item.setDestinationId(number * 100L + order);
            item.setDisplayOrder(order);
            day.getItems().add(item);
        }
        return day;
    }
}
