package com.tripbora.dto;

/** 업로드 버튼 옆 안내와 공개 약관 페이지가 공유하는 실제 게시 약관. */
public record ReviewPhotoUploadTerms(boolean available, Long policyVersionId, String version,
                                     String title, String contentHtml, String locale, String policyUrl) {
}
