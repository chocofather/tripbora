package com.tripbora.service.travelinfo.structured;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredContentParserTest {

    private static final String URL =
            "/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.webp";
    private static final String IMAGE = image(URL, "2000", "1333");

    private final StructuredContentParser parser =
            new StructuredContentParser(new StructuredContentValidator());

    // ---- 정상 파싱 -------------------------------------------------------------------------

    @Test
    void parsesAllSixBlockTypesInOrder() {
        StructuredContent content = parser.parseContent(document(
                sectionTitle("intro", "서울 궁 투어"),
                richText("text-1", "첫 문단\n\n둘째 문단"),
                fullImage("gate", IMAGE, "광화문 전경", "경복궁의 정문"),
                imageText("palace", "LEFT", IMAGE, "근정전", "근정전", "조선의 법궁"),
                slider("gyeongbok", "경복궁 주요 전각",
                        item("i1", IMAGE, "광화문", "광화문", "정문"),
                        item("i2", IMAGE, "흥례문", null, null)),
                callout("tip", "월요일은 휴궁입니다.")));

        assertThat(content.version()).isEqualTo(1);
        assertThat(content.blocks()).extracting(StructuredBlock::type).containsExactly(
                StructuredBlockType.SECTION_TITLE, StructuredBlockType.RICH_TEXT,
                StructuredBlockType.FULL_IMAGE, StructuredBlockType.IMAGE_TEXT,
                StructuredBlockType.IMAGE_SLIDER, StructuredBlockType.CALLOUT);
        assertThat(content.blocks()).extracting(StructuredBlock::id)
                .containsExactly("intro", "text-1", "gate", "palace", "gyeongbok", "tip");

        assertThat(content.blocks().get(0)).isEqualTo(
                new StructuredBlock.SectionTitle("intro", "서울 궁 투어"));
        assertThat(content.blocks().get(1)).isEqualTo(new StructuredBlock.RichText(
                "text-1", StructuredBlock.RichTextLayout.DEFAULT, "첫 문단\n\n둘째 문단"));
        StructuredImage image = new StructuredImage(URL, 2000, 1333);
        assertThat(content.blocks().get(2)).isEqualTo(
                new StructuredBlock.FullImage("gate", image, "광화문 전경", "경복궁의 정문"));
        assertThat(content.blocks().get(3)).isEqualTo(new StructuredBlock.ImageText(
                "palace", StructuredBlock.ImagePosition.LEFT, image, "근정전", "근정전", "조선의 법궁"));
        StructuredBlock.ImageSlider slider = (StructuredBlock.ImageSlider) content.blocks().get(4);
        assertThat(slider.title()).isEqualTo("경복궁 주요 전각");
        assertThat(slider.items()).containsExactly(
                new StructuredBlock.SliderItem("i1", image, "광화문", "광화문", "정문"),
                new StructuredBlock.SliderItem("i2", image, "흥례문", null, null));
        assertThat(content.blocks().get(5)).isEqualTo(
                new StructuredBlock.Callout("tip", "월요일은 휴궁입니다."));
    }

    @Test
    void legacySectionTitleLeadIsStillReadButDroppedFromTheModelAndCanonicalJson() {
        // 짧은 소개(lead)가 있던 시절에 저장된 글. 줄바꿈이 있거나 예전 한도(500자)를 넘어도 읽기 오류가 나지 않는다.
        String legacy = document(
                "{\"type\":\"SECTION_TITLE\",\"id\":\"intro\",\"title\":\"경복궁\",\"lead\":\"조선의 법궁\\n궁궐 여행의 시작\"}",
                "{\"type\":\"SECTION_TITLE\",\"id\":\"long\",\"title\":\"창덕궁\",\"lead\":\"" + "가".repeat(600) + "\"}",
                "{\"type\":\"SECTION_TITLE\",\"id\":\"empty\",\"title\":\"덕수궁\",\"lead\":null}");

        StructuredContent content = parser.parseContent(legacy);

        assertThat(content.blocks()).containsExactly(
                new StructuredBlock.SectionTitle("intro", "경복궁"),
                new StructuredBlock.SectionTitle("long", "창덕궁"),
                new StructuredBlock.SectionTitle("empty", "덕수궁"));
        assertThat(new StructuredContentSerializer().write(content))
                .isEqualTo("{\"version\":1,\"blocks\":["
                        + "{\"type\":\"SECTION_TITLE\",\"id\":\"intro\",\"title\":\"경복궁\"},"
                        + "{\"type\":\"SECTION_TITLE\",\"id\":\"long\",\"title\":\"창덕궁\"},"
                        + "{\"type\":\"SECTION_TITLE\",\"id\":\"empty\",\"title\":\"덕수궁\"}]}");
        // lead 만 예외다. 섹션 제목의 다른 모르는 키, 다른 블록의 lead 는 여전히 거부한다.
        assertInvalid(document("{\"type\":\"SECTION_TITLE\",\"id\":\"a\",\"title\":\"제목\",\"subtitle\":\"부제\"}"),
                "허용하지 않는 항목이 있습니다: subtitle");
        assertInvalid(document("{\"type\":\"CALLOUT\",\"id\":\"a\",\"text\":\"글\",\"lead\":\"소개\"}"),
                "허용하지 않는 항목이 있습니다: lead");
    }

    @Test
    void richTextLayoutDefaultsWhenMissingAndOnlyAcceptsTheTwoPresets() {
        StructuredContent content = parser.parseContent(document(
                richText("legacy", "layout 이 없던 예전 글"),
                "{\"type\":\"RICH_TEXT\",\"id\":\"default\",\"layout\":\"DEFAULT\",\"text\":\"기본\"}",
                "{\"type\":\"RICH_TEXT\",\"id\":\"focused\",\"layout\":\"FOCUSED\",\"text\":\"집중형\"}"));

        assertThat(content.blocks()).extracting(block -> ((StructuredBlock.RichText) block).layout())
                .containsExactly(StructuredBlock.RichTextLayout.DEFAULT, StructuredBlock.RichTextLayout.DEFAULT,
                        StructuredBlock.RichTextLayout.FOCUSED);
        // 다시 쓰면 기본값도 적어 둔다. 키 순서는 type, id, layout, text.
        assertThat(new StructuredContentSerializer().write(content)).contains(
                "{\"type\":\"RICH_TEXT\",\"id\":\"legacy\",\"layout\":\"DEFAULT\",\"text\":\"layout 이 없던 예전 글\"}",
                "{\"type\":\"RICH_TEXT\",\"id\":\"focused\",\"layout\":\"FOCUSED\",\"text\":\"집중형\"}");

        for (String layout : List.of("\"focused\"", "\"WIDE\"", "1", "\"\"", "{}")) {
            assertInvalid(document("{\"type\":\"RICH_TEXT\",\"id\":\"a\",\"layout\":" + layout + ",\"text\":\"글\"}"),
                    "구조화 콘텐츠 형식이 올바르지 않습니다.");
        }
        // 폭 프리셋은 일반 글에만 있다.
        assertInvalid(document("{\"type\":\"CALLOUT\",\"id\":\"a\",\"layout\":\"FOCUSED\",\"text\":\"글\"}"),
                "허용하지 않는 항목이 있습니다: layout");
    }

    @Test
    void optionalTextsMayBeMissingAndImageTextAcceptsRight() {
        StructuredContent content = parser.parseContent(document(
                "{\"id\":\"a\",\"type\":\"SECTION_TITLE\",\"title\":\"제목\"}",
                "{\"id\":\"b\",\"type\":\"FULL_IMAGE\",\"image\":" + IMAGE + ",\"alt\":\"설명\"}",
                imageText("c", "RIGHT", IMAGE, "설명", null, "본문"),
                "{\"id\":\"d\",\"type\":\"IMAGE_SLIDER\",\"items\":["
                        + item("i1", IMAGE, "설명", null, null) + "]}"));

        assertThat(content.blocks()).hasSize(4);
        assertThat(((StructuredBlock.ImageText) content.blocks().get(2)).imagePosition())
                .isEqualTo(StructuredBlock.ImagePosition.RIGHT);
    }

    @Test
    void sameItemIdMayRepeatInDifferentSliders() {
        StructuredContent content = parser.parseContent(document(
                slider("s1", "경복궁", item("i1", IMAGE, "광화문", null, null)),
                slider("s2", "창덕궁", item("i1", IMAGE, "돈화문", null, null))));

        assertThat(content.blocks()).hasSize(2);
    }

    @Test
    void acceptsMaximumBlocksAndSliderItems() {
        List<String> blocks = new ArrayList<>();
        for (int index = 0; index < StructuredContentValidator.MAX_BLOCKS; index++) {
            blocks.add(callout("b" + index, "문구"));
        }
        assertThat(parser.parseContent(document(blocks.toArray(String[]::new))).blocks())
                .hasSize(StructuredContentValidator.MAX_BLOCKS);

        String[] items = new String[StructuredContentValidator.MAX_SLIDER_ITEMS];
        for (int index = 0; index < items.length; index++) {
            items[index] = item("i" + index, IMAGE, "설명", null, null);
        }
        StructuredBlock.ImageSlider slider = (StructuredBlock.ImageSlider)
                parser.parseContent(document(slider("s", "제목", items))).blocks().get(0);
        assertThat(slider.items()).hasSize(StructuredContentValidator.MAX_SLIDER_ITEMS);
    }

    // ---- 최상위 / 버전 ---------------------------------------------------------------------

    @Test
    void rejectsEmptyOrNullDocument() {
        assertInvalid(null, "구조화 콘텐츠가 비어 있습니다.");
        assertInvalid("  ", "구조화 콘텐츠가 비어 있습니다.");
        assertInvalid("null", "구조화 콘텐츠가 비어 있습니다.");
        assertInvalid("{\"version\":1,\"blocks\":[]}", "블록을 하나 이상 추가해 주세요.");
        assertInvalid("{\"version\":1}", "블록을 하나 이상 추가해 주세요.");
    }

    @Test
    void rejectsUnsupportedOrMissingVersion() {
        String blocks = "\"blocks\":[" + callout("a", "문구") + "]";

        assertInvalid("{\"version\":2," + blocks + "}", "지원하지 않는 구조화 콘텐츠 버전입니다.");
        assertInvalid("{" + blocks + "}", "지원하지 않는 구조화 콘텐츠 버전입니다.");
        assertInvalid("{\"version\":\"1\"," + blocks + "}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid("{\"version\":1.0," + blocks + "}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    @Test
    void rejectsTooManyBlocks() {
        List<String> blocks = new ArrayList<>();
        for (int index = 0; index <= StructuredContentValidator.MAX_BLOCKS; index++) {
            blocks.add(callout("b" + index, "문구"));
        }

        assertInvalid(document(blocks.toArray(String[]::new)), "블록은 60개까지 추가할 수 있습니다.");
    }

    @Test
    void rejectsOversizedJsonBeforeParsing() {
        String huge = document(richText("a", "가".repeat(StructuredContentParser.MAX_JSON_LENGTH)));

        assertInvalid(huge, "구조화 콘텐츠가 너무 큽니다.");
    }

    // ---- strict 읽기 ----------------------------------------------------------------------

    @Test
    void rejectsUnknownOrMissingBlockType() {
        assertInvalid(document("{\"id\":\"a\",\"type\":\"VIDEO\",\"url\":\"x\"}"),
                "알 수 없는 블록 종류입니다. (위치: blocks[0])");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"callout\",\"text\":\"문구\"}"),
                "알 수 없는 블록 종류입니다.");
        assertInvalid(document("{\"id\":\"a\",\"text\":\"문구\"}"), "알 수 없는 블록 종류입니다.");
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevel() {
        assertInvalid("{\"version\":1,\"theme\":\"dark\",\"blocks\":[" + callout("a", "문구") + "]}",
                "허용하지 않는 항목이 있습니다: theme");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"CALLOUT\",\"text\":\"문구\",\"style\":\"x\"}"),
                "허용하지 않는 항목이 있습니다: style (위치: blocks[0].style)");
        // 디자인 값(margin·width 등)은 블록에 들어올 자리가 없다.
        assertInvalid(document("{\"id\":\"a\",\"type\":\"FULL_IMAGE\",\"image\":" + IMAGE
                        + ",\"alt\":\"설명\",\"width\":\"50%\"}"),
                "허용하지 않는 항목이 있습니다: width");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"FULL_IMAGE\",\"alt\":\"설명\",\"image\":"
                        + "{\"url\":\"" + URL + "\",\"width\":10,\"height\":10,\"focus\":\"top\"}}"),
                "허용하지 않는 항목이 있습니다: focus (위치: blocks[0].image.focus)");
        assertInvalid(document(slider("s", "제목",
                        "{\"id\":\"i1\",\"type\":\"IMAGE\",\"image\":" + IMAGE + ",\"alt\":\"설명\"}")),
                "허용하지 않는 항목이 있습니다: type (위치: blocks[0].items[0].type)");
    }

    @Test
    void rejectsDuplicateKeysTrailingContentAndTypeCoercion() {
        assertInvalid("{\"version\":1,\"version\":1,\"blocks\":[" + callout("a", "문구") + "]}",
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid(document(callout("a", "문구")) + " {}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"CALLOUT\",\"text\":123}"),
                "구조화 콘텐츠 형식이 올바르지 않습니다. (위치: blocks[0].text)");
        assertInvalid(document("{\"id\":7,\"type\":\"CALLOUT\",\"text\":\"문구\"}"),
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid(document(imageText("a", "0", IMAGE, "설명", null, "본문").replace("\"0\"", "0")),
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid("{\"version\":1,\"blocks\":[null]}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid("{\"version\":1,\"blocks\":{}}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalid("{\"version\":1,\"blocks\":[", "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    // ---- id -------------------------------------------------------------------------------

    @Test
    void rejectsInvalidBlockIds() {
        assertInvalid(document("{\"type\":\"CALLOUT\",\"text\":\"문구\"}"),
                "1번째 블록(강조 문구): 블록 식별자가 올바르지 않습니다.");
        assertInvalid(document(callout(" ", "문구")), "블록 식별자가 올바르지 않습니다.");
        assertInvalid(document(callout("a b", "문구")), "블록 식별자가 올바르지 않습니다.");
        assertInvalid(document(callout("<b>", "문구")), "블록 식별자가 올바르지 않습니다.");
        assertInvalid(document(callout("a".repeat(41), "문구")), "블록 식별자가 올바르지 않습니다.");
        assertThat(parser.parseContent(document(callout("A_b-9".repeat(8), "문구"))).blocks())
                .hasSize(1);
    }

    @Test
    void rejectsDuplicateBlockIds() {
        assertInvalid(document(callout("same", "하나"), richText("same", "둘")),
                "2번째 블록(본문): 블록 식별자가 중복되었습니다.");
    }

    @Test
    void rejectsDuplicateOrInvalidSliderItemIds() {
        assertInvalid(document(slider("s", "경복궁",
                        item("i1", IMAGE, "광화문", null, null),
                        item("i1", IMAGE, "흥례문", null, null))),
                "1번째 블록(이미지 슬라이더) 2번째 이미지: 이미지 식별자가 중복되었습니다.");
        assertInvalid(document(slider("s", "경복궁", item("i 1", IMAGE, "광화문", null, null))),
                "1번째 블록(이미지 슬라이더) 1번째 이미지: 이미지 식별자가 올바르지 않습니다.");
    }

    // ---- 슬라이더 ---------------------------------------------------------------------------

    @Test
    void rejectsEmptyOrOversizedSlider() {
        assertInvalid(document("{\"id\":\"s\",\"type\":\"IMAGE_SLIDER\",\"title\":\"제목\",\"items\":[]}"),
                "1번째 블록(이미지 슬라이더): 이미지를 한 장 이상 추가해 주세요.");
        assertInvalid(document("{\"id\":\"s\",\"type\":\"IMAGE_SLIDER\",\"title\":\"제목\"}"),
                "이미지를 한 장 이상 추가해 주세요.");

        String[] items = new String[StructuredContentValidator.MAX_SLIDER_ITEMS + 1];
        for (int index = 0; index < items.length; index++) {
            items[index] = item("i" + index, IMAGE, "설명", null, null);
        }
        assertInvalid(document(slider("s", "제목", items)), "이미지는 40장까지 추가할 수 있습니다.");
    }

    // ---- 이미지 ----------------------------------------------------------------------------

    @Test
    void rejectsImagesOutsideTheStructuredUploadPath() {
        List<String> urls = List.of(
                "/uploads/editor/123e4567-e89b-12d3-a456-426614174000.webp",
                "/uploads/travel-info/thumbnails/123e4567-e89b-12d3-a456-426614174000.webp",
                "https://example.com/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.webp",
                "//example.com/a.webp",
                "javascript:alert(1)",
                "/uploads/travel-info/content/../editor/123e4567-e89b-12d3-a456-426614174000.webp",
                "/uploads/travel-info/content/%2e%2e/123e4567-e89b-12d3-a456-426614174000.webp",
                "/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.webp?x=1",
                "/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.svg",
                "/uploads/travel-info/content/123E4567-E89B-12D3-A456-426614174000.WEBP",
                "uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000.webp",
                "/uploads/travel-info/content/photo.jpg");

        for (String url : urls) {
            assertInvalid(document(fullImage("a", image(url, "100", "100"), "설명", null)),
                    "1번째 블록(큰 이미지): 이미지 경로가 올바르지 않습니다.");
        }
        for (String extension : List.of("jpg", "png", "webp")) {
            String url = "/uploads/travel-info/content/123e4567-e89b-12d3-a456-426614174000." + extension;
            assertThat(parser.parseContent(document(fullImage("a", image(url, "1", "8000"), "설명", null)))
                    .blocks()).hasSize(1);
        }
    }

    @Test
    void rejectsMissingImage() {
        assertInvalid(document("{\"id\":\"a\",\"type\":\"FULL_IMAGE\",\"alt\":\"설명\"}"),
                "1번째 블록(큰 이미지): 이미지를 선택해 주세요.");
        assertInvalid(document(fullImage("a", "{\"width\":10,\"height\":10}", "설명", null)),
                "이미지를 선택해 주세요.");
        assertInvalid(document(slider("s", "제목", "{\"id\":\"i1\",\"alt\":\"설명\"}")),
                "1번째 블록(이미지 슬라이더) 1번째 이미지: 이미지를 선택해 주세요.");
    }

    @Test
    void rejectsInvalidImageDimensions() {
        for (String[] size : List.of(new String[]{"0", "100"}, new String[]{"100", "-1"},
                new String[]{"8001", "100"}, new String[]{"100", "99999"})) {
            assertInvalid(document(fullImage("a", image(URL, size[0], size[1]), "설명", null)),
                    "이미지 크기 정보가 올바르지 않습니다.");
        }
        assertInvalid(document(fullImage("a", "{\"url\":\"" + URL + "\",\"width\":100}", "설명", null)),
                "이미지 크기 정보가 올바르지 않습니다.");
        assertInvalid(document(fullImage("a", image(URL, "\"2000\"", "100"), "설명", null)),
                "구조화 콘텐츠 형식이 올바르지 않습니다. (위치: blocks[0].image.width)");
        assertInvalid(document(fullImage("a", image(URL, "2000.5", "100"), "설명", null)),
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    // ---- 이미지 출처표시(credit) -------------------------------------------------------------

    @Test
    void imageWithoutCreditStillParsesAndHasNoCredit() {
        StructuredContent content = parser.parseContent(document(
                fullImage("legacy", IMAGE, "설명", null),
                fullImage("explicit-null", creditImage("null"), "설명", null)));

        assertThat(content.blocks()).extracting(block -> ((StructuredBlock.FullImage) block).image())
                .containsExactly(new StructuredImage(URL, 2000, 1333), new StructuredImage(URL, 2000, 1333));
        assertThat(((StructuredBlock.FullImage) content.blocks().get(0)).image().credit()).isNull();
    }

    @Test
    void creditIsAcceptedOnEveryImageBlockWithOnlySomeFields() {
        String full = "{\"author\":\"John Doe\",\"source\":\"Wikimedia Commons\","
                + "\"sourceUrl\":\"https://commons.wikimedia.org/wiki/File:Gyeongbokgung.jpg\","
                + "\"license\":\"CC BY-SA 4.0\",\"licenseUrl\":\"http://creativecommons.org/licenses/by-sa/4.0/\"}";
        StructuredContent content = parser.parseContent(document(
                fullImage("full", creditImage(full), "설명", null),
                imageText("split", "LEFT", creditImage("{\"author\":\"홍길동\"}"), "설명", null, "본문"),
                slider("s", null, item("i1", creditImage("{\"source\":\"한국관광공사\",\"license\":\"공공누리 제1유형\"}"),
                        "설명", null, null)),
                grid("g", item("c1", creditImage("{\"author\":\" \",\"source\":\"한국관광공사\",\"sourceUrl\":\"\"}"),
                        "설명", null, null), item("c2", IMAGE, "설명", null, null))));

        assertThat(((StructuredBlock.FullImage) content.blocks().get(0)).image().credit())
                .isEqualTo(new StructuredImageCredit("John Doe", "Wikimedia Commons",
                        "https://commons.wikimedia.org/wiki/File:Gyeongbokgung.jpg", "CC BY-SA 4.0",
                        "http://creativecommons.org/licenses/by-sa/4.0/"));
        assertThat(((StructuredBlock.ImageText) content.blocks().get(1)).image().credit())
                .isEqualTo(new StructuredImageCredit("홍길동", null, null, null, null));
        assertThat(((StructuredBlock.ImageSlider) content.blocks().get(2)).items().get(0).image().credit())
                .isEqualTo(new StructuredImageCredit(null, "한국관광공사", null, "공공누리 제1유형", null));
        // 공백뿐인 값은 값이 없는 것으로 본다.
        StructuredBlock.ImageGrid grid = (StructuredBlock.ImageGrid) content.blocks().get(3);
        assertThat(grid.items().get(0).image().credit())
                .isEqualTo(new StructuredImageCredit(null, "한국관광공사", null, null, null));
        assertThat(grid.items().get(1).image().credit()).isNull();
    }

    @Test
    void creditNeedsAuthorOrSourceOnEveryImageBlock() {
        for (String credit : List.of("{}", "{\"author\":\" \",\"source\":\"\"}",
                "{\"license\":\"CC BY 4.0\",\"licenseUrl\":\"https://creativecommons.org/licenses/by/4.0/\"}")) {
            assertInvalid(document(fullImage("a", creditImage(credit), "설명", null)),
                    "1번째 블록(큰 이미지): 출처표시에는 저작자 또는 출처명을 입력해 주세요.");
            assertInvalid(document(imageText("a", "LEFT", creditImage(credit), "설명", null, "본문")),
                    "1번째 블록(이미지 + 글): 출처표시에는 저작자 또는 출처명을 입력해 주세요.");
            assertInvalid(document(slider("s", null, item("i1", creditImage(credit), "설명", null, null))),
                    "1번째 블록(이미지 슬라이더) 1번째 이미지: 출처표시에는 저작자 또는 출처명을 입력해 주세요.");
            assertInvalid(document(grid("g", item("c1", IMAGE, "설명", null, null),
                            item("c2", creditImage(credit), "설명", null, null))),
                    "1번째 블록(이미지 배치) 2번째 이미지: 출처표시에는 저작자 또는 출처명을 입력해 주세요.");
        }
    }

    @Test
    void creditTextsAndUrlsAreLengthChecked() {
        String url500 = "https://example.com/" + "a".repeat(480);
        assertThat(parser.parseContent(document(fullImage("a", creditImage(credit(
                "가".repeat(100), "나".repeat(100), url500, "다".repeat(100), url500)), "설명", null)))
                .blocks()).hasSize(1);

        assertInvalid(document(fullImage("a", creditImage(credit("가".repeat(101), "출처", null, null, null)),
                "설명", null)), "1번째 블록(큰 이미지): 저작자는 100자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", creditImage(credit("저작자", "가".repeat(101), null, null, null)),
                "설명", null)), "출처명은 100자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", creditImage(credit("저작자", null, null, "가".repeat(101), null)),
                "설명", null)), "라이선스는 100자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", creditImage(credit("저작자", null, url500 + "a", null, null)),
                "설명", null)), "출처 URL은 500자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", creditImage(credit("저작자", null, null, null, url500 + "a")),
                "설명", null)), "라이선스 URL은 500자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", creditImage(credit("저작자\\n둘째 줄", null, null, null, null)),
                "설명", null)), "저작자는 한 줄로 입력해 주세요.");
    }

    @Test
    void creditUrlsAcceptOnlyHttpAndHttpsAddresses() {
        for (String url : List.of("http://example.com", "https://commons.wikimedia.org/wiki/File:A.jpg?x=1#top",
                "HTTPS://CREATIVECOMMONS.ORG/licenses/by/4.0/")) {
            assertThat(parser.parseContent(document(
                    fullImage("a", creditImage(credit("저작자", null, url, null, url)), "설명", null))).blocks())
                    .hasSize(1);
        }
        for (String url : List.of("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<b>x</b>",
                "file:///etc/passwd", "ftp://example.com/a.jpg", "commons.wikimedia.org/wiki/File:A.jpg",
                "//example.com/a", "/uploads/a.jpg", "https://", "https:example.com", "not a url",
                " https://example.com")) {
            assertInvalid(document(fullImage("a", creditImage(credit("저작자", null, url, null, null)), "설명", null)),
                    "1번째 블록(큰 이미지): 출처 URL은 http 또는 https 주소로 입력해 주세요.");
            assertInvalid(document(slider("s", null, item("i1",
                            creditImage(credit(null, "출처", null, null, url)), "설명", null, null))),
                    "1번째 블록(이미지 슬라이더) 1번째 이미지: 라이선스 URL은 http 또는 https 주소로 입력해 주세요.");
        }
    }

    @Test
    void creditRejectsUnknownFields() {
        assertInvalid(document(fullImage("a", creditImage("{\"author\":\"저작자\",\"attributionRequired\":true}"),
                        "설명", null)),
                "허용하지 않는 항목이 있습니다: attributionRequired (위치: blocks[0].image.credit.attributionRequired)");
        assertInvalid(document(fullImage("a", creditImage("\"John Doe\""), "설명", null)),
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    // ---- 필수 글 / 길이 --------------------------------------------------------------------

    @Test
    void rejectsMissingRequiredTexts() {
        assertInvalid(document(sectionTitle("a", " ")),
                "1번째 블록(섹션 제목): 제목을 입력해 주세요.");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"RICH_TEXT\"}"),
                "1번째 블록(본문): 본문을 입력해 주세요.");
        assertInvalid(document(imageText("a", "LEFT", IMAGE, "설명", "제목", null)),
                "1번째 블록(이미지 + 글): 본문을 입력해 주세요.");
        assertInvalid(document("{\"id\":\"a\",\"type\":\"CALLOUT\",\"text\":\"\"}"),
                "1번째 블록(강조 문구): 강조 문구를 입력해 주세요.");
    }

    @Test
    void imageAltIsOptionalForEveryImageBlockButStillLengthChecked() {
        StructuredContent content = parser.parseContent(document(
                fullImage("full", IMAGE, "", null),
                imageText("split", "LEFT", IMAGE, null, null, "본문"),
                slider("s", null, item("i1", IMAGE, " ", null, null), item("i2", IMAGE, null, "광화문", null))));

        assertThat(content.blocks()).hasSize(3);
        assertThat(((StructuredBlock.FullImage) content.blocks().get(0)).alt()).isNull();
        assertThat(((StructuredBlock.ImageSlider) content.blocks().get(2)).items().get(0).alt()).isNull();
        // 값이 있으면 길이·한 줄 규칙은 그대로다.
        assertInvalid(document(fullImage("a", IMAGE, "가".repeat(201), null)),
                "이미지 설명(alt)은 200자 이하로 입력해 주세요.");
        assertInvalid(document(slider("s", null, item("i1", IMAGE, "첫 줄\\n둘째 줄", null, null))),
                "1번째 블록(이미지 슬라이더) 1번째 이미지: 이미지 설명(alt)은 한 줄로 입력해 주세요.");
        // 이미지 자체는 여전히 필수다.
        assertInvalid(document("{\"id\":\"a\",\"type\":\"FULL_IMAGE\"}"), "이미지를 선택해 주세요.");
    }

    @Test
    void imageTextRequiresKnownPosition() {
        assertInvalid(document("{\"id\":\"a\",\"type\":\"IMAGE_TEXT\",\"image\":" + IMAGE
                        + ",\"alt\":\"설명\",\"text\":\"본문\"}"),
                "1번째 블록(이미지 + 글): 이미지 위치를 선택해 주세요.");
        assertInvalid(document(imageText("a", "CENTER", IMAGE, "설명", null, "본문")),
                "구조화 콘텐츠 형식이 올바르지 않습니다. (위치: blocks[0].imagePosition)");
        assertInvalid(document(imageText("a", "left", IMAGE, "설명", null, "본문")),
                "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    @Test
    void rejectsTooLongTexts() {
        assertInvalid(document(sectionTitle("a", "가".repeat(121))),
                "1번째 블록(섹션 제목): 제목은 120자 이하로 입력해 주세요.");
        assertInvalid(document(richText("a", "가".repeat(5001))), "본문은 5000자 이하로 입력해 주세요.");
        assertInvalid(document(imageText("a", "LEFT", IMAGE, "설명", null, "가".repeat(3001))),
                "본문은 3000자 이하로 입력해 주세요.");
        assertInvalid(document(fullImage("a", IMAGE, "가".repeat(201), null)),
                "이미지 설명(alt)은 200자 이하로 입력해 주세요.");
        assertInvalid(document(callout("a", "가".repeat(501))), "강조 문구는 500자 이하로 입력해 주세요.");
        assertInvalid(document(slider("s", "제목", item("i1", IMAGE, "설명", "가".repeat(101), null))),
                "이미지 제목은 100자 이하로 입력해 주세요.");
        // 길이는 화면 글자(code point) 기준이다. 이모지 120개는 제목 한도 안이다.
        assertThat(parser.parseContent(document(sectionTitle("a", "😀".repeat(120)))).blocks())
                .hasSize(1);
    }

    @Test
    void singleLineFieldsRejectLineBreaksAndControlCharactersAreRejected() {
        assertInvalid(document(sectionTitle("a", "첫 줄\\n둘째 줄")),
                "1번째 블록(섹션 제목): 제목은 한 줄로 입력해 주세요.");
        assertInvalid(document(fullImage("a", IMAGE, "설명", "캡션\\n둘째 줄")), "캡션은 한 줄로 입력해 주세요.");
        assertInvalid(document(richText("a", "본문\\u0000")), "본문에 사용할 수 없는 문자가 있습니다.");
        assertInvalid(document(callout("a", "강조\\uD800")), "강조 문구에 사용할 수 없는 문자가 있습니다.");
        assertThat(parser.parseContent(document(richText("a", "첫 줄\\r\\n둘째 줄\\t끝"))).blocks())
                .hasSize(1);
    }

    @Test
    void validationErrorsUseStructuredContentFormField() {
        assertThatThrownBy(() -> parser.parseContent(document(callout("a", " "))))
                .isInstanceOf(StructuredContentValidationException.class)
                .extracting(exception -> ((StructuredContentValidationException) exception).getField())
                .isEqualTo("structuredContent");
    }

    // ---- structured_text ------------------------------------------------------------------

    @Test
    void parsesTranslationTextOverrides() {
        StructuredText text = parser.parseText("""
                {"blocks": {
                  "intro": {"title": "Seoul Palace Tour", "lead": "Five palaces\\nin a day"},
                  "gyeongbok": {"title": "Gyeongbokgung", "items": {
                    "i1": {"title": "Gwanghwamun", "caption": "Main gate", "alt": "Gwanghwamun gate"}
                  }},
                  "tip": {"text": "Closed on Mondays."}
                }}""");

        assertThat(text.blocks()).containsOnlyKeys("intro", "gyeongbok", "tip");
        // 예전 번역의 섹션 제목 lead 는 읽기만 하고 버린다.
        assertThat(text.blocks().get("intro"))
                .isEqualTo(new StructuredText.BlockText("Seoul Palace Tour", null, null, null, null));
        assertThat(new StructuredContentSerializer().write(text)).doesNotContain("lead", "Five palaces");
        assertThat(text.blocks().get("gyeongbok").items().get("i1"))
                .isEqualTo(new StructuredText.ItemText("Gwanghwamun", "Main gate", "Gwanghwamun gate"));
        assertThat(text.blocks().get("tip").items()).isEmpty();
    }

    @Test
    void emptyTranslationTextMeansNoOverride() {
        assertThat(parser.parseText(null)).isEqualTo(StructuredText.EMPTY);
        assertThat(parser.parseText(" ")).isEqualTo(StructuredText.EMPTY);
        assertThat(parser.parseText("null")).isEqualTo(StructuredText.EMPTY);
        assertThat(parser.parseText("{}").blocks()).isEmpty();
    }

    @Test
    void translationTextCannotCarryImagesLayoutOrUnknownKeys() {
        assertInvalidText("{\"blocks\":{\"a\":{\"title\":\"T\",\"image\":{\"url\":\"" + URL + "\"}}}}",
                "허용하지 않는 항목이 있습니다: image");
        assertInvalidText("{\"blocks\":{\"a\":{\"type\":\"CALLOUT\"}}}", "허용하지 않는 항목이 있습니다: type");
        assertInvalidText("{\"blocks\":{\"a\":{\"imagePosition\":\"RIGHT\"}}}",
                "허용하지 않는 항목이 있습니다: imagePosition");
        // 본문 폭은 원문 공통이라 번역에 들어올 수 없다.
        assertInvalidText("{\"blocks\":{\"a\":{\"layout\":\"FOCUSED\"}}}",
                "허용하지 않는 항목이 있습니다: layout");
        assertInvalidText("{\"blocks\":{\"a\":{\"items\":{\"i1\":{\"url\":\"" + URL + "\"}}}}}",
                "허용하지 않는 항목이 있습니다: url");
        assertInvalidText("{\"version\":1,\"blocks\":{}}", "허용하지 않는 항목이 있습니다: version");
        assertInvalidText("{\"blocks\":[]}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
        assertInvalidText("{\"blocks\":{\"a\":null}}", "구조화 콘텐츠 형식이 올바르지 않습니다.");
    }

    @Test
    void translationTextValidatesIdsLengthAndCharacters() {
        assertInvalidText("{\"blocks\":{\"a b\":{\"title\":\"T\"}}}",
                "번역 블록(a?b): 블록 식별자가 올바르지 않습니다.");
        assertInvalidText("{\"blocks\":{\"a\":{\"items\":{\"<i>\":{\"title\":\"T\"}}}}}",
                "번역 블록(a) 이미지(?i?): 이미지 식별자가 올바르지 않습니다.");
        assertInvalidText("{\"blocks\":{\"a\":{\"title\":\"" + "A".repeat(121) + "\"}}}",
                "번역 블록(a): 제목은 120자 이하로 입력해 주세요.");
        assertInvalidText("{\"blocks\":{\"a\":{\"alt\":\"line\\nbreak\"}}}",
                "번역 블록(a): 이미지 설명(alt)은 한 줄로 입력해 주세요.");
        assertInvalidText("{\"blocks\":{\"a\":{\"items\":{\"i1\":{\"caption\":\"" + "A".repeat(301) + "\"}}}}}",
                "번역 블록(a) 이미지(i1): 캡션은 300자 이하로 입력해 주세요.");

        assertThatThrownBy(() -> parser.parseText("{\"blocks\":{\"a b\":{}}}"))
                .isInstanceOf(StructuredContentValidationException.class)
                .extracting(exception -> ((StructuredContentValidationException) exception).getField())
                .isEqualTo("translations");
    }

    @Test
    void translationTextLimitsBlockAndItemCounts() {
        StringBuilder blocks = new StringBuilder();
        for (int index = 0; index <= StructuredContentValidator.MAX_BLOCKS; index++) {
            blocks.append(index == 0 ? "" : ",").append("\"b").append(index).append("\":{}");
        }
        assertInvalidText("{\"blocks\":{" + blocks + "}}", "번역 블록은 60개까지 저장할 수 있습니다.");

        StringBuilder items = new StringBuilder();
        for (int index = 0; index <= StructuredContentValidator.MAX_SLIDER_ITEMS; index++) {
            items.append(index == 0 ? "" : ",").append("\"i").append(index).append("\":{}");
        }
        assertInvalidText("{\"blocks\":{\"s\":{\"items\":{" + items + "}}}}",
                "번역 블록(s): 번역 이미지는 40장까지 저장할 수 있습니다.");
    }

    // ---- helpers --------------------------------------------------------------------------

    private void assertInvalid(String json, String message) {
        assertThatThrownBy(() -> parser.parseContent(json))
                .isInstanceOf(StructuredContentValidationException.class)
                .hasMessageContaining(message);
    }

    private void assertInvalidText(String json, String message) {
        assertThatThrownBy(() -> parser.parseText(json))
                .isInstanceOf(StructuredContentValidationException.class)
                .hasMessageContaining(message);
    }

    private static String document(String... blocks) {
        return "{\"version\":1,\"blocks\":[" + String.join(",", blocks) + "]}";
    }

    private static String image(String url, String width, String height) {
        return "{\"url\":\"" + url + "\",\"width\":" + width + ",\"height\":" + height + "}";
    }

    /** 기본 이미지(IMAGE)에 credit JSON 을 붙인다. */
    private static String creditImage(String credit) {
        return "{\"url\":\"" + URL + "\",\"width\":2000,\"height\":1333,\"credit\":" + credit + "}";
    }

    private static String credit(String author, String source, String sourceUrl, String license,
                                 String licenseUrl) {
        return "{\"author\":" + quoted(author) + ",\"source\":" + quoted(source)
                + ",\"sourceUrl\":" + quoted(sourceUrl) + ",\"license\":" + quoted(license)
                + ",\"licenseUrl\":" + quoted(licenseUrl) + "}";
    }

    private static String grid(String id, String... items) {
        return "{\"id\":\"" + id + "\",\"type\":\"IMAGE_GRID\",\"columns\":" + items.length
                + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String sectionTitle(String id, String title) {
        return "{\"id\":\"" + id + "\",\"type\":\"SECTION_TITLE\",\"title\":" + quoted(title) + "}";
    }

    private static String richText(String id, String text) {
        return "{\"id\":\"" + id + "\",\"type\":\"RICH_TEXT\",\"text\":" + quoted(text) + "}";
    }

    private static String fullImage(String id, String image, String alt, String caption) {
        return "{\"id\":\"" + id + "\",\"type\":\"FULL_IMAGE\",\"image\":" + image
                + ",\"alt\":" + quoted(alt) + ",\"caption\":" + quoted(caption) + "}";
    }

    private static String imageText(String id, String position, String image, String alt,
                                    String title, String text) {
        return "{\"id\":\"" + id + "\",\"type\":\"IMAGE_TEXT\",\"imagePosition\":" + quoted(position)
                + ",\"image\":" + image + ",\"alt\":" + quoted(alt) + ",\"title\":" + quoted(title)
                + ",\"text\":" + quoted(text) + "}";
    }

    private static String slider(String id, String title, String... items) {
        return "{\"id\":\"" + id + "\",\"type\":\"IMAGE_SLIDER\",\"title\":" + quoted(title)
                + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String item(String id, String image, String alt, String title, String caption) {
        return "{\"id\":\"" + id + "\",\"image\":" + image + ",\"alt\":" + quoted(alt)
                + ",\"title\":" + quoted(title) + ",\"caption\":" + quoted(caption) + "}";
    }

    private static String callout(String id, String text) {
        return "{\"id\":\"" + id + "\",\"type\":\"CALLOUT\",\"text\":" + quoted(text) + "}";
    }

    /**
     * JSON 문자열 값. 테스트 글에는 따옴표가 없으므로 줄바꿈만 escape 한다.
     * (이미 "\\n" 처럼 escape 해 둔 값은 그대로 지나간다)
     */
    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\n", "\\n") + "\"";
    }
}
