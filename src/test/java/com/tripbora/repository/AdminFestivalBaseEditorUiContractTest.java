package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축제·행사 폼의 한국어 원본 편집기 규약.
 *
 * <p>편집기 초기화는 등록·수정 양쪽에서 돌아야 하고, TourAPI 자동입력은 등록 화면에서만 돈다.
 * 예전에는 둘이 같은 스크립트에 묶여 있어 수정 화면에 편집기가 뜨지 않았다.
 */
class AdminFestivalBaseEditorUiContractTest {

    private static final String FORM = "/templates/admin/festivals/form.html";
    private static final String BASE_EDITOR_SCRIPT = "/static/js/admin-festival-form.js";
    private static final String AUTOFILL_SCRIPT = "/static/js/admin-festival-autofill.js";

    @Test
    void theBaseEditorScriptLoadsOnBothCreateAndEditScreens() throws IOException {
        String form = resource(FORM);

        // 조건 없이 실린다 = 등록·수정 모두에서 편집기가 뜬다
        assertThat(form).contains("<script src=\"/js/admin-festival-form.js\" defer></script>");
        // TourAPI 자동입력만 등록 화면 전용으로 남는다
        assertThat(form)
                .contains("th:if=\"${!editMode}\" th:src=\"@{/js/admin-festival-autofill.js}\"")
                .doesNotContain("th:if=\"${!editMode}\" src=\"/js/admin-festival-form.js\"");
        // 편집기 초기화 스크립트가 자동입력보다 먼저 실린다
        assertThat(form.indexOf("/js/admin-festival-form.js"))
                .isLessThan(form.indexOf("/js/admin-festival-autofill.js"));
    }

    @Test
    void theBaseEditorBindsToWhicheverFestivalFormItSitsIn() throws IOException {
        String script = resource(BASE_EDITOR_SCRIPT);

        assertThat(script)
                .contains("getElementById('festival-editor')")
                .contains("editorElement?.closest('form')")
                .contains("form.id")
                .contains("'festival-content'")
                // 수정 화면의 기존 본문이 편집기로 올라온다
                .contains("'festival-initial-content'")
                // 등록 화면 폼 id 를 박아 두지 않는다 (수정 화면에서 못 찾는 원인이었다)
                .doesNotContain("'festival-create-form'")
                .doesNotContain("'festival-edit-form'");
    }

    @Test
    void theAutofillScriptNoLongerOwnsTheEditorInitialisation() throws IOException {
        String autofill = resource(AUTOFILL_SCRIPT);

        assertThat(autofill)
                .doesNotContain("window.initQuillEditor(")
                // 자동입력은 이미 만들어진 편집기 인스턴스를 빌려 쓴다
                .contains("editorElement.quillEditorInstance")
                .contains("document.getElementById('festival-editor')")
                // 등록 화면 전용 패널이 없으면 그대로 빠진다
                .contains("[data-admin-festival-autofill]");
    }

    @Test
    void bothScreensKeepTheSameBaseContentInputsForSubmitSync() throws IOException {
        String form = resource(FORM);

        assertThat(form)
                .contains("<div id=\"festival-editor\"")
                .contains("id=\"festival-content\" th:field=\"*{content}\"")
                .contains("id=\"festival-initial-content\" hidden th:text=\"*{content}\"")
                // 폼 id 는 화면에 따라 갈린다
                .contains("th:id=\"${editMode} ? 'festival-edit-form' : 'festival-create-form'\"");
    }

    @Test
    void perEditorSubmitBindingLetsTheBaseAndFourTranslationEditorsCoexist()
            throws IOException {
        String initializer = resource("/static/js/quill-editor-init.js");
        String form = resource(FORM);

        assertThat(initializer)
                .contains("editorElement.dataset.quillEditorSubmitBound")
                .doesNotContain("form.dataset.quillEditorSubmitBound");
        // 번역 편집기는 별도 공통 스크립트가 그대로 맡는다
        assertThat(form)
                .contains("<script src=\"/js/admin-translation-editors.js\" defer></script>")
                .contains(":: travelInfoTranslations");
        assertThat(resource("/static/js/admin-translation-editors.js"))
                .contains("[data-translation-editor]")
                .contains("{required: false}")
                // 한국어 원본 편집기는 건드리지 않는다
                .doesNotContain("festival-editor")
                .doesNotContain("festival-content");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
