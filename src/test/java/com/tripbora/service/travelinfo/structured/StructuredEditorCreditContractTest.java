package com.tripbora.service.travelinfo.structured;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 블록 에디터(admin-structured-editor.js)의 이미지 출처표시(credit).
 * 에디터는 저장된 credit 을 읽어 다시 쓰고, 비어 있으면 쓰지 않으며, 새 파일을 올리면 지운다.
 * 테스트에서 JS 를 실행하지 않으므로 에디터 쪽은 코드 계약으로, 에디터가 쓰는 JSON 모양은 서버로 확인한다.
 */
class StructuredEditorCreditContractTest {

    private static final String URL =
            "/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.webp";

    private final StructuredContentParser parser =
            new StructuredContentParser(new StructuredContentValidator());
    private final StructuredContentSerializer serializer = new StructuredContentSerializer();

    @Test
    void editorCreditFieldsMatchTheServerRecord() throws IOException {
        String names = Arrays.stream(StructuredImageCredit.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(", "));

        assertThat(names).isEqualTo("'author', 'source', 'sourceUrl', 'license', 'licenseUrl'");
        assertThat(editor()).contains("const CREDIT_FIELDS = Object.freeze([" + names + "]);");
    }

    @Test
    void editorReadsAndWritesCreditForEveryImageSoAnUntouchedSaveKeepsIt() throws IOException {
        String editor = editor();

        // 읽기: 저장된 credit 을 이미지와 함께 읽는다. 블록·사진 칸은 모두 readImage 를 거친다.
        assertThat(editor)
                .contains("return {url: value.url, width, height, credit: readCredit(value.credit)};")
                .contains("image: readImage(raw.image), alt: text(raw.alt), caption: text(raw.caption)")
                .contains("image: readImage(raw.image), alt: text(raw.alt), title: text(raw.title), text: text(raw.text)")
                .contains("return {id: item.id, image: readImage(item.image), alt: text(item.alt),");
        // 쓰기: 큰 이미지·이미지 + 글(putImage)과 슬라이더·이미지 배치 사진(writeImageItem)이 같은 writeImage 를 쓴다.
        assertThat(editor)
                .contains("if (image) out.image = writeImage(image);")
                .contains("if (item.image) written.image = writeImage(item.image);")
                .contains("const written = {url: image.url, width: image.width, height: image.height};")
                .contains("if (credit) written.credit = credit;");
        // url / width / height 만 따로 적어 credit 을 빠뜨리는 예전 쓰기가 남아 있지 않다.
        assertThat(editor)
                .doesNotContain("{url: item.image.url, width: item.image.width, height: item.image.height}")
                .doesNotContain("out.image = {url: image.url");
        assertThat(count(editor, "width: image.width, height: image.height")).isEqualTo(1);
    }

    @Test
    void editorOmitsEmptyCreditAndClearsItWhenAFileIsUploaded() throws IOException {
        String editor = editor();

        // 켜 두고 모두 비우면 credit({}) 대신 아예 쓰지 않는다. URL 칸은 앞뒤 공백을 뗀다.
        assertThat(editor)
                .contains("return Object.keys(written).length ? written : null;")
                .contains("return CREDIT_URL_FIELDS.includes(name) ? value.trim() : value;");
        // 새 파일(교체·슬라이더 추가)은 업로드 한 곳에서 credit 없이 시작한다. 이미지를 빼면 credit 도 함께 없어진다.
        assertThat(editor)
                .contains("return {...image, credit: null};")
                .contains("assign(await uploadImage(file));")
                .contains("items[index].image = await uploadImage(accepted[index]);");
        // [+ 출처 정보 추가]는 빈 credit 을 만들어 펼치고, [출처 정보 제거]는 credit 을 지운다. 값이 있으면 먼저 묻는다.
        assertThat(editor)
                .contains("image.credit = emptyCredit();\n                            state.openCredits.add(imageKey);")
                .contains("image.credit = null;\n                                state.openCredits.delete(imageKey);")
                .contains("if (writeCredit(image.credit)\n"
                        + "                                    && !window.confirm('이 이미지의 출처 정보를 지울까요? 입력한 내용이 사라집니다.')) {");
    }

    @Test
    void editorImageCardSeparatesBasicInfoAndACompactCreditSection() throws IOException {
        String editor = editor();
        String css = resource("/static/css/admin-structured-editor.css");

        // 모든 이미지 자리가 같은 카드 하나를 쓴다: [이미지] [기본 정보: 블록의 글 칸 + 보조 alt] [출처 정보].
        // 큰 이미지·이미지 + 글은 블록에서, 슬라이더·이미지 배치는 사진 칸(renderImageItem)에서 부른다.
        assertThat(count(editor, "imageCard(block, {")).isEqualTo(3);
        assertThat(count(editor, "=> renderImageItem(block, item, index, errorId")).isEqualTo(2);
        assertThat(count(editor, "imageControl(")).isEqualTo(2); // 정의 + 카드 안 한 곳
        assertThat(count(editor, "creditControl(")).isEqualTo(2); // 정의 + 카드 안 한 곳
        assertThat(editor)
                .contains("el('div', {className: 'admin-structured-image-basics', role: 'group',\n"
                        + "                    'aria-label': `${options.name} 기본 정보`}, [")
                .contains("role: 'group', 'aria-label': '출처 정보'")
                .contains("return el('div', {className: `admin-form-field${options.secondary ? ' is-secondary' : ''}`}, [");
        // alt 는 카드 한 곳에서 한 단계 낮은 보조 입력으로 그린다. (제목 | 설명 | alt 세 칸이 아니다)
        assertThat(count(editor, "label: '접근성 설명(alt)'")).isEqualTo(1);
        assertThat(editor)
                .contains("field(block, {key: options.alt.key, label: '접근성 설명(alt)', max: LIMITS.alt, secondary: true,")
                .contains("help: '이미지를 설명하는 대체 텍스트입니다.'")
                .doesNotContain("label: '이미지 설명(alt)'", "admin-structured-item-fields");
        // 좁은 자리(이미지 배치 칸, 이미지 + 글의 사진 칸)는 같은 카드를 한 칸으로 쌓는다.
        assertThat(editor)
                .contains("name: position, imageKey: key('image'), image: item.image, stacked: options.fixed,")
                .contains("name: '이미지', imageKey: key('image'), image: block.image, stacked: true,")
                .contains("name: '큰 이미지', imageKey: key('image'), image: block.image,");
        // 출처: 이미지가 없으면 자리만(비활성), credit 이 없으면 글자 버튼 하나, 있으면 접힌 토글(요약 한 줄)이고 펼칠 수 있다.
        assertThat(editor)
                .contains("text: '+ 출처 정보 추가'")
                .contains("disabled: true,")
                .contains("text: '이미지를 올린 뒤 추가할 수 있습니다.'")
                .contains("el('span', {text: '출처 정보 있음'})")
                .contains("'aria-expanded': String(open)")
                .contains("const open = state.openCredits.has(imageKey);")
                .contains("open ? null : summary")
                .contains("text: '출처 정보 제거'")
                .doesNotContain("type: 'checkbox'", "외부 사진처럼 저작자·출처를 밝혀야 하는 이미지에만 켭니다.",
                        "if (!image) return null;");
        // 저장 검사에서 걸린 출처 칸이 접혀 있으면 펼친다.
        assertThat(editor)
                .contains("const at = key.indexOf(':credit-');")
                .contains("if (at >= 0) state.openCredits.add(key.slice(0, at));");
        // 배치: 카드 안은 [이미지 | 기본 정보] 두 칸 + 출처 한 줄, 좁은 자리(is-stacked)·좁은 화면은 한 칸.
        assertThat(css)
                .contains(".admin-structured-image-card {\n    display: grid;\n"
                        + "    grid-template-columns: minmax(150px, 200px) minmax(0, 1fr);")
                .contains(".admin-structured-image-card > .admin-structured-credit {\n    grid-column: 1 / -1;")
                .contains(".admin-structured-image-card.is-stacked {\n    grid-template-columns: minmax(0, 1fr);")
                .contains("grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));")
                .contains("    .admin-structured-image-card {\n        grid-template-columns: minmax(0, 1fr);")
                .contains("@media (max-width: 640px)")
                .contains("    .admin-structured-credit-names {\n        grid-template-columns: minmax(0, 1fr);")
                .doesNotContain("admin-structured-item-body", "admin-structured-item-fields");
    }

    @Test
    void editorShowsCreditInputsAndChecksThemForAllFourImageBlocks() throws IOException {
        String editor = editor();

        // 출처 칸은 모든 이미지 카드에 있다. 카드 하나가 자기 이미지(imageKey)의 출처를 그린다.
        assertThat(editor).contains("creditControl(block, options.image, options.imageKey, errorId)");
        assertThat(editor)
                .contains("checkCredit(block, block.image, key('image'), '');")
                .contains("checkCredit(block, item.image, itemKey('image'), `${index + 1}번째 이미지: `);")
                .contains("checkCredit(block, item.image, `${block.id}:${item.id}:image`, `${index + 1}번째 칸: `);");
        assertThat(count(editor, "checkCredit(block, ")).isEqualTo(4);
        // 입력 칸 다섯 개와 한도
        assertThat(editor)
                .contains("creditField('author', '저작자', LIMITS.creditAuthor)")
                .contains("creditField('source', '출처명', LIMITS.creditSource)")
                .contains("creditField('sourceUrl', '출처 URL', LIMITS.creditSourceUrl, {attributes: urlAttributes})")
                .contains("creditField('license', '라이선스', LIMITS.creditLicense, {")
                .contains("creditField('licenseUrl', '라이선스 URL', LIMITS.creditLicenseUrl,");
        // 서버와 같은 필수 규칙·문구
        assertThat(editor)
                .contains("if (!credit.author && !credit.source) {")
                .contains("출처표시에는 저작자 또는 출처명을 입력해 주세요.")
                .contains("출처 URL은 http 또는 https 주소로 입력해 주세요.")
                .contains("라이선스 URL은 http 또는 https 주소로 입력해 주세요.");
        // 미리보기: 빈 칸을 빼고, 저작자와 출처명이 같으면 한 번만 쓰고, 값이 없으면 숨긴다.
        assertThat(editor)
                .contains("[author, source === author ? '' : source, text(credit.license).trim()].filter(Boolean)")
                .contains("return parts.length ? `사진: ${parts.join(' · ')}` : '';")
                .contains("preview.hidden = line === '';");
    }

    @Test
    void editorUrlCheckMatchesTheServerScheme() throws IOException {
        // 에디터: // 가 있는 http / https 주소이고, host 가 있어야 한다. ("https:example.com" 같은 브라우저 관용 주소도 막는다)
        assertThat(editor())
                .contains("if (!/^https?:\\/\\/[^\\s/?#]+/i.test(value) || /[\\s<>\"{}|\\\\^`]/.test(value)) return false;")
                .contains("return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname !== '';");

        // 서버: 에디터가 막는 주소는 서버도 거부한다. (서버가 최종 기준)
        for (String url : List.of("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd",
                "ftp://example.com/a.jpg", "/uploads/a.jpg", "//example.com/a", "https:example.com",
                "https://", "example.com")) {
            assertThatThrownBy(() -> parser.parseContent(document(
                    fullImage(image("{\"source\":\"출처\",\"sourceUrl\":\"" + url + "\"}")))))
                    .isInstanceOf(StructuredContentValidationException.class)
                    .hasMessageContaining("출처 URL은 http 또는 https 주소로 입력해 주세요.");
        }
    }

    @Test
    void licenseSuggestionsAreFreeTextWithKnownUrls() throws IOException {
        String editor = editor();

        for (String license : List.of("공공누리 제1유형", "공공누리 제2유형", "공공누리 제3유형", "공공누리 제4유형",
                "CC BY 4.0", "CC BY-SA 4.0", "CC0")) {
            assertThat(editor).contains("{name: '" + license + "', url: 'https://");
        }
        // 제안값은 datalist 로만 보이고, 저장은 입력한 글 그대로다. URL 은 비었거나 다른 제안값의 URL 일 때만 채운다.
        assertThat(editor)
                .contains("el('datalist', {id: licenseListId}, CREDIT_LICENSES.map(item => el('option', {value: item.name})))")
                .contains("if (!known || (current && !CREDIT_LICENSES.some(item => item.url === current))) return;");
        // 에디터가 쓴 자유 글 라이선스를 서버도 그대로 받는다.
        StructuredContent content = parser.parseContent(document(
                fullImage(image("{\"author\":\"홍길동\",\"license\":\"저작권자 허락(2026-10-05)\"}"))));
        assertThat(((StructuredBlock.FullImage) content.blocks().get(0)).image().credit().license())
                .isEqualTo("저작권자 허락(2026-10-05)");
    }

    /**
     * 에디터 writeBlock 이 쓰는 모양(키 순서 포함) 그대로의 JSON. 네 블록 모두 credit 이 있거나 없고,
     * 저작자만 / 출처명만 있는 credit 도 있다. 서버가 읽고 다시 써도 글자 하나 바뀌지 않는다.
     */
    @Test
    void jsonInTheEditorShapeRoundTripsThroughTheServerUnchanged() {
        String full = "{\"author\":\"John Doe\",\"source\":\"Wikimedia Commons\","
                + "\"sourceUrl\":\"https://commons.wikimedia.org/wiki/File:A.jpg\",\"license\":\"CC BY-SA 4.0\","
                + "\"licenseUrl\":\"https://creativecommons.org/licenses/by-sa/4.0/\"}";
        String json = document(
                "{\"type\":\"FULL_IMAGE\",\"id\":\"b-full\",\"image\":" + image(full) + ",\"alt\":\"경복궁\"}",
                "{\"type\":\"IMAGE_TEXT\",\"id\":\"b-split\",\"imagePosition\":\"LEFT\",\"image\":"
                        + image("{\"author\":\"홍길동\"}") + ",\"text\":\"본문\"}",
                "{\"type\":\"IMAGE_SLIDER\",\"id\":\"b-slider\",\"items\":["
                        + "{\"id\":\"i-1\",\"image\":" + image("{\"source\":\"한국관광공사\",\"license\":\"공공누리 제1유형\"}")
                        + ",\"title\":\"강녕전\"},"
                        + "{\"id\":\"i-2\",\"image\":" + plainImage() + "}]}",
                "{\"type\":\"IMAGE_GRID\",\"id\":\"b-grid\",\"columns\":2,\"items\":["
                        + "{\"id\":\"i-1\",\"image\":" + plainImage() + "},"
                        + "{\"id\":\"i-2\",\"image\":" + image(full) + ",\"caption\":\"광화문\"}]}");

        StructuredContent content = parser.parseContent(json);

        assertThat(serializer.write(content)).isEqualTo(json);
        assertThat(((StructuredBlock.ImageText) content.blocks().get(1)).image().credit())
                .isEqualTo(new StructuredImageCredit("홍길동", null, null, null, null));
        assertThat(((StructuredBlock.ImageSlider) content.blocks().get(2)).items().get(1).image().credit()).isNull();
        // credit 이 없는 예전 글은 credit 키 없이 그대로다.
        String legacy = document("{\"type\":\"FULL_IMAGE\",\"id\":\"b-full\",\"image\":" + plainImage() + "}");
        assertThat(serializer.write(parser.parseContent(legacy))).isEqualTo(legacy).doesNotContain("credit");
    }

    @Test
    void creditStaysOutOfTranslations() throws IOException {
        // 번역 화면·스크립트에는 출처표시 칸이 없다.
        assertThat(resource("/templates/fragments/admin/travel-info-translation-tabs.html")).doesNotContain("credit");
        assertThat(resource("/static/js/admin-travel-info-form.js")).doesNotContain("credit");
        assertThat(resource("/static/js/admin-translation-tabs.js")).doesNotContain("credit");
        assertThat(StructuredText.BlockText.class.getRecordComponents())
                .extracting(RecordComponent::getName).doesNotContain("credit");
        assertThat(StructuredText.ItemText.class.getRecordComponents())
                .extracting(RecordComponent::getName).doesNotContain("credit");
        // 번역 JSON(structured_text)에 credit 을 넣으면 모르는 항목으로 거부한다.
        assertThatThrownBy(() -> parser.parseText("{\"blocks\":{\"a\":{\"credit\":{\"author\":\"A\"}}}}"))
                .hasMessageContaining("허용하지 않는 항목이 있습니다: credit");
        assertThatThrownBy(() -> parser.parseText(
                "{\"blocks\":{\"a\":{\"items\":{\"i1\":{\"credit\":{\"author\":\"A\"}}}}}}"))
                .hasMessageContaining("허용하지 않는 항목이 있습니다: credit");
    }

    @Test
    void adminFormLoadsTheNewEditorVersion() throws IOException {
        // 바꾼 JS·CSS 는 버전을 올려 캐시에 남은 예전 판(credit 을 버리는 readImage)과 섞이지 않게 한다.
        assertThat(resource("/templates/admin/travel-info/form.html"))
                .contains("/js/admin-structured-editor.js?v=20261014-rich-text-2")
                .contains("/css/admin-structured-editor.css?v=20261014-rich-text-2");
    }

    /**
     * 슬라이더 공통 출처 설정. 새 저장 구조 없이 그 슬라이더 사진마다의 credit 에 복사하는 입력 도우미다.
     * 테스트에서 JS 를 실행하지 않으므로 동작은 코드 계약으로, 복사된 결과(사진마다 같은 credit)는 서버로 확인한다.
     */
    @Test
    void sliderCommonCreditCopiesIntoEachImageCreditOfThatSliderOnly() throws IOException {
        String editor = editor();

        // 공통 출처 칸은 이미지 한 장의 출처 칸(creditInputs)을 그대로 쓰고, 입력값은 저장하지 않는다. (블록 id 별 draft)
        assertThat(count(editor, "creditInputs(")).isEqualTo(3); // 정의 + 이미지 출처 + 공통 출처
        assertThat(editor)
                .contains("...creditInputs(block, credit, imageKey, `${panelId}-licenses`, errorId, {onChange: refreshPreview})")
                .contains("...creditInputs(block, credit, prefix, `${panelId}-licenses`, errorId, {\n"
                        + "                        draft: true,")
                .contains("if (!options.draft) markDirty();")
                .contains("commonCredits: new Map(), // 블록 id → credit 입력값");
        assertThat(between(editor, "function serialize() {", "\n        }"))
                .contains("JSON.stringify({version: 1, blocks: state.blocks.map(writeBlock)})")
                .doesNotContain("common");
        // 슬라이더 블록에만 있고, 적용은 그 블록의 items 만 넘긴다. (다른 슬라이더 사진에는 닿지 않는다)
        assertThat(count(editor, "commonCreditControl(block, errorId)")).isEqualTo(2); // 정의 + 슬라이더 한 곳
        assertThat(editor)
                .contains("const applied = applyCommonCredit(block.items, credit, onlyEmpty);")
                .contains("commonCreditOverwrites(block.items, credit)")
                .contains("applyButton('apply-all', '전체 이미지에 적용', false)")
                .contains("applyButton('apply-empty', '출처 없는 이미지에만 적용', true)");
        // 사진마다 따로 복사한다(적용한 뒤 한 장만 고칠 수 있다). 빈 사진만 적용은 이미 출처가 있는 사진을 건너뛴다.
        String apply = between(editor, "function applyCommonCredit(items, credit, onlyEmpty) {", "\n    }");
        assertThat(apply)
                .contains("if (!item.image || (onlyEmpty && writeCredit(item.image.credit))) return;")
                .contains("item.image.credit = readCredit(written);");
        // 전체 적용이 다른 개별 출처를 덮어쓰면 먼저 묻는다. 적용 전 검사는 저장 검사와 같은 문구다.
        assertThat(editor)
                .contains("기존에 입력된 개별 출처 정보(이미지 ${overwrites}장)도 덮어씁니다. 적용할까요?")
                .contains("return current && JSON.stringify(current) !== common;");
        String problem = between(editor, "function commonCreditProblem(credit) {", "\n    }");
        assertThat(problem)
                .contains("출처표시에는 저작자 또는 출처명을 입력해 주세요.")
                .contains("출처 URL은 http 또는 https 주소로 입력해 주세요.")
                .contains("라이선스 URL은 http 또는 https 주소로 입력해 주세요.");

        // 결과 JSON: 사진마다 같은 credit 이 따로 들어간 40장 슬라이더를 서버가 그대로 받는다.
        String credit = "{\"source\":\"한국관광공사\",\"license\":\"공공누리 제1유형\"}";
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < StructuredContentValidator.MAX_SLIDER_ITEMS; index++) {
            items.append(index == 0 ? "" : ",")
                    .append("{\"id\":\"i-").append(index).append("\",\"image\":").append(image(credit)).append('}');
        }
        StructuredContent content = parser.parseContent(document(
                "{\"type\":\"IMAGE_SLIDER\",\"id\":\"b-slider\",\"items\":[" + items + "]}"));
        StructuredBlock.ImageSlider slider = (StructuredBlock.ImageSlider) content.blocks().get(0);
        assertThat(slider.items()).hasSize(40)
                .allSatisfy(item -> assertThat(item.image().credit())
                        .isEqualTo(new StructuredImageCredit(null, "한국관광공사", null, "공공누리 제1유형", null)));
    }

    private static String document(String... blocks) {
        return "{\"version\":1,\"blocks\":[" + String.join(",", blocks) + "]}";
    }

    private static String fullImage(String image) {
        return "{\"type\":\"FULL_IMAGE\",\"id\":\"a\",\"image\":" + image + "}";
    }

    private static String image(String credit) {
        return "{\"url\":\"" + URL + "\",\"width\":1600,\"height\":1067,\"credit\":" + credit + "}";
    }

    private static String plainImage() {
        return "{\"url\":\"" + URL + "\",\"width\":1600,\"height\":1067}";
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) {
            count++;
        }
        return count;
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertThat(from).as(start).isNotNegative();
        int to = text.indexOf(end, from + start.length());
        assertThat(to).as(end).isPositive();
        return text.substring(from, to);
    }

    private String editor() throws IOException {
        return resource("/static/js/admin-structured-editor.js");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
