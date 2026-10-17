package com.tripbora.repository;

import com.tripbora.model.TravelPlanItem;
import com.tripbora.model.TravelPlanItemDetails;
import com.tripbora.repository.travelplan.TravelPlanItemDetailsTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TravelPlanStructuredItemMapperContractTest {
    @Test void sourceAndFinalReadsUseTheJsonHandlerAndSnapshotsWriteIt() throws Exception {
        var configuration = configuration();
        for (String id : new String[]{"TravelPlanItemMapper.findByDayId", "TravelPlanItemMapper.findByPlanId",
                "TravelPlanItemMapper.findByIdAndDayId", "TravelPlanItemMapper.findPreviousItem",
                "TravelPlanItemMapper.findNextItem", "TravelPlanFinalMapper.findItemsBySnapshotId"}) {
            var mapping = configuration.getMappedStatement("com.tripbora.repository.travelplan." + id);
            assertThat(mapping.getResultMaps().get(0).getResultMappings())
                    .anySatisfy(field -> {
                        assertThat(field.getProperty()).isEqualTo("details");
                        assertThat(field.getTypeHandler()).isInstanceOf(TravelPlanItemDetailsTypeHandler.class);
                    });
            assertThat(mapping.getBoundSql(Map.of()).getSql()).contains("detail_json");
        }
        var item = new TravelPlanItem();
        item.setDetails(new TravelPlanItemDetails());
        var insert = configuration.getMappedStatement("com.tripbora.repository.travelplan.TravelPlanItemMapper.insertItem")
                .getBoundSql(item);
        assertThat(insert.getParameterMappings()).anySatisfy(field -> {
            assertThat(field.getProperty()).isEqualTo("details");
            assertThat(field.getTypeHandler()).isInstanceOf(TravelPlanItemDetailsTypeHandler.class);
        });
    }

    @Test void legacyWritesPreserveJsonWhilePromotionClearsItAndMovesDoNotRewriteIt() throws Exception {
        var configuration = configuration();
        var prefix = "com.tripbora.repository.travelplan.TravelPlanItemMapper.";
        assertThat(configuration.getMappedStatement(prefix + "updateContent").getBoundSql(Map.of()).getSql())
                .doesNotContain("detail_json");
        assertThat(configuration.getMappedStatement(prefix + "updateDetails").getBoundSql(Map.of()).getSql())
                .contains("THEN detail_json ELSE NULL END");
        // MySQL 단일 테이블 UPDATE는 SET을 왼쪽부터 평가하므로 이전 tag 비교가 먼저여야 한다.
        String legacyUpdate = configuration.getMappedStatement(prefix + "updateDetails").getBoundSql(Map.of()).getSql();
        assertThat(legacyUpdate.indexOf("detail_json = CASE"))
                .isLessThan(legacyUpdate.indexOf("tag = ?", legacyUpdate.indexOf("END")));
        assertThat(configuration.getMappedStatement(prefix + "promoteAlternativeContent").getBoundSql(Map.of()).getSql())
                .contains("detail_json = NULL");
        assertThat(configuration.getMappedStatement(prefix + "moveToDayWithVersion").getBoundSql(Map.of()).getSql())
                .doesNotContain("detail_json");
        assertThat(configuration.getMappedStatement(prefix + "updateStructuredDetails").getBoundSql(Map.of()).getSql())
                .contains("AND travel_plan_day_id = ? AND version = ?");
    }

    private Configuration configuration() throws Exception {
        var result = new Configuration();
        for (String name : new String[]{"TravelPlanItemMapper.xml", "TravelPlanFinalMapper.xml"}) {
            try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/mapper/" + name))) {
                new XMLMapperBuilder(stream, result, name, result.getSqlFragments()).parse();
            }
        }
        return result;
    }
}
