package com.tripbora.controller.comment;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.destination.DestinationCommentController;
import com.tripbora.dto.CommentDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.CommentLikeService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.comment.ReviewImageService;
import com.tripbora.service.translation.ContentTranslationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({DestinationCommentController.class, DestinationCommentImageController.class, CommentEditController.class})
@Import(SecurityConfig.class)
class ReviewPhotoUploadRequestTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReviewImageService photos;
    @MockitoBean DestinationCommentService comments;
    @MockitoBean CommentLikeService likes;
    @MockitoBean ContentTranslationService translations;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;

    @Test void createUsesAuthenticatedOwnerAndShownUploadPolicy() throws Exception {
        when(comments.create(eq(10L), eq(7L), eq("리뷰"), anyList(), isNull(), eq(4), eq(42L)))
                .thenReturn(new CommentDto());
        mvc.perform(multipart("/comments").file(file()).param("destinationId", "10")
                        .param("content", "리뷰").param("rating", "4").param("photoUploadPolicyVersionId", "42")
                        .param("userId", "999").with(user(member())).with(csrf()))
                .andExpect(status().isOk());
        verify(comments).create(eq(10L), eq(7L), eq("리뷰"), anyList(), isNull(), eq(4), eq(42L));
    }

    @Test void addAndEditForwardTheSamePerUploadPolicy() throws Exception {
        mvc.perform(multipart("/comments/20/images").file(file()).param("photoUploadPolicyVersionId", "42")
                        .with(user(member())).with(csrf())).andExpect(status().isNoContent());
        verify(photos).add(eq(20L), eq(7L), anyList(), eq(42L));
        mvc.perform(multipart("/comments/20/with-images").file(file()).param("content", "수정")
                        .param("photoUploadPolicyVersionId", "42").with(request -> {request.setMethod("PUT"); return request;})
                        .with(user(member())).with(csrf())).andExpect(status().isNoContent());
        verify(photos).edit(eq(20L), eq(7L), eq("수정"), isNull(), anyList(), isNull(), eq(false), eq(42L));
    }

    @Test void stalePolicyIsAnExplicitConflictRatherThanSuccessfulUpload() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "사진 업로드 약관을 다시 확인해 주세요."))
                .when(photos).add(eq(20L), eq(7L), anyList(), eq(41L));
        mvc.perform(multipart("/comments/20/images").file(file()).param("photoUploadPolicyVersionId", "41")
                        .with(user(member())).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("사진 업로드 약관을 다시 확인해 주세요."));
    }

    @Test void uploadStillRequiresLoginAndCsrf() throws Exception {
        mvc.perform(multipart("/comments/20/images").file(file()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/comments/20/images").file(file()).with(user(member())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(photos);
    }

    private MockMultipartFile file() { return new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1}); }
    private CustomUserDetails member() {
        User owner = new User(); owner.setId(7L); owner.setUserRole(UserRole.USER);
        return new CustomUserDetails(owner);
    }
}
