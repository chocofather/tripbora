package com.tripbora.controller.course;

import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseThemeDto;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseThemeRenderingTest {
    private final SpringTemplateEngine engine = engine();

    @Test
    void detailEscapesLabelsInMappingOrderAndCardShowsTwoPlusOne() {
        var context = context();
        context.setVariable("themes", List.of(theme(7L, "쇼핑"), theme(4L, "맛집·카페"), theme(8L, "<사진>")));
        context.setVariable("compact", false);
        var detail = Jsoup.parse(engine.process("course/fragments/themes", context));
        assertThat(detail.select(".course-theme-badge").eachText()).containsExactly("쇼핑", "맛집·카페", "<사진>");
        assertThat(detail.select("사진")).isEmpty();
        context.setVariable("compact", true);
        var card = Jsoup.parse(engine.process("course/fragments/themes", context));
        assertThat(card.select(".course-theme-badge").eachText()).containsExactly("쇼핑", "맛집·카페", "+1");
        context.setVariable("themes", List.of());
        assertThat(Jsoup.parse(engine.process("course/fragments/themes", context)).select(".course-theme-badges")).isEmpty();
    }

    @Test
    void pickerUsesMasterLabelsAndRestoresOrderedHiddenValuesAndSelectedChips() {
        var context = context();
        context.setVariable("courseThemes", List.of(theme(1L, "DB label"), theme(9L, "긴 테마 이름")));
        var course = new CourseEditDto(); course.setThemeIds(List.of(9L, 1L)); context.setVariable("course", course);
        var document = Jsoup.parse(engine.process("course/fragments/themes", context));
        assertThat(document.select("button[data-course-theme]").eachText()).containsExactly("DB label", "긴 테마 이름");
        assertThat(document.select("[data-course-theme][aria-pressed=true]")).hasSize(2);
        assertThat(document.select("input[name=themeIds]").eachAttr("value")).containsExactly("9", "1");
    }

    private Context context() {
        var context = new Context(); context.setVariable("themes", List.of()); context.setVariable("compact", false);
        return context;
    }
    private CourseThemeDto theme(Long id, String name) {
        var theme = new CourseThemeDto(); theme.setId(id); theme.setName(name); return theme;
    }
    private static SpringTemplateEngine engine() {
        var resolver = new ClassLoaderTemplateResolver(); resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8"); var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver); return engine;
    }
}
