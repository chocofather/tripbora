package com.tripbora.controller;

import com.tripbora.model.PolicyType;
import com.tripbora.dto.ReviewPhotoUploadTerms;
import com.tripbora.service.policy.GalleryConsentService;
import com.tripbora.service.policy.SignupPolicy;
import com.tripbora.service.policy.SignupPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.MediaType;

@Controller
@RequiredArgsConstructor
public class PolicyController {

    private final SignupPolicyService signupPolicyService;
    private final GalleryConsentService galleryConsentService;

    @GetMapping(value = "/review-photo-terms", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ReviewPhotoUploadTerms reviewPhotoTermsJson(@RequestParam(required = false) Long versionId) {
        return galleryConsentService.uploadTerms(versionId);
    }

    @GetMapping(value = "/review-photo-terms", produces = MediaType.TEXT_HTML_VALUE)
    public String reviewPhotoTerms(@RequestParam(required = false) Long versionId, Model model) {
        ReviewPhotoUploadTerms terms = galleryConsentService.uploadTerms(versionId);
        model.addAttribute("policy", terms.available() ? new SignupPolicy(terms.policyVersionId(),
                PolicyType.PHOTO_UPLOAD_NOTICE, terms.version(), false, false, terms.title(),
                terms.contentHtml(), terms.locale(), null) : null);
        model.addAttribute("policyHeadingCode", PolicyType.PHOTO_UPLOAD_NOTICE.getLabelCode());
        return "policy/detail";
    }

    @GetMapping("/terms")
    public String terms(Model model) {
        return policyPage(model, PolicyType.TERMS_OF_SERVICE, "policy.terms.title");
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        return policyPage(model, PolicyType.PRIVACY_POLICY, "policy.privacy.title");
    }

    private String policyPage(Model model, PolicyType type, String headingCode) {
        SignupPolicy policy = signupPolicyService.loadSignupPolicies().policies().stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElse(null);
        model.addAttribute("policy", policy);
        model.addAttribute("policyHeadingCode", headingCode);
        return "policy/detail";
    }
}
