package com.tripbora.service.travelinfo.structured;

import java.util.regex.Pattern;

/** 서버의 도메인 설정. 요청 JSON에는 포함하지 않으며 검증과 파일 관리가 같은 namespace를 사용한다. */
public record StructuredImagePolicy(String urlPrefix) {
    public static final StructuredImagePolicy TRAVEL_INFO =
            new StructuredImagePolicy("/uploads/travel-info/content/");

    private static final Pattern FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|webp)");

    public StructuredImagePolicy {
        // 업로드 루트 전체나 상대 경로를 허용하지 않는다. 도메인/용도별 하위 폴더만 지정한다.
        if (urlPrefix == null || !urlPrefix.matches("/uploads/(?:[a-z0-9][a-z0-9-]*/){2,}")) {
            throw new IllegalArgumentException("구조화 이미지 전용 namespace가 필요합니다.");
        }
    }

    public boolean allows(String url) {
        return url != null && url.startsWith(urlPrefix)
                && FILE_NAME.matcher(url.substring(urlPrefix.length())).matches();
    }

    public String fileName(String url) {
        if (!allows(url)) {
            throw new IllegalArgumentException("관리 대상이 아닌 본문 이미지 경로입니다.");
        }
        return url.substring(urlPrefix.length());
    }

    /** FileUploadService가 업로드 루트 아래에서만 해석하는 상대 디렉터리. */
    public String directory() {
        return urlPrefix.substring("/uploads/".length(), urlPrefix.length() - 1);
    }
}
