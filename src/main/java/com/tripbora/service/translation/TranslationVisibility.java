package com.tripbora.service.translation;

import com.tripbora.config.i18n.SupportedLanguage;

public final class TranslationVisibility {
    private TranslationVisibility() {
    }

    public static boolean shouldOffer(String sourceLanguage, String targetLanguage) {
        var source = SupportedLanguage.fromLanguageTag(sourceLanguage);
        var target = SupportedLanguage.fromLanguageTag(targetLanguage);
        return source.isPresent() && target.isPresent() && source.get() != target.get();
    }

    public static boolean shouldOffer(String sourceLanguage, String targetLanguage, String text) {
        return shouldOffer(sourceLanguage, targetLanguage)
                || (SupportedLanguage.fromLanguageTag(targetLanguage).isPresent()
                    && MixedLanguageSupport.shouldTranslate(text, targetLanguage));
    }
}
