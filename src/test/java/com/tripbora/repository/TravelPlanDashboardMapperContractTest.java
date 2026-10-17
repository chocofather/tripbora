package com.tripbora.repository;

import com.tripbora.repository.travelplan.TravelPlanDashboardMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TravelPlanDashboardMapperContractTest {
    @Test
    void childMutationsScopeThePlanAndRequireTheExpectedVersion() throws Exception {
        var configuration = configuration();
        for (String name : new String[]{"updateChecklist", "deleteChecklist", "deleteExpense"}) {
            String sql = configuration.getMappedStatement(TravelPlanDashboardMapper.class.getName() + "." + name)
                    .getBoundSql(Map.of("planId", 1L, "id", 2L, "version", 3L,
                            "title", "title", "completed", true)).getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains("WHERE travel_plan_id = ? AND id = ? AND version = ?"), sql);
        }
    }

    @Test
    void membershipRecheckUsesCurrentReadAndMigrationHasCascadeAndExactAmount() throws Exception {
        String sql = configuration().getMappedStatement(TravelPlanDashboardMapper.class.getName() + ".findActiveMemberForUpdate")
                .getBoundSql(Map.of("planId", 1L, "userId", 2L)).getSql();
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(sql.contains("status = 'ACTIVE'"));
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V20261018_002__add_travel_plan_dashboard.sql"));
        assertEquals(3, migration.split("ON DELETE CASCADE", -1).length - 1);
        assertTrue(migration.contains("DECIMAL(15,2)"));
        assertTrue(migration.contains("CHECK (amount >= 0)"));
    }

    private Configuration configuration() throws Exception {
        var configuration = new Configuration();
        try (InputStream stream = Files.newInputStream(Path.of("src/main/resources/mapper/TravelPlanDashboardMapper.xml"))) {
            new XMLMapperBuilder(stream, configuration, "TravelPlanDashboardMapper.xml", configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
