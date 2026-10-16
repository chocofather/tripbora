package com.tripbora.config;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.seo.SeoRobotsPolicy;
import com.tripbora.seo.SeoSiteUrl;
import com.tripbora.web.HeaderNavigation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

@ControllerAdvice
public class GlobalRequestControllerAdvice {

    private static final String DEFAULT_OG_IMAGE = "/images/branding/tripbora-og.png";

    private final MessageSource messageSource;
    private final String configuredSiteBaseUrl;
    private final String contactEmail;
    private final String operatorName;

    public GlobalRequestControllerAdvice(
            MessageSource messageSource,
            @Value("${seo.site-base-url:}") String configuredSiteBaseUrl,
            @Value("${app.contact-email:}") String contactEmail,
            @Value("${app.operator-name:}") String operatorName) {
        this.messageSource = messageSource;
        this.configuredSiteBaseUrl = configuredSiteBaseUrl;
        this.contactEmail = contactEmail == null ? "" : contactEmail.strip();
        this.operatorName = operatorName == null ? "" : operatorName.strip();
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isBlank()
                ? request.getRequestURI()
                : request.getRequestURI() + "?" + query;
    }

    @ModelAttribute("headerNavigation")
    public HeaderNavigation headerNavigation(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return new HeaderNavigation(path, request.getParameter("type"));
    }

    @ModelAttribute
    public void localeAttributes(Model model, Locale locale) {
        SupportedLanguage currentLanguage = SupportedLanguage.fromLocale(locale)
                .orElse(SupportedLanguage.KOREAN);
        model.addAttribute("supportedLanguages", SupportedLanguage.all());
        model.addAttribute("currentLanguage", currentLanguage);
        model.addAttribute("currentLanguageTag", currentLanguage.getLanguageTag());
        model.addAttribute("footerContactEmail", contactEmail);
        model.addAttribute("footerOperatorName", operatorName);
    }

    @ModelAttribute
    public void seoAttributes(Model model, Locale locale, HttpServletRequest request) {
        String path = request.getRequestURI();
        SeoDefaults defaults = defaults(path, request);
        model.addAttribute("seoTitle", message(defaults.titleKey(), locale));
        model.addAttribute("seoDescription", message(defaults.descriptionKey(), locale));
        model.addAttribute("seoCanonicalPath", path);
        model.addAttribute("seoSiteBaseUrl",
                SeoSiteUrl.resolveBaseUrl(configuredSiteBaseUrl, request));
        boolean filteredSearch = "/travel-info".equals(path)
                && request.getParameter("keyword") != null
                && !request.getParameter("keyword").isBlank();
        model.addAttribute("seoRobots", SeoRobotsPolicy.isNoindex(path) || filteredSearch
                ? SeoRobotsPolicy.NOINDEX : SeoRobotsPolicy.INDEX);
        model.addAttribute("seoOpenGraphType", "website");
        model.addAttribute("seoImage", DEFAULT_OG_IMAGE);
    }

    private SeoDefaults defaults(String path, HttpServletRequest request) {
        if ("/".equals(path)) {
            return new SeoDefaults("seo.home.title", "seo.home.description");
        }
        if ("/about".equals(path)) {
            return new SeoDefaults("seo.about.title", "seo.about.description");
        }
        if ("/terms".equals(path)) {
            return new SeoDefaults("seo.terms.title", "seo.terms.description");
        }
        if ("/privacy".equals(path)) {
            return new SeoDefaults("seo.privacy.title", "seo.privacy.description");
        }
        if ("/destinations".equals(path)) {
            return new SeoDefaults("seo.destinations.title", "seo.destinations.description");
        }
        if ("/travel-info".equals(path)) {
            boolean festival = "FESTIVAL".equalsIgnoreCase(request.getParameter("contentType"));
            return festival
                    ? new SeoDefaults("seo.festivals.title", "seo.festivals.description")
                    : new SeoDefaults("seo.travelInfo.title", "seo.travelInfo.description");
        }
        if ("/events".equals(path)) {
            return new SeoDefaults("seo.events.title", "seo.events.description");
        }
        if ("/board/list".equals(path)) {
            return new SeoDefaults("seo.community.title", "seo.community.description");
        }
        return new SeoDefaults("seo.default.title", "seo.default.description");
    }

    private String message(String code, Locale locale) {
        return messageSource.getMessage(code, null, locale);
    }

    private record SeoDefaults(String titleKey, String descriptionKey) {
    }

}
