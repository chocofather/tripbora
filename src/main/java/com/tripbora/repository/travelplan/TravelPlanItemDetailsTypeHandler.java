package com.tripbora.repository.travelplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbora.model.TravelPlanItemDetails;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;

/** 명시적 resultMap/parameter mapping에서만 쓰는 JSON handler. */
public class TravelPlanItemDetailsTypeHandler extends BaseTypeHandler<TravelPlanItemDetails> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, TravelPlanItemDetails value,
                                    JdbcType jdbcType) throws SQLException {
        try {
            statement.setString(index, JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new SQLException("일정 상세 JSON을 저장할 수 없습니다.", exception);
        }
    }

    @Override public TravelPlanItemDetails getNullableResult(ResultSet result, String column) throws SQLException {
        return read(result.getString(column));
    }
    @Override public TravelPlanItemDetails getNullableResult(ResultSet result, int column) throws SQLException {
        return read(result.getString(column));
    }
    @Override public TravelPlanItemDetails getNullableResult(CallableStatement statement, int column) throws SQLException {
        return read(statement.getString(column));
    }

    private TravelPlanItemDetails read(String value) throws SQLException {
        if (value == null) return null;
        try {
            return JSON.readValue(value, TravelPlanItemDetails.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("저장된 일정 상세 JSON을 읽을 수 없습니다.", exception);
        }
    }
}
