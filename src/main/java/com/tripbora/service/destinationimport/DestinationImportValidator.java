package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationImportIssue;
import com.tripbora.dto.destinationimport.DestinationImportItem;
import com.tripbora.model.DestinationType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JSON 일괄등록 한 건의 내용 검증. 스키마는 파서가, 지역·카테고리·편의시설 매핑은 Master Resolver 가 본다.
 *
 * <ul>
 *   <li>문자열 길이: DB 컬럼 길이(VARCHAR 는 글자 수, TEXT 는 UTF-8 바이트)</li>
 *   <li>*_info 원문 테이블은 utf8mb3 라 4바이트 문자(이모지 등)를 막는다. 번역 테이블은 utf8mb4 다</li>
 *   <li>좌표 쌍·범위, 외부 ID 형식, 국내·해외별 허용 외부 ID</li>
 *   <li>경고: 국내 좌표가 국내 범위를 벗어남, 검증할 수 없는 Place ID, 근거 없는 사실값, 좌표 없음</li>
 * </ul>
 */
@Component
public class DestinationImportValidator {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern TOUR_API_CONTENT_ID = Pattern.compile("[0-9]{1,30}");
    private static final Pattern WIKIDATA_QID = Pattern.compile("Q[1-9][0-9]{0,14}");
    /** Google Place ID 는 영문·숫자·_·- 로 된 긴 문자열이다. 실제 존재 여부는 확인할 수 없다. */
    private static final Pattern GOOGLE_PLACE_ID = Pattern.compile("[A-Za-z0-9_-]{16,255}");
    /** 대한민국을 넉넉히 감싸는 범위. 벗어나면 경고만 한다. */
    private static final BigDecimal KOREA_MIN_LATITUDE = new BigDecimal("33");
    private static final BigDecimal KOREA_MAX_LATITUDE = new BigDecimal("39");
    private static final BigDecimal KOREA_MIN_LONGITUDE = new BigDecimal("124");
    private static final BigDecimal KOREA_MAX_LONGITUDE = new BigDecimal("132");
    private static final BigDecimal MAX_RATING = new BigDecimal("5");

    private static final Map<DestinationType, String> BASE_TABLES = new EnumMap<>(Map.of(
            DestinationType.ATTRACTION, "attraction_info",
            DestinationType.ACCOMMODATION, "accommodation_info",
            DestinationType.RESTAURANTS, "restaurant_info",
            DestinationType.CAFE, "restaurant_info",
            DestinationType.ACTIVITY, "activity_info",
            DestinationType.SHOP, "shop_info"));

    /**
     * @param factFields 값이 들어 있는 사실정보 필드. 미리보기에서 evidence 와 함께 보여준다
     */
    public record Result(List<DestinationImportIssue> errors, List<DestinationImportIssue> warnings,
                         List<String> factFields) {
    }

    public static boolean isTourApiContentId(String value) {
        return value != null && TOUR_API_CONTENT_ID.matcher(value).matches();
    }

    /** @return 대문자로 맞춘 QID. 형식이 틀리면 null */
    public static String normalizeQid(String value) {
        if (value == null) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        return WIKIDATA_QID.matcher(normalized).matches() ? normalized : null;
    }

    public static boolean isGooglePlaceId(String value) {
        return value != null && GOOGLE_PLACE_ID.matcher(value).matches();
    }

    /**
     * @param domestic Master Resolver 가 판단한 국내 여부. 국가를 찾지 못했으면 null 이고 국내·해외 규칙은 건너뛴다
     */
    public Result validate(DestinationImportItem item, String path, Boolean domestic) {
        return validate(item, path, domestic, false);
    }

    public Result validateUpdate(DestinationImportItem item, String path, Boolean domestic) {
        return validate(item, path, domestic, true);
    }

    private Result validate(DestinationImportItem item, String path, Boolean domestic, boolean update) {
        List<DestinationImportIssue> errors = new ArrayList<>();
        List<DestinationImportIssue> warnings = new ArrayList<>();
        List<String> factFields = new ArrayList<>();

        if (item.key() != null && (item.key().length() > DestinationImportFields.KEY_MAX_LENGTH
                || !KEY.matcher(item.key()).matches())) {
            errors.add(issue(path + ".key", "영문·숫자·.·_·- 로 " + DestinationImportFields.KEY_MAX_LENGTH
                    + "자 이내여야 합니다."));
        }
        validateTranslations(item, path + ".translations", errors, update);
        validateCoordinates(item, path, domestic, errors, warnings, factFields);
        validateExternal(item.external(), path + ".external", domestic, errors, warnings, factFields);
        validateInfo(item, path + ".info", errors, factFields);
        validateEvidence(item, path, errors, warnings, factFields);
        return new Result(List.copyOf(errors), List.copyOf(warnings), List.copyOf(factFields));
    }

    /**
     * 파일 안 key 중복. 같은 key 가 다시 나오면 뒤 행마다 오류를 돌려준다. key 가 없는 행은 비교하지 않는다.
     *
     * @return 행 위치 → 오류. 중복이 아닌 행은 들어 있지 않다
     */
    public Map<Integer, DestinationImportIssue> duplicateKeys(List<DestinationImportParser.ParsedItem> items) {
        Map<String, String> firstPaths = new HashMap<>();
        Map<Integer, DestinationImportIssue> duplicates = new HashMap<>();
        for (int position = 0; position < items.size(); position++) {
            DestinationImportParser.ParsedItem parsed = items.get(position);
            String key = parsed.item() == null ? null : parsed.item().key();
            if (key == null) continue;
            String firstPath = firstPaths.putIfAbsent(key, parsed.path());
            if (firstPath != null) {
                duplicates.put(position, issue(parsed.path() + ".key", "같은 key '" + key + "'가 " + firstPath
                        + "에도 있습니다. key 는 파일 안에서 한 번만 쓸 수 있습니다."));
            }
        }
        return duplicates;
    }

    private void validateTranslations(DestinationImportItem item, String path, List<DestinationImportIssue> errors,
                                      boolean update) {
        for (String language : DestinationImportFields.LANGUAGES) {
            DestinationImportItem.Text text = item.translation(language);
            if (text == null) continue;
            String languagePath = path + "." + language;
            maxLength(text.name(), DestinationImportFields.NAME_MAX_LENGTH, languagePath + ".name", errors);
            maxLength(text.shortDescription(), DestinationImportFields.SHORT_DESCRIPTION_MAX_LENGTH,
                    languagePath + ".shortDescription", errors);
            maxBytes(text.description(), DestinationImportFields.TEXT_MAX_BYTES, languagePath + ".description", errors);
            if (!update && !DestinationImportFields.KOREAN.equals(language) && text.name() == null
                    && (text.shortDescription() != null || text.description() != null)) {
                errors.add(issue(languagePath + ".name", "설명을 넣으려면 이 언어의 여행지명도 넣어야 합니다."));
            }
        }
    }

    private void validateCoordinates(DestinationImportItem item, String path, Boolean domestic,
                                     List<DestinationImportIssue> errors, List<DestinationImportIssue> warnings,
                                     List<String> factFields) {
        BigDecimal latitude = item.latitude();
        BigDecimal longitude = item.longitude();
        if (latitude == null && longitude == null) {
            warnings.add(issue(path + ".latitude", "좌표가 없어 중복 판별을 이름·지역으로만 합니다."));
            return;
        }
        if (latitude == null || longitude == null) {
            errors.add(issue(path + (latitude == null ? ".latitude" : ".longitude"),
                    "latitude 와 longitude 는 함께 넣거나 함께 비워야 합니다."));
            return;
        }
        factFields.add("latitude");
        factFields.add("longitude");
        boolean latitudeValid = latitude.abs().compareTo(new BigDecimal("90")) <= 0;
        boolean longitudeValid = longitude.abs().compareTo(new BigDecimal("180")) <= 0;
        if (!latitudeValid) errors.add(issue(path + ".latitude", "-90 ~ 90 사이여야 합니다. 현재 " + latitude.toPlainString()));
        if (!longitudeValid) errors.add(issue(path + ".longitude", "-180 ~ 180 사이여야 합니다. 현재 " + longitude.toPlainString()));
        if (latitudeValid && longitudeValid && Boolean.TRUE.equals(domestic)
                && (latitude.compareTo(KOREA_MIN_LATITUDE) < 0 || latitude.compareTo(KOREA_MAX_LATITUDE) > 0
                || longitude.compareTo(KOREA_MIN_LONGITUDE) < 0 || longitude.compareTo(KOREA_MAX_LONGITUDE) > 0)) {
            warnings.add(issue(path + ".latitude", "국내 여행지인데 좌표가 대한민국 범위를 벗어납니다. 위도·경도가 바뀌지 않았는지 확인해 주세요."));
        }
    }

    private void validateExternal(DestinationImportItem.External external, String path, Boolean domestic,
                                  List<DestinationImportIssue> errors, List<DestinationImportIssue> warnings,
                                  List<String> factFields) {
        String contentId = external.tourApiContentId();
        if (contentId != null) {
            if (!isTourApiContentId(contentId)) {
                errors.add(issue(path + ".tourApiContentId", "숫자만 1~30자리여야 합니다: " + contentId));
            } else if (Boolean.FALSE.equals(domestic)) {
                errors.add(issue(path + ".tourApiContentId", "해외 여행지에는 TourAPI contentId 를 넣을 수 없습니다."));
            }
        }
        String qid = external.wikidataQid();
        if (qid != null) {
            factFields.add("wikidataQid");
            if (normalizeQid(qid) == null) {
                errors.add(issue(path + ".wikidataQid", "Q 뒤에 숫자가 오는 Wikidata QID 여야 합니다(예: Q12345): " + qid));
            } else if (Boolean.TRUE.equals(domestic)) {
                errors.add(issue(path + ".wikidataQid", "국내 여행지에는 Wikidata QID 를 넣을 수 없습니다. TourAPI contentId 만 연결할 수 있습니다."));
            }
        }
        String placeId = external.googlePlaceId();
        if (placeId != null) {
            factFields.add("googlePlaceId");
            if (!isGooglePlaceId(placeId)) {
                errors.add(issue(path + ".googlePlaceId", "영문·숫자·_·- 로 된 16~"
                        + DestinationImportFields.GOOGLE_PLACE_ID_MAX_LENGTH + "자여야 합니다."));
            } else if (Boolean.TRUE.equals(domestic)) {
                errors.add(issue(path + ".googlePlaceId", "국내 여행지에는 Google Place ID 를 넣을 수 없습니다."));
            } else if (Boolean.FALSE.equals(domestic)) {
                warnings.add(issue(path + ".googlePlaceId",
                        "외부 검증되지 않은 Place ID 입니다. 등록 전에 지도에서 같은 장소인지 확인해 주세요."));
            }
        }
    }

    private void validateInfo(DestinationImportItem item, String path, List<DestinationImportIssue> errors,
                              List<String> factFields) {
        DestinationType type = item.type();
        if (type == null) return;
        Map<String, DestinationImportFields.Field> fields = DestinationImportFields.infoFieldMap(type);
        DestinationImportItem.Info info = item.info();
        for (Map.Entry<String, Object> entry : info.values().entrySet()) {
            DestinationImportFields.Field field = fields.get(entry.getKey());
            if (field == null) continue;
            String fieldPath = path + "." + field.name();
            Object value = entry.getValue();
            if (field.fact()) factFields.add(field.name());
            switch (field.kind()) {
                case TEXT -> {
                    maxLength((String) value, field.maxLength(), fieldPath, errors);
                    rejectFourByte((String) value, BASE_TABLES.get(type), fieldPath, errors);
                }
                case LONG_TEXT -> {
                    maxBytes((String) value, field.maxLength(), fieldPath, errors);
                    rejectFourByte((String) value, BASE_TABLES.get(type), fieldPath, errors);
                }
                case URL -> {
                    maxLength((String) value, field.maxLength(), fieldPath, errors);
                    if (!isHttpUrl((String) value)) {
                        errors.add(issue(fieldPath, "http:// 또는 https:// 로 시작하는 주소여야 합니다."));
                    }
                }
                case RATING -> {
                    BigDecimal rating = (BigDecimal) value;
                    if (rating.signum() < 0 || rating.compareTo(MAX_RATING) > 0
                            || rating.stripTrailingZeros().scale() > 1) {
                        errors.add(issue(fieldPath, "0.0 ~ 5.0 사이, 소수 첫째 자리까지여야 합니다. 현재 " + rating.toPlainString()));
                    }
                }
                case BOOLEAN, INTEGER -> {
                    // 형식은 파서가 이미 확인했다.
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> language : info.translations().entrySet()) {
            for (Map.Entry<String, String> entry : language.getValue().entrySet()) {
                DestinationImportFields.Field field = fields.get(entry.getKey());
                if (field == null) continue;
                String fieldPath = path + ".translations." + language.getKey() + "." + field.name();
                if (field.kind() == DestinationImportFields.Kind.LONG_TEXT) {
                    maxBytes(entry.getValue(), field.maxLength(), fieldPath, errors);
                } else {
                    maxLength(entry.getValue(), field.maxLength(), fieldPath, errors);
                }
            }
        }
    }

    private void validateEvidence(DestinationImportItem item, String path, List<DestinationImportIssue> errors,
                                  List<DestinationImportIssue> warnings, List<String> factFields) {
        for (int index = 0; index < item.evidence().size(); index++) {
            String url = item.evidence().get(index).url();
            String urlPath = path + ".evidence[" + index + "].url";
            if (url == null) continue;
            if (url.length() > DestinationImportFields.EVIDENCE_URL_MAX_LENGTH) {
                errors.add(issue(urlPath, "최대 " + DestinationImportFields.EVIDENCE_URL_MAX_LENGTH + "자, 현재 " + url.length() + "자"));
            } else if (!isHttpUrl(url)) {
                errors.add(issue(urlPath, "http:// 또는 https:// 로 시작하는 주소여야 합니다."));
            }
        }
        if (!factFields.isEmpty() && item.evidence().isEmpty()) {
            warnings.add(issue(path + ".evidence", "사실정보(" + String.join(", ", factFields)
                    + ")가 있지만 근거(evidence)가 없습니다. 실제 출처에서 확인한 값인지 검수해 주세요."));
        }
    }

    private void maxLength(String value, int max, String path, List<DestinationImportIssue> errors) {
        if (value == null) return;
        int length = value.codePointCount(0, value.length());
        if (length > max) {
            errors.add(issue(path, "최대 " + max + "자, 현재 " + length + "자"));
        }
    }

    private void maxBytes(String value, int max, String path, List<DestinationImportIssue> errors) {
        if (value == null) return;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > max) {
            NumberFormat format = NumberFormat.getIntegerInstance(Locale.KOREA);
            errors.add(issue(path, "최대 " + format.format(max) + "바이트, 현재 " + format.format(bytes) + "바이트"));
        }
    }

    /** utf8mb3 컬럼은 BMP 밖의 문자(이모지·일부 한자 확장)를 저장하지 못한다. */
    private void rejectFourByte(String value, String table, String path, List<DestinationImportIssue> errors) {
        if (value == null) return;
        value.codePoints().filter(codePoint -> codePoint > 0xFFFF).findFirst().ifPresent(codePoint ->
                errors.add(issue(path, "이모지 등 4바이트 문자(" + new String(Character.toChars(codePoint))
                        + ")는 저장할 수 없습니다. " + table + " 원문 컬럼이 utf8mb3 입니다.")));
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return true;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (Exception exception) {
            return false;
        }
    }

    private static DestinationImportIssue issue(String path, String message) {
        return new DestinationImportIssue(path, message);
    }
}
