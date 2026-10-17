package com.tripbora.repository;

import com.tripbora.model.TravelPlanItemDetails;
import com.tripbora.repository.travelplan.TravelPlanItemDetailsTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelPlanItemDetailsTypeHandlerTest {
    @Test void jsonRoundTripKeepsUnicodeNewlinesAndLocalDateStrings() throws Exception {
        var handler = new TravelPlanItemDetailsTypeHandler();
        var details = new TravelPlanItemDetails();
        details.setStayName("서울 숙소 🧳");
        details.setCheckInDate("2026-10-18");
        details.setBookingMemo("예약\n조식 포함");
        var statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement, 1, details, JdbcType.VARCHAR);
        var json = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(1), json.capture());
        var result = mock(ResultSet.class);
        when(result.getString("detail_json")).thenReturn(json.getValue());
        assertThat(handler.getNullableResult(result, "detail_json")).isEqualTo(details);
    }

    @Test void nullJsonPreservesLegacyRowsAndMalformedJsonFailsClearly() throws Exception {
        var handler = new TravelPlanItemDetailsTypeHandler();
        var result = mock(ResultSet.class);
        assertThat(handler.getNullableResult(result, "detail_json")).isNull();
        when(result.getString("detail_json")).thenReturn("{not-json}");
        assertThatThrownBy(() -> handler.getNullableResult(result, "detail_json")).isInstanceOf(SQLException.class);
    }
}
