package com.tripbora.config;

import com.tripbora.controller.diary.DiaryPrivatePhotoController;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.diary.DiaryPrivatePhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어떤 업로드 폴더가 주소만으로 열리는지의 경계.
 *
 * <p>예전에는 {@code /uploads/**} 를 통째로 열어 두어, 비공개 여행일기의 사진도 주소를 아는
 * 사람이면 로그인 없이 볼 수 있었다. 이제 공개해도 되는 폴더만 열고 개인 사진 네 폴더는
 * 정적 매핑에서도 접근 규칙에서도 빠진다.
 *
 * <p>정적 매핑과 접근 규칙이 따로 늘어나면 한쪽만 열리는 구멍이 생기므로 둘이 같은 목록을
 * 쓰는지도 함께 본다.
 */
/*
  이 조각에는 controller 가 필요 없지만 @WebMvcTest 는 하나를 요구한다.
  /uploads/** 만 부르므로 어떤 controller 가 실려도 결과는 같다.
*/
@WebMvcTest(controllers = DiaryPrivatePhotoController.class)
@Import({SecurityConfig.class, WebConfig.class})
class UploadPathExposureTest {

    /** private 저장소로 옮겨 간 개인 사진 폴더. 어느 쪽에도 들어 있으면 안 된다. */
    private static final List<String> PRIVATE_DIRECTORIES = List.of(
            "diary-covers", "diary-pages", "diary-cover-elements", "diary-cover-designs");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiaryPrivatePhotoService privatePhotoService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    /** 개인 사진 폴더는 공개 정적 매핑 목록에 없다. */
    @Test
    void thePrivateDiaryFoldersAreNotPubliclyMapped() {
        assertThat(WebConfig.PUBLIC_UPLOAD_DIRECTORIES)
                .doesNotContainAnyElementsOf(PRIVATE_DIRECTORIES);
    }

    /** 공개 콘텐츠 폴더는 그대로 열려 있어야 한다. */
    @Test
    void thePublicContentFoldersAreStillMapped() {
        assertThat(WebConfig.PUBLIC_UPLOAD_DIRECTORIES).contains(
                "events", "destinations", "travel-info", "posts", "editor",
                "comments", "post-comments", "course-comments", "course/cover",
                "profiles", "diary-stickers", "icons");
    }

    /**
     * 예전 주소로 개인 사진을 직접 열 수 없다.
     *
     * <p>비로그인 요청은 로그인으로 돌려보내진다 — 200 으로 사진이 나가지 않는 것이 핵심이다.
     * (controlled endpoint 가 서버 안에서 예전 파일을 읽어 주는 것과는 다른 이야기다)
     */
    @Test
    void theOldPublicUrlsOfPrivateDiaryPhotosNoLongerServeAnything() throws Exception {
        for (String directory : PRIVATE_DIRECTORIES) {
            mockMvc.perform(get("/uploads/" + directory
                            + "/123e4567-e89b-12d3-a456-426614174000.jpg"))
                    .andExpect(status().is3xxRedirection());
        }
    }

    /**
     * 공개 이미지 주소는 비로그인에서도 인증으로 막히지 않는다.
     *
     * <p>이 조각에는 실제 파일이 없어 404 가 나오지만, 그것은 "허용되었고 파일이 없다"는 뜻이다.
     * 막혔다면 위의 개인 폴더처럼 로그인으로 돌려보내진다.
     */
    @Test
    void thePublicImageUrlsAreStillReachableWithoutLogin() throws Exception {
        for (String directory : List.of("destinations", "events", "profiles",
                "posts", "diary-stickers", "icons", "course/cover")) {
            mockMvc.perform(get("/uploads/" + directory + "/sample.jpg"))
                    .andExpect(status().isNotFound());
        }
    }
}
