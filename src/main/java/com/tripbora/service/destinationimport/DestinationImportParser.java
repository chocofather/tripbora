package com.tripbora.service.destinationimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tripbora.dto.destinationimport.DestinationImportIssue;
import com.tripbora.dto.destinationimport.DestinationImportItem;
import com.tripbora.model.DestinationSeason;
import com.tripbora.model.DestinationType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON 일괄등록 파일을 엄격하게 읽는다. 스키마(필드 이름·값 형식·필수값·enum)만 보고,
 * 길이·지역·카테고리 같은 내용 검증은 Validator 와 Master Resolver 가 한다.
 *
 * <ul>
 *   <li>모르는 필드, 같은 필드 두 번, JSON 뒤에 남은 글자는 오류다</li>
 *   <li>문자열은 NFC 로 맞추고 앞뒤 공백을 지운다. 빈 문자열은 null 이다</li>
 *   <li>오류는 처음 하나에서 멈추지 않고 행마다 모두 모은다</li>
 * </ul>
 */
@Component
public class DestinationImportParser {

    private static final Set<String> FILE_FIELDS = Set.of("version", "meta", "destinations");
    private static final Set<String> META_FIELDS = Set.of("generator", "generatedAt", "note");
    private static final Set<String> ITEM_FIELDS = Set.of("key", "type", "season", "region", "latitude", "longitude",
            "external", "categories", "mainCategory", "amenities", "translations", "info", "evidence");
    private static final Set<String> REGION_FIELDS = Set.of("country", "city", "district");
    private static final Set<String> EXTERNAL_FIELDS = Set.of("tourApiContentId", "wikidataQid", "googlePlaceId");
    private static final Set<String> TEXT_FIELDS = Set.of("name", "shortDescription", "description");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("url", "fields");
    private static final Set<String> REGISTER_FIELDS = Set.of("index", "allowPossibleDuplicate", "item");
    private static final String INFO_TRANSLATIONS = "translations";

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 파일 전체. {@code errors} 가 있으면 행을 만들지 않는다. */
    public record ParsedFile(List<DestinationImportIssue> errors, List<ParsedItem> items) {
    }

    /** 여행지 한 건. 스키마 오류가 있어도 읽을 수 있는 값은 item 에 담아 다른 검증을 이어 간다. */
    public record ParsedItem(int index, String path, DestinationImportItem item, List<DestinationImportIssue> errors) {
    }

    /** 등록 요청 한 건. 오류가 있으면 item 은 null 일 수 있다. */
    public record ParsedRegisterRequest(int index, boolean allowPossibleDuplicate, ParsedItem item,
                                        List<DestinationImportIssue> errors) {
    }

    public ParsedFile parseFile(String json) {
        return parseFile(json, false);
    }

    /** 업데이트에서만 누락·null을 기존값 유지로 해석한다. 신규 등록 계약은 그대로 둔다. */
    public ParsedFile parseUpdateFile(String json) {
        return parseFile(json, true);
    }

    private ParsedFile parseFile(String json, boolean update) {
        List<DestinationImportIssue> errors = new ArrayList<>();
        JsonNode root = readTree(json, errors);
        if (root == null) {
            return new ParsedFile(errors, List.of());
        }
        if (!root.isObject()) {
            errors.add(issue("", "최상위 값은 {\"version\": 1, \"destinations\": [...]} 형태의 객체여야 합니다."));
            return new ParsedFile(errors, List.of());
        }
        unknownFields(root, FILE_FIELDS, "", errors);
        JsonNode version = root.get("version");
        if (version == null || version.isNull()) {
            errors.add(issue("version", "필수 값입니다. " + DestinationImportFields.VERSION + "을 넣어 주세요."));
        } else if (!version.isIntegralNumber() || version.asInt() != DestinationImportFields.VERSION) {
            errors.add(issue("version", "지원하는 버전은 " + DestinationImportFields.VERSION + "뿐입니다."));
        }
        JsonNode meta = root.get("meta");
        if (meta != null && !meta.isNull()) {
            if (meta.isObject()) {
                unknownFields(meta, META_FIELDS, "meta", errors);
                for (String field : META_FIELDS) {
                    text(meta.get(field), "meta." + field, errors);
                }
            } else {
                errors.add(issue("meta", "객체여야 합니다."));
            }
        }
        JsonNode destinations = root.get("destinations");
        if (destinations == null || destinations.isNull()) {
            errors.add(issue("destinations", "필수 값입니다."));
        } else if (!destinations.isArray()) {
            errors.add(issue("destinations", "배열이어야 합니다."));
        } else if (destinations.isEmpty()) {
            errors.add(issue("destinations", "여행지가 한 건도 없습니다."));
        } else if (destinations.size() > DestinationImportFields.MAX_DESTINATIONS) {
            errors.add(issue("destinations", "한 번에 최대 " + DestinationImportFields.MAX_DESTINATIONS
                    + "건까지 등록할 수 있습니다. 현재 " + destinations.size() + "건"));
        }
        if (!errors.isEmpty()) {
            return new ParsedFile(errors, List.of());
        }
        List<ParsedItem> items = new ArrayList<>(destinations.size());
        for (int index = 0; index < destinations.size(); index++) {
            items.add(update ? parseUpdateItem(destinations.get(index), index) : parseItem(destinations.get(index), index));
        }
        return new ParsedFile(List.of(), items);
    }

    private ParsedItem parseUpdateItem(JsonNode node, int index) {
        ParsedItem parsed = parseItem(node, index);
        if (node == null || !node.isObject()) return parsed;
        List<DestinationImportIssue> errors = new ArrayList<>(parsed.errors());
        String prefix = parsed.path();
        // 스키마·형식 오류는 유지하고, 업데이트에 필요 없는 신규 등록 필수값 오류만 제외한다.
        errors.removeIf(error -> (error.message().startsWith("필수 값입니다")
                && ((error.path().equals(prefix + ".type") && absent(node.get("type")))
                || (error.path().equals(prefix + ".season") && absent(node.get("season")))
                || (error.path().equals(prefix + ".region") && absent(node.get("region")))))
                || error.path().equals(prefix + ".categories") && (absent(node.get("categories"))
                    || node.path("categories").isArray() && node.path("categories").isEmpty())
                || error.path().equals(prefix + ".translations.ko.name")
                    && error.message().equals("한국어 여행지명은 필수입니다."));
        if (parsed.item().type() == null && !absent(node.get("info"))) {
            errors.add(issue(prefix + ".type", "운영정보를 보낼 때에는 기존 여행지의 type을 함께 지정해 주세요."));
        }
        return new ParsedItem(index, parsed.path(), parsed.item(), List.copyOf(errors));
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    /** 등록 요청 본문: {"index": 3, "allowPossibleDuplicate": false, "item": {...}} */
    public ParsedRegisterRequest parseRegisterRequest(String body) {
        List<DestinationImportIssue> errors = new ArrayList<>();
        JsonNode root = readTree(body, errors);
        if (root == null || !root.isObject()) {
            if (root != null) errors.add(issue("", "등록 요청 형식이 올바르지 않습니다."));
            return new ParsedRegisterRequest(-1, false, null, errors);
        }
        unknownFields(root, REGISTER_FIELDS, "", errors);
        JsonNode indexNode = root.get("index");
        int index = indexNode != null && indexNode.isIntegralNumber() ? indexNode.asInt() : -1;
        if (index < 0 || index >= DestinationImportFields.MAX_DESTINATIONS) {
            errors.add(issue("index", "0~" + (DestinationImportFields.MAX_DESTINATIONS - 1) + " 사이의 순번이어야 합니다."));
        }
        JsonNode allow = root.get("allowPossibleDuplicate");
        if (allow != null && !allow.isNull() && !allow.isBoolean()) {
            errors.add(issue("allowPossibleDuplicate", "true 또는 false 여야 합니다."));
        }
        if (!errors.isEmpty()) {
            return new ParsedRegisterRequest(index, false, null, errors);
        }
        ParsedItem item = parseItem(root.get("item"), index);
        return new ParsedRegisterRequest(index, allow != null && allow.asBoolean(false), item, List.of());
    }

    public ParsedItem parseItem(JsonNode node, int index) {
        String path = "destinations[" + index + "]";
        List<DestinationImportIssue> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add(issue(path, "여행지는 JSON 객체여야 합니다."));
            return new ParsedItem(index, path, null, errors);
        }
        unknownFields(node, ITEM_FIELDS, path, errors);
        String key = text(node.get("key"), path + ".key", errors);
        DestinationType type = enumValue(DestinationType.class, node.get("type"), path + ".type", errors);
        DestinationSeason season = enumValue(DestinationSeason.class, node.get("season"), path + ".season", errors);
        DestinationImportItem.Region region = region(node.get("region"), path + ".region", errors);
        BigDecimal latitude = number(node.get("latitude"), path + ".latitude", errors);
        BigDecimal longitude = number(node.get("longitude"), path + ".longitude", errors);
        DestinationImportItem.External external = external(node.get("external"), path + ".external", errors);
        JsonNode categoryNode = node.get("categories");
        List<String> categories = stringList(categoryNode, path + ".categories", errors);
        if (categoryNode == null || categoryNode.isNull() || (categoryNode.isArray() && categoryNode.isEmpty())) {
            errors.add(issue(path + ".categories", "카테고리를 1개 이상 넣어야 합니다."));
        }
        String mainCategory = text(node.get("mainCategory"), path + ".mainCategory", errors);
        List<String> amenities = stringList(node.get("amenities"), path + ".amenities", errors);
        Map<String, DestinationImportItem.Text> translations =
                translations(node.get("translations"), path + ".translations", errors);
        DestinationImportItem.Info info = info(node.get("info"), type, node.get("type"), path + ".info", errors);
        List<DestinationImportItem.Evidence> evidence = evidence(node.get("evidence"), path + ".evidence", errors);
        DestinationImportItem item = new DestinationImportItem(key, type, season, region, latitude, longitude, external,
                categories, mainCategory, amenities, translations, info, evidence);
        return new ParsedItem(index, path, item, errors);
    }

    /** 문자열을 NFC 로 맞추고 앞뒤 공백을 지운다. 비면 null. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private JsonNode readTree(String json, List<DestinationImportIssue> errors) {
        if (json == null || json.isBlank()) {
            errors.add(issue("", "JSON을 붙여넣거나 파일을 선택해 주세요."));
            return null;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DestinationImportFields.MAX_BYTES) {
            errors.add(issue("", "JSON은 최대 1MB까지 받을 수 있습니다. 현재 " + (bytes + 1023) / 1024 + "KB"));
            return null;
        }
        try {
            return mapper.readTree(json.startsWith("﻿") ? json.substring(1) : json);
        } catch (JsonProcessingException exception) {
            errors.add(issue("", syntaxMessage(exception)));
            return null;
        }
    }

    private String syntaxMessage(JsonProcessingException exception) {
        String original = exception.getOriginalMessage() == null ? "" : exception.getOriginalMessage();
        JsonLocation location = exception.getLocation();
        String where = location == null || location.getLineNr() < 1 ? ""
                : " (" + location.getLineNr() + "번째 줄 " + location.getColumnNr() + "번째 글자)";
        if (original.startsWith("Duplicate field")) {
            return "같은 필드가 두 번 있습니다: " + original.replaceFirst("^Duplicate field ", "") + where;
        }
        return "JSON 문법 오류입니다" + where + ": " + original;
    }

    private void unknownFields(JsonNode node, Set<String> allowed, String path, List<DestinationImportIssue> errors) {
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            String name = property.getKey();
            if (!allowed.contains(name)) {
                errors.add(issue(join(path, name), "알 수 없는 필드입니다. 사용할 수 있는 필드: "
                        + allowed.stream().sorted().collect(Collectors.joining(", "))));
            }
        }
    }

    private String text(JsonNode node, String path, List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(issue(path, "문자열이어야 합니다."));
            return null;
        }
        return clean(node.textValue());
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String path,
                                            List<DestinationImportIssue> errors) {
        String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
        String value = text(node, path, errors);
        if (value == null) {
            if (node == null || node.isNull() || node.isTextual()) {
                errors.add(issue(path, "필수 값입니다. 허용 값: " + allowed));
            }
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        errors.add(issue(path, "허용하지 않는 값입니다: " + value + ". 허용 값: " + allowed));
        return null;
    }

    private BigDecimal number(JsonNode node, String path, List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            errors.add(issue(path, "숫자여야 합니다(따옴표 없이)."));
            return null;
        }
        return node.decimalValue();
    }

    private DestinationImportItem.Region region(JsonNode node, String path, List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            errors.add(issue(path, "필수 값입니다. {\"country\": ..., \"city\": ..., \"district\": ...}"));
            return null;
        }
        if (!node.isObject()) {
            errors.add(issue(path, "객체여야 합니다."));
            return null;
        }
        unknownFields(node, REGION_FIELDS, path, errors);
        JsonNode countryNode = node.get("country");
        String country = text(countryNode, path + ".country", errors);
        if (country == null && (countryNode == null || countryNode.isNull() || countryNode.isTextual())) {
            errors.add(issue(path + ".country", "필수 값입니다."));
        }
        return new DestinationImportItem.Region(country,
                text(node.get("city"), path + ".city", errors),
                text(node.get("district"), path + ".district", errors));
    }

    private DestinationImportItem.External external(JsonNode node, String path, List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(issue(path, "객체여야 합니다."));
            return null;
        }
        unknownFields(node, EXTERNAL_FIELDS, path, errors);
        return new DestinationImportItem.External(
                text(node.get("tourApiContentId"), path + ".tourApiContentId", errors),
                text(node.get("wikidataQid"), path + ".wikidataQid", errors),
                text(node.get("googlePlaceId"), path + ".googlePlaceId", errors));
    }

    /** 문자열 배열. 빈 칸·문자열이 아닌 칸은 오류다. */
    private List<String> stringList(JsonNode node, String path, List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(issue(path, "문자열 배열이어야 합니다."));
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            String itemPath = path + "[" + index + "]";
            String value = text(node.get(index), itemPath, errors);
            if (value == null) {
                if (node.get(index).isNull() || node.get(index).isTextual()) {
                    errors.add(issue(itemPath, "빈 값은 넣을 수 없습니다."));
                }
                continue;
            }
            values.add(value);
        }
        return values;
    }

    private Map<String, DestinationImportItem.Text> translations(JsonNode node, String path,
                                                                 List<DestinationImportIssue> errors) {
        Map<String, DestinationImportItem.Text> translations = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            errors.add(issue(path + ".ko.name", "한국어 여행지명은 필수입니다."));
            return translations;
        }
        if (!node.isObject()) {
            errors.add(issue(path, "언어 코드(ko, en, ja, zh-CN, zh-TW)를 키로 하는 객체여야 합니다."));
            return translations;
        }
        unknownFields(node, Set.copyOf(DestinationImportFields.LANGUAGES), path, errors);
        for (String language : DestinationImportFields.LANGUAGES) {
            JsonNode value = node.get(language);
            String languagePath = path + "." + language;
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isObject()) {
                errors.add(issue(languagePath, "{\"name\", \"shortDescription\", \"description\"} 객체여야 합니다."));
                continue;
            }
            unknownFields(value, TEXT_FIELDS, languagePath, errors);
            translations.put(language, new DestinationImportItem.Text(
                    text(value.get("name"), languagePath + ".name", errors),
                    text(value.get("shortDescription"), languagePath + ".shortDescription", errors),
                    text(value.get("description"), languagePath + ".description", errors)));
        }
        DestinationImportItem.Text korean = translations.get(DestinationImportFields.KOREAN);
        JsonNode koreanName = node.path(DestinationImportFields.KOREAN).get("name");
        if ((korean == null || korean.name() == null)
                && (koreanName == null || koreanName.isNull() || koreanName.isTextual())) {
            errors.add(issue(path + ".ko.name", "한국어 여행지명은 필수입니다."));
        }
        return translations;
    }

    private DestinationImportItem.Info info(JsonNode node, DestinationType type, JsonNode typeNode, String path,
                                            List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(issue(path, "객체여야 합니다."));
            return null;
        }
        if (type == null) {
            if (typeNode != null && !typeNode.isNull()) {
                errors.add(issue(path, "type이 올바르지 않아 운영정보를 확인할 수 없습니다."));
            }
            return null;
        }
        Map<String, DestinationImportFields.Field> fields = DestinationImportFields.infoFieldMap(type);
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String fieldPath = path + "." + entry.getKey();
            if (INFO_TRANSLATIONS.equals(entry.getKey())) {
                infoTranslations(entry.getValue(), type, fields, fieldPath, translations, errors);
                continue;
            }
            DestinationImportFields.Field field = fields.get(entry.getKey());
            if (field == null) {
                errors.add(issue(fieldPath, type.name() + " 운영정보에 없는 필드입니다. 사용할 수 있는 필드: "
                        + String.join(", ", fields.keySet()) + ", translations"));
                continue;
            }
            Object value = infoValue(entry.getValue(), field, fieldPath, errors);
            if (value != null) {
                values.put(field.name(), value);
            }
        }
        return new DestinationImportItem.Info(values, translations);
    }

    private Object infoValue(JsonNode node, DestinationImportFields.Field field, String path,
                             List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (field.kind()) {
            case TEXT, LONG_TEXT, URL -> text(node, path, errors);
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    errors.add(issue(path, "true, false 또는 null 이어야 합니다."));
                    yield null;
                }
                yield node.booleanValue();
            }
            case INTEGER -> {
                if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
                    errors.add(issue(path, "0 이상의 정수여야 합니다."));
                    yield null;
                }
                yield node.longValue();
            }
            case RATING -> number(node, path, errors);
        };
    }

    private void infoTranslations(JsonNode node, DestinationType type, Map<String, DestinationImportFields.Field> fields,
                                  String path, Map<String, Map<String, String>> translations,
                                  List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            errors.add(issue(path, "언어 코드(en, ja, zh-CN, zh-TW)를 키로 하는 객체여야 합니다."));
            return;
        }
        for (Map.Entry<String, JsonNode> language : node.properties()) {
            String languagePath = path + "." + language.getKey();
            if (DestinationImportFields.KOREAN.equals(language.getKey())) {
                errors.add(issue(languagePath, "한국어는 info 원문 필드에 씁니다. translations 에는 en, ja, zh-CN, zh-TW 만 넣습니다."));
                continue;
            }
            if (!DestinationImportFields.INFO_TRANSLATION_LANGUAGES.contains(language.getKey())) {
                errors.add(issue(languagePath, "알 수 없는 언어입니다. 사용할 수 있는 언어: "
                        + String.join(", ", DestinationImportFields.INFO_TRANSLATION_LANGUAGES)));
                continue;
            }
            JsonNode value = language.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isObject()) {
                errors.add(issue(languagePath, "객체여야 합니다."));
                continue;
            }
            Map<String, String> translated = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String fieldPath = languagePath + "." + entry.getKey();
                DestinationImportFields.Field field = fields.get(entry.getKey());
                if (field == null || !field.translatable()) {
                    errors.add(issue(fieldPath, type.name() + " 운영정보에서 번역할 수 없는 필드입니다. 번역 가능한 필드: "
                            + fields.values().stream().filter(DestinationImportFields.Field::translatable)
                            .map(DestinationImportFields.Field::name).collect(Collectors.joining(", "))));
                    continue;
                }
                String text = text(entry.getValue(), fieldPath, errors);
                if (text != null) {
                    translated.put(field.name(), text);
                }
            }
            translations.put(language.getKey(), translated);
        }
    }

    private List<DestinationImportItem.Evidence> evidence(JsonNode node, String path,
                                                          List<DestinationImportIssue> errors) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add(issue(path, "배열이어야 합니다. 예: [{\"url\": \"https://...\", \"fields\": [\"openingHours\"]}]"));
            return List.of();
        }
        List<DestinationImportItem.Evidence> evidence = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode entry = node.get(index);
            String entryPath = path + "[" + index + "]";
            if (entry == null || !entry.isObject()) {
                errors.add(issue(entryPath, "{\"url\", \"fields\"} 객체여야 합니다."));
                continue;
            }
            unknownFields(entry, EVIDENCE_FIELDS, entryPath, errors);
            String url = text(entry.get("url"), entryPath + ".url", errors);
            if (url == null) {
                errors.add(issue(entryPath + ".url", "근거 주소는 필수입니다."));
            }
            evidence.add(new DestinationImportItem.Evidence(url,
                    stringList(entry.get("fields"), entryPath + ".fields", errors)));
        }
        return evidence;
    }

    private static String join(String path, String field) {
        return path == null || path.isEmpty() ? field : path + "." + field;
    }

    private static DestinationImportIssue issue(String path, String message) {
        return new DestinationImportIssue(path, message);
    }
}
