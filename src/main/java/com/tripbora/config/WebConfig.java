package com.tripbora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 주소만 알면 누구나 열어도 되는 업로드 폴더. 여기 적힌 폴더만 정적으로 열린다.
     *
     * <p>예전에는 {@code /uploads/**} 를 통째로 매핑해서 개인 다이어리 사진까지 함께 열렸다.
     * 개인 사진(diary-covers / diary-pages / diary-cover-elements / diary-cover-designs)은
     * 이 목록에 넣지 않는다 — 소유권과 PIN 을 확인하는 통제된 endpoint 로만 나간다.
     *
     * <p>폴더를 새로 늘릴 때는 그 안의 파일이 정말 공개되어도 되는지 먼저 확인한다.
     */
    static final List<String> PUBLIC_UPLOAD_DIRECTORIES = List.of(
            "events",          // 축제·행사 이미지와 포스터(events/posters)
            "destinations",    // 여행지 이미지
            "travel-info",     // 여행정보 썸네일
            "posts",           // 공개 게시글 이미지
            "editor",          // 리치 텍스트 에디터 이미지
            "comments",        // 여행지 댓글 이미지
            "post-comments",   // 게시글 댓글 이미지
            "course-comments", // 여행 코스 댓글 이미지
            "course/cover",    // 공개 Course 대표 이미지 전용
            "profiles",        // 프로필 이미지 (공개 프로필이 비로그인 공개다)
            "diary-stickers",  // 관리자 스티커 카탈로그 (공용 asset)
            "icons");          // 사이트 아이콘과 편의시설 아이콘(icons/amenities)

    /**
     * 배포에 함께 나가는 정적 리소스 폴더. 여기 적힌 것만 브라우저 캐시를 허용한다.
     *
     * <p>회원이 올린 파일({@code /uploads/**})과 통제된 endpoint 로만 나가는 개인 사진은
     * 이 목록에 넣지 않는다. 그쪽은 소유권·PIN 검사와 기존 캐시 정책을 그대로 둔다.
     */
    private static final List<String> BUNDLED_STATIC_DIRECTORIES =
            List.of("css", "js", "images", "fonts");

    /**
     * 정적 리소스를 브라우저가 들고 있어도 되는 시간.
     *
     * <p>내용 해시 URL을 사용하되, 기존 버전 없는 URL의 캐시 시간도 늘리지 않는다.
     */
    private static final Duration BUNDLED_STATIC_CACHE = Duration.ofHours(1);

    @Value("${custom.upload-path}")
    private String uploadPath;

    @Bean
    public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        // Thymeleaf의 @{...}를 실제 정적 파일의 내용 해시 URL로 변환한다.
        return new ResourceUrlEncodingFilter();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        /*
          배포 정적 리소스에만 짧은 캐시를 준다.

          Spring Security 는 모든 응답에 no-store 를 붙이지만, 응답에 Cache-Control 이
          이미 있으면 건드리지 않는다(CacheControlHeadersWriter). 헤더를 쓰는 시점도
          핸들러가 끝난 뒤라서, 여기서 먼저 붙인 값이 그대로 나간다.
          그래서 보안 필터를 걷어내지 않고도 정적 파일만 캐시할 수 있다.

          HTML 은 Controller 가 만들어 이 핸들러를 지나지 않으므로 그대로 no-store 다.
        */
        for (String directory : BUNDLED_STATIC_DIRECTORIES) {
            registry.addResourceHandler("/" + directory + "/**")
                    .addResourceLocations("classpath:/static/" + directory + "/")
                    .setCacheControl(CacheControl.maxAge(BUNDLED_STATIC_CACHE).cachePublic())
                    .resourceChain(true)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }

        Path uploadRoot = Paths.get(uploadPath).toAbsolutePath().normalize();
        for (String directory : PUBLIC_UPLOAD_DIRECTORIES) {
            registry.addResourceHandler("/uploads/" + directory + "/**")
                    .addResourceLocations("file:" + uploadRoot.resolve(directory) + "/")
                    .setCachePeriod(3600);
        }
    }
}
