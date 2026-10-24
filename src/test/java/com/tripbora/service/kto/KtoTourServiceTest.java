package com.tripbora.service.kto;

import com.tripbora.dto.kto.KtoTourAutofillResponse;
import com.tripbora.dto.kto.KtoTourSearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KtoTourServiceTest {

    @Test
    void bulkImportKeepsFirstImageWhenAdditionalImageApiFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/detailCommon2"))
                .andRespond(withSuccess(detailBody("""
                        "title":"경복궁", "firstimage":"https://tong.visitkorea.or.kr/cms/resource/main.jpg",
                        "firstimage2":"https://tong.visitkorea.or.kr/cms/resource/thumb.jpg", "cpyrhtDivCd":"Type3"
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {
            assertThat(request.getURI().getPath()).endsWith("/detailImage2");
            assertDecodedQuery(request.getURI(), "numOfRows", "20");
            assertDecodedQuery(request.getURI(), "pageNo", "1");
            assertDecodedQuery(request.getURI(), "imageYN", "Y");
        }).andRespond(withServerError());

        assertThat(service.getBulkImportImages("126508"))
                .extracting("imageUrl", "copyrightDivisionCode", "main")
                .containsExactly(tuple("https://tong.visitkorea.or.kr/cms/resource/main.jpg", "Type3", true));
        server.verify();
    }

    @Test
    void bulkImportLimitsAdditionalCandidatesEvenIfApiReturnsMoreThanRequested() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        server.expect(request -> { }).andRespond(withSuccess(detailBody("""
                "firstimage":"https://tong.visitkorea.or.kr/cms/resource/main.jpg", "cpyrhtDivCd":"Type1"
                """), MediaType.APPLICATION_JSON));
        String[] images = new String[101];
        for (int i = 0; i < images.length; i++) {
            images[i] = """
                    {"originimgurl":"https://tong.visitkorea.or.kr/cms/resource/%d.jpg", "cpyrhtDivCd":"Type1"}
                    """.formatted(i);
        }
        String response = """
                {"response":{"header":{"resultCode":"0000"},"body":{"totalCount":101,
                "items":{"item":[%s]}}}}
                """.formatted(String.join(",", images));
        server.expect(request -> assertDecodedQuery(request.getURI(), "numOfRows", "20"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        var candidates = service.getBulkImportImages("126508");
        assertThat(candidates).hasSize(21);
        assertThat(candidates.get(20).imageUrl()).endsWith("/19.jpg");
        server.verify();
    }

    @Test
    void manualImportRetainsOneHundredCandidateRequestAndItsErrorPolicy() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        server.expect(request -> { }).andRespond(withSuccess(detailBody("""
                "firstimage":"https://tong.visitkorea.or.kr/cms/resource/main.jpg", "cpyrhtDivCd":"Type1"
                """), MediaType.APPLICATION_JSON));
        server.expect(request -> assertDecodedQuery(request.getURI(), "numOfRows", "100"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.getImportableImages("126508")).isInstanceOf(KtoTourApiException.class);
        server.verify();
    }

    @Test
    void searchesKorService2AndReturnsOnlySupportedDestinationCandidates() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String json = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "numOfRows":10,"pageNo":1,"totalCount":3,"items":{"item":[
                    {"contentid":"126508","contenttypeid":"12","title":"창덕궁","addr1":"서울 종로구 율곡로 99",
                     "addr2":"","mapx":"126.991","mapy":"37.579"},
                    {"contentid":"culture-1","contenttypeid":"14","title":"창덕궁 문화관","addr1":"서울 종로구",
                     "mapx":"126.992","mapy":"37.580"},
                    {"contentid":"food-1","contenttypeid":"39","title":"창덕궁 식당","addr1":"서울 종로구"},
                    {"contentid":"stay-1","contenttypeid":"32","title":"창덕궁 호텔","addr1":"서울 종로구"},
                    {"contentid":"festival-1","contenttypeid":"15","title":"창덕궁 축제","addr1":"서울 종로구"}
                  ]}}}}
                """;

        server.expect(request -> assertSearchRequest(request.getURI(), "창덕궁", "sample-key"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        KtoTourSearchResponse response = service.search("창덕궁", 1, 10);

        assertThat(response.pageNo()).isEqualTo(1);
        assertThat(response.numOfRows()).isEqualTo(10);
        assertThat(response.totalCount()).isEqualTo(3);
        // 음식점(39)·숙박(32)도 검색 결과에 포함되고, 지원하지 않는 타입(15)만 걸러진다.
        assertThat(response.items()).extracting("contentId")
                .containsExactly("126508", "culture-1", "food-1", "stay-1");
        assertThat(response.items()).extracting("contentTypeId", "contentTypeName")
                .contains(tuple("39", "음식점"), tuple("32", "숙박"));
        assertThat(response.items().get(0)).satisfies(item -> {
            assertThat(item.contentTypeId()).isEqualTo("12");
            assertThat(item.contentTypeName()).isEqualTo("관광지");
            assertThat(item.title()).isEqualTo("창덕궁");
            assertThat(item.address()).isEqualTo("서울 종로구 율곡로 99");
            assertThat(item.longitude()).isEqualTo("126.991");
            assertThat(item.latitude()).isEqualTo("37.579");
        });
        server.verify();
    }

    /** 관광사진 검색: 제목순 결과에서 관광지 계열·제목 일치 콘텐츠를 먼저 골라 이미지와 저작권 코드를 그대로 준다. */
    @Test
    void photoSearchPicksRelevantContentsAndKeepsCopyrightCodesPerImage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String image = "https://tong.visitkorea.or.kr/cms/resource/";
        String search = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "numOfRows":100,"pageNo":1,"totalCount":5,"items":{"item":[
                    {"contentid":"shop","contenttypeid":"38","title":"경복궁 기념품점",
                     "firstimage":"%1$sshop.jpg","cpyrhtDivCd":"Type3"},
                    {"contentid":"festival","contenttypeid":"15","title":"경복궁 축제",
                     "firstimage":"%1$sfestival.jpg","cpyrhtDivCd":"Type1"},
                    {"contentid":"geonchung","contenttypeid":"12","title":"경복궁 건청궁",
                     "firstimage":"%1$sgeonchung.jpg","cpyrhtDivCd":"Type1"},
                    {"contentid":"126508","contenttypeid":"12","title":"경복궁",
                     "firstimage":"%1$smain.jpg","cpyrhtDivCd":"Type1"}
                  ]}}}}
                """.formatted(image);
        String details = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "numOfRows":100,"pageNo":1,"totalCount":2,"items":{"item":[
                    {"contentid":"126508","imgname":"경복궁_1","originimgurl":"%1$smain.jpg","cpyrhtDivCd":"Type1"},
                    {"contentid":"126508","imgname":"경복궁_2","originimgurl":"%1$sextra.jpg","cpyrhtDivCd":"Type3"}
                  ]}}}}
                """.formatted(image);

        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/KorService2/searchKeyword2");
            assertDecodedQuery(request.getURI(), "keyword", "경복궁");
            assertDecodedQuery(request.getURI(), "arrange", "O");
            assertDecodedQuery(request.getURI(), "numOfRows", "100");
        }).andRespond(withSuccess(search, MediaType.APPLICATION_JSON));
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/KorService2/detailImage2");
            assertDecodedQuery(request.getURI(), "contentId", "126508");
        }).andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        // 한 콘텐츠의 추가이미지 실패는 그 콘텐츠의 대표이미지와 다른 콘텐츠를 막지 않는다.
        server.expect(request -> assertDecodedQuery(request.getURI(), "contentId", "geonchung"))
                .andRespond(withServerError());

        var contents = service.searchContentImages("경복궁", 2);

        assertThat(contents).extracting("contentId", "title")
                .containsExactly(tuple("126508", "경복궁"), tuple("geonchung", "경복궁 건청궁"));
        assertThat(contents.get(0).images()).extracting("imageUrl", "copyrightDivisionCode", "main")
                .containsExactly(
                        tuple(image + "main.jpg", "Type1", true),
                        tuple(image + "extra.jpg", "Type3", false));
        assertThat(contents.get(1).images()).extracting("imageUrl", "copyrightDivisionCode")
                .containsExactly(tuple(image + "geonchung.jpg", "Type1"));
        server.verify();
    }

    @Test
    void keepsEncodedGeneralKeyUnchangedInTheFinalRawQuery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String encodedKey = "synthetic%2Bkey%2Fwith%3Dreserved";
        KtoTourService service = service(builder, encodedKey);

        server.expect(request -> assertSearchRequest(request.getURI(), "창덕궁", encodedKey))
                .andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));

        assertThat(service.search("창덕궁", 1, 10).items()).isEmpty();
        server.verify();
    }

    @Test
    void handlesEmptySearchItemsWithoutFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        server.expect(request -> { }).andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));

        assertThat(service.search("없는 장소", 1, 10).items()).isEmpty();
        server.verify();
    }

    /** JSON 일괄등록의 contentId 확인은 공통정보 한 번만 부르고, 없는 contentId 는 empty 로 돌려준다. */
    @Test
    void findTitleCallsOnlyDetailCommonAndTellsMissingContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String found = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"126508","contenttypeid":"12","title":"창덕궁<br>"
                }]}}}}
                """;
        String missing = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":""}}}
                """;
        server.expect(request -> assertDetailRequest(request.getURI(), "/detailCommon2", false))
                .andRespond(withSuccess(found, MediaType.APPLICATION_JSON));
        server.expect(request -> assertDetailRequest(request.getURI(), "/detailCommon2", false))
                .andRespond(withSuccess(missing, MediaType.APPLICATION_JSON));

        assertThat(service.findTitle("126508")).contains("창덕궁");
        assertThat(service.findTitle("126508")).isEmpty();
        // detailIntro2 는 부르지 않는다.
        server.verify();
    }

    @Test
    void mergesCommonAndAttractionIntroAndSanitizesHtml() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String common = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"126508","contenttypeid":"12","title":"창덕궁",
                  "overview":"조선의 궁궐<br>두 번째 <b>문장</b><script>alert(1)</script>",
                  "addr1":"서울 종로구 율곡로 99","addr2":"와룡동", "mapx":"126.991", "mapy":"37.579",
                  "homepage":"<a href=\\\"https://royal.khs.go.kr/\\\">공식 홈페이지</a>","tel":"02-3668-2300"
                }]}}}}
                """;
        String intro = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"126508","contenttypeid":"12",
                  "restdate":"매주 월요일(공휴일인 경우 다음날 휴관), 1월 1일, 설날 및 추석 당일, 기관 사정에 따른 임시 휴관일",
                  "usetime":"3월~10월 09:00~18:00<br>11월~2월 09:00~17:00<br>입장 마감은 관람 종료 1시간 전",
                  "usefee":"개인 성인 3,000원, 청소년 1,500원, 어린이 무료<br>단체 성인 2,400원, 청소년 1,200원",
                  "infocenter":"안내소 02-0000-0000",
                  "expguide":"한국어 정규 해설과 외국어 해설은 운영 시간이 다르며 사전 예약이 필요할 수 있습니다. 현장 운영 상황에 따라 일부 해설 일정이 변경될 수 있습니다."
                }]}}}}
                """;

        server.expect(request -> assertDetailRequest(request.getURI(), "/detailCommon2", false))
                .andRespond(withSuccess(common, MediaType.APPLICATION_JSON));
        server.expect(request -> assertDetailRequest(request.getURI(), "/detailIntro2", true))
                .andRespond(withSuccess(intro, MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("126508", "12");

        assertThat(result.contentId()).isEqualTo("126508");
        assertThat(result.contentTypeId()).isEqualTo("12");
        assertThat(result.title()).isEqualTo("창덕궁");
        assertThat(result.address()).isEqualTo("서울 종로구 율곡로 99 와룡동");
        assertThat(result.longitude()).isEqualTo("126.991");
        assertThat(result.latitude()).isEqualTo("37.579");
        assertThat(result.overview()).isEqualTo("조선의 궁궐\n두 번째 문장");
        assertThat(result.homepageUrl()).isEqualTo("https://royal.khs.go.kr/");
        assertThat(result.contactNumber()).isEqualTo("02-3668-2300");
        assertThat(result.closedDays()).isEqualTo(
                "매주 월요일(공휴일인 경우 다음날 휴관), 1월 1일, 설날 및 추석 당일, 기관 사정에 따른 임시 휴관일");
        assertThat(result.openingHours()).isEqualTo(
                "3월~10월 09:00~18:00\n11월~2월 09:00~17:00\n입장 마감은 관람 종료 1시간 전");
        assertThat(result.admissionFee()).isEqualTo(
                "개인 성인 3,000원, 청소년 1,500원, 어린이 무료\n단체 성인 2,400원, 청소년 1,200원");
        assertThat(result.guide()).isEqualTo(
                "한국어 정규 해설과 외국어 해설은 운영 시간이 다르며 사전 예약이 필요할 수 있습니다. 현장 운영 상황에 따라 일부 해설 일정이 변경될 수 있습니다.");
        server.verify();
    }

    @Test
    void normalizesContentTypeSpecificIntroFieldsAndToleratesMissingValues() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String common = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"culture-1","contenttypeid":"14","title":"문화시설","homepage":"javascript:alert(1)"
                }]}}}}
                """;
        String intro = """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "restdateculture":"화요일","usetimeculture":"10:00~17:00","usefee":"무료",
                  "infocenterculture":"02-1111-2222"
                }]}}}}
                """;
        server.expect(request -> { }).andRespond(withSuccess(common, MediaType.APPLICATION_JSON));
        server.expect(request -> { }).andRespond(withSuccess(intro, MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("culture-1", "14");

        assertThat(result.address()).isNull();
        assertThat(result.longitude()).isNull();
        assertThat(result.latitude()).isNull();
        assertThat(result.overview()).isNull();
        assertThat(result.homepageUrl()).isNull();
        assertThat(result.closedDays()).isEqualTo("화요일");
        assertThat(result.openingHours()).isEqualTo("10:00~17:00");
        assertThat(result.admissionFee()).isEqualTo("무료");
        assertThat(result.contactNumber()).isEqualTo("02-1111-2222");
        server.verify();
    }

    @Test
    void rejectsMissingKeyBeforeAnyHttpCallAndUsesSafeFailures() {
        KtoTourService missingKeyService = service(RestClient.builder(), "  ");
        assertThatThrownBy(() -> missingKeyService.search("창덕궁", 1, 10))
                .isInstanceOf(KtoTourApiException.class)
                .hasMessage("TourAPI 인증키가 설정되지 않았습니다.");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String key = "synthetic%2Bsecret%3D";
        KtoTourService service = service(builder, key);
        server.expect(request -> { }).andRespond(withServerError());

        assertThatThrownBy(() -> service.search("창덕궁", 1, 10))
                .isInstanceOf(KtoTourApiException.class)
                .hasMessage("관광정보를 불러오지 못했습니다.")
                .hasMessageNotContaining(key);
        server.verify();
    }

    @Test
    void restaurantDetailFillsMenuHoursClosedDaysAndFallsBackToTheInformationCenter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");

        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","title":"가야밀면돼지국밥",
                        "addr1":"경기 고양시","tel":""
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","firstmenu":"밀면",
                        "opentimefood":"11:00~22:00","restdatefood":"연중무휴",
                        "infocenterfood":"031-915-1459","treatmenu":"돼지국밥 / 비빔면 등",
                        "parkingfood":"가능","packing":"불가능","seat":"","reservationfood":""
                        """), MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("2891928", "39");

        assertThat(result.mainMenu()).isEqualTo("밀면");
        assertThat(result.openingHours()).isEqualTo("11:00~22:00");
        assertThat(result.closedDays()).isEqualTo("연중무휴");
        // tel 이 비어 있으면 infocenterfood 로 대체한다 (기존 계약 유지)
        assertThat(result.contactNumber()).isEqualTo("031-915-1459");
        // 이번 단계에서는 숙박 전용 값이 채워지지 않는다
        assertThat(result.checkinTime()).isNull();
        assertThat(result.roomCount()).isNull();
        server.verify();
    }

    @Test
    void restaurantDetailPrefersTheCommonTelOverTheInformationCenter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");

        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","tel":"031-000-0000"
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","infocenterfood":"031-915-1459"
                        """), MediaType.APPLICATION_JSON));

        assertThat(service.getDetail("2891928", "39").contactNumber()).isEqualTo("031-000-0000");
        server.verify();
    }

    @Test
    void accommodationDetailFillsCheckInOutRoomCountAndRoomType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");
        String longRoomType = "크리스탈 더블 / 크리스탈 트윈 / 사파이어 더블 / 사파이어 디럭스 / "
                + "사파이어 패밀리 더블 / 사파이어 트리플 / 스위트";

        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2650614","contenttypeid":"32","title":"강남아르누보씨티호텔",
                        "addr1":"서울 서초구","tel":""
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2650614","contenttypeid":"32","checkintime":"15:00",
                        "checkouttime":"11:00","roomcount":"192실","roomtype":"%s",
                        "infocenterlodging":"02-580-7500","parkinglodging":"가능"
                        """.formatted(longRoomType)), MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("2650614", "32");

        assertThat(result.checkinTime()).isEqualTo("15:00");
        assertThat(result.checkoutTime()).isEqualTo("11:00");
        assertThat(result.roomCount()).isEqualTo(192);
        assertThat(result.contactNumber()).isEqualTo("02-580-7500");
        // room_type 이 varchar(255) 이므로 32자를 넘는 원문도 자르지 않고 그대로 보존한다
        assertThat(longRoomType.length()).isGreaterThan(32);
        assertThat(result.roomType()).isEqualTo(longRoomType);
        assertThat(result.mainMenu()).isNull();
        server.verify();
    }

    @Test
    void unreadableRoomCountBecomesNullInsteadOfFailing() {
        for (String roomCount : new String[]{"", "객실 정보 없음", "많음"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KtoTourService service = service(builder, "sample-key");

            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"2650614","contenttypeid":"32","tel":"02-580-7500"
                            """), MediaType.APPLICATION_JSON));
            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"2650614","contenttypeid":"32","roomcount":"%s"
                            """.formatted(roomCount)), MediaType.APPLICATION_JSON));

            assertThat(service.getDetail("2650614", "32").roomCount())
                    .as("roomcount=%s", roomCount).isNull();
            server.verify();
        }
    }

    @Test
    void stringFlagsBecomeNullableBooleansAndNeverGuess() {
        record Case(String raw, Boolean expected) {}
        Case[] cases = {
                new Case(null, null),
                new Case("", null),
                new Case("가능", true),
                new Case("가능요금 (무료)", true),
                new Case("가능 (객실당 1대 무료 주차)", true),
                new Case("있음", true),
                // "불가능" 은 "가능" 을 포함하므로 부정 판정이 먼저 이뤄져야 한다 (회귀 방지)
                new Case("불가능", false),
                new Case("불가", false),
                new Case("없음", false),
                new Case("1", true),
                new Case("0", false),
                // 추측성 판정 금지 - 어느 쪽도 확실하지 않으면 null 로 남긴다
                new Case("주차 관련 문의", null),
                new Case("전화 예약 문의", null)
        };

        for (Case testCase : cases) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KtoTourService service = service(builder, "sample-key");
            String parkingfood = testCase.raw() == null ? "" : ",\"parkingfood\":\"%s\"".formatted(testCase.raw());

            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"2891928","contenttypeid":"39","tel":"031-000-0000"
                            """), MediaType.APPLICATION_JSON));
            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"2891928","contenttypeid":"39"%s
                            """.formatted(parkingfood)), MediaType.APPLICATION_JSON));

            assertThat(service.getDetail("2891928", "39").parkingAvailable())
                    .as("parkingfood=%s", testCase.raw()).isEqualTo(testCase.expected());
            server.verify();
        }
    }

    @Test
    void restaurantDetailNormalizesParkingTakeoutAndReservationFlags() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");

        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","title":"가야밀면돼지국밥","tel":""
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2891928","contenttypeid":"39","firstmenu":"밀면",
                        "opentimefood":"11:00~22:00","restdatefood":"연중무휴",
                        "infocenterfood":"031-915-1459","parkingfood":"가능요금 (무료)",
                        "packing":"불가능","reservationfood":""
                        """), MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("2891928", "39");

        assertThat(result.parkingAvailable()).isTrue();
        assertThat(result.takeoutAvailable()).isFalse();
        assertThat(result.reservation()).isNull();
        // 기존 자동입력 값은 그대로 유지된다
        assertThat(result.mainMenu()).isEqualTo("밀면");
        assertThat(result.openingHours()).isEqualTo("11:00~22:00");
        assertThat(result.closedDays()).isEqualTo("연중무휴");
        assertThat(result.contactNumber()).isEqualTo("031-915-1459");
        server.verify();
    }

    @Test
    void accommodationDetailNormalizesOnlyTheLodgingParkingFlag() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");

        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2650614","contenttypeid":"32","title":"강남아르누보씨티호텔","tel":""
                        """), MediaType.APPLICATION_JSON));
        server.expect(request -> {})
                .andRespond(withSuccess(detailBody("""
                        "contentid":"2650614","contenttypeid":"32","checkintime":"15:00",
                        "checkouttime":"11:00","roomcount":"192실","roomtype":"트윈",
                        "infocenterlodging":"02-580-7500",
                        "parkinglodging":"가능 (객실당 1대 무료 주차)","reservationlodging":"가능"
                        """), MediaType.APPLICATION_JSON));

        KtoTourAutofillResponse result = service.getDetail("2650614", "32");

        assertThat(result.parkingAvailable()).isTrue();
        // 음식점 전용 플래그는 숙박 응답에 섞이지 않는다
        assertThat(result.takeoutAvailable()).isNull();
        // accommodation_info 에 대응 컬럼이 없으므로 reservationlodging 은 매핑하지 않는다
        assertThat(result.reservation()).isNull();
        // 기존 자동입력 값은 그대로 유지된다
        assertThat(result.checkinTime()).isEqualTo("15:00");
        assertThat(result.checkoutTime()).isEqualTo("11:00");
        assertThat(result.roomCount()).isEqualTo(192);
        assertThat(result.roomType()).isEqualTo("트윈");
        assertThat(result.contactNumber()).isEqualTo("02-580-7500");
        server.verify();
    }

    @Test
    void otherContentTypesNeverReceiveTheNewBooleanFlags() {
        for (String contentTypeId : new String[]{"12", "14", "25", "28", "38"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KtoTourService service = service(builder, "sample-key");

            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"126508","contenttypeid":"%s","tel":"02-000-0000"
                            """.formatted(contentTypeId)), MediaType.APPLICATION_JSON));
            server.expect(request -> {})
                    .andRespond(withSuccess(detailBody("""
                            "contentid":"126508","contenttypeid":"%s","parking":"가능",
                            "parkingfood":"가능","packing":"가능","reservationfood":"가능",
                            "parkinglodging":"가능"
                            """.formatted(contentTypeId)), MediaType.APPLICATION_JSON));

            KtoTourAutofillResponse result = service.getDetail("126508", contentTypeId);

            assertThat(result.parkingAvailable()).as("contentTypeId=%s", contentTypeId).isNull();
            assertThat(result.takeoutAvailable()).as("contentTypeId=%s", contentTypeId).isNull();
            assertThat(result.reservation()).as("contentTypeId=%s", contentTypeId).isNull();
            server.verify();
        }
    }

    @Test
    void restaurantAndCafeSearchesAskTourApiForFoodContentTypeOnly() {
        for (String destinationType : new String[]{"RESTAURANTS", "CAFE"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KtoTourService service = service(builder, "sample-key");

            server.expect(request -> {
                assertSearchRequest(request.getURI(), "국밥", "sample-key");
                assertDecodedQuery(request.getURI(), "contentTypeId", "39");
            }).andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));

            service.search("국밥", 1, 10, destinationType);
            server.verify();
        }
    }

    @Test
    void accommodationSearchAsksTourApiForStayContentTypeOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KtoTourService service = service(builder, "sample-key");

        server.expect(request -> {
            assertSearchRequest(request.getURI(), "호텔", "sample-key");
            assertDecodedQuery(request.getURI(), "contentTypeId", "32");
        }).andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));

        service.search("호텔", 1, 10, "ACCOMMODATION");
        server.verify();
    }

    @Test
    void otherDestinationTypesKeepTheExistingUnrestrictedSearch() {
        for (String destinationType : new String[]{"ATTRACTION", "ACTIVITY", "SHOP", null, ""}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KtoTourService service = service(builder, "sample-key");

            server.expect(request -> {
                assertSearchRequest(request.getURI(), "창덕궁", "sample-key");
                // 기존 계약 그대로: contentTypeId 로 범위를 좁히지 않는다
                assertThat(request.getURI().getQuery()).doesNotContain("contentTypeId");
            }).andRespond(withSuccess(emptyResponse(), MediaType.APPLICATION_JSON));

            service.search("창덕궁", 1, 10, destinationType);
            server.verify();
        }
    }

    private String detailBody(String itemFields) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                %s
                }]}}}}
                """.formatted(itemFields);
    }

    private KtoTourService service(RestClient.Builder builder, String apiKey) {
        return new KtoTourService(builder, new ObjectMapper(), apiKey,
                "https://kto.example.test/KorService2");
    }

    private void assertSearchRequest(URI uri, String keyword, String encodedKey) {
        assertThat(uri.getPath()).isEqualTo("/KorService2/searchKeyword2");
        assertDecodedQuery(uri, "keyword", keyword);
        assertDecodedQuery(uri, "MobileOS", "ETC");
        assertDecodedQuery(uri, "MobileApp", "TripBora");
        assertDecodedQuery(uri, "_type", "json");
        assertDecodedQuery(uri, "pageNo", "1");
        assertDecodedQuery(uri, "numOfRows", "10");
        assertThat(rawQueryValue(uri, "serviceKey")).isEqualTo(encodedKey);
    }

    private void assertDetailRequest(URI uri, String expectedPath, boolean intro) {
        assertThat(uri.getPath()).isEqualTo("/KorService2" + expectedPath);
        assertDecodedQuery(uri, "contentId", "126508");
        if (intro) {
            assertDecodedQuery(uri, "contentTypeId", "12");
        }
    }

    private void assertDecodedQuery(URI uri, String name, String expected) {
        assertThat(UriUtils.decode(rawQueryValue(uri, name), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    private String rawQueryValue(URI uri, String name) {
        String prefix = name + "=";
        return Arrays.stream(uri.getRawQuery().split("&"))
                .filter(part -> part.startsWith(prefix))
                .map(part -> part.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    private String emptyResponse() {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "numOfRows":10,"pageNo":1,"totalCount":0,"items":""}}}
                """;
    }
}
