package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CourseQuillUiContractTest {

    @Test
    void dayFormsRestoreNestedValuesAndKeepSelectionLimits() throws IOException {
        for (String name : new String[]{"write", "edit"}) {
            String template = resource("/templates/course/" + name + ".html");
            assertThat(template)
                    .contains("id=\"course-day-tabs\"", "id=\"course-add-day\"")
                    .contains("id=\"course-search-panel\"", "class=\"course-search-inline\" hidden")
                    .doesNotContain("<dialog", "role=\"dialog\"", "aria-modal", "course-item-dialog")
                    .contains("class=\"button-group course-save-bar\"")
                    .doesNotContain("id=\"destination-target-day\"")
                    .contains("th:each=\"day : ${course.days}\"")
                    .contains("th:each=\"stop : ${day.items}\"")
                    .contains("data-memo=${stop.memo}")
                    .contains("data-item-id=${stop.itemId}", "data-address=${stop.address}")
                    .contains("data-latitude=${stop.latitude}", "data-longitude=${stop.longitude}")
                    .contains("/css/course-location.css", "/js/map-init.js", "/js/course-location.js")
                    .contains("${courseCountries}", "data-course-country", "data-country-code=${country.code}")
                    .doesNotContain("course-country-picker", "data-country-restriction", "name=\"countryId\"", "overseas-country")
                    .contains("th:text=\"${formError}\"");
        }
        assertThat(resource("/static/js/course-write.js"))
                .contains("title.maxLength = 100", "input.maxLength = 1000", "input.type = 'time'")
                .contains("days.length >= 90", "items.length >= 50")
                .contains("name.textContent = destination.name")
                .doesNotContain(".innerHTML")
                .contains("if (item.id === null)", "`${itemPrefix}.placeName`")
                .contains("TripBora 여행지", "직접 추가", "TRANSPORT: '교통'", "ETC: '기타'")
                .contains("clientId: ++nextClientId", "window.CourseLocation?.attach");
    }

    @Test
    void countriesBelongToDirectItemsAndSearchIsUnrestricted() throws IOException {
        assertThat(resource("/static/js/course-write.js"))
                .contains("countrySelect.dataset.directField = 'countryId'")
                .contains("['countryId', 'itemType', 'address', 'latitude', 'longitude']")
                .contains("일정의 국가를 선택해 주세요.")
                .doesNotContain("requestCountryChange", "selectedCourseCountryId", "useCountrySelection");
    }

    @Test
    void writeAndEditUseSharedQuillAssetsAndKeepTheHtmlContentField() throws IOException {
        String write = resource("/templates/course/write.html");
        String edit = resource("/templates/course/edit.html");

        assertCourseEditorTemplate(write);
        assertCourseEditorTemplate(edit);

        assertThat(write)
                .contains("data-initial-content-id=\"initial-content\"")
                .contains("th:if=\"${course != null}\" th:text=\"${course.content}\"");
        assertThat(edit)
                .contains("data-initial-content-id=\"initial-content\"")
                .contains("id=\"initial-content\" hidden th:text=\"${course.content}\"");
    }

    @Test
    void courseJavascriptDelegatesHtmlHandlingToQuillAndKeepsDestinationBehavior() throws IOException {
        String courseJavascript = resource("/static/js/course-write.js");
        String quillInitializer = resource("/static/js/quill-editor-init.js");

        assertThat(courseJavascript)
                .contains("window.initQuillEditor(")
                .contains("form.dataset.initialContentId || undefined")
                .contains("async function searchDestinations()")
                .contains("function addDestination(destination)")
                .contains("function moveDestination(dayIndex, index, offset)")
                .contains("function removeDestination(dayIndex, index)")
                .contains("`days[${dayIndex}]`")
                .contains("`${prefix}.items[${itemIndex}]`")
                .contains("days.some(day => day.items.length === 0)")
                .contains("각 Day에 여행지를 한 곳 이상 추가해 주세요.")
                .contains("이미 선택한 여행지입니다.", "isDestinationSelected(destination)")
                .contains("selected ? '추가됨' : '추가'", "course-item-time-range", "course-day-tab")
                .contains("course-item-inline-editor", "aria-expanded", "aria-controls")
                .doesNotContain("showModal", "itemDialog", "searchDialog", "aria-haspopup", "overflow = 'hidden'")
                .contains("setAttribute('role', 'tabpanel')", "setAttribute('aria-selected'")
                .doesNotContain("day.collapsed", "course-day-toggle", "targetDaySelect")
                .doesNotContain("hidden.name = 'destinationIds'")
                .contains("if (event.defaultPrevented) return")
                .contains("submitButton.disabled = true")
                .doesNotContain(
                        "initToastEditor",
                        "getMarkdown()",
                        "getHTML()",
                        "contentInput.value"
                );

        assertThat(quillInitializer)
                .contains("quill.clipboard.convert({")
                .contains("quill.setContents(delta, 'silent')")
                .contains("const hasImage = Array.from(quill.root.querySelectorAll('img[src]'))")
                .contains("contentInput.value = quill.getSemanticHTML()")
                .contains("fetch('/api/upload/editor-image'")
                .contains("insertEmbed(range.index, 'image', data.url, 'user')")
                .contains("attribute: ['width']")
                .contains("modules: ['Resize']")
                .doesNotContain("Quill.register('modules/resize', window.QuillResize");
    }

    @Test
    void courseEditorUsesSharedResponsiveSizingAndDetailUsesRichTextStyles() throws IOException {
        String css = resource("/static/css/course-write.css");
        String detailCss = resource("/static/css/course-detail.css");
        String detail = resource("/templates/course/detail.html");

        assertThat(css)
                .contains("max-width: 1200px")
                .contains(".course-write-wrapper .write-form")
                .contains("max-width: 1120px")
                .contains(".course-editor-shell")
                .contains("--quill-editor-min-height: 180px", "--quill-editor-min-height: 160px")
                .contains(".course-write-wrapper .course-editor-shell.quill-editor-shell")
                .contains("box-sizing: border-box")
                .contains("max-height: 360px", "overflow-y: auto", "minmax(0, 1fr)")
                .contains(".course-day-tabs", "overflow-x: auto")
                .contains(".course-write-wrapper .course-day-menu .order-control")
                .contains(".course-item-inline-editor[hidden]", ".course-save-bar", "env(safe-area-inset-bottom)")
                .doesNotContain("::backdrop", ".course-dialog", "position: fixed")
                .contains(".course-item-time-range")
                .doesNotContain(".editor-group #editor");

        assertThat(detail)
                .contains("/css/quill-content.css")
                .contains("class=\"course-introduction-content rich-text-content\"")
                .contains("th:utext=\"${course.content}\"")
                .doesNotContain("toastui-editor-contents", "uicdn.toast.com", "/js/editor-init.js");

        assertThat(detailCss)
                .contains(".course-introduction > h2")
                .doesNotContain(".course-introduction h2");
    }

    private void assertCourseEditorTemplate(String template) {
        assertThat(template)
                .contains("quill@2.0.3/dist/quill.snow.css")
                .contains("quill@2.0.3/dist/quill.js")
                .contains("quill-resize-module@2.1.3/dist/resize.css")
                .contains("quill-resize-module@2.1.3/dist/resize.js")
                .contains("/css/quill-content.css")
                .contains("/js/quill-editor-init.js")
                .contains("/js/course-write.js")
                .contains("class=\"course-editor-shell quill-editor-shell\"")
                .contains("class=\"course-editor quill-editor\"")
                .contains("type=\"hidden\" name=\"content\" id=\"content-input\"")
                .doesNotContain("toastui", "uicdn.toast.com", "/js/editor-init.js", "latest");

        assertThat(template.indexOf("quill@2.0.3/dist/quill.js"))
                .isLessThan(template.indexOf("quill-resize-module@2.1.3/dist/resize.js"));
        assertThat(template.indexOf("quill-resize-module@2.1.3/dist/resize.js"))
                .isLessThan(template.indexOf("/js/quill-editor-init.js"));
        assertThat(template.indexOf("/js/quill-editor-init.js"))
                .isLessThan(template.indexOf("/js/course-write.js"));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
