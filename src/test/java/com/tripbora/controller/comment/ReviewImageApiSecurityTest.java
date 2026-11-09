package com.tripbora.controller.comment;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.admin.AdminReviewImageController;
import com.tripbora.dto.ReviewImageAdminDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 SecurityConfig를 통한 작성자 API 인증·CSRF와 관리자 경계 검증. */
@WebMvcTest({DestinationCommentImageController.class, CommentEditController.class, AdminReviewImageController.class, MyReviewImageController.class, GalleryConsentController.class})
@Import(SecurityConfig.class)
class ReviewImageApiSecurityTest {
    @Test void retiredGalleryGetAndEveryDecisionReturnGoneWithoutServiceWrites() throws Exception {
        mvc.perform(get("/mypage/gallery-consent").with(user(principal(UserRole.USER))))
                .andExpect(status().isGone());
        for (String action : List.of("AGREE", "DECLINE", "LATER"))
            mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + action + "\",\"version\":\"v1\",\"policyVersionId\":40}"))
                    .andExpect(status().isGone());
        verifyNoInteractions(galleryConsents);
    }

    @Test void retiredGalleryApiDoesNotAllowAdminToOptIn() throws Exception {
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.ADMIN))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"AGREE\",\"version\":\"v1\",\"policyVersionId\":40}"))
                .andExpect(status().isGone());
        verifyNoInteractions(galleryConsents);
    }
    @Test void retiredGalleryApiReturnsAnExplicitReasonToTheExistingFetchClient() throws Exception {
        mvc.perform(put("/mypage/gallery-consent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agreed\":true,\"version\":\"old\"}").with(user(principal(UserRole.USER))).with(csrf()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("종료")));
        verifyNoInteractions(galleryConsents);
    }
    @Test void manualBatchAndExplicitGroupRejectUseAdminAndCsrfBoundaries() throws Exception {
        String base="/admin/review-images/destinations/10/workflow";
        for(String path:List.of("/batch","/reject-group")) {
            String body=path.equals("/batch")?"{\"action\":\"REJECT\",\"expected\":{\"6\":\"PENDING\"}}":"{\"anchorId\":6,\"kind\":\"exact\",\"expected\":{\"6\":\"PENDING\"}}";
            mvc.perform(post(base+path).contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.USER))).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post(base+path).contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
            mvc.perform(post(base+path).contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isOk());
        }
        verify(workflow).batchModerate(10L,"REJECT",java.util.Map.of(6L,"PENDING"),7L);
        verify(workflow).rejectGroup(10L,6L,"exact",java.util.Map.of(6L,"PENDING"),7L);
    }
    @Test void manualAndWholeGroupConflictsReturnExplicitAtomicFailure() throws Exception {
        String base="/admin/review-images/destinations/10/workflow";
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"사진 상태 변경"))
                .when(workflow).batchModerate(eq(10L),eq("APPROVE"),anyMap(),eq(7L));
        mvc.perform(post(base+"/batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\",\"expected\":{\"6\":\"PENDING\"}}")
                .with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("처리 0건 · 묶음 전체 취소: 사진 상태 변경")));
        doThrow(new org.springframework.dao.CannotAcquireLockException("concurrent moderation"))
                .when(workflow).rejectGroup(eq(10L),eq(6L),eq("exact"),anyMap(),eq(7L));
        mvc.perform(post(base+"/reject-group").contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchorId\":6,\"kind\":\"exact\",\"expected\":{\"6\":\"PENDING\"}}")
                .with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("처리 0건")));
    }
    @MockitoBean com.tripbora.service.comment.ReviewPhotoWorkflowService workflow;
    @Test void workflowMutationsRequireAdminCsrfAndReturnAtomicFailure() throws Exception {
        String base="/admin/review-images/destinations/10/workflow";
        String body="{\"expected\":{\"1\":\"PENDING\",\"2\":\"PENDING\"}}";
        mvc.perform(post(base+"/approve").contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.USER))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(base+"/approve").contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
        verifyNoInteractions(workflow);
        mvc.perform(post(base+"/approve").contentType(MediaType.APPLICATION_JSON).content(body).with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isOk());
        verify(workflow).approveClean(10L,java.util.Map.of(1L,"PENDING",2L,"PENDING"),7L);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"다른 관리자 심사"))
                .when(workflow).decide(eq(10L),eq(1L),eq("similar"),eq(List.of(1L,2L)),anyMap(),eq(7L));
        mvc.perform(post(base+"/decide").contentType(MediaType.APPLICATION_JSON)
                .content("{\"anchorId\":1,\"kind\":\"similar\",\"selected\":[1,2],\"expected\":{\"1\":\"APPROVED\",\"2\":\"PENDING\"}}")
                .with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("0건")));
        mvc.perform(get(base).param("section","INVALID").with(user(principal(UserRole.ADMIN)))).andExpect(status().isBadRequest());
    }
    @MockitoBean com.tripbora.service.comment.ReviewDuplicateService duplicates;
    @Test void duplicateKeepRequiresAdminAndCsrfAndReportsRollback() throws Exception {
        String url="/admin/review-images/destinations/10/duplicates/keep";
        String json="{\"representative\":1,\"expected\":{\"1\":\"APPROVED\",\"2\":\"PENDING\"}}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json).with(user(principal(UserRole.USER))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json).with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
        verifyNoInteractions(duplicates);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"동의 변경"))
                .when(duplicates).keep(eq(10L),eq(1L),anyMap(),eq(7L));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json).with(user(principal(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("0건 · 전체 취소")));
    }
    @MockitoBean com.tripbora.service.comment.ReviewGalleryPriorityService priorities;
    @MockitoBean com.tripbora.repository.comment.ReviewPhotoAnalysisMapper analysisMapper;
    @Test void destinationListAndDetailRenderSeparateNavigationAndScopedForms() throws Exception {
        var row=new com.tripbora.dto.ReviewDestinationAdminDto();row.setDestinationId(10L);row.setDestinationName("산굼부리");row.setRegionName("제주");row.setCategoryNames("자연");row.setTotal(35);row.setPending(30);row.setApproved(1);row.setRejected(1);row.setRevoked(1);row.setNone(2);row.setReviewCount(10);row.setUserCount(10);
        when(queue.destinations("산","pending",1)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Destinations(List.of(row),true));
        var list=mvc.perform(get("/admin/review-images/destinations").param("q","산").param("page","1").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("산굼부리","사진이 연결된 리뷰 10개","/admin/review-images/destinations/10");
        var photos=new java.util.ArrayList<ReviewImageAdminDto>();
        for(int i=1;i<=35;i++){var p=new ReviewImageAdminDto();p.setId((long)i);p.setCommentId((long)(20+i%10));p.setUserId((long)(i%10));p.setDestinationId(10L);p.setNickname("여행자"+i%10);p.setCreatedAt(java.time.LocalDateTime.of(2026,11,1,10,0));p.setImageUrl("/fixture/"+i+".svg");p.setGalleryStatus(i<=30?"PENDING":i==31?"APPROVED":i==32?"REJECTED":i==33?"REVOKED":"NONE");photos.add(p);}
        for(int i=0;i<5;i++){var p=photos.get(i);p.setAnalysisState("READY");p.setPixelWidth(1200);p.setPixelHeight(800);p.setMeanBrightness(100.0);p.setSharpness(200.0);p.setQualityFlags(i==0?"BLUR":"");}
        photos.get(0).setExactCount(1);photos.get(1).setSimilarCount(4);
        photos.get(30).setGalleryPriority(3);photos.get(30).setGalleryPriorityVersion(4);
        when(queue.summary(10L)).thenReturn(row);
        var detail=mvc.perform(get("/admin/review-images/destinations/10").param("page","1").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc=org.jsoup.Jsoup.parse(detail);
        for (var rendered : List.of(org.jsoup.Jsoup.parse(list),doc)) {
            for (var asset : rendered.select("link[href], script[src]")) {
                String url=asset.hasAttr("href")?asset.attr("href"):asset.attr("src");
                if (!url.startsWith("/css/") && !url.startsWith("/js/")) continue;
                assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
                var response=mvc.perform(get(url).with(user(principal(UserRole.ADMIN))))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentType()).isIn("text/css","text/javascript","application/javascript");
            }
        }
        assertThat(doc.select("[data-workflow-grid]")).hasSize(1);
        assertThat(doc.select("button[data-section]")).hasSize(3);
        assertThat(doc.select("[data-photo-workflow]")).hasSize(1);
        assertThat(detail).contains("중복 후보 없음","동일·유사 사진","검사 대기·제한","원본 리뷰 사진은 유지됩니다");
        assertThat(doc.select("[data-workflow-csrf] [name=_csrf]")).hasSize(1);
        assertThat(doc.select("[data-manual-actions]")).hasSize(1);
        assertThat(doc.select("[data-photo-workflow]").attr("data-page")).isEqualTo("1");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/admin-review-destinations"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/admin-review-destinations/page.html"),doc.selectFirst("[data-photo-queue]").outerHtml());
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/admin-review-destinations/list.html"),org.jsoup.Jsoup.parse(list).selectFirst(".admin-page-section").outerHtml());
    }
    @Test void destinationEndpointsKeepAdminCsrfAndValidationBoundaries() throws Exception {
        mvc.perform(get("/admin/review-images/destinations/10").with(user(principal(UserRole.USER)))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/review-images/destinations/10/batch").param("ids","1").param("action","APPROVE").with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/review-images/destinations/10").param("status","INVALID").with(user(principal(UserRole.ADMIN)))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/review-images/destinations/10").param("sort","INVALID").with(user(principal(UserRole.ADMIN)))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/review-images/destinations").param("sort","INVALID").with(user(principal(UserRole.ADMIN)))).andExpect(status().isBadRequest());
        verifyNoInteractions(queue);
    }
    @Test void comparisonAndPriorityEndpointsKeepAdminCsrfAndExplicitVersion() throws Exception {
        String target="/admin/review-images/destinations/10/photos/31/priority";
        mvc.perform(post(target).param("priority","2").param("version","4").with(user(principal(UserRole.USER))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(target).param("priority","2").param("version","4").with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
        verifyNoInteractions(priorities);
        mvc.perform(post(target).param("priority","2").param("version","4").with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isNoContent());
        verify(priorities).setPriority(10L,31L,7L,2,4L);
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"새로고침 필요"))
                .when(priorities).setPriority(10L,31L,7L,null,4L);
        mvc.perform(post(target).param("version","4").with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("새로고침 필요"));
        mvc.perform(get("/admin/review-images/destinations/10/photos/1/matches").with(user(principal(UserRole.USER)))).andExpect(status().isForbidden());
        var matches=java.util.stream.LongStream.rangeClosed(1,21).mapToObj(id->{var m=new com.tripbora.dto.ReviewPhotoMatch();m.setId(id);return m;}).toList();
        when(analysisMapper.matches(10L,1L,20L,21)).thenReturn(matches);
        mvc.perform(get("/admin/review-images/destinations/10/photos/1/matches").param("page","1").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos.length()").value(20)).andExpect(jsonPath("$.hasNext").value(true));
        verify(analysisMapper).matches(10L,1L,20L,21);
    }
    @Test void scopedBatchReturnsToSameDestinationFilterAndPage() throws Exception {
        when(queue.batchForDestination(10L,List.of(1L),7L,"APPROVE",null)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Result(1,0));
        mvc.perform(post("/admin/review-images/destinations/10/batch").param("ids","1").param("action","APPROVE").param("status","PENDING").param("page","2")
                .with(user(principal(UserRole.ADMIN))).with(csrf())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/review-images/destinations/10?status=PENDING&page=2&sort=latest"));
        verify(queue).batchForDestination(10L,List.of(1L),7L,"APPROVE",null);
    }
    @MockitoBean com.tripbora.service.comment.ReviewPhotoAdminService queue;
    @Test void pendingQueueShowsPhotoScopeRestrictionWithoutRemovingRejection() throws Exception {
        var photo=new ReviewImageAdminDto();photo.setId(1L);photo.setCommentId(20L);photo.setDestinationId(10L);
        photo.setGalleryStatus("PENDING");photo.setEligible(false);photo.setEligibilityReason("PHOTO_CONSENT_INVALID");
        when(queue.list("PENDING","",0)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Queue(
                List.of(new com.tripbora.dto.ReviewPhotoGroup(photo,List.of(photo))),false,java.util.Map.of("PENDING",1L)));
        var response=mvc.perform(get("/admin/review-images").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc=org.jsoup.Jsoup.parse(response);
        assertThat(doc.select("[data-single][value=APPROVE]")).isEmpty();
        assertThat(doc.select("[data-single][value=REJECT]")).hasSize(1);
        assertThat(doc.select("[data-approve-card][disabled]")).hasSize(1);
        assertThat(doc.text()).contains("원본 동의 근거", "반려 가능");
    }
    @Test void groupedAdminQueueRendersAllPhotosAndPreservesReviewLinks() throws Exception {
        var photos=new java.util.ArrayList<com.tripbora.dto.ReviewImageAdminDto>();
        for(int i=1;i<=5;i++) { var p=new com.tripbora.dto.ReviewImageAdminDto();p.setId((long)i);p.setCommentId(20L);p.setDestinationId(10L);p.setDestinationName("파리");p.setNickname("여행자");p.setContent("방문 경험");p.setImageUrl("/fixture/"+i+".svg");p.setGalleryStatus(i<=3?"PENDING":i==4?"APPROVED":"REJECTED");photos.add(p); }
        photos.forEach(photo -> photo.setEligible(true));
        when(queue.list("PENDING","",0)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Queue(
                List.of(new com.tripbora.dto.ReviewPhotoGroup(photos.get(0),photos)),false,java.util.Map.of("PENDING",3L,"APPROVED",1L,"REJECTED",1L)));
        var html=mvc.perform(get("/admin/review-images").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document=org.jsoup.Jsoup.parse(html);
        assertThat(document.select(".review-photo-group")).hasSize(1);
        assertThat(document.select(".review-queue-photo")).hasSize(5);
        String script=document.selectFirst("script[src*='admin-review-photos']").attr("src");
        assertThat(script).matches("/js/admin-review-photos-[a-f0-9]{32}\\.js");
        var asset=mvc.perform(get(script).with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(asset.getContentType()).isIn("text/javascript","application/javascript");
        assertThat(document.select("a[href='/destinations/10?commentId=20']")).hasSize(1);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/admin-review-queue"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/admin-review-queue/page.html"),document.selectFirst("[data-photo-queue]").outerHtml());
    }

    @Test void batchReviewRequiresAdministratorAndCsrf() throws Exception {
        mvc.perform(post("/admin/review-images/batch").param("ids","1").param("action","APPROVE").with(user(principal(UserRole.USER))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/review-images/batch").param("ids","1").param("action","APPROVE").with(user(principal(UserRole.ADMIN)))).andExpect(status().isForbidden());
        verifyNoInteractions(queue);
    }
    @Test void batchReviewReportsPartialResultsUsingPrincipal() throws Exception {
        when(queue.batch(List.of(1L,2L),7L,"APPROVE",null)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Result(1,1));
        mvc.perform(post("/admin/review-images/batch").param("ids","1","2").param("action","APPROVE")
                .with(user(principal(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("notice",org.hamcrest.Matchers.containsString("성공 1건 · 실패 1건")));
        verify(queue).batch(List.of(1L,2L),7L,"APPROVE",null);
    }
    @Test
    void combinedEditUsesPrincipalAndRequiresCsrf() throws Exception {
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/comments/10/with-images")
                        .param("content", "수정 후기").param("removeImageIds", "20")
                        .with(user(principal(UserRole.USER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/comments/10/with-images")
                        .param("content", "수정 후기").param("removeImageIds", "20")
                        .with(user(principal(UserRole.USER))).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).edit(eq(10L), eq(7L), eq("수정 후기"), eq(List.of(20L)), isNull(), isNull(), eq(false), isNull());
    }
    @MockitoBean com.tripbora.service.policy.GalleryConsentService galleryConsents;

    @Test
    void memberGalleryDecisionRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/mypage/gallery-consent")).andExpect(status().is3xxRedirection());
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(galleryConsents);
    }

    @Test
    void legacyBooleanWithdrawalStillUsesTheAuthenticatedPrincipal() throws Exception {
        when(galleryConsents.decide(7L, false, "v1")).thenReturn(new com.tripbora.dto.GalleryConsentState(true, false, "v1"));
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":false,\"version\":\"v1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreed").value(false));
        verify(galleryConsents).decide(7L, false, "v1");
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone());
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true}"))
                .andExpect(status().isGone());
        verifyNoMoreInteractions(galleryConsents);
    }

    @Test
    void explicitHistoricalWithdrawalUsesPrincipalVersionAndCsrf() throws Exception {
        String body = "{\"action\":\"WITHDRAW\",\"version\":\"v1\",\"policyVersionId\":40,\"userId\":99}";
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(galleryConsents);
        when(galleryConsents.decide(7L, "WITHDRAW", "v1", 40L))
                .thenReturn(new com.tripbora.dto.GalleryConsentState(true, false, "v1"));
        mvc.perform(put("/mypage/gallery-consent").with(user(principal(UserRole.USER))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(galleryConsents).decide(7L, "WITHDRAW", "v1", 40L);
    }
    @Test
    void privatePhotoPageIncludesHiddenCommentManagement() throws Exception {
        ReviewImageAdminDto photo = new ReviewImageAdminDto();
        photo.setCommentId(10L);
        photo.setDestinationId(30L);
        photo.setDestinationName("내 사진 여행지");
        photo.setContent("숨김 처리된 내 댓글");
        when(service.myPhotos(7L, 0)).thenReturn(List.of(photo));
        mvc.perform(get("/mypage/review-images").with(user(principal(UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-review-owner-id=\"7\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-id=\"10\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("숨김 처리된 내 댓글")));
        verify(service).myPhotos(7L, 0);
    }

    @Test
    void privatePhotoPageRequiresAuthentication() throws Exception {
        mvc.perform(get("/mypage/review-images")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }
    @Autowired MockMvc mvc;
    @MockitoBean ReviewImageService service;
    @MockitoBean CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean UserMapper userMapper;

    @Test
    void anonymousCannotReadOwnerMetadata() throws Exception {
        mvc.perform(get("/comments/10/images")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }

    @Test
    void imageDeletionRequiresCsrf() throws Exception {
        mvc.perform(delete("/comments/10/images/20").with(user(principal(UserRole.USER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void consentUsesAuthenticatedIdentityAndRequiresExplicitBoolean() throws Exception {
        mvc.perform(put("/comments/10/images/20/consent").with(user(principal(UserRole.USER)))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":true,\"rightsConfirmed\":true}"))
                .andExpect(status().isNoContent());
        verify(service).consent(10L, 20L, 7L, true, true);
        mvc.perform(put("/comments/10/images/20/consent").with(user(principal(UserRole.USER)))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }

    @Test
    void userCannotReviewAndAdminCannotBypassCsrf() throws Exception {
        mvc.perform(post("/admin/review-images/20/review").param("action", "APPROVE")
                        .with(user(principal(UserRole.USER))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/review-images/20/review").param("action", "APPROVE")
                        .with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void administratorReviewUsesPrincipalAndInternalRedirect() throws Exception {
        mvc.perform(post("/admin/review-images/20/review").param("action", "REJECT")
                        .param("reason", "사용 권한 확인 필요").with(user(principal(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/review-images?status=PENDING"));
        verify(service).review(20L, 7L, "REJECT", "사용 권한 확인 필요");
    }

    @Test
    void administratorListRendersPreviewAndCsrfProtectedReviewForm() throws Exception {
        ReviewImageAdminDto image = new ReviewImageAdminDto();
        image.setId(20L);
        image.setCommentId(10L);
        image.setDestinationId(30L);
        image.setDestinationName("사진 여행지");
        image.setNickname("사진 작성자");
        image.setImageUrl("/uploads/comments/test.jpg");
        image.setGalleryStatus("PENDING");
        image.setContent("후기 본문");
        when(queue.list("PENDING", "", 0)).thenReturn(new com.tripbora.service.comment.ReviewPhotoAdminService.Queue(List.of(new com.tripbora.dto.ReviewPhotoGroup(image,List.of(image))),false,java.util.Map.of("PENDING",1L)));
        mvc.perform(get("/admin/review-images").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("사진 작성자")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/review-images/batch")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }

    private CustomUserDetails principal(UserRole role) {
        User user = new User();
        user.setId(7L);
        user.setUserRole(role);
        return new CustomUserDetails(user);
    }
}
