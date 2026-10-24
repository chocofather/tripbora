package com.tripbora.service.kto;

import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.dto.kto.*;
import com.tripbora.service.travelinfo.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KtoFestivalBulkJobServiceTest {
    final KtoFestivalBulkPreparationService preparation = mock(KtoFestivalBulkPreparationService.class);
    final FestivalRegistrationService registration = mock(FestivalRegistrationService.class);
    final Queue<Runnable> pending = new ArrayDeque<>();
    final Executor executor = pending::add;

    @Test
    void acceptsOnceCountsOnlyCompletedTransactionsAndContinuesAfterIndividualFailure() {
        var first = prepared("1");
        var second = prepared("2");
        var duplicate = prepared("3");
        when(preparation.resolve("a", 7L)).thenReturn(first);
        when(preparation.resolve("b", 7L)).thenReturn(second);
        when(preparation.resolve("c", 7L)).thenReturn(duplicate);
        when(registration.createWithRequiredThumbnail(first.form(), 7L)).thenReturn(new FestivalRegistrationResult(101L, null));
        when(registration.createWithRequiredThumbnail(second.form(), 7L)).thenThrow(new FestivalValidationException(null, "포스터 저장 실패"));
        when(preparation.isRegistered(duplicate.form())).thenReturn(true);
        var service = new KtoFestivalBulkJobService(preparation, registration, executor);
        var request = new KtoFestivalBulkStartRequest(UUID.randomUUID(), List.of("a", "b", "c"));
        var accepted = service.start(request, 7L);
        assertThat(accepted.processed()).isZero();
        assertThat(service.start(request, 7L).id()).isEqualTo(accepted.id());
        assertThat(pending).hasSize(1);
        pending.remove().run();
        var result = service.get(request.requestId(), 7L);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.duplicate()).isEqualTo(1);
        assertThat(result.items()).extracting(KtoFestivalBulkJob.Item::status).containsExactly("SUCCESS", "FAILED", "DUPLICATE");
        verify(registration, times(1)).createWithRequiredThumbnail(first.form(), 7L);
        verify(registration, never()).createWithRequiredThumbnail(duplicate.form(), 7L);
        assertThatThrownBy(() -> service.get(request.requestId(), 8L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void requestIdentityCannotBeReusedForDifferentSelectionAndOneOwnerCannotQueueTwice() {
        when(preparation.resolve("a", 7L)).thenReturn(prepared("1"));
        var service = new KtoFestivalBulkJobService(preparation, registration, executor);
        UUID id = UUID.randomUUID();
        service.start(new KtoFestivalBulkStartRequest(id, List.of("a")), 7L);
        assertThatThrownBy(() -> service.start(new KtoFestivalBulkStartRequest(id, List.of("b")), 7L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.start(new KtoFestivalBulkStartRequest(UUID.randomUUID(), List.of("a")), 7L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(pending).hasSize(1);
    }

    @Test
    void queueRejectionNeverLeavesAnAcceptedJobAndDuplicateOccurrenceCannotBeQueued() {
        when(preparation.resolve("a", 7L)).thenReturn(prepared("1"));
        when(preparation.resolve("b", 7L)).thenReturn(prepared("1"));
        var rejected = new KtoFestivalBulkJobService(preparation, registration, task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> rejected.start(new KtoFestivalBulkStartRequest(id, List.of("a")), 7L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> rejected.get(id, 7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var service = new KtoFestivalBulkJobService(preparation, registration, executor);
        assertThatThrownBy(() -> service.start(new KtoFestivalBulkStartRequest(UUID.randomUUID(), List.of("a", "b")), 7L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(pending).isEmpty();
        verifyNoInteractions(registration);
    }

    static KtoFestivalBulkPreparationService.Prepared prepared(String id) {
        FestivalCreateForm form = new FestivalCreateForm();
        form.setTitle("축제 " + id); form.setKtoFestivalContentId(id); form.setKtoThumbnailImageSelection("MAIN");
        form.setStartDate(LocalDate.of(2026, 10, 1)); form.setEndDate(LocalDate.of(2026, 10, 2));
        KtoFestivalBulkPreview preview = new KtoFestivalBulkPreview(null, id, 2026, form.getTitle(), form.getStartDate(),
                form.getEndDate(), null, null, null, "READY", List.of(), List.of());
        return new KtoFestivalBulkPreparationService.Prepared(7L, form, preview, Instant.now().plusSeconds(3600));
    }
}
