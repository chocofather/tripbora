package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축제·행사 외국어 TourAPI 자동입력 UI 규약.
 *
 * <p>여행지 화면과 같은 흐름(match → detail → 빈 칸만 채움)을 쓰고,
 * 국문 contentId 는 좌표 복구에만 쓰며 외국어 상세 조회에 넘기지 않는다.
 */
class AdminFestivalForeignAutofillUiContractTest {

    private static final String SCRIPT = "/static/js/admin-festival-foreign-autofill.js";
    private static final String FESTIVAL_FORM = "/templates/admin/festivals/form.html";
    private static final String TRAVEL_INFO_FORM = "/templates/admin/travel-info/form.html";

    @Test
    void theFestivalFormShowsOnlyAStatusLineAndCarriesTheReadOnlyKtoContentId()
            throws IOException {
        String form = resource(FESTIVAL_FORM);

        assertThat(form)
                .contains("<script src=\"/js/admin-festival-foreign-autofill.js\" defer></script>")
                .contains("data-festival-foreign-autofill")
                .contains("data-festival-foreign-status")
                // 따로 누를 버튼은 두지 않는다 — 국문 축제를 고르면 바로 이어서 돈다
                .doesNotContain("data-festival-foreign-autofill-button")
                .doesNotContain("외국어 정보 자동입력</button>")
                // 수정 화면에서만 국문 KTO 식별자를 읽기 전용으로 실어 준다
                .contains("data-festival-kto-content-id=${editMode}")
                .contains("${festivalKtoContentId}")
                // 사용자가 고치는 입력값으로 만들지 않는다
                .doesNotContain("th:field=\"*{externalContentId}\"")
                .doesNotContain("name=\"externalContentId\"");
    }

    @Test
    void theKoreanSelectionStartsTheForeignAutofillWithoutAnyButton() throws IOException {
        String foreign = resource(SCRIPT);
        String korean = resource("/static/js/admin-festival-autofill.js");

        // 국문 자동입력이 끝나면 신호를 보내고, 외국어 자동입력이 그 신호로 이어 돈다.
        assertThat(korean)
                .contains("new CustomEvent('festival:korean-autofill-applied'")
                .contains("const contentId = ktoFestivalContentId.value.trim();")
                .contains("if (!contentId) return;");
        assertThat(foreign)
                .contains("document.addEventListener('festival:korean-autofill-applied'")
                .contains("fillForeignTranslations(contentId)")
                // 버튼 클릭 진입점은 없다
                .doesNotContain("data-festival-foreign-autofill-button")
                .doesNotContain("addEventListener('click'");
    }

    @Test
    void theEditScreenNeverRefetchesForeignDataOnPageLoad() throws IOException {
        String script = resource(SCRIPT);

        // 신호가 오는 경우에만 부른다. 화면이 열렸다는 이유로 부르지 않는다.
        assertThat(script)
                .doesNotContain("fillForeignTranslations()")
                .doesNotContain("window.addEventListener('load'");
        // 수정 화면에는 국문 자동입력 패널이 없어 신호 자체가 오지 않는다.
        assertThat(resource(FESTIVAL_FORM))
                .contains("th:if=\"${!editMode}\" th:src=\"@{/js/admin-festival-autofill.js}\"");
    }

    @Test
    void theGeneralTravelInfoFormNeverGetsTheFestivalAutofill() throws IOException {
        assertThat(resource(TRAVEL_INFO_FORM))
                .doesNotContain("admin-festival-foreign-autofill.js")
                .doesNotContain("data-festival-foreign-autofill")
                .doesNotContain("festivalKtoContentId");
    }

    @Test
    void everyLanguageIsMatchedWithFestivalTrueAndTheKoreanContentId() throws IOException {
        String script = resource(SCRIPT);

        assertThat(script)
                .contains("{code: 'en', label: '영어'}")
                .contains("{code: 'ja', label: '일본어'}")
                .contains("{code: 'zh-CN', label: '간체'}")
                .contains("{code: 'zh-TW', label: '번체'}")
                .contains("/admin/api/kto/tour/foreign-match?")
                .contains("festival: 'true'")
                .contains("koreanContentId: koreanContentId");
    }

    @Test
    void theKoreanContentIdIsOnlyUsedForMatchingAndNeverForTheForeignDetail()
            throws IOException {
        String script = resource(SCRIPT);
        String detailBlock = between(script,
                "const detailParams = new URLSearchParams({", "});");

        assertThat(detailBlock)
                // 외국어 상세는 매칭 결과가 알려 준 외국어 contentId 로만 부른다
                .contains("contentId: matched.matched.contentId")
                .doesNotContain("koreanContentId");
        assertThat(script)
                .contains("/admin/api/kto/tour/foreign-detail?")
                .contains("matched.matched.contentTypeId");
    }

    @Test
    void theKoreanContentIdComesFromTheKoreanAutofillSignal() throws IOException {
        String korean = resource("/static/js/admin-festival-autofill.js");

        // 신규 등록에서 국문 자동입력이 채운 hidden 값이 그대로 신호에 실린다.
        assertThat(korean).contains("ktoFestivalContentId.value = hasText(detail.contentId)");
        assertThat(resource(SCRIPT)).contains("const contentId = event.detail?.contentId;");
    }

    @Test
    void manuallyRegisteredFestivalsNeverTriggerAnyForeignRequest() throws IOException {
        String script = resource(SCRIPT);

        assertThat(script)
                // 국문 TourAPI 축제가 아니면 신호가 없고, 신호가 와도 값이 없으면 빠진다.
                .contains("if (!koreanTitle || !koreanContentId)")
                .contains("if (!contentId) return;");
    }

    @Test
    void allEightTranslationFieldsAreMappedFromTheForeignDetail() throws IOException {
        String script = resource(SCRIPT);

        // 제목·본문
        assertThat(script)
                .contains("findByLanguage('data-translation-title', language.code), detail.title")
                .contains("fillEditorIfEmpty(language.code, detail.overview)");
        // 행사 상세정보 여섯 칸
        assertThat(script)
                .contains("{field: 'eventPlace', marker: 'data-translation-event-place'}")
                .contains("{field: 'address', marker: 'data-translation-address'}")
                .contains("{field: 'playTime', marker: 'data-translation-play-time'}")
                .contains("{field: 'useTime', marker: 'data-translation-use-time'}")
                .contains("{field: 'sponsor1', marker: 'data-translation-sponsor1'}")
                .contains("{field: 'sponsor2', marker: 'data-translation-sponsor2'}");
        // 이번 단계에서 다루지 않는 값
        assertThat(script)
                .doesNotContain("sponsor1Tel", "sponsor2Tel", "contactTel", "homepageUrl")
                .doesNotContain("startDate", "endDate");
    }

    @Test
    void inputsAreOnlyFilledWhenTheyAreEmptyAndTheApiValueIsNot() throws IOException {
        String fill = between(resource(SCRIPT), "function fillIfEmpty(element, value)", "}\n\n");

        assertThat(fill)
                // 이미 값이 있으면 그대로 둔다
                .contains("if (!element || element.value.trim()) return false;")
                // API 값이 비어 있으면 아무 것도 넣지 않는다
                .contains("if (!hasText(value)) return false;");
    }

    @Test
    void theEditorIsFilledThroughQuillSoTheScreenAndTheSubmittedValueAgree()
            throws IOException {
        String script = resource(SCRIPT);
        String fillEditor = between(script,
                "function fillEditorIfEmpty(languageCode, value)", "\n    /**");

        assertThat(fillEditor)
                // 기존 편집기 인스턴스를 그대로 쓴다
                .contains("editorElement?.quillEditorInstance")
                // 글도 이미지도 없을 때만 채운다
                .contains("!quill.getText().trim() && !quill.root.querySelector('img[src]')")
                .contains("if (!editorIsEmpty) return false;")
                // 화면(Quill)과 제출용 hidden input 을 함께 맞춘다
                .contains("quill.setContents(delta, 'silent')")
                .contains("findByLanguage('data-translation-content', languageCode)")
                .contains("quill.getSemanticHTML()");
    }

    @Test
    void oneLanguageFailingNeverCancelsTheOthers() throws IOException {
        String script = resource(SCRIPT);

        assertThat(script)
                // 한 요청이 reject 돼도 나머지 언어가 취소되지 않는다
                .contains("Promise.allSettled")
                .doesNotContain("Promise.all(")
                .contains("result.status === 'fulfilled' ? result.value : 'error'")
                .contains("일치하는 축제·행사가 없습니다.")
                .contains("불러오지 못했습니다.");
    }

    @Test
    void theKoreanAutofillAndTheKoreanBaseFieldsAreLeftAlone() throws IOException {
        String script = resource(SCRIPT);

        assertThat(script)
                // 국문 제목은 검색어로만 읽고 고치지 않는다
                .contains("titleInput.value.trim()")
                // 국문 본문·기간·이미지·썸네일에는 손대지 않는다
                // (kto-festival-content-id 는 국문 식별자를 읽기만 하는 값이라 예외다)
                .doesNotContain("getElementById('festival-content')")
                .doesNotContain("festival-editor")
                .doesNotContain("festival-start-date", "festival-end-date")
                .doesNotContain("thumbnailImageId", "ktoThumbnailImageSelection")
                .doesNotContain("festival-event-place", "festival-address");
        // 국문 자동입력 스크립트는 그대로다
        assertThat(resource("/static/js/admin-festival-autofill.js"))
                .doesNotContain("foreign-match", "foreign-detail", "data-translation-");
    }

    @Test
    void nothingIsSavedOrSentBackToTheServerByTheAutofill() throws IOException {
        String script = resource(SCRIPT);

        assertThat(script)
                // 읽기만 한다. 저장은 관리자가 저장 버튼을 눌렀을 때 폼으로 이뤄진다.
                .doesNotContain("method: 'POST'", "method: \"POST\"")
                .doesNotContain("form.submit()")
                .doesNotContain("localStorage", "sessionStorage");
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as(start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as(end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
