package com.tripbora.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripbora.service.travelplan.TravelPlanValidationException;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** 항공/숙소의 선택 상세. 날짜와 시각은 현지 표기 그대로이며 시간대 변환을 하지 않는다. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TravelPlanItemDetails {
    private String flightNumber;
    private String departurePlace;
    private String arrivalPlace;
    private String departureDate;
    private String departureTime;
    private String arrivalDate;
    private String arrivalTime;
    private String stayName;
    private String checkInDate;
    private String checkInTime;
    private String checkOutDate;
    private String checkOutTime;
    private String address;
    private String bookingMemo;

    /** 다른 종류의 입력은 옮기지 않아 타입 전환 뒤 이전 예약 정보가 섞이지 않는다. */
    public TravelPlanItemDetails normalizedFor(TravelPlanItemType type) {
        var result = new TravelPlanItemDetails();
        if (type == TravelPlanItemType.FLIGHT || type == TravelPlanItemType.TRANSPORT) {
            if (type == TravelPlanItemType.FLIGHT) result.flightNumber = text(flightNumber, 200);
            result.departurePlace = text(departurePlace, 200);
            result.arrivalPlace = text(arrivalPlace, 200);
            result.departureDate = date(departureDate);
            result.departureTime = time(departureTime);
            result.arrivalDate = date(arrivalDate);
            result.arrivalTime = time(arrivalTime);
        } else if (type == TravelPlanItemType.ACCOMMODATION) {
            result.stayName = text(stayName, 200);
            result.checkInDate = date(checkInDate);
            result.checkInTime = time(checkInTime);
            result.checkOutDate = date(checkOutDate);
            result.checkOutTime = time(checkOutTime);
            result.address = text(address, 500);
            result.validateStayOrder();
        } else {
            return null;
        }
        result.bookingMemo = text(bookingMemo, 1000);
        return result;
    }

    private void validateStayOrder() {
        // 날짜가 빠진 부분 입력은 전날/다음날을 추측하지 않는다.
        if (checkInDate == null || checkOutDate == null) return;
        int dateOrder = checkOutDate.compareTo(checkInDate);
        if (dateOrder < 0 || (dateOrder == 0 && checkInTime != null && checkOutTime != null
                && checkOutTime.compareTo(checkInTime) < 0)) {
            throw invalid("체크아웃은 체크인보다 빠를 수 없습니다.");
        }
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        value = value.strip();
        if (value.codePointCount(0, value.length()) > max || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                        || (c >= 0xD800 && c <= 0xDFFF))) {
            throw invalid("상세 정보는 일반 텍스트 " + max + "자 이내로 입력해 주세요.");
        }
        return value;
    }

    private static String date(String value) {
        value = text(value, 10);
        if (value == null) return null;
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format", value, 0);
            LocalDate.parse(value);
            return value;
        } catch (DateTimeParseException exception) {
            throw invalid("날짜는 YYYY-MM-DD 형식의 실제 날짜로 입력해 주세요.");
        }
    }

    private static String time(String value) {
        value = text(value, 5);
        if (value == null) return null;
        try {
            if (!value.matches("\\d{2}:\\d{2}")) throw new DateTimeParseException("format", value, 0);
            LocalTime.parse(value);
            return value;
        } catch (DateTimeParseException exception) {
            throw invalid("시각은 00:00부터 23:59 사이로 입력해 주세요.");
        }
    }

    private static TravelPlanValidationException invalid(String message) {
        return new TravelPlanValidationException("content", message);
    }
}
