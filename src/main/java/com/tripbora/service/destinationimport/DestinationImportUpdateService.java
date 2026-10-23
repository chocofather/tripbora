package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationImportIssue;
import com.tripbora.dto.destinationimport.DestinationImportItem;
import com.tripbora.dto.destinationimport.DestinationUpdatePreview;
import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.DestinationSeason;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.destination.DestinationDuplicateCheck;
import com.tripbora.service.destination.DestinationDuplicateQuery;
import com.tripbora.service.destination.DestinationDuplicateService;
import com.tripbora.service.destination.DestinationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 신규 등록과 분리된 기존 여행지 보강. 저장 권한은 세션에 보관한 단일 사용 계획으로 한정한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DestinationImportUpdateService {
    static final String SESSION_KEY = DestinationImportUpdateService.class.getName() + ".tickets";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(20);
    private static final int MAX_TICKETS = 30;
    private static final String CACHE_KEY = SESSION_KEY + ".file";
    private static final String RECEIPTS_KEY = SESSION_KEY + ".receipts";
    private record Receipt(Long userId, Instant expiresAt, DestinationUpdatePreview.Result result) { }

    private final DestinationImportParser parser;
    private final DestinationImportMasterResolver resolver;
    private final DestinationImportValidator validator;
    private final DestinationUpdateMerge merge;
    private final DestinationUpdateStore store;
    private final DestinationFieldProtectionService protections;
    private final DestinationDuplicateService duplicateService;
    private final DestinationService destinationService;
    private final CountryCategoryService countryCategoryService;
    private final PlatformTransactionManager transactionManager;

    record Ticket(Long userId, Instant expiresAt, DestinationImportParser.ParsedItem parsed,
                  Set<String> fields, boolean replaceRelations, DestinationUpdateSnapshot before,
                  Set<String> previousProtection, DestinationUpdateMerge.Plan plan) { }

    /** Only this session/user/file can reuse these read-only preview inputs. Apply never uses this cache. */
    private static final class PreviewFile {
        final String token = UUID.randomUUID().toString();
        final Long userId;
        final String json;
        final Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        final DestinationImportParser.ParsedFile parsed;
        final DestinationImportMasterResolver.MasterData master;
        final Map<Integer, List<String>> matchErrors = new HashMap<>();
        final Map<Integer, Long> exactTargets = new HashMap<>();
        final Map<Integer, List<DestinationUpdatePreview.Candidate>> candidates = new HashMap<>();
        final Map<Long, DestinationUpdateSnapshot> snapshots = new HashMap<>();
        final Set<Long> loadedTargets = new HashSet<>();
        final Map<Long, Set<String>> protection = new HashMap<>();
        final Map<String, DestinationImportMasterResolver.Resolution> resolutions = new HashMap<>();
        final Map<String, DestinationImportValidator.Result> validations = new HashMap<>();
        final Map<String, DestinationDuplicateCheck> placeChecks = new HashMap<>();
        final Map<Long, Boolean> placeRegionAllowed = new HashMap<>();
        final Map<String, Long> timings = new LinkedHashMap<>();
        DestinationDuplicateService.UpdateLookup duplicateLookup;
        PreviewFile(Long userId, String json, DestinationImportParser.ParsedFile parsed,
                    DestinationImportMasterResolver.MasterData master) {
            this.userId = userId; this.json = json; this.parsed = parsed; this.master = master;
        }
    }

    private static final class Evaluation {
        final DestinationImportParser.ParsedItem parsed;
        final DestinationUpdatePreview.Selection selection;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        List<DestinationUpdatePreview.Candidate> candidates = List.of();
        DestinationUpdateSnapshot before;
        Set<String> previousProtection = Set.of();
        DestinationUpdateMerge.Plan plan;
        String status = "INVALID";
        String message;

        Evaluation(DestinationImportParser.ParsedItem parsed, DestinationUpdatePreview.Selection selection) {
            this.parsed = parsed;
            this.selection = selection;
            parsed.errors().forEach(error -> errors.add(error.text()));
        }
    }

    public DestinationUpdatePreview preview(DestinationUpdatePreview.Request request, HttpSession session, Long userId) {
        long started = System.nanoTime();
        if (session == null) return failedPreview("로그인 세션이 필요합니다.");
        String generation = UUID.randomUUID().toString();
        // 실패한 새 파일도 이전 파일의 실행 권한을 남기지 않는다.
        synchronized (session) {
            session.removeAttribute(SESSION_KEY);
            session.setAttribute(SESSION_KEY + ".generation", generation);
        }
        if (userId == null || userId <= 0) return failedPreview("로그인이 필요합니다.");
        if (request == null) return failedPreview("업데이트 파일이 필요합니다.");
        PreviewFile cached;
        synchronized (session) {
            Object stored = session.getAttribute(CACHE_KEY);
            cached = stored instanceof PreviewFile value ? value : null;
            if (request.previewToken() == null) {
                session.removeAttribute(CACHE_KEY);
                cached = null;
            } else if (cached == null || !request.previewToken().equals(cached.token)
                    || !Objects.equals(cached.userId, userId) || !Objects.equals(cached.json, request.json())
                    || !cached.expiresAt.isAfter(Instant.now())) {
                session.removeAttribute(CACHE_KEY);
                return failedPreview("파일 미리보기가 없거나 만료되었습니다. 파일을 다시 미리봐 주세요.");
            }
        }
        long parseStarted = System.nanoTime();
        log.debug("destination JSON stage started requestId={} stage=parse reused={}", MDC.get("destinationImportRequest"), cached != null);
        DestinationImportParser.ParsedFile file = cached == null ? parser.parseUpdateFile(request.json()) : cached.parsed;
        long parseNanos = System.nanoTime() - parseStarted;
        if (!file.errors().isEmpty()) return new DestinationUpdatePreview(file.errors(), List.of());
        if (file.items().isEmpty() || file.items().size() > MAX_TICKETS) {
            return failedPreview("여행지는 1~30개여야 합니다.");
        }
        Map<Integer, DestinationUpdatePreview.Selection> selections = new HashMap<>();
        for (var selection : request.selections()) {
            if (selection == null || selection.index() < 0 || selection.index() >= file.items().size()
                    || selections.putIfAbsent(selection.index(), selection) != null) {
                return failedPreview("행 선택 정보가 올바르지 않습니다.");
            }
        }
        try {
            long prepareStarted = System.nanoTime();
            log.debug("destination JSON stage started requestId={} stage=master reused={}", MDC.get("destinationImportRequest"), cached != null);
            PreviewFile context = cached == null ? new PreviewFile(userId, request.json(), file, resolver.load()) : cached;
            long masterNanos = cached == null ? System.nanoTime() - prepareStarted : 0L;
            long waiting = System.nanoTime();
            log.debug("destination JSON stage started requestId={} stage=previewLock", MDC.get("destinationImportRequest"));
            synchronized (context) {
            try {
            log.debug("destination JSON stage finished requestId={} stage=previewLock elapsedMs={}", MDC.get("destinationImportRequest"), (System.nanoTime() - waiting) / 1_000_000.0);
            context.timings.clear();
            context.timings.put("parse", parseNanos);
            context.timings.put("master", masterNanos);
            if (cached == null) prepareMatches(context);
            loadTargets(context, selections);
            long prepareNanos = System.nanoTime() - prepareStarted;
            var master = context.master;
            Map<Long, CountryCategory> regions = new HashMap<>();
            List<Evaluation> evaluations = new ArrayList<>();
            for (var parsed : file.items()) {
                Evaluation evaluation = new Evaluation(parsed, selections.getOrDefault(parsed.index(),
                        DestinationUpdatePreview.Selection.empty(parsed.index())));
                evaluations.add(evaluation);
                try {
                    evaluate(evaluation, master, regions, context);
                } catch (UpdateConflict conflict) {
                    evaluation.errors.add(conflict.getMessage());
                }
            }
            validator.duplicateKeys(file.items()).forEach((index, issue) -> evaluations.get(index).errors.add(issue.text()));
            Map<Long, List<Evaluation>> byTarget = new HashMap<>();
            for (Evaluation evaluation : evaluations) if (evaluation.before != null) {
                byTarget.computeIfAbsent(evaluation.before.destinationId(), ignored -> new ArrayList<>()).add(evaluation);
            }
            byTarget.values().stream().filter(rows -> rows.size() > 1).forEach(rows -> rows.forEach(row ->
                    row.errors.add("같은 파일에서 동일한 여행지를 두 번 보강할 수 없습니다.")));
            Map<String, Ticket> tickets = new LinkedHashMap<>();
            List<DestinationUpdatePreview.Row> rows = new ArrayList<>();
            for (Evaluation evaluation : evaluations) {
                String token = null;
                if (!evaluation.errors.isEmpty()) {
                    evaluation.status = "INVALID";
                    evaluation.message = String.join(" / ", evaluation.errors);
                } else if (evaluation.plan != null && !evaluation.plan.protectedFields().isEmpty()) {
                    token = UUID.randomUUID().toString();
                    tickets.put(token, new Ticket(userId, context.expiresAt, evaluation.parsed,
                            ticketFields(evaluation.plan),
                            evaluation.selection.replaceRelations(), evaluation.before,
                            Set.copyOf(evaluation.previousProtection), evaluation.plan));
                }
                var item = evaluation.parsed.item();
                rows.add(new DestinationUpdatePreview.Row(evaluation.parsed.index(), item == null ? null : item.key(),
                        item == null ? null : item.koreanName(),
                        evaluation.status, evaluation.message,
                        evaluation.before == null ? null : evaluation.before.destinationId(),
                        item == null ? null : item.external().tourApiContentId(), evaluation.candidates,
                        evaluation.plan == null ? List.of() : evaluation.plan.changes(), token,
                        List.copyOf(evaluation.warnings), item == null ? List.of() : item.evidence()));
            }
            synchronized (session) {
                if (!generation.equals(session.getAttribute(SESSION_KEY + ".generation"))) {
                    return failedPreview("새로운 미리보기 요청이 있습니다. 최신 결과를 사용해 주세요.");
                }
                session.setAttribute(SESSION_KEY, tickets);
                session.setAttribute(CACHE_KEY, context);
            }
            log.debug("destination JSON preview rows={} reused={} parseMs={} prepareMs={} totalMs={}",
                    rows.size(), cached != null, parseNanos / 1_000_000.0, prepareNanos / 1_000_000.0,
                    (System.nanoTime() - started) / 1_000_000.0);
            Map<String, Double> stageMillis = new LinkedHashMap<>();
            context.timings.forEach((key, value) -> stageMillis.put(key, value / 1_000_000.0));
            log.debug("destination JSON preview rows={} reused={} stageMs={}", rows.size(), cached != null, stageMillis);
            return new DestinationUpdatePreview(List.of(), List.copyOf(rows), context.token);
            } finally {
                // The full destination index lives for one request only; session caches keep bounded results.
                context.duplicateLookup = null;
            }
            }
        } catch (RuntimeException exception) {
            log.error("destination JSON preview failed requestId={} exceptionType={}", MDC.get("destinationImportRequest"), exception.getClass().getSimpleName());
            return failedPreview("업데이트 미리보기를 만들지 못했습니다. 파일과 현재 기준 데이터를 확인해 주세요.");
        }
    }

    private void prepareMatches(PreviewFile context) {
        List<Integer> candidateRows = new ArrayList<>();
        List<DestinationDuplicateQuery> queries = new ArrayList<>();
        Map<Integer, String> tourApiIds = new LinkedHashMap<>();
        Map<Integer, String> wikidataIds = new LinkedHashMap<>();
        for (var parsed : context.parsed.items()) {
            List<String> errors = new ArrayList<>();
            context.matchErrors.put(parsed.index(), errors);
            if (!parsed.errors().isEmpty() || parsed.item() == null) continue;
            var item = parsed.item();
            timed(context, "validation", () -> validator.validateUpdate(item, parsed.path(), null))
                    .errors().forEach(error -> errors.add(error.text()));
            String contentId = item.external().tourApiContentId();
            String qid = DestinationImportValidator.normalizeQid(item.external().wikidataQid());
            if (contentId != null && item.external().wikidataQid() != null) errors.add("TourAPI contentId와 Wikidata QID를 함께 지정할 수 없습니다.");
            if (item.external().wikidataQid() != null && qid == null) errors.add("Wikidata QID 형식이 올바르지 않습니다.");
            if (!errors.isEmpty()) continue;
            if (contentId != null || qid != null) {
                (contentId != null ? tourApiIds : wikidataIds).put(parsed.index(), contentId != null ? contentId : qid);
            } else {
                var resolution = timed(context, "resolution", () -> resolver.resolve(item, parsed.path(), context.master));
                resolution.errors().forEach(error -> errors.add(error.text()));
                if (!errors.isEmpty()) continue;
                candidateRows.add(parsed.index());
                queries.add(query(item, resolution.regionId(), null));
            }
        }
        if (!tourApiIds.isEmpty()) {
            var found = timed(context, "matching", () -> store.externalTargets(DestinationService.KTO_TOUR_API_SOURCE_TYPE,
                    new LinkedHashSet<>(tourApiIds.values())));
            tourApiIds.forEach((index, contentId) -> context.exactTargets.put(index, found.get(contentId)));
        }
        if (!wikidataIds.isEmpty()) {
            var found = timed(context, "matching", () -> store.externalTargets(DestinationService.WIKIDATA_SOURCE_TYPE,
                    new LinkedHashSet<>(wikidataIds.values())));
            wikidataIds.forEach((index, qid) -> context.exactTargets.put(index, found.get(qid)));
        }
        List<List<DestinationDuplicateCheck>> matches = queries.isEmpty() ? List.of()
                : timed(context, "matching", () -> updateLookup(context).findCandidates(queries));
        for (int i = 0; i < candidateRows.size(); i++) {
            context.candidates.put(candidateRows.get(i), matches.get(i).stream()
                    .filter(candidate -> candidate.destinationId() != null)
                    .map(candidate -> new DestinationUpdatePreview.Candidate(candidate.destinationId(),
                            candidate.destinationName(), candidate.message())).toList());
        }
    }

    private void loadTargets(PreviewFile context, Map<Integer, DestinationUpdatePreview.Selection> selections) {
        Set<Long> targets = new LinkedHashSet<>();
        for (var parsed : context.parsed.items()) {
            if (!parsed.errors().isEmpty() || !context.matchErrors.getOrDefault(parsed.index(), List.of()).isEmpty()) continue;
            var selection = selections.getOrDefault(parsed.index(), DestinationUpdatePreview.Selection.empty(parsed.index()));
            Long id = context.exactTargets.get(parsed.index());
            if (context.exactTargets.containsKey(parsed.index())) {
                if (selection.destinationId() != null && !Objects.equals(selection.destinationId(), id)) continue;
            } else {
                id = selection.destinationId();
                Long selectedId = id;
                if (!selection.confirmedMatch() || id == null || context.candidates.getOrDefault(parsed.index(), List.of())
                        .stream().noneMatch(candidate -> candidate.destinationId().equals(selectedId))) continue;
            }
            if (id != null) targets.add(id);
        }
        // Candidate changes cannot grow a session into an unbounded snapshot store.
        context.snapshots.keySet().retainAll(targets);
        context.protection.keySet().retainAll(targets);
        context.loadedTargets.retainAll(targets);
        context.placeChecks.keySet().removeIf(key -> !targets.contains(Long.valueOf(key.substring(key.indexOf(':') + 1))));
        context.placeRegionAllowed.keySet().retainAll(targets);
        context.resolutions.keySet().removeIf(key -> !targets.contains(Long.valueOf(key.substring(key.indexOf(':') + 1))));
        context.validations.keySet().removeIf(key -> !targets.contains(Long.valueOf(key.substring(key.indexOf(':') + 1))));
        targets.removeAll(context.loadedTargets);
        if (!targets.isEmpty()) {
            context.snapshots.putAll(timed(context, "snapshot", () -> store.readAll(targets)));
            context.protection.putAll(timed(context, "protection", () -> protections.protectedFieldsAll(targets)));
            context.loadedTargets.addAll(targets);
        }
    }

    private void evaluate(Evaluation evaluation, DestinationImportMasterResolver.MasterData master,
                          Map<Long, CountryCategory> regions, PreviewFile context) {
        if (!evaluation.errors.isEmpty()) return;
        DestinationImportItem item = evaluation.parsed.item();
        if (item == null) {
            evaluation.errors.add("여행지 항목을 읽을 수 없습니다.");
            return;
        }
        evaluation.errors.addAll(context.matchErrors.getOrDefault(evaluation.parsed.index(), List.of()));
        if (!evaluation.errors.isEmpty()) return;
        String contentId = item.external().tourApiContentId();
        String qid = DestinationImportValidator.normalizeQid(item.external().wikidataQid());
        if (contentId != null && item.external().wikidataQid() != null) {
            evaluation.errors.add("TourAPI contentId와 Wikidata QID를 함께 지정할 수 없습니다.");
            return;
        }
        if (item.external().wikidataQid() != null && qid == null) {
            evaluation.errors.add("Wikidata QID 형식이 올바르지 않습니다.");
            return;
        }
        Long target;
        if (contentId != null || qid != null) {
            target = context.exactTargets.get(evaluation.parsed.index());
            if (evaluation.selection.destinationId() != null
                    && !Objects.equals(target, evaluation.selection.destinationId())) {
                evaluation.errors.add("선택한 여행지가 외부 원본 ID와 일치하지 않습니다.");
                return;
            }
            if (target == null) {
                evaluation.status = "NOT_FOUND";
                evaluation.message = "외부 원본 ID가 일치하는 기존 여행지가 없습니다. 신규 등록은 하지 않습니다.";
                return;
            }
        } else {
            evaluation.candidates = context.candidates.getOrDefault(evaluation.parsed.index(), List.of());
            target = evaluation.selection.destinationId();
            if (target != null && evaluation.candidates.stream().noneMatch(candidate -> candidate.destinationId().equals(target))) {
                evaluation.errors.add("조회된 후보에 없는 여행지를 업데이트할 수 없습니다.");
                return;
            }
            if (target == null || !evaluation.selection.confirmedMatch()) {
                evaluation.status = evaluation.candidates.isEmpty() ? "NOT_FOUND" : "NEEDS_MATCH";
                evaluation.message = evaluation.candidates.isEmpty() ? "일치하는 기존 여행지 후보가 없습니다."
                        : "후보의 지역과 원본을 확인한 뒤 같은 여행지임을 확인해 주세요.";
                return;
            }
        }
        evaluation.before = context.snapshots.get(target);
        if (evaluation.before == null) {
            evaluation.errors.add("기존 여행지가 삭제되었습니다.");
            return;
        }
        validateIdentity(item, evaluation.before);
        if (item.type() != null && item.type() != evaluation.before.type()) {
            evaluation.errors.add("기존 여행지의 유형을 변경할 수 없습니다.");
            return;
        }
        String targetKey = evaluation.parsed.index() + ":" + target;
        var resolution = context.resolutions.computeIfAbsent(targetKey,
                ignored -> timed(context, "resolution", () -> resolveForTarget(item, evaluation.parsed.path(), evaluation.before, master, regions)));
        validateSourceRegion(evaluation.before, resolution.domestic());
        if (resolution.domestic() == null) {
            evaluation.errors.add("여행지의 국내/해외 지역을 확인할 수 없습니다.");
        }
        resolution.errors().forEach(error -> evaluation.errors.add(error.text()));
        var validation = context.validations.computeIfAbsent(targetKey,
                ignored -> timed(context, "validation", () -> validator.validateUpdate(item, evaluation.parsed.path(), resolution.domestic())));
        validation.errors().forEach(error -> evaluation.errors.add(error.text()));
        validation.warnings().forEach(warning -> evaluation.warnings.add(warning.text()));
        if (!evaluation.errors.isEmpty()) return;
        evaluation.previousProtection = context.protection.getOrDefault(target, Set.of());
        evaluation.plan = timed(context, "diff", () -> merge.plan(evaluation.before, item, resolution, evaluation.selection.fields(),
                evaluation.selection.replaceRelations()));
        evaluation.errors.addAll(evaluation.plan.errors());
        evaluation.warnings.addAll(evaluation.plan.warnings());
        evaluation.plan = withWarnings(evaluation.plan, evaluation.warnings);
        if (evaluation.plan.patch().containsKey("googlePlaceId")) {
            if (!evaluation.plan.patch().containsKey("regionId") && !context.placeRegionAllowed.computeIfAbsent(target,
                    ignored -> Boolean.FALSE.equals(domestic(numberId(evaluation.before.values().get("regionId")), regions)))) {
                throw conflict("Google Place ID는 실제 저장될 해외 지역에서만 사용할 수 있습니다. 지역 선택을 확인해 주세요.");
            }
            Long checkedTarget = target;
            var check = context.placeChecks.computeIfAbsent(targetKey, ignored -> timed(context, "matching", () ->
                    updateLookup(context).checkExcluding(query(item, resolution.regionId(), evaluation.before), checkedTarget)));
            if (check.confirmed()) evaluation.errors.add("Google Place ID가 다른 여행지와 중복됩니다.");
        }
        evaluation.status = "MATCHED";
        evaluation.message = evaluation.plan.protectedFields().isEmpty() ? "보강할 필드를 선택해 주세요."
                : "선택한 필드만 업데이트합니다.";
    }

    private static Set<String> ticketFields(DestinationUpdateMerge.Plan plan) {
        Set<String> fields = plan.changes().stream().filter(DestinationUpdatePreview.Change::selected)
                .map(DestinationUpdatePreview.Change::path).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (fields.contains("latitude") || fields.contains("longitude")) fields.addAll(Set.of("latitude", "longitude"));
        return Set.copyOf(fields);
    }

    private DestinationDuplicateService.UpdateLookup updateLookup(PreviewFile context) {
        if (context.duplicateLookup == null) context.duplicateLookup = duplicateService.openUpdateLookup();
        return context.duplicateLookup;
    }

    private static <T> T timed(PreviewFile context, String stage, Supplier<T> action) {
        long started = System.nanoTime();
        log.debug("destination JSON stage started requestId={} stage={}", MDC.get("destinationImportRequest"), stage);
        try { return action.get(); }
        finally {
            long duration = System.nanoTime() - started;
            context.timings.merge(stage, duration, Long::sum);
            log.debug("destination JSON stage finished requestId={} stage={} elapsedMs={}", MDC.get("destinationImportRequest"), stage, duration / 1_000_000.0);
        }
    }

    public DestinationUpdatePreview.Result apply(DestinationUpdatePreview.ApplyRequest request, HttpSession session, Long userId) {
        if (request == null || session == null || userId == null || userId <= 0 || request.token() == null) {
            return result("INVALID", "유효한 로그인 세션과 미리보기 토큰이 필요합니다.", null, 0);
        }
        Ticket ticket;
        synchronized (session) {
            Map<String, Ticket> tickets = tickets(session);
            ticket = tickets.get(request.token());
            if (ticket == null || !Objects.equals(ticket.userId(), userId)) {
                return result("INVALID", "미리보기가 없거나 현재 사용자와 일치하지 않습니다.", null, 0);
            }
            if (!ticket.expiresAt().isAfter(Instant.now())) {
                tickets.remove(request.token());
                return result("INVALID", "미리보기가 만료되었습니다. 다시 확인해 주세요.", null, 0);
            }
            if (!request.confirmed() || (!ticket.plan().warnings().isEmpty() && !request.acknowledgeWarnings())
                    || (ticket.plan().hasRemovals() && !request.confirmRelationRemoval())) {
                return result("INVALID", "변경 내용, 경고 및 관계 제거 여부를 확인해 주세요.", ticket.before().destinationId(), 0);
            }
            // 모든 저장 시도는 한 번만 허용한다. 충돌·실패 뒤에도 다시 미리봐야 한다.
            Map<String, Receipt> receipts = receipts(session);
            receipts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(Instant.now()));
            if (receipts.size() >= MAX_TICKETS * 2) {
                var completed = receipts.entrySet().stream().filter(entry -> !entry.getValue().result().status().equals("RUNNING"))
                        .map(Map.Entry::getKey).findFirst();
                if (completed.isEmpty()) return result("INVALID", "처리 중인 저장 요청이 많습니다. 기존 요청의 결과부터 확인해 주세요.", ticket.before().destinationId(), 0);
                receipts.remove(completed.get());
            }
            receipts.put(request.token(), new Receipt(userId, Instant.now().plus(TOKEN_TTL),
                    result("RUNNING", "서버에서 저장 처리 중입니다. 저장을 재요청하지 말고 잠시 후 결과를 확인하세요.", ticket.before().destinationId(), 0)));
            tickets.remove(request.token());
            // A successful write or conflict requires a fresh DB snapshot on the next preview.
            // Other row tickets remain valid for the current multi-row apply operation.
            session.removeAttribute(CACHE_KEY);
        }
        DestinationUpdatePreview.Result outcome;
        try {
            outcome = new TransactionTemplate(transactionManager).execute(status -> applyTicket(ticket));
        } catch (UpdateConflict exception) {
            outcome = result("CONFLICT", exception.getMessage(), ticket.before().destinationId(), 0);
        } catch (org.springframework.transaction.TransactionSystemException | org.springframework.transaction.HeuristicCompletionException exception) {
            log.error("destination JSON transaction outcome unknown requestId={} exceptionType={}", MDC.get("destinationImportRequest"), exception.getClass().getSimpleName());
            outcome = result("UNKNOWN", "트랜잭션 완료 결과를 확정하지 못했습니다. 자동 재시도하지 말고 실제 여행지의 반영 상태를 확인해 주세요.", ticket.before().destinationId(), 0);
        } catch (RuntimeException exception) {
            log.error("destination JSON apply failed exceptionType={}", exception.getClass().getSimpleName());
            outcome = result("FAILED", "보강 저장에 실패하여 해당 여행지의 변경을 모두 취소했습니다.",
                    ticket.before().destinationId(), 0);
        }
        // TransactionTemplate has returned after commit/rollback. A lost HTTP response can be checked without another write.
        try {
            synchronized (session) {
                receipts(session).put(request.token(), new Receipt(userId, Instant.now().plus(TOKEN_TTL), outcome));
            }
        } catch (IllegalStateException expiredSession) {
            log.warn("destination JSON apply completed after session invalidation");
        }
        return outcome;
    }

    public DestinationUpdatePreview.Result applyStatus(String token, HttpSession session, Long userId) {
        if (session != null && userId != null && token != null) synchronized (session) {
            Receipt receipt = receipts(session).get(token);
            if (receipt != null && Objects.equals(receipt.userId(), userId) && receipt.expiresAt().isAfter(Instant.now())) return receipt.result();
        }
        return result("UNKNOWN", "서버의 저장 결과 기록을 확인할 수 없습니다. 실제 여행지의 반영 상태를 확인한 뒤 새로 접속해 주세요. 자동 재시도하지 마세요.", null, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Receipt> receipts(HttpSession session) {
        Object value = session.getAttribute(RECEIPTS_KEY);
        if (value instanceof Map<?, ?>) return (Map<String, Receipt>) value;
        Map<String, Receipt> receipts = new LinkedHashMap<>();
        session.setAttribute(RECEIPTS_KEY, receipts);
        return receipts;
    }

    private DestinationUpdatePreview.Result applyTicket(Ticket ticket) {
        Long id = ticket.before().destinationId();
        // 트랜잭션의 첫 DB 읽기는 여행지 잠금이다.
        long lockStarted = System.nanoTime();
        log.debug("destination JSON stage started requestId={} stage=saveSnapshotLock", MDC.get("destinationImportRequest"));
        DestinationUpdateSnapshot current = store.read(id, true);
        log.debug("destination JSON stage finished requestId={} stage=saveSnapshotLock elapsedMs={}", MDC.get("destinationImportRequest"), (System.nanoTime() - lockStarted) / 1_000_000.0);
        if (!sameSnapshot(ticket.before(), current)) throw conflict("미리보기 이후 여행지 정보가 변경되었습니다.");
        if (!ticket.previousProtection().equals(protections.protectedFields(id))) {
            throw conflict("미리보기 이후 보호 필드가 변경되었습니다.");
        }
        var item = ticket.parsed().item();
        validateIdentity(item, current);
        var resolution = resolveForTarget(item, ticket.parsed().path(), current, resolver.load(), new HashMap<>());
        validateSourceRegion(current, resolution.domestic());
        var validation = validator.validateUpdate(item, ticket.parsed().path(), resolution.domestic());
        if (resolution.domestic() == null || !resolution.errors().isEmpty() || !validation.errors().isEmpty()) {
            throw conflict("현재 기준 데이터에서 보강 내용을 검증하지 못했습니다.");
        }
        var rawPlan = merge.plan(current, item, resolution, ticket.fields(), ticket.replaceRelations());
        List<String> warnings = new ArrayList<>(validation.warnings().stream().map(DestinationImportIssue::text).toList());
        warnings.addAll(rawPlan.warnings());
        var plan = withWarnings(rawPlan, warnings);
        if (!plan.errors().isEmpty() || !canonical(plan.patch()).equals(canonical(ticket.plan().patch()))
                || !plan.protectedFields().equals(ticket.plan().protectedFields())
                || !plan.warnings().equals(ticket.plan().warnings()) || plan.hasRemovals() != ticket.plan().hasRemovals()) {
            throw conflict("미리보기 이후 적용 계획이 달라졌습니다.");
        }
        if (plan.patch().containsKey("googlePlaceId")
                && !plan.patch().containsKey("regionId")
                && !Boolean.FALSE.equals(domestic(numberId(current.values().get("regionId")), new HashMap<>()))) {
            throw conflict("Google Place ID는 실제 저장될 해외 지역에서만 사용할 수 있습니다.");
        }
        if (plan.patch().containsKey("googlePlaceId")
                && duplicateService.checkExcluding(query(item, resolution.regionId(), current), id).confirmed()) {
            throw conflict("Google Place ID가 다른 여행지와 중복됩니다.");
        }
        if (!plan.patch().isEmpty()) store.apply(id, current.type(), plan.patch());
        protections.protect(id, plan.protectedFields(), ticket.userId());
        return result(plan.patch().isEmpty() ? "NO_CHANGE" : "UPDATED",
                plan.patch().isEmpty() ? "값은 동일하며 선택한 필드의 보호를 유지했습니다." : "선택한 필드를 보강했습니다.",
                id, plan.patch().size());
    }

    private DestinationImportMasterResolver.Resolution resolveForTarget(DestinationImportItem item, String path,
            DestinationUpdateSnapshot target, DestinationImportMasterResolver.MasterData master,
            Map<Long, CountryCategory> regions) {
        List<String> categories = item.categories();
        if (categories.isEmpty() && item.mainCategory() != null) {
            categories = ((Collection<?>) target.values().getOrDefault("categories", List.of())).stream()
                    .map(id -> target.labels().get("category." + id)).filter(Objects::nonNull).toList();
        }
        DestinationSeason season = item.season();
        if (season == null && target.values().get("season") != null) {
            season = DestinationSeason.valueOf(target.values().get("season").toString());
        }
        DestinationImportItem effective = new DestinationImportItem(item.key(), target.type(), season, item.region(),
                item.latitude(), item.longitude(), item.external(), categories, item.mainCategory(), item.amenities(),
                item.translations(), item.info(), item.evidence());
        var resolution = resolver.resolve(effective, path, master);
        if (item.region().country() == null && (item.region().city() != null || item.region().district() != null)) {
            List<DestinationImportIssue> errors = new ArrayList<>(resolution.errors());
            errors.add(new DestinationImportIssue(path + ".region.country", "지역을 변경하려면 국가부터 지정해야 합니다."));
            return new DestinationImportMasterResolver.Resolution(null, null, List.of(), resolution.categoryIds(),
                    resolution.mainCategoryId(), resolution.mainCategoryName(), resolution.requestedMainCategoryId(),
                    resolution.amenityIds(), List.copyOf(errors));
        }
        if (item.region().country() != null || item.region().city() != null || item.region().district() != null) return resolution;
        Long regionId = numberId(target.values().get("regionId"));
        Boolean domestic = domestic(regionId, regions);
        return new DestinationImportMasterResolver.Resolution(regionId, domestic, List.of(), resolution.categoryIds(),
                resolution.mainCategoryId(), resolution.mainCategoryName(), resolution.requestedMainCategoryId(),
                resolution.amenityIds(), resolution.errors().stream()
                        .filter(error -> !error.path().equals(path + ".region")
                                && !error.path().startsWith(path + ".region."))
                        .toList());
    }

    private Boolean domestic(Long regionId, Map<Long, CountryCategory> regions) {
        Set<Long> visited = new HashSet<>();
        boolean found = false;
        while (regionId != null && visited.add(regionId) && visited.size() <= 10) {
            CountryCategory region = regions.computeIfAbsent(regionId, countryCategoryService::getById);
            if (region == null) return null;
            found = true;
            if ("KR".equalsIgnoreCase(region.getCode())) return true;
            regionId = region.getParentId();
        }
        return found && regionId == null ? false : null;
    }

    private static void validateIdentity(DestinationImportItem item, DestinationUpdateSnapshot target) {
        String contentId = item.external().tourApiContentId();
        String qid = item.external().wikidataQid();
        if ((contentId != null && (!DestinationService.KTO_TOUR_API_SOURCE_TYPE.equals(target.sourceType())
                || !contentId.equals(target.externalContentId())))
                || (qid != null && (!DestinationService.WIKIDATA_SOURCE_TYPE.equals(target.sourceType())
                || !qid.equalsIgnoreCase(target.externalContentId())))) {
            throw conflict("외부 원본 ID가 기존 여행지와 일치하지 않습니다.");
        }
    }

    private static void validateSourceRegion(DestinationUpdateSnapshot target, Boolean domestic) {
        if ((DestinationService.KTO_TOUR_API_SOURCE_TYPE.equals(target.sourceType()) && Boolean.FALSE.equals(domestic))
                || (DestinationService.WIKIDATA_SOURCE_TYPE.equals(target.sourceType()) && Boolean.TRUE.equals(domestic))) {
            throw conflict("기존 외부 원본의 국내/해외 분류와 다른 지역으로 변경할 수 없습니다.");
        }
    }

    private static DestinationDuplicateQuery query(DestinationImportItem item, Long regionId, DestinationUpdateSnapshot fallback) {
        List<String> names = item.translations().values().stream().map(DestinationImportItem.Text::name)
                .filter(Objects::nonNull).toList();
        String placeId = item.external().googlePlaceId();
        BigDecimal latitude = item.latitude();
        BigDecimal longitude = item.longitude();
        if (fallback != null) {
            if (placeId == null) placeId = (String) fallback.values().get("googlePlaceId");
            if (latitude == null) latitude = decimal(fallback.values().get("latitude"));
            if (longitude == null) longitude = decimal(fallback.values().get("longitude"));
        }
        String source = item.external().tourApiContentId() != null ? DestinationService.KTO_TOUR_API_SOURCE_TYPE
                : item.external().wikidataQid() != null ? DestinationService.WIKIDATA_SOURCE_TYPE : null;
        String externalId = item.external().tourApiContentId() != null ? item.external().tourApiContentId()
                : DestinationImportValidator.normalizeQid(item.external().wikidataQid());
        return new DestinationDuplicateQuery(source, externalId, placeId, names, regionId, latitude, longitude);
    }

    private static boolean sameSnapshot(DestinationUpdateSnapshot before, DestinationUpdateSnapshot current) {
        return current != null && Objects.equals(before.destinationId(), current.destinationId())
                && before.type() == current.type() && Objects.equals(before.sourceType(), current.sourceType())
                && Objects.equals(before.externalContentId(), current.externalContentId())
                && canonical(before.values()).equals(canonical(current.values()));
    }

    private static Object canonical(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, canonical(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) return collection.stream().map(DestinationImportUpdateService::canonical).toList();
        return value;
    }

    private static Long numberId(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static DestinationUpdateMerge.Plan withWarnings(DestinationUpdateMerge.Plan plan, List<String> warnings) {
        return new DestinationUpdateMerge.Plan(plan.changes(), plan.patch(), plan.protectedFields(),
                List.copyOf(warnings), plan.errors(), plan.hasRemovals());
    }
    private static BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    private static DestinationUpdatePreview failedPreview(String message) {
        return new DestinationUpdatePreview(List.of(new DestinationImportIssue("", message)), List.of());
    }
    private static DestinationUpdatePreview.Result result(String status, String message, Long id, int count) {
        return new DestinationUpdatePreview.Result(status, message, id, count);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Ticket> tickets(HttpSession session) {
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof Map<?, ?> ? (Map<String, Ticket>) value : new LinkedHashMap<>();
    }
    private static UpdateConflict conflict(String message) { return new UpdateConflict(message); }
    private static class UpdateConflict extends RuntimeException {
        UpdateConflict(String message) { super(message); }
    }
}
