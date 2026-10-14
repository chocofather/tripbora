package com.tripbora.service.travelinfo.structured;

import java.util.List;

/**
 * 구조화 콘텐츠 이미지 한 장. 이미지는 언어와 상관없이 함께 쓴다. (alt·캡션 같은 글은 블록 쪽에 둔다)
 *
 * <p>width / height 는 업로드 때 서버가 읽어 둔 실제 크기다. 공개 화면이 자리를 미리 잡아
 * 화면이 밀리지 않게 하는 데 쓴다.
 *
 * <p>credit 은 출처표시가 필요한 이미지에만 있는 선택 값이다. 없으면 JSON 에 쓰지 않으므로
 * credit 이 생기기 전에 저장된 JSON 도 그대로 읽고 같은 모양으로 다시 쓴다.
 *
 * @param url 도메인의 {@link StructuredImagePolicy} 아래 서버가 만든 파일 경로만 받는다
 */
public record StructuredImage(String url, Integer width, Integer height, StructuredImageCredit credit) {

    /** 출처표시가 없는 이미지. */
    public StructuredImage(String url, Integer width, Integer height) {
        this(url, width, height, null);
    }

    /**
     * 공개 화면에 그릴 출처표시 조각. 출처표시가 없으면 빈 목록이라 화면은 출처 줄을 만들지 않는다.
     * (Jackson 속성 이름 규칙에 맞지 않아 JSON 에는 쓰이지 않는다)
     */
    public List<StructuredImageCredit.Part> creditParts() {
        return credit == null ? List.of() : credit.displayParts();
    }

    /** 기존 여행정보 호출부를 위한 호환 이름. 공용 검증은 도메인별 policy를 받는다. */
    public static final String URL_PREFIX = StructuredImagePolicy.TRAVEL_INFO.urlPrefix();
}
