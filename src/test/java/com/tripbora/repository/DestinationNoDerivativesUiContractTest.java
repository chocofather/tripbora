package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 공공누리 제3유형(변경금지) 대표 이미지는 사용자에게 보이는 모든 카드·썸네일에서 잘리지 않게 그린다. */
class DestinationNoDerivativesUiContractTest {

    @Test
    void everyDestinationThumbnailCanBeDrawnWhole() throws IOException {
        assertThat(resource("/static/css/style.css"))
                .contains("img.is-no-derivatives {")
                .contains("object-fit: contain !important;")
                .contains("transform: none !important;");

        assertThat(resource("/templates/home.html"))
                .contains("${destination.imageNoDerivatives} ? 'is-no-derivatives'")
                .contains("${landmark.imageNoDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/templates/destination/fragment.html"))
                .contains("${d.imageNoDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/templates/destination/detail.html"))
                .contains("${d.imageNoDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/templates/search.html"))
                .contains("${result.imageNoDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/templates/course/detail.html"))
                .contains("${stop.imageNoDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/templates/course/edit.html"))
                .contains("course/fragments/schedule-input :: scheduleInput");
        assertThat(resource("/templates/course/fragments/schedule-input.html"))
                .contains("data-image-no-derivatives=${stop.imageNoDerivatives}");
        assertThat(resource("/templates/mypage/bookmarks.html"))
                .contains("${bookmark.imageNoDerivatives} ? 'is-no-derivatives'");

        // 관리 화면은 style.css 를 쓰지 않아 자기 스타일에 같은 규칙을 둔다.
        assertThat(resource("/templates/admin/destinations/image-upload.html"))
                .contains("${img.noDerivatives} ? 'is-no-derivatives'");
        assertThat(resource("/static/css/admin-destination-images.css"))
                .contains(".admin-destination-image-preview img.is-no-derivatives")
                .contains(".admin-image-order-thumb img.is-no-derivatives");

        assertThat(resource("/static/js/home.js")).contains("dest.imageNoDerivatives");
        assertThat(resource("/static/js/random-travel.js")).contains("destination.imageNoDerivatives");
        assertThat(resource("/static/js/course-write.js"))
                .contains("destination.imageNoDerivatives")
                .contains("element.dataset.imageNoDerivatives === 'true'");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
