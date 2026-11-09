package com.tripbora.controller.destination;

import com.tripbora.config.*;
import com.tripbora.dto.*;
import com.tripbora.model.DestinationImage;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.LoginThrottle;
import com.tripbora.service.destination.DestinationGalleryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC/Security와 공개 JSON 계약만 검증하며 DB/Flyway를 시작하지 않는다. */
@WebMvcTest(DestinationGalleryController.class)
@Import(SecurityConfig.class)
class DestinationGalleryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DestinationGalleryService gallery;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean LoginThrottle throttle;

    @Test void anonymousReadUsesSharedPageAndNeverCachesWithdrawnRights() throws Exception {
        var admin=new DestinationImage();admin.setId(30L);admin.setImageUrl("/uploads/destinations/30.jpg");
        var review=new ReviewGalleryPhoto();review.setId(31L);review.setCommentId(7L);review.setDestinationId(15L);
        review.setImageUrl("/uploads/comments/31.jpg");
        when(gallery.page(15L,"ALL",null)).thenReturn(new DestinationGalleryPage(List.of(
                DestinationGalleryImage.administrator(admin),DestinationGalleryImage.traveler(review)),"next",null));
        mvc.perform(get("/destinations/15/gallery"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.items[0].galleryKey").value("admin:30"))
                .andExpect(jsonPath("$.items[1].galleryKey").value("review:31"))
                .andExpect(jsonPath("$.items[1].reviewUrl").value("/destinations/15?commentId=7"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
        verify(gallery).page(15L,"ALL",null);
    }

    @Test void filterAndOpaqueCursorAreForwardedAndNextRequestReadsAgain() throws Exception {
        when(gallery.page(15L,"TRAVELER","cursor")).thenReturn(
                new DestinationGalleryPage(List.of(),"continue", "previous"),new DestinationGalleryPage(List.of(),null,null));
        mvc.perform(get("/destinations/15/gallery").param("filter","TRAVELER").param("cursor","cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("continue"));
        mvc.perform(get("/destinations/15/gallery").param("filter","TRAVELER").param("cursor","cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(gallery,times(2)).page(15L,"TRAVELER","cursor");
    }

    @Test void privateDestinationAndMalformedCursorErrorsAreVisibleToClient() throws Exception {
        when(gallery.page(15L,"ALL",null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(gallery.page(15L,"ALL","bad")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        mvc.perform(get("/destinations/15/gallery")).andExpect(status().isNotFound());
        mvc.perform(get("/destinations/15/gallery").param("cursor","bad")).andExpect(status().isBadRequest());
    }

    @Test void getPermissionDoesNotBypassCsrfForMutationRequests() throws Exception {
        mvc.perform(post("/destinations/15/gallery")).andExpect(status().isForbidden());
        verifyNoInteractions(gallery);
    }
}
