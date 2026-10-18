package com.tripbora.controller.travelplan;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.TravelPlanChatImageDto;
import com.tripbora.dto.TravelPlanChatMessageDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanChatImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TravelPlanChatImageController.class)
@Import(SecurityConfig.class)
class TravelPlanChatImageControllerTest {
    private static final String UPLOAD = "/travel-plans/42/chat/image-messages";
    private static final String IMAGE = "/travel-plans/42/chat/messages/9/images/7";
    private static final String REQUEST_ID = "21d75460-f188-44c8-a278-739901ed7f55";

    @Autowired private MockMvc mvc;
    @MockitoBean private TravelPlanChatImageService service;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;
    @TempDir Path temporaryDirectory;

    @Test
    void multipartRequiresCsrfBeforeInvokingService() throws Exception {
        mvc.perform(multipart(UPLOAD).file(photo()).with(user(member()))
                        .param("clientRequestId", REQUEST_ID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousUploadAndImageReadRequireAuthentication() throws Exception {
        mvc.perform(multipart(UPLOAD).file(photo()).with(csrf())
                        .param("clientRequestId", REQUEST_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(IMAGE).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedMultipartPassesTheSessionPrincipalAndAllAttachments() throws Exception {
        var first = photo();
        var second = new MockMultipartFile("images", "second.png", "image/png", new byte[]{4, 5});
        var dto = new TravelPlanChatMessageDto(9L, 11L, "여행자", "숙소 사진", 123L, false,
                List.of(new TravelPlanChatImageDto(7L, IMAGE, 10, 20)));
        when(service.send(any(), eq(42L), eq("숙소 사진"), eq(REQUEST_ID), anyList())).thenReturn(dto);

        mvc.perform(multipart(UPLOAD).file(first).file(second).with(user(member())).with(csrf())
                        .param("content", "숙소 사진").param("clientRequestId", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.images[0].url").value(IMAGE));

        verify(service).send(argThat(principal -> principal instanceof Authentication authentication
                        && authentication.getPrincipal() instanceof CustomUserDetails details
                        && Long.valueOf(7L).equals(details.getId())),
                eq(42L), eq("숙소 사진"), eq(REQUEST_ID), eq(List.of(first, second)));
    }

    @Test
    void authorizedImageResponseUsesPrivateCacheAndContentTypeHeaders() throws Exception {
        byte[] bytes = {1, 2, 3};
        Path file = temporaryDirectory.resolve("photo.png");
        Files.write(file, bytes);
        when(service.image(any(), eq(42L), eq(9L), eq(7L)))
                .thenReturn(new TravelPlanChatImageService.ImageContent(file, "image/png"));

        mvc.perform(get(IMAGE).with(user(member())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void activeMembershipDenialFromServiceIsForbidden() throws Exception {
        when(service.image(any(), eq(42L), eq(9L), eq(7L)))
                .thenThrow(new AccessDeniedException("여행계획에 참여 중이 아닙니다."));
        when(service.send(any(), eq(42L), anyString(), eq(REQUEST_ID), anyList()))
                .thenThrow(new AccessDeniedException("여행계획에 참여 중이 아닙니다."));

        mvc.perform(get(IMAGE).with(user(member()))).andExpect(status().isForbidden());
        mvc.perform(multipart(UPLOAD).file(photo()).with(user(member())).with(csrf())
                        .param("clientRequestId", REQUEST_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletedPreJoinOrMismatchedImageIsNotFound() throws Exception {
        when(service.image(any(), eq(42L), eq(9L), eq(7L)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get(IMAGE).with(user(member()))).andExpect(status().isNotFound());
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("images", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    private CustomUserDetails member() {
        var member = new User();
        member.setId(7L);
        member.setUserRole(UserRole.USER);
        return new CustomUserDetails(member);
    }
}
