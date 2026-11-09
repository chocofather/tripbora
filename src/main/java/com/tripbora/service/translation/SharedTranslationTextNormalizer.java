package com.tripbora.service.translation;

import java.util.Optional;
import java.util.regex.Pattern;

/** 공용 캐시 비교용일 뿐 원문 저장·provider 전송에는 사용하지 않는다. */
public final class SharedTranslationTextNormalizer {
    private static final Pattern HORIZONTAL = Pattern.compile("[ \\t]+");
    private static final Pattern FORMATTED_LINE = Pattern.compile("(?m)^(?:[ \\t]+|[-*+#] |[0-9]+[.)] )");
    private static final Pattern PARAGRAPH = Pattern.compile("\\n[ \\t]*\\n");

    private SharedTranslationTextNormalizer() {}

    public static Optional<String> normalize(String text, String mimeType) {
        if (text == null || !"text/plain".equals(mimeType)) return Optional.empty();
        String lines = text.replace("\r\n", "\n");
        // 단독 CR, 특수 공백, 코드·표·들여쓰기·문단은 완전 일치 경로에 남긴다.
        if (lines.codePoints().anyMatch(c -> (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c))
                && c != ' ' && c != '\t' && c != '\n')
                || lines.indexOf('`') >= 0 || lines.indexOf('|') >= 0
                || lines.matches("(?s).*[\"'“”‘’].*")
                || lines.matches("(?s)^(?:[-*+#] |[0-9]+[.)] ).*")
                || lines.matches("(?s).*[<>{}\\[\\]=].*")
                || PARAGRAPH.matcher(lines).find()
                || (lines.indexOf('\n') >= 0 && FORMATTED_LINE.matcher(lines).find())
                || (lines.indexOf('\n') >= 0 && Pattern.compile("\\S(?:\\t| {2,})\\S").matcher(lines).find())
                || lines.matches("(?s).*\\d[ \\t]{2,}\\d.*")) return Optional.empty();
        String normalized = HORIZONTAL.matcher(lines).replaceAll(" ");
        // 단어 사이 공백을 없애지 않으며 단일 줄바꿈도 접지 않는다.
        normalized = normalized.replaceAll("(?m)^ +| +$", "");
        return normalized.isBlank() ? Optional.empty() : Optional.of(normalized);
    }
}
