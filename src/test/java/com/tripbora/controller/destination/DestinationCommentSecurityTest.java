package com.tripbora.controller.destination;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.PageResult;
import com.tripbora.dto.CommentLocationDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.CommentLikeService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.translation.ContentTranslationResponse;
import com.tripbora.service.translation.ContentTranslationService;
import com.tripbora.service.translation.TranslatableContentType;
import com.tripbora.service.translation.TranslationDailyLimitException;
import com.tripbora.service.translation.TranslationMonthlyLimitException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.Cookie;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DestinationCommentController.class)
@Import(SecurityConfig.class)
class DestinationCommentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DestinationCommentService destinationCommentService;
    @MockitoBean
    private CommentLikeService commentLikeService;
    @MockitoBean
    private ContentTranslationService contentTranslationService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void hiddenDestinationRejectsEveryPublicCommentReadRoute() throws Exception {
        doThrow(new com.tripbora.service.destination.DestinationNotFoundException())
                .when(destinationCommentService).requireDestinationAccessible(10L, false);
        for (String path : List.of("/comments/list", "/comments/list/page", "/comments/images",
                "/comments/35/location")) {
            mockMvc.perform(get(path).param("destinationId", "10"))
                    .andExpect(status().isNotFound());
        }
        verify(destinationCommentService, never()).getCommentDtosWithWriter(10L, null, "oldest");
        verify(destinationCommentService, never()).getCommentsPaged(10L, null, 0, 5, "latest");
        verify(destinationCommentService, never()).getCommentImageDtos(10L);
        verify(destinationCommentService, never()).getCommentLocation(10L, 35L);
    }

    @Test
    void onlyAdministratorPrincipalCanUseTheHiddenCommentReadException() throws Exception {
        doThrow(new com.tripbora.service.destination.DestinationNotFoundException())
                .when(destinationCommentService).requireDestinationAccessible(10L, false);
        when(destinationCommentService.getCommentLocation(10L, 35L))
                .thenReturn(Optional.of(new CommentLocationDto(1)));
        for (UserRole role : List.of(UserRole.USER, UserRole.ADMIN)) {
            User user = new User();
            user.setId(7L);
            user.setUserRole(role);
            CustomUserDetails principal = new CustomUserDetails(user);
            for (String path : List.of("/comments/list", "/comments/list/page", "/comments/images",
                    "/comments/35/location")) {
                mockMvc.perform(get(path).param("destinationId", "10")
                                .with(authentication(new UsernamePasswordAuthenticationToken(
                                        principal, "n/a", principal.getAuthorities()))))
                        .andExpect(role == UserRole.ADMIN
                                ? status().is2xxSuccessful() : status().isNotFound());
            }
        }
        verify(destinationCommentService, org.mockito.Mockito.times(4))
                .requireDestinationAccessible(10L, true);
    }

    @Test
    void hiddenDestinationRejectsTranslationBeforeUsingTheTranslationCache() throws Exception {
        doThrow(new com.tripbora.service.destination.DestinationNotFoundException())
                .when(destinationCommentService).requireCommentDestinationAccessible(35L, false);
        mockMvc.perform(get("/comments/35/translation"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(contentTranslationService);
    }

    @Test
    void hiddenDestinationRejectsNewCommentsBeforeSavingImages() throws Exception {
        doThrow(new com.tripbora.service.destination.DestinationNotFoundException())
                .when(destinationCommentService).requireDestinationAccessible(10L, false);
        User user = new User();
        user.setId(7L);
        user.setUserRole(UserRole.USER);
        CustomUserDetails principal = new CustomUserDetails(user);
        mockMvc.perform(multipart("/comments")
                        .param("destinationId", "10").param("content", "comment")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                principal, "n/a", principal.getAuthorities())))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        verify(destinationCommentService, never()).create(
                eq(10L), eq(7L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void guestCanGetLatestPagedCommentsWithThreadAndActiveCommentTotals() throws Exception {
        when(destinationCommentService.getCommentsPaged(10L, null, 0, 5, "latest"))
                .thenReturn(new PageResult<>(List.of(), 6, 0, 5, 9));

        mockMvc.perform(get("/comments/list/page")
                        .param("destinationId", "10")
                        .param("page", "0")
                        .param("size", "5")
                        .param("sort", "latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalCommentCount").value(9))
                .andExpect(jsonPath("$.last").value(false));

        verify(destinationCommentService).getCommentsPaged(10L, null, 0, 5, "latest");
    }

    @Test
    void everySelectedImagePartReachesTheService() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("encoded");
        user.setUserRole(UserRole.USER);
        CustomUserDetails principal = new CustomUserDetails(user);

        mockMvc.perform(multipart("/comments")
                        .file(new MockMultipartFile("images", "a.jpg", "image/jpeg", new byte[]{1}))
                        .file(new MockMultipartFile("images", "b.jpg", "image/jpeg", new byte[]{2}))
                        .file(new MockMultipartFile("images", "c.jpg", "image/jpeg", new byte[]{3}))
                        .param("destinationId", "10")
                        .param("content", "사진 3장")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                principal, "n/a", principal.getAuthorities())))
                        .with(csrf()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(destinationCommentService)
                .create(eq(10L), eq(7L), eq("사진 3장"), captor.capture(), isNull());
        assertThat(captor.getValue())
                .extracting(MultipartFile::getOriginalFilename)
                .containsExactly("a.jpg", "b.jpg", "c.jpg");
    }

    /** 실제 사진이 아닌 첨부는 서버 오류가 아니라 안내 문구를 담은 400 으로 끝난다. */
    @Test
    void anInvalidImageIsAnsweredWithAUserErrorMessage() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("encoded");
        user.setUserRole(UserRole.USER);
        CustomUserDetails principal = new CustomUserDetails(user);
        when(destinationCommentService.create(eq(10L), eq(7L), eq("위장 파일"),
                org.mockito.ArgumentMatchers.anyList(), isNull()))
                .thenThrow(new com.tripbora.service.file.UnsupportedImageFormatException(
                        com.tripbora.service.file.FileUploadService.UNSUPPORTED_IMAGE_MESSAGE));

        mockMvc.perform(multipart("/comments")
                        .file(new MockMultipartFile("images", "x.html", "text/html",
                                "<script>alert(1)</script>".getBytes()))
                        .param("destinationId", "10")
                        .param("content", "위장 파일")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                principal, "n/a", principal.getAuthorities())))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(com.tripbora.service.file.FileUploadService.UNSUPPORTED_IMAGE_MESSAGE));
    }

    @Test
    void guestCanResolveAValidatedCommentLocation() throws Exception {
        when(destinationCommentService.getCommentLocation(10L, 35L))
                .thenReturn(Optional.of(new CommentLocationDto(2)));

        mockMvc.perform(get("/comments/35/location")
                        .param("destinationId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));

        verify(destinationCommentService).getCommentLocation(10L, 35L);
    }

    @Test
    void guestTranslationUsesServerLocaleAndRemoteAddress() throws Exception {
        when(contentTranslationService.translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ja", "203.0.113.9", null))
                .thenReturn(ContentTranslationResponse.ready(
                        "美しい場所です。", "ko", "ja", false));

        mockMvc.perform(get("/comments/35/translation")
                        .cookie(new Cookie("TRIPBORA_LOCALE", "ja"))
                        .header("X-Forwarded-For", "198.51.100.77")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translatedText").value("美しい場所です。"))
                .andExpect(jsonPath("$.targetLanguage").value("ja"));

        verify(contentTranslationService).translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ja", "203.0.113.9", null);
    }

    @Test
    void authenticatedTranslationUsesImmutableUserId() throws Exception {
        User user = new User();
        user.setId(42L);
        user.setUserPassword("encoded");
        user.setUserRole(UserRole.USER);
        CustomUserDetails principal = new CustomUserDetails(user);
        when(contentTranslationService.translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ko", "203.0.113.9", 42L))
                .thenReturn(ContentTranslationResponse.ready("번역", "en", "ko", false));

        mockMvc.perform(get("/comments/35/translation")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                principal, "n/a", principal.getAuthorities()))))
                .andExpect(status().isOk());

        verify(contentTranslationService).translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ko", "203.0.113.9", 42L);
    }

    @Test
    void monthlyTranslationLimitReturnsAGenericRateLimitResponse() throws Exception {
        when(contentTranslationService.translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ko", "127.0.0.1", null))
                .thenThrow(new TranslationMonthlyLimitException());

        mockMvc.perform(get("/comments/35/translation"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message")
                        .value("현재 번역 요청을 이용할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    @Test
    void dailyTranslationLimitReturnsTheSameGenericRateLimitResponse() throws Exception {
        when(contentTranslationService.translate(
                TranslatableContentType.DESTINATION_COMMENT, 35L, "ko", "127.0.0.1", null))
                .thenThrow(new TranslationDailyLimitException());

        mockMvc.perform(get("/comments/35/translation"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message")
                        .value("현재 번역 요청을 이용할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }
}
