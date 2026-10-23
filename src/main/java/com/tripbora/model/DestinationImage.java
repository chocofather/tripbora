    package com.tripbora.model;

    import lombok.Data;
    import lombok.NoArgsConstructor;

    import java.net.URI;
    import java.sql.Timestamp;
    import java.util.Locale;


    @Data
    @NoArgsConstructor
    public class DestinationImage {
        public static final String COMMONS_SOURCE_TYPE = "WIKIMEDIA_COMMONS";
        public static final String PIXABAY_SOURCE_TYPE = "PIXABAY";

        private Long id; // 여행지 이미지 번호
        private String imageUrl; // 이미지 Url
        private String sourceType; // 이미지 유입 경로
        private String sourceName; // 사진 제공기관명
        private String externalContentId; // 외부 API 콘텐츠 식별자
        private String sourceTitle; // 외부 원본 사진 제목
        private String photographer; // 촬영자/저작자
        private String licenseType; // 라이선스 유형
        private String licenseDetail; // 라이선스 세부 이용조건
        private String sourceUrl; // 의미가 확정되지 않은 기존 source_url / legacy_source_url
        private String commonSourceUrl; // 제공기관/컬렉션 공통 페이지
        private String workPageUrl; // 사진별 원본 페이지
        private String sourceImageUrl; // 외부 제공처의 원본 이미지 URL
        private Timestamp licenseCheckedAt; // 라이선스 조건 확인 시각
        private String licenseName; // 라이선스 표기명 (예: CC BY-SA 4.0). 신규 출처 행에만 있다
        private String licenseUrl; // 라이선스 전문 URL. 신규 출처 행에만 있다
        private String customAttribution; // 제공처가 요구한 별도 크레딧
        private String commonsFileTitle; // Wikimedia Commons 파일 제목
        private String wikidataQid; // Commons 사진을 가져온 Wikidata 항목
        private Boolean sourceRecordPresent; // 신규 출처 행 존재 여부 (필드별 구 컬럼 대체 방지)
        private Timestamp createdAt; // 생성일
        private Boolean isMain; // 메인이미지 여부
        private Boolean isSlide; // 슬라이드 여부
        private Integer orderIndex; // 이미지 순서
        private Long destinationId; // 여행지번호
        private int cardPositionX = 50; // 목록 카드의 object-position (%)
        private int cardPositionY = 50;

        public String getLicenseLabel() {
            return DestinationImageLicenseType.displayName(licenseType);
        }

        /** 공공누리 제3유형(변경금지). 화면에서도 잘라 보이지 않게 원본 비율로 그린다. */
        public boolean isNoDerivatives() {
            return DestinationImageLicenseType.KOGL_TYPE_3.name().equals(text(licenseType));
        }

        /** 세부조건, 라이선스 표기명, 기본 라벨 순으로 사람이 읽는 값을 쓴다. */
        public String getLicenseDisplay() {
            String detail = text(licenseDetail);
            if (detail != null) {
                return detail;
            }
            String name = text(licenseName);
            if (name != null) {
                return name;
            }
            return getLicenseLabel();
        }

        public boolean isAttributionPresent() {
            return text(sourceName) != null || getLicenseDisplay() != null
                    || text(photographer) != null || getAttributionUrl() != null;
        }

        public boolean isCommonsImage() {
            return COMMONS_SOURCE_TYPE.equals(sourceType);
        }

        /** Pixabay 검색 응답에서 고른 사진. 출처는 저장 때 서버가 받은 값이라 관리 화면에서 고치지 않는다. */
        public boolean isPixabayImage() {
            return PIXABAY_SOURCE_TYPE.equals(sourceType);
        }

        /**
         * 관리 화면 카드의 출처 등록 상태.
         * <ul>
         *   <li>COMMONS: 원본에서 검증한 Commons 사진 (관리 화면에서 고치지 않는다)</li>
         *   <li>PIXABAY: Pixabay 검색 응답으로 저장한 사진 (관리 화면에서 고치지 않는다)</li>
         *   <li>MISSING: 제공기관·촬영자가 모두 비어 있다 — '출처 입력 필요'</li>
         *   <li>LICENSE_NEEDED: 제공기관·촬영자는 있으나 라이선스가 비었거나 '확인 안 됨'</li>
         *   <li>COMPLETE: 그 밖</li>
         * </ul>
         */
        public String getSourceStatus() {
            if (isCommonsImage()) {
                return "COMMONS";
            }
            if (isPixabayImage()) {
                return "PIXABAY";
            }
            if (text(sourceName) == null && text(photographer) == null) {
                return "MISSING";
            }
            String license = text(licenseType);
            if (license == null || "UNKNOWN".equals(license)) {
                return "LICENSE_NEEDED";
            }
            return "COMPLETE";
        }

        /** 카드에 한 줄로 보이는 출처 요약(제공기관 · 촬영자 · 라이선스). 모두 비어 있으면 {@code null}. */
        public String getSourceSummary() {
            String summary = java.util.stream.Stream.of(text(sourceName), text(photographer), getLicenseDisplay())
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining(" · "));
            return summary.isEmpty() ? null : summary;
        }

        /** 공개 화면의 출처 링크. Commons·Pixabay 사진은 사진별 원본 페이지, 그 외는 기존 출처 URL을 쓴다. */
        public String getAttributionUrl() {
            return isCommonsImage() || isPixabayImage() ? safeHttpUrl(workPageUrl) : getSafeSourceUrl();
        }

        public String getSafeLicenseUrl() {
            return safeHttpUrl(licenseUrl);
        }

        /** 외부 링크로 렌더링해도 되는 절대 HTTP(S) URL만 돌려준다. */
        public String getSafeSourceUrl() {
            return safeHttpUrl(sourceUrl);
        }

        private String safeHttpUrl(String value) {
            String candidate = text(value);
            if (candidate == null) {
                return null;
            }
            try {
                URI uri = URI.create(candidate);
                String scheme = uri.getScheme() == null
                        ? ""
                        : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!("http".equals(scheme) || "https".equals(scheme))
                        || uri.getHost() == null
                        || uri.getUserInfo() != null) {
                    return null;
                }
                return uri.toString();
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private String text(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
