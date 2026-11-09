package com.tripbora.controller.destination;

import com.tripbora.config.*;
import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.security.CustomUserDetails;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DestinationGalleryRenderingTest.Preview.class)
@Import({SecurityConfig.class,DestinationGalleryRenderingTest.Preview.class})
class DestinationGalleryRenderingTest {
    @Controller static class Preview {
        @GetMapping("/review-gallery-preview/{kind}") String gallery(@PathVariable String kind,Model model) {
            var admin=new DestinationImage();admin.setId(7L);admin.setImageUrl("/fixture/admin.svg");
            admin.setSourceName("Official source");admin.setLicenseDetail("CC BY 4.0");
            admin.setLicenseUrl("https://creativecommons.org/licenses/by/4.0/");admin.setPhotographer("Photographer");
            admin.setSourceUrl("https://example.com/photo");
            var review=new ReviewGalleryPhoto();review.setId(7L);review.setCommentId(20L);review.setDestinationId(15L);review.setImageUrl("/fixture/review.svg");
            var list=new ArrayList<DestinationGalleryImage>();
            if(kind.equals("protected")){
                admin.setLicenseType("KOGL_TYPE_3");list.add(DestinationGalleryImage.administrator(admin));
                admin.setId(8L);admin.setLicenseType(null);list.add(DestinationGalleryImage.administrator(admin));
            }
            if(kind.equals("admin")||kind.equals("mixed"))list.add(DestinationGalleryImage.administrator(admin));
            if(kind.equals("review")||kind.equals("mixed"))list.add(DestinationGalleryImage.traveler(review));
            model.addAttribute("galleryImages",list);model.addAttribute("hasImageAttribution",true);
            return "destination/detail :: #destination-gallery";
        }
        @GetMapping("/review-gallery-modal-preview") String modal(){return "destination/detail :: #destination-image-modal";}
        @GetMapping("/review-gallery-messages-preview") String messages(){return "destination/detail :: #destination-detail-i18n";}
        @GetMapping("/destination-gallery-paging-preview/{part}") String paged(@PathVariable String part,Model model) {
            var photos=new ArrayList<DestinationGalleryImage>();
            for(long id=1;id<=30;id++){
                var photo=new DestinationImage();photo.setId(id);photo.setDestinationId(15L);
                photo.setImageUrl("/fixture/admin.svg");photo.setSourceName("Official source");
                photos.add(DestinationGalleryImage.administrator(photo));
            }
            model.addAttribute("galleryImages",photos);
            model.addAttribute("hasImageAttribution",true);
            model.addAttribute("galleryEndpoint","/destinations/15/gallery");
            model.addAttribute("galleryNextCursor","cursor-30");
            return switch(part){
                case "grid"->"destination/detail :: #destination-gallery-grid";
                case "modal"->"destination/detail :: #destination-image-modal";
                case "messages"->"destination/detail :: #destination-detail-i18n";
                default->"destination/detail :: #destination-gallery";
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ResourceUrlProvider resources;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean com.tripbora.security.LoginThrottle throttle;
    @MockitoBean com.tripbora.repository.user.UserMapper users;

    @Test void pagedGalleryFixtureUsesRealMarkupAndHashedResources() throws Exception {
        Path directory=Path.of("build/reports/destination-gallery-paging");Files.createDirectories(directory);
        var owner=new User();owner.setId(7L);owner.setUserRole(UserRole.USER);
        StringBuilder fixture=new StringBuilder();
        for(String part:List.of("messages","main","grid","modal")){
            String html=mvc.perform(get("/destination-gallery-paging-preview/"+part)
                            .with(user(new CustomUserDetails(owner))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            fixture.append(html);
        }
        Files.writeString(directory.resolve("detail.html"),fixture);
        var page=Jsoup.parse(fixture.toString());
        assertThat(page.select(".slide")).hasSize(30);
        assertThat(page.selectFirst("#destination-gallery").attr("data-gallery-endpoint"))
                .isEqualTo("/destinations/15/gallery");
        var paths=new LinkedHashMap<String,String>();
        for(String asset:List.of("/js/detail-carousel.js","/js/destination-image-modal.js",
                "/css/detail.css","/css/destination-detail-mobile.css")){
            String path=resources.getForLookupPath(asset);
            assertThat(path).matches("/(js|css)/.+-[a-f0-9]{32}\\.(js|css)");
            mvc.perform(get(path)).andExpect(status().isOk());
            paths.put(asset,path);
        }
        Files.writeString(directory.resolve("resources.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(paths));
    }

    @Test void actualGalleryAndModalRenderForEveryLanguageAndContentCombination() throws Exception {
        Path directory=Path.of("build/reports/review-gallery");Files.createDirectories(directory);
        var owner=new User();owner.setId(7L);owner.setUserRole(UserRole.USER);
        for(var language:com.tripbora.config.i18n.SupportedLanguage.values()){
            var cookie=new jakarta.servlet.http.Cookie(com.tripbora.config.i18n.TripBoraLocaleResolver.COOKIE_NAME,language.getLanguageTag());
            for(String kind:List.of("admin","review","mixed","empty","protected")){
                String html=mvc.perform(get("/review-gallery-preview/"+kind).cookie(cookie).with(user(new CustomUserDetails(owner))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                var page=Jsoup.parse(html);
                assertThat(page.select(".slide")).hasSize(kind.equals("mixed")||kind.equals("protected")?2:kind.equals("empty")?0:1);
                if(kind.equals("protected"))assertThat(page.select(".destination-gallery-thumbnails button.is-no-derivatives")).hasSize(1);
                if(kind.equals("mixed"))assertThat(page.select(".slide").eachAttr("data-gallery-key")).containsExactly("admin:7","review:7");
                if(kind.equals("review")){
                    assertThat(page.select("[data-review-id='20'][data-review-image-id='7']")).hasSize(1);
                    assertThat(page.selectFirst("[data-destination-image-review-link]").attr("href")).isEqualTo("/destinations/15?commentId=20");
                    assertThat(page.selectFirst("[data-destination-image-review-link]").hasAttr("hidden")).isFalse();
                }
                assertThat(html).doesNotContain("??");Files.writeString(directory.resolve(kind+"-"+language.getLanguageTag()+".html"),html);
            }
            for(String part:List.of("modal","messages")){
                String html=mvc.perform(get("/review-gallery-"+part+"-preview").cookie(cookie).with(user(new CustomUserDetails(owner))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                Files.writeString(directory.resolve(part+"-"+language.getLanguageTag()+".html"),html);
            }
        }
        for(String asset:List.of("/js/detail-carousel.js","/js/destination-image-modal.js","/css/destination-detail-mobile.css")){
            String url=resources.getForLookupPath(asset);assertThat(url).matches("/(js|css)/.+-[a-f0-9]{32}\\.(js|css)");
            var response=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css","text/javascript","application/javascript");
        }
    }
}
