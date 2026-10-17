package com.tripbora.model;

import com.tripbora.service.travelplan.TravelPlanValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TravelPlanItemDetailsTest {
    @Test void transportKeepsRouteDatesTimesAndMemoWithoutFlightOrStayFields() {
        var details = new TravelPlanItemDetails();
        details.setDeparturePlace("Station A");
        details.setArrivalPlace("Station B");
        details.setDepartureDate("2026-10-18");
        details.setArrivalDate("2026-10-19");
        details.setDepartureTime("23:30");
        details.setArrivalTime("01:00");
        details.setBookingMemo("Train ticket");
        details.setFlightNumber("KE123");
        details.setStayName("Hotel");
        var normalized = details.normalizedFor(TravelPlanItemType.TRANSPORT);
        assertThat(normalized.getDeparturePlace()).isEqualTo("Station A");
        assertThat(normalized.getArrivalPlace()).isEqualTo("Station B");
        assertThat(normalized.getDepartureDate()).isEqualTo("2026-10-18");
        assertThat(normalized.getArrivalDate()).isEqualTo("2026-10-19");
        assertThat(normalized.getDepartureTime()).isEqualTo("23:30");
        assertThat(normalized.getArrivalTime()).isEqualTo("01:00");
        assertThat(normalized.getBookingMemo()).isEqualTo("Train ticket");
        assertThat(normalized.getFlightNumber()).isNull();
        assertThat(normalized.getStayName()).isNull();
    }
    @Test void typeAliasesUseTheSameCentralMappingForDisplayAndPersistence() {
        assertThat(TravelPlanItemType.parse("STAY")).isEqualTo(TravelPlanItemType.ACCOMMODATION);
        assertThat(TravelPlanItemType.fromTag("MOVE")).isEqualTo(TravelPlanItemType.TRANSPORT);
        assertThat(TravelPlanItemType.fromTag("FOOD")).isEqualTo(TravelPlanItemType.MEAL);
        assertThat(TravelPlanItemType.fromTag(null)).isEqualTo(TravelPlanItemType.SCHEDULE);
        assertThat(TravelPlanItemType.fromTag("old-custom-tag")).isEqualTo(TravelPlanItemType.SCHEDULE);
    }
    @Test void flightDetailsKeepLocalDatesAndPartialFieldsWithoutInventingTimezones() {
        var details = new TravelPlanItemDetails();
        details.setFlightNumber(" KE123 ");
        details.setDeparturePlace("서울 인천");
        details.setArrivalPlace("런던 히스로");
        details.setDepartureDate("2026-10-18");
        details.setDepartureTime("23:30");
        details.setArrivalTime("04:30");
        details.setStayName("무관한 숙소");
        var normalized = details.normalizedFor(TravelPlanItemType.FLIGHT);
        assertThat(normalized.getFlightNumber()).isEqualTo("KE123");
        assertThat(normalized.getArrivalDate()).isNull();
        assertThat(normalized.getArrivalTime()).isEqualTo("04:30");
        assertThat(normalized.getStayName()).isNull();
    }

    @Test void accommodationKeepsBookingFieldsAndClearsFlightFields() {
        var details = new TravelPlanItemDetails();
        details.setStayName("숙소");
        details.setAddress("상세 주소");
        details.setCheckInDate("2028-02-29");
        details.setCheckOutTime("11:00");
        details.setBookingMemo("예약 확인\n참고 메모");
        details.setFlightNumber("KE123");
        var normalized = details.normalizedFor(TravelPlanItemType.ACCOMMODATION);
        assertThat(normalized.getStayName()).isEqualTo("숙소");
        assertThat(normalized.getBookingMemo()).contains("\n");
        assertThat(normalized.getFlightNumber()).isNull();
        assertThat(details.normalizedFor(TravelPlanItemType.NOTE)).isNull();
    }

    @Test void invalidCalendarDatesTimesHtmlAndOversizedValuesAreRejected() {
        var details = new TravelPlanItemDetails();
        for (String date : new String[]{"2026-02-29", "2026-13-01", "26-01-01", "2026-1-1"}) {
            details.setDepartureDate(date);
            assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.FLIGHT))
                    .isInstanceOf(TravelPlanValidationException.class);
        }
        details.setDepartureDate(null);
        for (String time : new String[]{"24:00", "9:00", "10:00:00"}) {
            details.setDepartureTime(time);
            assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.FLIGHT))
                    .isInstanceOf(TravelPlanValidationException.class);
        }
        details.setDepartureTime(null);
        details.setFlightNumber("<b>KE123</b>");
        assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.FLIGHT))
                .isInstanceOf(TravelPlanValidationException.class);
        details.setFlightNumber(null);
        details.setDeparturePlace("가".repeat(201));
        assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.FLIGHT))
                .isInstanceOf(TravelPlanValidationException.class);
    }

    @Test void accommodationRejectsCheckoutBeforeCheckinWhenBothDatesAreKnown() {
        var details = new TravelPlanItemDetails();
        details.setCheckInDate("2026-10-19");
        details.setCheckOutDate("2026-10-18");
        assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.ACCOMMODATION))
                .isInstanceOf(TravelPlanValidationException.class);
        details.setCheckOutDate("2026-10-19");
        details.setCheckInTime("15:00");
        details.setCheckOutTime("11:00");
        assertThatThrownBy(() -> details.normalizedFor(TravelPlanItemType.ACCOMMODATION))
                .isInstanceOf(TravelPlanValidationException.class);
    }

    @Test void accommodationAllowsOvernightAndPartialDatesWithoutGuessingTheMissingDay() {
        var details = new TravelPlanItemDetails();
        details.setCheckInDate("2026-10-18");
        details.setCheckOutDate("2026-10-19");
        details.setCheckInTime("15:00");
        details.setCheckOutTime("11:00");
        assertThatCode(() -> details.normalizedFor(TravelPlanItemType.ACCOMMODATION)).doesNotThrowAnyException();
        details.setCheckOutDate(null);
        assertThatCode(() -> details.normalizedFor(TravelPlanItemType.ACCOMMODATION)).doesNotThrowAnyException();
        details.setCheckOutDate("2026-10-18");
        details.setCheckOutTime("15:00");
        assertThatCode(() -> details.normalizedFor(TravelPlanItemType.ACCOMMODATION)).doesNotThrowAnyException();
    }
}
