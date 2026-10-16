package com.tripbora.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CourseGoogleMapsUrlTest {
    private static final String BASE = "https://www.google.com/maps/search/?api=1&query=";

    @Test
    void linkedPlaceIdTakesPriorityOverCoordinatesAndUsesCurrentDisplayName() {
        var stop = linked("難波駅", "大阪市");
        stop.setGooglePlaceId("ChIJ_test+&/id");
        coordinates(stop);
        assertThat(decoded(stop)).isEqualTo(BASE + "難波駅&query_place_id=ChIJ_test+&/id");
        assertThat(stop.getGoogleMapsUrl()).contains("query_place_id=ChIJ_test%2B%26%2Fid");
        stop.setName("Namba Station");
        assertThat(decoded(stop)).isEqualTo(BASE + "Namba Station&query_place_id=ChIJ_test+&/id");
    }

    @Test
    void linkedNameAndActualRegionTakePriorityOverCoordinatesWithoutCountryIds() {
        var stop = linked("갑사", "공주시");
        coordinates(stop);
        assertThat(decoded(stop)).isEqualTo(BASE + "갑사 공주시");
        stop.setRegionName(null);
        assertThat(decoded(stop)).isEqualTo(BASE + "갑사");
    }

    @Test
    void directUsesNameAndAddressAndIgnoresLinkedMetadata() {
        var stop = new CourseStopDto();
        stop.setName("롯데호텔 서울");
        stop.setAddress(" 서울 중구 을지로 30 ");
        stop.setGooglePlaceId("not-a-linked-item");
        stop.setRegionName("not-a-direct-address");
        coordinates(stop);
        assertThat(decoded(stop)).isEqualTo(BASE + "롯데호텔 서울 서울 중구 을지로 30");
        stop.setAddress(" ");
        assertThat(decoded(stop)).isEqualTo(BASE + "롯데호텔 서울");
        stop.setName(null);
        stop.setAddress("서울 중구 을지로 30");
        assertThat(decoded(stop)).isEqualTo(BASE + "서울 중구 을지로 30");
    }

    @Test
    void coordinatesAreLastFallbackAndMissingInformationHidesLink() {
        var stop = new CourseStopDto();
        assertThat(stop.getGoogleMapsUrl()).isNull();
        stop.setLatitude(BigDecimal.ZERO);
        assertThat(stop.getGoogleMapsUrl()).isNull();
        stop.setLongitude(BigDecimal.ZERO);
        assertThat(decoded(stop)).isEqualTo(BASE + "0,0");
        stop.setDestinationId(10L);
        stop.setName("이름 없는 여행지");
        stop.setRegionName("공주시");
        assertThat(decoded(stop)).isEqualTo(BASE + "0,0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"갑사", "경복궁", "浅草寺", "東京駅", "台北101", "北京故宫", "A & B + C/#?= 서울"})
    void encodesUnicodeSpacesAndReservedCharactersAsOneQueryValue(String name) {
        var stop = new CourseStopDto();
        stop.setName(name);
        String url = stop.getGoogleMapsUrl();
        assertThat(url).doesNotContain(" ", "#");
        assertThat(url.substring(BASE.length())).doesNotContain("&", "=", "?");
        assertThat(decoded(stop)).isEqualTo(BASE + name);
    }

    private CourseStopDto linked(String name, String region) {
        var stop = new CourseStopDto();
        stop.setDestinationId(1L);
        stop.setName(name);
        stop.setRegionName(region);
        return stop;
    }

    private void coordinates(CourseStopDto stop) {
        stop.setLatitude(new BigDecimal("37.5"));
        stop.setLongitude(new BigDecimal("127.1"));
    }

    private String decoded(CourseStopDto stop) {
        return URLDecoder.decode(stop.getGoogleMapsUrl(), StandardCharsets.UTF_8);
    }
}
