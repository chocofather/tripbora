package com.tripbora.repository;

import com.tripbora.dto.DestinationSearchResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCountryContractTest {

    @Test
    void courseDestinationsResolveCountriesInOneRecursiveBatch() throws IOException {
        String mapper = resource("/mapper/CourseMapper.xml");
        String query = between(mapper, "<select id=\"findDestinationCountries\"", "</select>");

        assertThat(query)
                .contains("WITH RECURSIVE destination_regions")
                .contains("<foreach collection=\"destinationIds\"")
                .contains("JOIN country_categories parent ON parent.id = destination_regions.parent_id")
                .contains("SUBSTRING_INDEX(region.code, '-', 1) AS country_code")
                .contains("region_id AS countryId")
                .doesNotContain("= 7", "= 8");
    }

    @Test
    void courseInsertStoresResolvedCountryId() throws IOException {
        String mapper = resource("/mapper/CourseMapper.xml");
        String insert = between(mapper, "<insert id=\"insertCourse\"", "</insert>");

        assertThat(insert)
                .contains("title, title_source_language, user_id, country_id")
                .contains("content, content_source_language")
                .contains("#{title}, #{titleSourceLanguage}, #{userId}, #{countryId}")
                .contains("#{content}, #{contentSourceLanguage}");
    }

    @Test
    void courseUpdateLoadsAndStoresCountryBeforeReplacingStops() throws IOException {
        String mapper = resource("/mapper/CourseMapper.xml");
        String editQuery = between(mapper, "<select id=\"findActiveCourse\"", "</select>");
        String update = between(mapper, "<update id=\"updateCourse\"", "</update>");
        String stops = between(mapper, "<select id=\"findCourseStops\"", "</select>");

        assertThat(editQuery)
                .contains("c.country_id")
                .contains("country.region_name AS country_name")
                .contains("LEFT JOIN country_categories country ON country.id = c.country_id");
        assertThat(update).contains("country_id = #{countryId}");
        assertThat(stops)
                .contains("course.country_id AS country_id")
                .contains("country.region_name AS country_name")
                .contains("ORDER BY cd.visit_order ASC");
    }

    @Test
    void destinationSearchProvidesCountryDataForCreateUx() throws IOException {
        DestinationSearchResultDto result = new DestinationSearchResultDto();
        result.setCountryId(8L);
        result.setCountryName("일본");
        assertThat(result.getCountryId()).isEqualTo(8L);
        assertThat(result.getCountryName()).isEqualTo("일본");

        String mapper = resource("/mapper/DestinationSearchMapper.xml");
        assertThat(mapper)
                .contains("property=\"countryId\" column=\"country_id\"")
                .contains("property=\"countryName\" column=\"country_name\"")
                .contains("LEFT JOIN destination_countries dc ON dc.destination_id = d.id");
    }

    @Test
    void courseCountryListExcludesDomesticRegionsWithoutUsingDepth() throws IOException {
        String mapper = resource("/mapper/CountryCategoryMapper.xml");
        String query = between(mapper, "<select id=\"selectCourseCountries\"", "</select>");

        assertThat(query)
                .contains("country.parent_id IS NULL")
                .contains("country_child.code LIKE CONCAT(country.code, '-%')")
                .contains("country.parent_id IS NOT NULL")
                .contains("parent.parent_id IS NULL")
                .contains("country.code NOT LIKE '%-%'")
                .contains("CASE WHEN country.parent_id IS NULL THEN 0 ELSE 1 END")
                .doesNotContain("depth = 2", "region_name = '대한민국'");
    }

    @Test
    void currentHierarchySelectsCountriesButNotContinentsRegionsOrCities() throws IOException {
        JsonNode categories = new ObjectMapper().readTree(resource("/json/country_categories.json"));
        Map<Long, JsonNode> byId = new HashMap<>();
        categories.forEach(category -> byId.put(category.get("id").asLong(), category));

        Set<String> selectedNames = new HashSet<>();
        categories.forEach(country -> {
            JsonNode parentIdNode = country.get("parent_id");
            boolean root = parentIdNode == null || parentIdNode.isNull();
            String code = country.get("code").asText();
            boolean rootCountry = root && hasCountryPrefixedChild(categories, country.get("id").asLong(), code);
            boolean overseasCountry = !root
                    && isRoot(byId.get(parentIdNode.asLong()))
                    && !code.contains("-");
            if (rootCountry || overseasCountry) selectedNames.add(country.get("region_name").asText());
        });

        assertThat(selectedNames)
                .contains("대한민국", "일본", "대만", "프랑스", "미국")
                .doesNotContain("아시아", "유럽", "남아메리카", "북아메리카", "아프리카", "오세아니아", "중동")
                .doesNotContain("서울", "경기", "부산")
                .doesNotContain("도쿄", "오사카", "교토");
    }

    @Test
    void destinationSearchCountryFilterIsOptionalAndServerSide() throws IOException {
        String mapper = resource("/mapper/DestinationSearchMapper.xml");
        String query = between(mapper, "<sql id=\"searchQuery\"", "</sql>");

        assertThat(query)
                .contains("<if test=\"countryId != null\">")
                .contains("AND dc.country_id = #{countryId}");
    }

    @Test
    void createAndEditUseItemCountriesAndUnrestrictedDestinationSearch() throws IOException {
        String writeTemplate = resource("/templates/course/write.html");
        String editTemplate = resource("/templates/course/edit.html");
        String script = resource("/static/js/course-write.js");
        String schedule = resource("/templates/course/fragments/schedule-input.html");
        for (String page : new String[]{writeTemplate, editTemplate}) {
            assertThat(page).contains("course/fragments/schedule-input :: scheduleInput");
            String template = page + schedule;
            assertThat(template)
                    .contains("${courseCountries}", "data-course-country")
                    .contains("data-country-id=${country.id}", "data-country-name=${country.regionName}")
                    .contains("data-country-code=${country.code}")
                    .contains("data-country-id=${stop.countryId}", "data-country-name=${stop.countryName}")
                    .doesNotContain("data-country-restriction", "data-country-scope", "name=\"countryId\"", "overseas-country-input");
        }
        assertThat(script)
                .contains("countrySelect.dataset.directField = 'countryId'")
                .contains("if (!countryOption(item.countryId))")
                .contains("['countryId', 'itemType', 'address', 'latitude', 'longitude']")
                .contains("item.countryCode = selected?.dataset.countryCode")
                .doesNotContain("selectedCountryId !== destinationCountryId")
                .doesNotContain("requestCountryChange", "days.forEach(day => day.items.splice(0, day.items.length))");
    }

    @Test
    void countryFilterSupportsRegularNamesAndHangulInitials() throws IOException {
        String script = resource("/static/js/country-combobox.js");

        assertThat(script)
                .contains("function extractHangulInitials(value)")
                .contains("'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ'")
                .contains("codePoint < 0xAC00 || codePoint > 0xD7A3")
                .contains("Math.floor((codePoint - 0xAC00) / 588)")
                .contains(".trim().toLocaleLowerCase('ko-KR')")
                .contains("!normalizedName.includes(keyword)")
                .contains("!extractHangulInitials(normalizedName).includes(keyword)");
    }

    private boolean hasCountryPrefixedChild(JsonNode categories, long parentId, String countryCode) {
        for (JsonNode child : categories) {
            JsonNode childParent = child.get("parent_id");
            if (childParent != null && !childParent.isNull()
                    && childParent.asLong() == parentId
                    && child.get("code").asText().startsWith(countryCode + "-")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRoot(JsonNode category) {
        return category != null && (category.get("parent_id") == null || category.get("parent_id").isNull());
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
