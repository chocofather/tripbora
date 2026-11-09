package com.tripbora.service.translation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SharedTranslationTextNormalizerTest {
    private String normalize(String text) { return SharedTranslationTextNormalizer.normalize(text,"text/plain").orElseThrow(); }

    @Test void onlyAsciiHorizontalWhitespaceAndCrLfAreNormalized() {
        assertThat(normalize("  Hello\t beautiful   world!  ")).isEqualTo("Hello beautiful world!");
        assertThat(normalize("Hello world.\r\nAnother sentence. ")).isEqualTo("Hello world.\nAnother sentence.");
        for(String text:new String[]{"맛있는 음식", "とても 美味しい", "这里 食物很好", "這裡 食物很好", "좋아요 Very good!"})
            assertThat(normalize("  "+text.replace(" ","\t  ")+"  ")).isEqualTo(text);
    }
    @Test void wordBoundariesCasePunctuationNumbersAndUnicodeFormsRemainDistinct() {
        for(String[] pair:new String[][]{{"now here","nowhere"},{"아버지가 방에","아버지 가방에"},{"東京 大阪","東京大阪"},
                {"你好 世界","你好世界"},{"Paris","paris"},{"1.0","10"},{"Hello!","Hello?"},{"ＡＢＣ","ABC"},{"é","e\u0301"}})
            assertThat(normalize(pair[0])).isNotEqualTo(normalize(pair[1]));
        assertThat(normalize("First line.\nSecond line.")).isNotEqualTo(normalize("First line. Second line."));
    }
    @Test void paragraphsFormattingAndSpecialWhitespaceUseOnlyExactCache() {
        for(String text:new String[]{"First\n\nSecond", "First\n \nSecond", "First\n  Second", "First\n\tSecond",
                "```code```", "a | b", "name  1  2", "x = y", "print(\"a  b\")", "a 'b  c'", "- item  one", "a\rb", "a\u00a0b", "a\u3000b", "a\u0000b"})
            assertThat(SharedTranslationTextNormalizer.normalize(text,"text/plain")).as(text).isEmpty();
        assertThat(SharedTranslationTextNormalizer.normalize("<p>Hello  world</p>","text/html")).isEmpty();
        assertThat(SharedTranslationTextNormalizer.normalize("Name\tCity\nAlice\tParis","text/plain")).isEmpty();
        assertThat(SharedTranslationTextNormalizer.normalize("Name  City\nAlice  Paris","text/plain")).isEmpty();
    }
    @Test void longProfileDoesNotTruncateOrMergeExistingProfiles() {
        var source=new TranslationSourceSnapshot(TranslatableContentType.DESTINATION_COMMENT,1L,"content","hello  world","en",null);
        var metadata=new TranslationProviderMetadata("p".repeat(64));
        var raw=ContentTranslationService.sourceHash(source.text());
        var key=SharedTranslationKey.forSource(source,raw,metadata);
        assertThat(key.normalized()).isFalse();assertThat(key.profile()).isEqualTo(metadata.profile());
        assertThat(key.hash()).containsExactly(raw);
    }
}
