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
    void hugeNestedIndexReturnsBadRequestWithoutWritingAnything() throws Exception {
        CourseService service = mock(CourseService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CourseController(service, mock(CountryCategoryService.class)))
                .setSingleView(new MappingJackson2JsonView()).build();
        mvc.perform(post("/course/write").param("days[9999].dayNumber", "10000"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
