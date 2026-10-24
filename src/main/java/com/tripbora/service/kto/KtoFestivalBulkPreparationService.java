package com.tripbora.service.kto;

import com.tripbora.dto.FestivalCreateForm;
import com.tripbora.dto.kto.*;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.service.travelinfo.FestivalRegistrationService;
import com.tripbora.service.travelinfo.FestivalValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** KTO가 정규화한 단건 자동입력 값을 같은 저장 폼으로 연결한다. 사진 파일은 내려받지 않는다. */
@Service
@RequiredArgsConstructor
public class KtoFestivalBulkPreparationService {
    private static final Duration PREVIEW_TTL = Duration.ofHours(1);
    private static final int MAX_PREVIEWS = 500;
    private final KtoFestivalService kto;
    private final KtoForeignTourService foreign;
    private final InfoCategoryMapper categories;
    private final FestivalRegistrationService registration;
    private final FestivalInfoMapper festivals;
    private final Map<String, Prepared> previews = new LinkedHashMap<>();

    // 동시에 여러 관리자가 미리보기를 눌러도 KTO 호출을 한꺼번에 몰아 보내지 않는다.
    public synchronized KtoFestivalBulkPreview preview(KtoFestivalBulkPreviewRequest request, Long userId) {
        requireOwner(userId);
        cleanup();
        if (request == null || request.contentId() == null || !request.contentId().matches("[0-9]{1,30}")
                || request.eventYear() < 1900 || request.eventYear() > 2999
                || request.posterSelection() == null || request.posterSelection().isBlank()
                || request.posterSelection().length() > 100) {
            throw invalid("축제·개최연도와 선택한 포스터를 확인해 주세요.");
        }
        for (Prepared cached : previews.values()) {
            if (cached.ownerId().equals(userId)
                    && cached.form().getKtoFestivalContentId().equals(request.contentId())
                    && cached.preview().eventYear() == request.eventYear()
                    && cached.form().getKtoThumbnailImageSelection().equals(request.posterSelection())) {
                return cached.preview();
            }
        }
        if (previews.size() >= MAX_PREVIEWS) throw invalid("준비 중인 축제가 많습니다. 잠시 후 다시 시도해 주세요.");
        KtoFestivalAutofillResponse detail = kto.getDetail(request.contentId());
        if (detail == null || !request.contentId().equals(detail.contentId())) {
            throw invalid("KTO 축제 상세정보가 선택한 행사와 일치하지 않습니다.");
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        FestivalCreateForm form = map(detail);
        form.setKtoThumbnailImageSelection(request.posterSelection());
        if (detail.eventStartDate() == null || detail.eventStartDate().getYear() != request.eventYear()) {
            errors.add("KTO 상세정보의 개최연도가 검색한 개최분과 다릅니다. 다시 검색해 주세요.");
        }
        KtoFestivalThumbnailCandidate poster = kto.getThumbnailCandidates(request.contentId()).stream()
                .filter(candidate -> request.posterSelection().equals(candidate.selectionKey()) && candidate.selectable())
                .findFirst().orElse(null);
        if (poster == null) errors.add("허용된 포스터를 다시 선택해 주세요. 이미지가 없는 행사는 일괄등록할 수 없습니다.");
        boolean duplicate = isRegistered(form);
        if (duplicate) errors.add("이미 등록된 해당 연도의 TourAPI 축제·행사입니다.");
        if (errors.isEmpty()) {
            try {
                registration.validateForCreate(form, userId);
            } catch (FestivalValidationException exception) {
                errors.add(exception.getMessage());
                duplicate = isRegistered(form);
            }
        }
        if (errors.isEmpty()) fillTranslations(form, warnings);
        // 번역까지 채운 최종 폼도 같은 단건 검증을 통과해야 한다.
        if (errors.isEmpty()) {
            try { registration.validateForCreate(form, userId); }
            catch (FestivalValidationException exception) {
                errors.add(exception.getMessage()); duplicate = isRegistered(form);
            }
        }
        String token = errors.isEmpty() ? UUID.randomUUID().toString() : null;
        KtoFestivalBulkPreview response = new KtoFestivalBulkPreview(token, request.contentId(), request.eventYear(),
                detail.title(), detail.eventStartDate(), detail.eventEndDate(), detail.address(), detail.categoryName(),
                poster, duplicate ? "DUPLICATE" : errors.isEmpty() ? "READY" : "NEEDS_REVIEW", errors, warnings);
        if (token != null) previews.put(token, new Prepared(userId, form, response, Instant.now().plus(PREVIEW_TTL)));
        return response;
    }

    public synchronized Prepared resolve(String token, Long userId) {
        requireOwner(userId);
        cleanup();
        Prepared prepared = previews.get(token);
        if (prepared == null || !prepared.ownerId().equals(userId)) {
            throw invalid("등록 준비가 만료되었거나 다른 관리자의 선택입니다. 등록 내용을 다시 확인해 주세요.");
        }
        return prepared;
    }

    public boolean isRegistered(FestivalCreateForm form) {
        return form.getStartDate() != null && festivals.countOccurrence("KTO_TOURAPI",
                form.getKtoFestivalContentId(), form.getStartDate().getYear()) > 0;
    }

    private FestivalCreateForm map(KtoFestivalAutofillResponse detail) {
        FestivalCreateForm form = new FestivalCreateForm();
        form.setTitle(detail.title()); form.setContent(paragraphs(detail.overview()));
        form.setStartDate(detail.eventStartDate()); form.setEndDate(detail.eventEndDate());
        InfoCategory category = categories.findAll().stream()
                .filter(candidate -> candidate.getContentType() == TravelInfoContentType.FESTIVAL
                        && Objects.equals(candidate.getName(), detail.categoryName()))
                .findFirst().orElse(null);
        form.setCategoryId(category == null ? null : category.getId());
        form.setEventPlace(detail.eventPlace()); form.setAddress(detail.address());
        form.setPlayTime(detail.playTime()); form.setUseTime(detail.useTimeFestival());
        form.setSponsor1(detail.sponsor1()); form.setSponsor1Tel(detail.sponsor1Tel());
        form.setSponsor2(detail.sponsor2()); form.setSponsor2Tel(detail.sponsor2Tel());
        form.setContactTel(detail.tel());
        form.setHomepageUrl(nonblank(detail.eventHomepage()) ? detail.eventHomepage() : detail.homepage());
        form.setKtoFestivalContentId(detail.contentId());
        return form;
    }

    private void fillTranslations(FestivalCreateForm form, List<String> warnings) {
        // 기존 외국어 매칭용 임시 조회값일 뿐, 등록 폼이나 DB에는 좌표를 저장하지 않는다.
        Optional<KtoFestivalCoordinates> coordinates = kto.getCoordinates(form.getKtoFestivalContentId());
        if (coordinates.isEmpty()) {
            warnings.add("KTO 좌표를 확인하지 못해 기존 외국어 축제 매칭을 실행하지 않았습니다.");
            return;
        }
        KtoFestivalCoordinates point = coordinates.get();
        // 단건 자동입력과 같은 확정 매칭만 사용한다. 후보를 임의로 고르거나 번역을 만들지 않는다.
        for (KtoForeignLanguage language : KtoForeignLanguage.values()) {
            try {
                KtoForeignTourMatchResponse match = foreign.match(language, form.getTitle(), point.mapX(), point.mapY(), true);
                if (match == null || match.status() != KtoForeignTourMatchResponse.Status.MATCHED || match.matched() == null) continue;
                var detail = foreign.getDetail(language, match.matched().contentId(), match.matched().contentTypeId());
                if (detail == null) continue;
                form.getTranslations().stream().filter(slot -> language.getLanguageTag().equals(slot.getLanguageCode()))
                        .findFirst().ifPresent(slot -> { slot.setTitle(detail.title()); slot.setContent(paragraphs(detail.overview())); });
                form.getFestivalInfoTranslations().stream().filter(slot -> language.getLanguageTag().equals(slot.getLanguageCode()))
                        .findFirst().ifPresent(slot -> {
                            slot.setEventPlace(detail.eventPlace()); slot.setAddress(detail.address());
                            slot.setPlayTime(detail.playTime()); slot.setUseTime(detail.useTime());
                            slot.setSponsor1(detail.sponsor1()); slot.setSponsor2(detail.sponsor2());
                        });
            } catch (KtoForeignTourApiException exception) {
                warnings.add(language.getLabel() + " 축제 정보를 불러오지 못해 해당 번역은 빈 값으로 유지됩니다.");
            }
        }
    }

    private void cleanup() { previews.values().removeIf(prepared -> prepared.expiresAt().isBefore(Instant.now())); }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    static String paragraphs(String value) {
        if (!nonblank(value)) return "";
        return value.lines().filter(line -> !line.isBlank()).map(line -> "<p>" + HtmlUtils.htmlEscape(line.strip()) + "</p>")
                .reduce("", String::concat);
    }
    private static void requireOwner(Long userId) { if (userId == null) throw invalid("관리자 정보를 확인할 수 없습니다."); }
    private static FestivalValidationException invalid(String message) { return new FestivalValidationException(null, message); }
    public record Prepared(Long ownerId, FestivalCreateForm form, KtoFestivalBulkPreview preview, Instant expiresAt) {}
}
