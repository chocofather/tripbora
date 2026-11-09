package com.tripbora.service.translation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MixedLanguageSupportTest {
    @Test void meaningfulMixedSentencesOfferTranslationWithWholeTextSourceHints() {
        String[][] cases={
                {"정말 좋은 식당입니다. The food was very delicious.","ko","ko","en"},
                {"정말 좋은 식당입니다. The food was very delicious.","ko","ja","und"},
                {"とても美味しいお店です。 The food was very delicious.","ja","ko","und"},
                {"とても美味しいお店です。 The food was very delicious.","ja","en","ja"},
                {"这里的食物非常好吃 정말 좋은 식당입니다.","zh-CN","en","und"},
                {"這裡的食物非常好吃 정말 좋은 식당입니다.","zh-TW","zh-TW","ko"}};
        for(var row:cases) {
            assertThat(TranslationVisibility.shouldOffer(row[1],row[2],row[0])).as(row[0]+" -> "+row[2]).isTrue();
            assertThat(MixedLanguageSupport.sourceHint(row[0],row[2],row[1])).isEqualTo(row[3]);
        }
        assertThat(TranslationVisibility.shouldOffer("ko","ko","정말 맛있어요. Very good!")).isTrue();
    }
    @Test void pureKoreanUrlsNumbersAndSimpleProperNamesDoNotTriggerExtraCalls() {
        for(String text:new String[]{"정말 맛있고 직원분이 친절합니다.","좋았어요 2026 11 02", "좋았어요 https://example.com/the/food/was/great", "좋았어요 New York Seoul UNESCO", "좋았어요 reader@example.com"}) {
            assertThat(TranslationVisibility.shouldOffer("ko","ko",text)).as(text).isFalse();
        }
        assertThat(TranslationVisibility.shouldOffer("ja","ja","とても美味しいお店です。")).isFalse();
        assertThat(MixedLanguageSupport.shouldTranslate("정말 맛있어요. Very good!","invalid")).isFalse();
    }
}
