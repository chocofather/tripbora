package com.tripbora.controller.course;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class CourseFormCapacityTest {
    @Test
    void serverCanParseTheMaximumNestedScheduleWithoutTruncatingParametersOrUtf8Memos() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        ServerProperties properties = Binder.get(environment).bind("server", ServerProperties.class).get();
        // Item 5 fields, Day id/number/title, title/content/country/CSRF.
        int maximumFields = 90 * 50 * 5 + 90 * 3 + 4;
        // UTF-8 Korean memo percent encoding uses 9 ASCII bytes per character.
        long maximumMemoBytes = 90L * 50 * 1000 * 9;
        assertThat(properties.getTomcat().getMaxParameterCount()).isGreaterThanOrEqualTo(maximumFields);
        assertThat(properties.getTomcat().getMaxHttpFormPostSize().toBytes())
                .isGreaterThan(maximumMemoBytes + maximumFields * 100L);
    }
}
