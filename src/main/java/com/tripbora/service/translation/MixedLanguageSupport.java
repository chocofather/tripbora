package com.tripbora.service.translation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 로컬의 보수적인 혼합 문장 판별. 본문 분할이나 외부 언어 감지 호출은 하지 않는다. */
public final class MixedLanguageSupport {
    private static final Pattern LINKS = Pattern.compile("(?i)(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?");

    private MixedLanguageSupport() {}

    public static boolean shouldTranslate(String text, String target) {
        if (com.tripbora.config.i18n.SupportedLanguage.fromLanguageTag(target).isEmpty()) return false;
        var languages = languages(text);
        return languages.size() > 1 && languages.stream().anyMatch(language -> !language.equals(base(target)));
    }

    public static String sourceHint(String text, String target, String fallback) {
        var languages = languages(text);
        if (languages.size() < 2) return fallback;
        languages.remove(base(target));
        // 대상 언어 이외의 언어가 하나면 그것을 힌트로 삼아 전체 원문을 한 번에 전송한다.
        // 두 개 이상이면 기존 Google 자동 감지를 사용한다.
        return languages.size() == 1 ? languages.iterator().next() : "und";
    }

    private static String base(String tag) {
        return tag != null && tag.startsWith("zh") ? "zh" : tag;
    }

    private static Set<String> languages(String text) {
        var result = new LinkedHashSet<String>();
        if (text == null || text.isBlank()) return result;
        String cleaned = LINKS.matcher(text).replaceAll(" ");
        long hangul = count(cleaned, Character.UnicodeScript.HANGUL);
        long kana = count(cleaned, Character.UnicodeScript.HIRAGANA) + count(cleaned, Character.UnicodeScript.KATAKANA);
        long han = count(cleaned, Character.UnicodeScript.HAN);
        if (hangul >= 2) result.add("ko");
        if (kana >= 2) result.add("ja");
        else if (kana == 0 && han >= 4) result.add("zh");
        // URL·숫자·Seoul/New York/UNESCO 같은 단순 고유명사는 혼합 문장 근거로 쓰지 않는다.
        var words = LATIN_WORD.matcher(cleaned);
        int lowerWords = 0, wordCount = 0, letters = 0;
        while (words.find()) {
            String word = words.group();
            if (word.length() >= 2 && !word.equals(word.toUpperCase(java.util.Locale.ROOT))) {
                wordCount++; letters += word.length();
                if (word.equals(word.toLowerCase(java.util.Locale.ROOT))) lowerWords++;
            }
        }
        if (wordCount >= 2 && lowerWords >= 1 && letters >= 7) result.add("en");
        return result;
    }

    private static long count(String text, Character.UnicodeScript script) {
        return text.codePoints().filter(c -> Character.UnicodeScript.of(c) == script).count();
    }
}
