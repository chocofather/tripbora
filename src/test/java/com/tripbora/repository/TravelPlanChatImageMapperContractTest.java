package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class TravelPlanChatImageMapperContractTest {
    @Test void mapperStatementsParseWithoutConnectingToDatabase() throws Exception {
        Configuration config=new Configuration();
        for(String name:new String[]{"TravelPlanChatMapper","TravelPlanChatImageMapper"}) {
            Path path=Path.of("src/main/resources/mapper/"+name+".xml");
            try(var stream=Files.newInputStream(path)) {
                new XMLMapperBuilder(stream,config,path.toString(),config.getSqlFragments()).parse();
            }
        }
        assertThat(config.hasStatement("com.tripbora.repository.travelplan.TravelPlanChatMapper.findByRequestId")).isTrue();
        assertThat(config.hasStatement("com.tripbora.repository.travelplan.TravelPlanChatImageMapper.findByMessageIds")).isTrue();
        assertThat(config.hasStatement("com.tripbora.repository.travelplan.TravelPlanChatImageMapper.isReferenced")).isTrue();
    }
}
