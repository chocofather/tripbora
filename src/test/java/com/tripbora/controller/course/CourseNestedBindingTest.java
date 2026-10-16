package com.tripbora.controller.course;

import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseNestedBindingTest {
    @Test
    void bindsDirectFieldsAndItemCountryWhileRejectingEndTime() {
        var request = new com.tripbora.dto.CourseCreateRequest();
        var binder = new org.springframework.web.bind.WebDataBinder(request);
        new CourseController(mock(CourseService.class), mock(CountryCategoryService.class))
                .configureCourseBinding(binder);
        var values = new org.springframework.beans.MutablePropertyValues();
        values.add("days[0].dayNumber", "1");
        values.add("days[0].items[0].itemType", "STAY");
        values.add("days[0].items[0].placeName", "숙소");
        values.add("days[0].items[0].address", "주소");
        values.add("days[0].items[0].latitude", "37.5");
        values.add("days[0].items[0].longitude", "127");
        values.add("days[0].items[0].countryId", "999");
        values.add("days[0].items[0].endTime", "12:00");
        binder.bind(values);
        var item = request.getDays().get(0).getItems().get(0);
        var bean = new org.springframework.beans.BeanWrapperImpl(item);
        org.assertj.core.api.Assertions.assertThat(bean.getPropertyValue("itemType")).isEqualTo("STAY");
        org.assertj.core.api.Assertions.assertThat(bean.getPropertyValue("placeName")).isEqualTo("숙소");
        org.assertj.core.api.Assertions.assertThat(bean.getPropertyValue("address")).isEqualTo("주소");
        org.assertj.core.api.Assertions.assertThat((java.math.BigDecimal) bean.getPropertyValue("latitude"))
                .isEqualByComparingTo("37.5");
        org.assertj.core.api.Assertions.assertThat(item.getEndTime()).isNull();
        org.assertj.core.api.Assertions.assertThat(item.getCountryId()).isEqualTo(999L);
        org.assertj.core.api.Assertions.assertThat(binder.getBindingResult().getSuppressedFields())
                .containsExactly("days[0].items[0].endTime");
    }

    @Test
    void hugeNestedIndexReturnsBadRequestWithoutWritingAnything() throws Exception {
        CourseService service = mock(CourseService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CourseController(service, mock(CountryCategoryService.class)))
                .setSingleView(new MappingJackson2JsonView()).build();
        mvc.perform(post("/courses/new").param("days[9999].dayNumber", "10000"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
