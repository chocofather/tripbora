package com.tripbora.service.kto;

import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.dto.kto.*;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.service.travelinfo.FestivalRegistrationService;
import com.tripbora.service.travelinfo.FestivalValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KtoFestivalBulkPreparationServiceTest {
    final KtoFestivalService kto = mock(KtoFestivalService.class);
    final KtoForeignTourService foreign = mock(KtoForeignTourService.class);
    final InfoCategoryMapper categories = mock(InfoCategoryMapper.class);
    final FestivalRegistrationService registration = mock(FestivalRegistrationService.class);
    final FestivalInfoMapper festivals = mock(FestivalInfoMapper.class);
    KtoFestivalBulkPreparationService service;

    @BeforeEach
    void setup() {
        service = new KtoFestivalBulkPreparationService(kto, foreign, categories, registration, festivals);
        InfoCategory category = new InfoCategory(); category.setId(8L); category.setName("축제");
        category.setContentType(TravelInfoContentType.FESTIVAL);
        when(categories.findAll()).thenReturn(List.of(category));
        when(kto.getDetail("123")).thenReturn(detail());
        when(kto.getThumbnailCandidates("123")).thenReturn(List.of(new KtoFestivalThumbnailCandidate(
                "DETAIL:poster", "https://example.test/poster.jpg", "포스터", "추가사진", "KOGL_TYPE_3", true, null)));
        when(kto.getCoordinates("123")).thenReturn(Optional.empty());
    }

    @Test
    void mapsEverySupportedSingleFormFieldAndRequiresOwnerBoundPreviewToken() {
        var response = service.preview(new KtoFestivalBulkPreviewRequest("123", 2026, "DETAIL:poster"), 7L);
        assertThat(response.status()).isEqualTo("READY");
        FestivalCreateForm form = service.resolve(response.token(), 7L).form();
        assertThat(form.getTitle()).isEqualTo("서울 축제");
        assertThat(form.getContent()).isEqualTo("<p>소개 &lt;본문&gt;</p><p>두 번째 문단</p>");
        assertThat(form.getCategoryId()).isEqualTo(8L);
        assertThat(form.getAddress()).isEqualTo("서울 주소");
        assertThat(form.getEventPlace()).isEqualTo("행사장");
        assertThat(form.getPlayTime()).isEqualTo("10:00~18:00");
        assertThat(form.getUseTime()).isEqualTo("무료");
        assertThat(form.getSponsor1()).isEqualTo("주최");
        assertThat(form.getSponsor1Tel()).isEqualTo("02-111-1111");
        assertThat(form.getSponsor2()).isEqualTo("주관");
        assertThat(form.getSponsor2Tel()).isEqualTo("02-222-2222");
        assertThat(form.getContactTel()).isEqualTo("02-333-3333");
        assertThat(form.getHomepageUrl()).isEqualTo("https://festival.example.test");
        assertThat(form.getKtoFestivalContentId()).isEqualTo("123");
        assertThat(form.getKtoThumbnailImageSelection()).isEqualTo("DETAIL:poster");
        assertThat(form.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(form.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        verify(registration, times(2)).validateForCreate(form, 7L);
        verify(registration, never()).create(any(), any());
        assertThatThrownBy(() -> service.resolve(response.token(), 9L)).isInstanceOf(FestivalValidationException.class);
    }

    @Test
    void changedOccurrenceAndUnselectablePosterNeverProduceReadyTokens() {
        assertThat(service.preview(new KtoFestivalBulkPreviewRequest("123", 2025, "DETAIL:poster"), 7L).status())
                .isEqualTo("NEEDS_REVIEW");
        assertThat(service.preview(new KtoFestivalBulkPreviewRequest("123", 2026, "MAIN"), 7L).token()).isNull();
        verify(registration, never()).createWithRequiredThumbnail(any(), any());
    }

    @Test
    void reusesTemporaryCoordinatesForForeignMatchingWithoutPersistingThem() {
        when(kto.getCoordinates("123")).thenReturn(Optional.of(new KtoFestivalCoordinates("126.978", "37.5665")));
        when(foreign.match(KtoForeignLanguage.ENGLISH, "서울 축제", "126.978", "37.5665", true))
                .thenReturn(KtoForeignTourMatchResponse.matched(new KtoForeignTourCandidateResponse(
                        "999", "85", "Seoul Festival", "126.978", "37.5665", 0)));
        when(foreign.getDetail(KtoForeignLanguage.ENGLISH, "999", "85")).thenReturn(new KtoForeignTourDetailResponse(
                "Seoul Festival", "English overview", null, null, null, null, null, null,
                "English venue", "English address", "10:00-18:00", "Free", "Organizer", "Host"));
        var response = service.preview(new KtoFestivalBulkPreviewRequest("123", 2026, "DETAIL:poster"), 7L);
        var form = service.resolve(response.token(), 7L).form();
        verify(foreign).match(KtoForeignLanguage.ENGLISH, "서울 축제", "126.978", "37.5665", true);
        assertThat(FestivalCreateForm.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("latitude", "longitude");
        assertThat(form.getTranslations().stream().filter(slot -> "en".equals(slot.getLanguageCode())).findFirst().orElseThrow().getTitle())
                .isEqualTo("Seoul Festival");
        assertThat(form.getFestivalInfoTranslations().stream().filter(slot -> "en".equals(slot.getLanguageCode())).findFirst().orElseThrow().getAddress())
                .isEqualTo("English address");
        assertThat(form.getTranslations().stream().filter(slot -> "ja".equals(slot.getLanguageCode())).findFirst().orElseThrow().getTitle()).isEmpty();
    }

    @Test
    void duplicateOccurrenceIsExcludedAndReadyPreparationIsReusedWithoutMoreKtoCalls() {
        var request = new KtoFestivalBulkPreviewRequest("123", 2026, "DETAIL:poster");
        var first = service.preview(request, 7L);
        assertThat(service.preview(request, 7L).token()).isEqualTo(first.token());
        verify(kto, times(1)).getDetail("123");
        when(festivals.countOccurrence("KTO_TOURAPI", "123", 2026)).thenReturn(1);
        var duplicate = service.preview(request, 9L);
        assertThat(duplicate.status()).isEqualTo("DUPLICATE");
        assertThat(duplicate.token()).isNull();
    }

    static KtoFestivalAutofillResponse detail() {
        return new KtoFestivalAutofillResponse("123", "서울 축제", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
                null, null, "서울 주소", "행사장", "소개 <본문>\n두 번째 문단", "10:00~18:00", "무료", "주최", "02-111-1111",
                "주관", "02-222-2222", "https://example.test", "https://festival.example.test", "02-333-3333",
                "EV", "EV01", "EV010100", "축제");
    }
}
