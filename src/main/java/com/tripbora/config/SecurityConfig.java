package com.tripbora.config;

import com.tripbora.security.RestrictedAccountFilter;
import com.tripbora.security.LoginThrottle;
import com.tripbora.security.LoginThrottleFilter;
import com.tripbora.security.MissingEmailAccountFilter;
import com.tripbora.security.WithdrawalPendingAccountFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

@Configuration
@EnableWebSecurity
/*
  관리자 Controller 들이 이미 @PreAuthorize("hasRole('ADMIN')") 를 달고 있었지만 이 설정이
  없어 실제로는 아무 일도 하지 않았다. 켜 두어야 아래 /admin/** URL 규칙과 함께 진짜 이중
  방어가 된다. URL 규칙은 그대로 두므로 정상 흐름의 동작은 달라지지 않는다.
*/
@EnableMethodSecurity
@RequiredArgsConstructor
@Import(LoginThrottleConfig.class)
public class SecurityConfig {

    private final CustomLoginSuccessHandler customLoginSuccessHandler; // ✅ 여기에 추가
    private final CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 로그인 후 복귀 대상은 실제 페이지 이동 요청만 저장한다.
     * 기본 설정은 favicon·정적 리소스·/.well-known/** 같은 브라우저 보조 요청까지 저장해
     * 원래 보던 페이지의 SavedRequest 를 덮어쓴다.
     */
    @Bean
    public RequestCache navigationRequestCache() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new NavigationRequestMatcher());
        return requestCache;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RequestCache navigationRequestCache,
            ObjectProvider<RestrictedAccountFilter> restrictedAccountFilter,
            ObjectProvider<WithdrawalPendingAccountFilter> withdrawalPendingAccountFilter,
            ObjectProvider<MissingEmailAccountFilter> missingEmailAccountFilter,
            LoginThrottle loginThrottle,
            ObjectProvider<SocialOAuth2LoginSuccessHandler> socialOAuth2LoginSuccessHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository,
            ObjectProvider<TripBoraAuthenticationRestorer> authenticationRestorer)
            throws Exception {

        // 이용제한 회원 접근 통제. 웹 계층 테스트 슬라이스에는 빈이 없으므로 선택 주입한다.
        restrictedAccountFilter.ifAvailable(
                filter -> http.addFilterAfter(filter, AuthorizationFilter.class));
        // 탈퇴 유예 회원 접근 통제. 상태가 서로 배타적이라 이용제한 격리와 겹치지 않는다.
        withdrawalPendingAccountFilter.ifAvailable(
                filter -> http.addFilterAfter(filter, AuthorizationFilter.class));
        // 이메일 미등록 소셜 회원 격리. 판정 자체가 ACTIVE 한정이라 위 두 상태 격리를 앞지르지 않는다.
        missingEmailAccountFilter.ifAvailable(
                filter -> http.addFilterAfter(filter, AuthorizationFilter.class));
        http.addFilterBefore(
                new LoginThrottleFilter(loginThrottle),
                UsernamePasswordAuthenticationFilter.class);

        http.requestCache(cache -> cache.requestCache(navigationRequestCache));

        /*
          CSRF 는 Spring Security 기본 정책을 그대로 쓴다.
          GET/HEAD/OPTIONS/TRACE 를 뺀 모든 요청(POST/PUT/PATCH/DELETE)이 토큰을 요구한다.
          비로그인 사용자가 보내는 로그인·회원가입·비밀번호 찾기 같은 폼도 같은 보호를 받는다.
          (화면은 Thymeleaf 가 hidden 토큰을, fetch 는 layout 의 meta 토큰을 실어 보낸다)

          예전에는 보호할 주소를 목록으로 적어 두었는데, 목록에 없는 상태 변경 요청이
          그대로 통과했다. 그 목록을 없앤 것이 이번 변경이다.
          토큰을 실을 수 없는 외부 서비스 callback 이 없으므로 보호에서 빼는 주소도 두지 않는다.
          (소셜 로그인 redirect 는 GET 이라 애초에 CSRF 대상이 아니다)
        */
        http.authorizeHttpRequests(auth -> auth

                        // 에디터 이미지 업로드는 로그인 사용자만. 아래 /api/** 공개 규칙보다 먼저 와야 한다.
                        .requestMatchers("/api/upload/**").authenticated()

                        /*
                          공개 업로드 이미지. 예전에는 /uploads/** 를 통째로 열어 두어 개인 다이어리
                          사진까지 주소만 알면 열렸다. 이제 공개해도 되는 폴더만 연다.
                          목록은 정적 매핑(WebConfig)과 한 벌이어야 해서 그쪽 상수를 그대로 쓴다.
                          개인 사진은 여기에 없고 /diaries/** 의 통제된 endpoint 로만 나간다.
                        */
                        .requestMatchers(publicUploadPatterns()).permitAll()
                        // 메인 여행지 카드 썸네일. 공개 여행지 사진(destinations)을 줄인 것뿐이다.
                        .requestMatchers(HttpMethod.GET, "/thumbnails/destinations/**").permitAll()

                        /* === 비회원도 접근 가능한 공개 영역 === */
                        .requestMatchers(
                                "/", "/home", "/about", "/terms", "/privacy",
                                "/random-travel", "/locale",
                                "/robots.txt", "/sitemap.xml",
                                "/login", "/logout",
                                "/oauth2/**", "/login/oauth2/**", "/social-signup",
                                "/social-signup/email-status", "/social-signup/link-existing",
                                // 인증 대기 계정의 이메일 오타 수정. 로그인 상태가 아니라
                                // 세션 문맥과 소셜 재인증으로만 보호된다.
                                "/account/email-required/change",
                                "/account/email-required/change/start",
                                "/account/email-required/change/cancel",
                                "/account/email-required/change/email-status",
                                "/account/email-required/change/password",
                                "/account/email-required/change/password/start",
                                "/social-link", "/social-link/cancel",
                                "/register", "/users/register",
                                "/users/verify", "/users/register/verify-waiting",
                                "/users/verification/resend",
                                // 인증 대기 화면이 세션 문맥만으로 진행 상태를 확인한다.
                                "/users/verification/status",
                                "/users/find-password", "/users/reset-password/**",
                                // 메일로 받은 복구 링크만 공개다. 복구 요청은 탈퇴 유예 안내 화면에서만 한다.
                                "/users/recover-account/confirm",
                                "/css/**", "/js/**", "/images/**", "/fonts/**",
                                "/favicon.ico", "/favicon-16x16.png", "/favicon-32x32.png", "/apple-touch-icon.png",
                                "/android-chrome-192x192.png", "/android-chrome-512x512.png",
                                "/safari-pinned-tab.svg", "/site.webmanifest",
                                "/webjars/**",   // STOMP 클라이언트 등 정적 라이브러리
                                "/api/**",     "/api/destinations/**",
                                "/search", "/search.html",
                                "/destinations/**",
                                "/category/**",
                                "/bookmarks/check",    // NEW
                                "/bookmarks/count",     // NEW
                                // ✅ 댓글 목록 및 이미지 조회는 비로그인도 접근 가능하게 설정
                                "/comments/list", "/comments/images",  "/comments/list/page",
                                "/events", "/events/**",
                                "/board/list", "/board/fragment"
                                ).permitAll()

                        // 댓글 deep-link 위치 조회는 상세페이지와 동일하게 공개 읽기만 허용
                        .requestMatchers(new RegexRequestMatcher(
                                "^/comments/[0-9]+/location$", HttpMethod.GET.name())).permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/comments/[0-9]+/translation$", HttpMethod.GET.name())).permitAll()

                        // 사용자 여행정보 목록 GET만 공개
                        .requestMatchers(HttpMethod.GET, "/travel-info").permitAll()

                        // 숫자 ID 사용자 여행정보 상세 GET만 공개
                        .requestMatchers(new RegexRequestMatcher(
                                "^/travel-info/[0-9]+(?:\\?.*)?$", "GET")).permitAll()

                        // 숫자 ID 축제·행사 전용 상세 GET만 공개
                        .requestMatchers(new RegexRequestMatcher(
                                "^/festivals/[0-9]+(?:\\?.*)?$", "GET")).permitAll()

                        // 고객센터 공지사항 목록과 숫자 ID 상세 GET만 공개
                        .requestMatchers(HttpMethod.GET, "/support/notices").permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/support/notices/[0-9]+$", HttpMethod.GET.name())).permitAll()

                        // 자주 묻는 질문 목록 GET만 공개
                        .requestMatchers(HttpMethod.GET, "/support/faq").permitAll()

                        // 1:1 문의는 목록·작성·상세·삭제 모두 로그인 사용자 전용
                        .requestMatchers("/support/inquiries", "/support/inquiries/**").authenticated()

                        // 게시글 일반 댓글 목록은 비회원도 조회 가능
                        .requestMatchers(HttpMethod.GET, "/post-comments", "/post-comments/page").permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/post-comments/[0-9]+/location$", HttpMethod.GET.name())).permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/post-comments/[0-9]+/translation$", HttpMethod.GET.name())).permitAll()

                        // 게시글 댓글 작성·수정·삭제는 로그인 사용자만 가능
                        .requestMatchers("/post-comments", "/post-comments/**").authenticated()

                        // 여행 코스 일반 댓글 목록은 비회원도 조회 가능
                        .requestMatchers(HttpMethod.GET, "/course-comments", "/course-comments/page").permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/course-comments/[0-9]+/location$", HttpMethod.GET.name())).permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/course-comments/[0-9]+/translation$", HttpMethod.GET.name())).permitAll()

                        // 여행 코스 댓글 작성·수정·삭제는 로그인 사용자만 가능
                        .requestMatchers("/course-comments", "/course-comments/**").authenticated()

                        // 숫자 ID 게시글 상세 GET만 공개 (/post/write는 일치하지 않음)
                        .requestMatchers(new RegexRequestMatcher("^/post/[0-9]+$", "GET")).permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/post/[0-9]+/translation$", HttpMethod.GET.name())).permitAll()

                        // 목록/숫자 ID 읽기만 공개. 작성·수정·삭제는 아래 authenticated 정책과 CSRF를 유지한다.
                        .requestMatchers(HttpMethod.GET, "/courses").permitAll()
                        .requestMatchers(new RegexRequestMatcher("^/courses?/[0-9]+(?:\\?.*)?$", "GET")).permitAll()
                        .requestMatchers(new RegexRequestMatcher(
                                "^/courses?/[0-9]+/translation(?:\\?.*)?$", HttpMethod.GET.name())).permitAll()

                        // 숫자 ID 공개 회원 프로필 GET만 공개 (계정 관련 /users/** 전체는 공개하지 않음)
                        .requestMatchers(new RegexRequestMatcher("^/users/[0-9]+$", "GET")).permitAll()

                        // 초대 링크 미리보기 GET만 공개 (URL-safe Base64 토큰 한 조각)
                        // 방 관리 경로 /travel-plans/{id}/** 는 그대로 인증이 필요하다
                        .requestMatchers(new RegexRequestMatcher(
                                "^/travel-plans/invitations/[A-Za-z0-9_-]+$",
                                HttpMethod.GET.name())).permitAll()

                        // 비회원 다이어리 체험 시작 화면 GET 한 건만 공개한다.
                        // 체험 다이어리는 브라우저 localStorage 에만 남고 서버에 저장되지 않는다.
                        // 아래 /diaries/** 인증 규칙보다 먼저 와야 하며, 회원용 저장 endpoint 는
                        // 그대로 인증이 필요하다(POST /diaries, /diaries/{id}/** 등).
                        // 이 matcher 는 경로 뒤에 물음표까지 붙은 문자열을 보므로 쿼리스트링을 함께 허용한다.
                        // (체험 정보 보완이 /diaries/demo/new?mode=complete 로 들어온다)
                        .requestMatchers(new RegexRequestMatcher(
                                "^/diaries/demo(?:/new|/edit|/cover)?(?:\\?.*)?$",
                                HttpMethod.GET.name())).permitAll()

                        /* === 관리자만 접근 가능한 영역 === */
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        /* === 로그인한 사용자만 접근 가능한 영역 === */
                        .requestMatchers(
                                "/bookmark/**",
                                "/comments/**", // 여전히 필요하지만 list/images는 위에서 permitAll 되었음
                                "/diaries/**",  // 개인 여행일기는 본인만 접근
                                "/mypage/**",
                                "/users/profile/**",
                                "/bookmarks/**"
                        ).authenticated()

                        /* === 그 외는 인증 필요 === */
                        .anyRequest().authenticated()
                )
                .formLogin(login -> {
                    login.loginPage("/login")
                            .loginProcessingUrl("/login")
                            .usernameParameter("email")
                            .successHandler(customLoginSuccessHandler)
                            .permitAll();
                    login.failureHandler(
                            new LoginAuthenticationFailureHandler(loginThrottle));
                })
                .oauth2Login(oauth -> {
                    clientRegistrationRepository.ifAvailable(registrations ->
                            oauth.authorizationEndpoint(endpoint ->
                                    endpoint.authorizationRequestResolver(
                                            new SocialWithdrawalAuthorizationRequestResolver(
                                                    registrations))));
                    socialOAuth2LoginSuccessHandler.ifAvailable(oauth::successHandler);
                    oauth.failureHandler(new OAuth2LoginFailureHandler(
                            authenticationRestorer.getIfAvailable()));
                })
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(customLogoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            String accept = request.getHeader("Accept");
                            if (accept != null && accept.contains("application/json")) {
                                // JS fetch 요청 등: 401 JSON 응답
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write("{\"error\": \"Unauthorized\"}");
                            } else {
                                // 일반 브라우저 접근: 로그인 페이지로 이동
                                response.sendRedirect("/login?redirect=" + request.getRequestURI());
                            }
                        })
                );



        return http.build();
    }

    /**
     * 공개해도 되는 업로드 폴더의 요청 패턴.
     *
     * <p>정적 매핑({@link WebConfig#PUBLIC_UPLOAD_DIRECTORIES})과 같은 목록을 써서
     * 한쪽만 늘어나 매핑은 없는데 접근만 열리는 일이 생기지 않게 한다.
     */
    private static String[] publicUploadPatterns() {
        return WebConfig.PUBLIC_UPLOAD_DIRECTORIES.stream()
                .map(directory -> "/uploads/" + directory + "/**")
                .toArray(String[]::new);
    }
}
