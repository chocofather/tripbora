package com.tripbora.service.kto;

import com.tripbora.dto.kto.KtoFestivalBulkJob;
import com.tripbora.dto.kto.KtoFestivalBulkStartRequest;
import com.tripbora.service.travelinfo.FestivalRegistrationService;
import com.tripbora.service.travelinfo.FestivalValidationException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 짧은 시작/상태 요청과 별개로 실행한다. 항목별 저장는 다른 빈의 트랜잭션 프록시를 통과한다. */
@Service
@Slf4j
public class KtoFestivalBulkJobService {
    private static final int MAX_JOBS = 100;
    private static final Duration RESULT_TTL = Duration.ofHours(24);
    private final KtoFestivalBulkPreparationService preparation;
    private final FestivalRegistrationService registration;
    private final Executor executor;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();

    @Autowired
    public KtoFestivalBulkJobService(KtoFestivalBulkPreparationService preparation, FestivalRegistrationService registration) {
        this(preparation, registration, new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(3), task -> {
                    Thread worker = new Thread(task, "kto-festival-bulk"); worker.setDaemon(true); return worker;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    KtoFestivalBulkJobService(KtoFestivalBulkPreparationService preparation, FestivalRegistrationService registration, Executor executor) {
        this.preparation = preparation; this.registration = registration; this.executor = executor;
    }

    public synchronized KtoFestivalBulkJob start(KtoFestivalBulkStartRequest request, Long userId) {
        if (userId == null) throw error(HttpStatus.FORBIDDEN, "관리자 정보를 확인할 수 없습니다.");
        if (request == null || request.requestId() == null || request.previewTokens() == null
                || request.previewTokens().isEmpty() || request.previewTokens().size() > 30
                || request.previewTokens().stream().anyMatch(token -> token == null || token.isBlank())) {
            throw error(HttpStatus.BAD_REQUEST, "등록 준비가 완료된 축제를 1~30개 선택해 주세요.");
        }
        Job existing = jobs.get(request.requestId());
        if (existing != null) {
            if (!existing.ownerId.equals(userId) || !existing.tokens.equals(request.previewTokens()))
                throw error(HttpStatus.CONFLICT, "같은 작업 ID를 다른 등록 요청에 사용할 수 없습니다.");
            return existing.snapshot();
        }
        cleanup();
        if (jobs.values().stream().anyMatch(job -> job.ownerId.equals(userId) && !job.complete())) {
            throw error(HttpStatus.CONFLICT, "진행 중인 일괄등록 결과를 먼저 확인해 주세요.");
        }
        if (jobs.size() >= MAX_JOBS) throw error(HttpStatus.TOO_MANY_REQUESTS, "저장된 작업이 많습니다. 잠시 후 다시 시도해 주세요.");
        List<KtoFestivalBulkPreparationService.Prepared> items = request.previewTokens().stream()
                .map(token -> preparation.resolve(token, userId)).toList();
        Set<String> occurrences = new HashSet<>();
        for (var item : items) {
            if (!occurrences.add(item.preview().contentId() + ":" + item.preview().eventYear())) {
                throw error(HttpStatus.BAD_REQUEST, "같은 축제의 같은 개최연도를 여러 번 선택할 수 없습니다.");
            }
        }
        Job job = new Job(request.requestId(), userId, List.copyOf(request.previewTokens()), items);
        jobs.put(request.requestId(), job);
        try { executor.execute(() -> run(job)); }
        catch (RejectedExecutionException exception) {
            jobs.remove(request.requestId());
            throw error(HttpStatus.TOO_MANY_REQUESTS, "다른 일괄등록이 진행 중입니다. 결과를 확인한 뒤 다시 시도해 주세요.");
        }
        return job.snapshot();
    }

    public synchronized KtoFestivalBulkJob get(UUID id, Long userId) {
        cleanup();
        Job job = jobs.get(id);
        if (job == null || !job.ownerId.equals(userId)) {
            throw error(HttpStatus.NOT_FOUND, "작업 결과를 확인할 수 없습니다. 관리자 목록에서 등록 여부를 확인해 주세요.");
        }
        return job.snapshot();
    }

    private void run(Job job) {
        try {
            for (int index = 0; index < job.prepared.size(); index++) {
                var prepared = job.prepared.get(index);
                job.update(index, "RUNNING", null, "등록 중", null);
                try {
                    if (preparation.isRegistered(prepared.form())) {
                        job.update(index, "DUPLICATE", null, "이미 등록된 해당 연도의 축제입니다.", null);
                        continue;
                    }
                    var result = registration.createWithRequiredThumbnail(prepared.form(), job.ownerId);
                    // 프록시가 반환한 뒤에만 커밋이 확인된 성공으로 센다.
                    job.update(index, "SUCCESS", result.festivalId(), "등록 완료", result.imageWarning());
                } catch (FestivalValidationException exception) {
                    job.update(index, registeredAfterFailure(prepared) ? "DUPLICATE" : "FAILED", null, exception.getMessage(), null);
                } catch (RuntimeException exception) {
                    log.warn("KTO 축제 일괄등록 항목 실패: contentId={}, failureType={}",
                            prepared.preview().contentId(), exception.getClass().getSimpleName());
                    job.update(index, "FAILED", null, "등록 결과를 확인하지 못했습니다. 관리자 목록에서 확인 후 다시 진행해 주세요.", null);
                }
            }
        } finally { job.finish(); }
    }

    private boolean registeredAfterFailure(KtoFestivalBulkPreparationService.Prepared prepared) {
        try { return preparation.isRegistered(prepared.form()); }
        catch (RuntimeException ignored) { return false; }
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(RESULT_TTL);
        jobs.values().removeIf(job -> job.complete() && job.finishedAt.isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() { if (executor instanceof ExecutorService service) service.shutdown(); }

    private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    private static final class Job {
        final UUID id; final Long ownerId; final List<String> tokens;
        List<KtoFestivalBulkPreparationService.Prepared> prepared;
        final List<KtoFestivalBulkJob.Item> items = new ArrayList<>();
        private boolean finished;
        private Instant finishedAt;
        Job(UUID id, Long ownerId, List<String> tokens, List<KtoFestivalBulkPreparationService.Prepared> prepared) {
            this.id = id; this.ownerId = ownerId; this.tokens = tokens; this.prepared = prepared;
            for (var item : prepared) {
                var preview = item.preview();
                items.add(new KtoFestivalBulkJob.Item(preview.contentId(), preview.eventYear(), preview.title(), "PENDING", null, "대기 중", null));
            }
        }
        synchronized boolean complete() { return finished; }
        synchronized void update(int index, String state, Long festivalId, String message, String warning) {
            var previous = items.get(index);
            items.set(index, new KtoFestivalBulkJob.Item(previous.contentId(), previous.eventYear(), previous.title(), state, festivalId, message, warning));
        }
        synchronized void finish() { finished = true; finishedAt = Instant.now(); prepared = List.of(); }
        synchronized KtoFestivalBulkJob snapshot() {
            int success = count("SUCCESS"), failed = count("FAILED"), duplicate = count("DUPLICATE");
            return new KtoFestivalBulkJob(id.toString(), finished ? "COMPLETE" : "RUNNING", items.size(), success + failed + duplicate,
                    success, failed, duplicate, items);
        }
        private int count(String status) { return (int) items.stream().filter(item -> status.equals(item.status())).count(); }
    }
}
