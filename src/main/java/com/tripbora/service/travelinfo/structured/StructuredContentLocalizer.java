package com.tripbora.service.travelinfo.structured;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 원문 구조화 콘텐츠와 한 언어의 번역 글(structured_text)을 합친다.
 *
 * <p>블록 종류·순서·이미지·위치는 언제나 원문 것을 쓴다. 번역은 글 칸만 바꿀 수 있고, 번역에 없는 칸은
 * 원문(한국어) 글을 그대로 쓴다.
 *
 * <p>번역 글 정리 규칙 ({@link #alignToBase}):
 * <ul>
 *   <li>원문에 없는 블록 id, 원문 슬라이더에 없는 이미지 id 는 저장하지 않고 뺀다.
 *       (원문에서 블록을 지우거나 복제한 뒤 남은 번역 칸이 오류가 되지 않게 한다)</li>
 *   <li>그 블록 종류에 없는 칸(예: 강조 문구 블록의 caption)도 뺀다.</li>
 *   <li>덮어쓸 글이 남지 않은 블록·이미지는 통째로 뺀다.</li>
 * </ul>
 */
@Component
public class StructuredContentLocalizer {

    /** 원문 구조에 맞는 번역 칸만 남긴다. 남는 칸이 없으면 빈 번역이다. */
    public StructuredText alignToBase(StructuredContent base, StructuredText text) {
        if (text == null || text.blocks().isEmpty()) {
            return StructuredText.EMPTY;
        }
        Map<String, StructuredText.BlockText> aligned = new LinkedHashMap<>();
        for (StructuredBlock block : base.blocks()) {
            StructuredText.BlockText override = text.blocks().get(block.id());
            if (override == null) {
                continue;
            }
            StructuredText.BlockText kept = keepApplicableFields(block, override);
            if (kept.hasOverrides()) {
                aligned.put(block.id(), kept);
            }
        }
        return new StructuredText(aligned);
    }

    /** 원문에 번역 글을 덮어쓴 표시용 구조. 번역 칸이 비어 있으면 원문 글을 쓴다. */
    public StructuredContent apply(StructuredContent base, StructuredText text) {
        if (text == null || text.blocks().isEmpty()) {
            return base;
        }
        List<StructuredBlock> localized = new ArrayList<>(base.blocks().size());
        for (StructuredBlock block : base.blocks()) {
            StructuredText.BlockText override = text.blocks().get(block.id());
            localized.add(override == null ? block : applyOverride(block, override));
        }
        return new StructuredContent(base.version(), localized);
    }

    private StructuredText.BlockText keepApplicableFields(StructuredBlock block,
                                                          StructuredText.BlockText override) {
        if (block instanceof StructuredBlock.SectionTitle) {
            return blockText(override.title(), null, null, null, Map.of());
        }
        if (block instanceof StructuredBlock.RichText) {
            // 일반 글 번역은 서식 본문(html)도 받는다. html 이 있으면 그것만 남긴다. (글은 html 에서 나온다)
            return override.html() != null
                    ? new StructuredText.BlockText(null, null, null, null, Map.of(), override.html())
                    : blockText(null, override.text(), null, null, Map.of());
        }
        if (block instanceof StructuredBlock.Callout) {
            return blockText(null, override.text(), null, null, Map.of());
        }
        if (block instanceof StructuredBlock.FullImage) {
            return blockText(null, null, override.caption(), override.alt(), Map.of());
        }
        if (block instanceof StructuredBlock.ImageText) {
            return blockText(override.title(), override.text(), null, override.alt(), Map.of());
        }
        if (block instanceof StructuredBlock.ImageSlider slider) {
            return blockText(override.title(), null, null, null, applicableItems(slider.items(), override));
        }
        if (block instanceof StructuredBlock.ImageGrid grid) {
            // 이미지 배치에는 블록 제목이 없다. 사진마다 제목·설명·alt 만 번역한다.
            return blockText(null, null, null, null, applicableItems(grid.items(), override));
        }
        return blockText(null, null, null, null, Map.of());
    }

    /** 원문 블록에 있는 사진 id 의 번역 글만 남긴다. */
    private Map<String, StructuredText.ItemText> applicableItems(List<StructuredBlock.SliderItem> baseItems,
                                                                 StructuredText.BlockText override) {
        Map<String, StructuredText.ItemText> items = new LinkedHashMap<>();
        for (StructuredBlock.SliderItem item : baseItems) {
            StructuredText.ItemText itemOverride = override.items().get(item.id());
            if (itemOverride != null && itemOverride.hasOverrides()) {
                items.put(item.id(), itemOverride);
            }
        }
        return items;
    }

    private StructuredBlock applyOverride(StructuredBlock block, StructuredText.BlockText override) {
        if (block instanceof StructuredBlock.SectionTitle sectionTitle) {
            return new StructuredBlock.SectionTitle(sectionTitle.id(),
                    pick(override.title(), sectionTitle.title()));
        }
        if (block instanceof StructuredBlock.RichText richText) {
            // 본문 폭은 원문 공통이다. 번역 글이 있으면 원문 서식이 아니라 번역 글(서식 본문 또는 일반 글)을 쓴다.
            if (override.html() != null) {
                return new StructuredBlock.RichText(richText.id(), richText.layout(), null, override.html());
            }
            if (override.text() != null) {
                return new StructuredBlock.RichText(richText.id(), richText.layout(), override.text());
            }
            return richText;
        }
        if (block instanceof StructuredBlock.FullImage fullImage) {
            return new StructuredBlock.FullImage(fullImage.id(), fullImage.image(),
                    localizedAlt(override.alt(), fullImage.alt(), override.caption()),
                    pick(override.caption(), fullImage.caption()));
        }
        if (block instanceof StructuredBlock.ImageText imageText) {
            return new StructuredBlock.ImageText(imageText.id(), imageText.imagePosition(),
                    imageText.image(),
                    localizedAlt(override.alt(), imageText.alt(), override.title()),
                    pick(override.title(), imageText.title()),
                    pick(override.text(), imageText.text()));
        }
        if (block instanceof StructuredBlock.ImageSlider slider) {
            return new StructuredBlock.ImageSlider(slider.id(),
                    pick(override.title(), slider.title()), localizedItems(slider.items(), override));
        }
        if (block instanceof StructuredBlock.ImageGrid grid) {
            // 칸 수와 사진 순서는 원문 공통이다.
            return new StructuredBlock.ImageGrid(grid.id(), grid.columns(), localizedItems(grid.items(), override));
        }
        if (block instanceof StructuredBlock.Callout callout) {
            return new StructuredBlock.Callout(callout.id(), pick(override.text(), callout.text()));
        }
        return block;
    }

    /** 사진마다 번역 글을 덮어쓴다. 이미지와 순서는 원문 그대로다. */
    private List<StructuredBlock.SliderItem> localizedItems(List<StructuredBlock.SliderItem> baseItems,
                                                            StructuredText.BlockText override) {
        List<StructuredBlock.SliderItem> items = new ArrayList<>(baseItems.size());
        for (StructuredBlock.SliderItem item : baseItems) {
            StructuredText.ItemText itemOverride = override.items().get(item.id());
            items.add(itemOverride == null ? item : new StructuredBlock.SliderItem(item.id(),
                    item.image(),
                    localizedAlt(itemOverride.alt(), item.alt(), itemOverride.title(), itemOverride.caption()),
                    pick(itemOverride.title(), item.title()),
                    pick(itemOverride.caption(), item.caption())));
        }
        return items;
    }

    private StructuredText.BlockText blockText(String title, String text, String caption,
                                               String alt, Map<String, StructuredText.ItemText> items) {
        return new StructuredText.BlockText(title, text, caption, alt, items);
    }

    private String pick(String override, String base) {
        return override != null ? override : base;
    }

    /**
     * 번역 화면의 이미지 alt. 번역 alt 가 있으면 그것을 쓴다. 번역 alt 는 없지만 그 이미지의 번역 글
     * (제목·캡션)이 있으면 원문(한국어) alt 를 남기지 않고 비워, 화면이 그 언어의 글로 대신 채우게 한다.
     * (StructuredBlock 의 effectiveAlt / itemAlt) 그 이미지에 번역 글이 하나도 없으면 원문 alt 를 그대로 쓴다.
     */
    private String localizedAlt(String overrideAlt, String baseAlt, String... localizedTexts) {
        if (overrideAlt != null) {
            return overrideAlt;
        }
        for (String localized : localizedTexts) {
            if (localized != null) {
                return null;
            }
        }
        return baseAlt;
    }
}
