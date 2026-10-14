package com.tripbora.service.travelinfo;

import com.tripbora.dto.AdminTravelInfoDetailDto;
import com.tripbora.dto.AdminTravelInfoListItemDto;
import com.tripbora.dto.InfoPeriodForm;
import com.tripbora.dto.TravelInfoForm;
import com.tripbora.dto.TravelInfoDetailDto;
import com.tripbora.dto.TravelInfoListItemDto;
import com.tripbora.dto.TravelInfoPeriodDto;
import com.tripbora.dto.TravelInfoTranslationForm;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.BookmarkTargetType;
import com.tripbora.model.FestivalInfo;
import com.tripbora.model.InfoCategory;
import com.tripbora.model.InfoImage;
import com.tripbora.model.InfoPeriod;
import com.tripbora.model.TravelInfo;
import com.tripbora.model.TravelInfoContentFormat;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.model.TravelInfoTranslation;
import com.tripbora.repository.bookmark.BookmarkMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.travelinfo.FestivalInfoMapper;
import com.tripbora.repository.travelinfo.TravelInfoMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.travelinfo.structured.StructuredContent;
import com.tripbora.service.travelinfo.structured.StructuredContentService;
import com.tripbora.service.travelinfo.structured.StructuredContentImageCleanup;
import com.tripbora.service.travelinfo.structured.StructuredImagePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TravelInfoService {

    private static final String KOREAN_CODE = SupportedLanguage.KOREAN.getLanguageTag();

    /** 번역 슬롯 언어. 한국어는 base 입력이 대신하므로 여기에서 뺀다. */
    private static final Set<String> SUPPORTED_TRANSLATION_CODES = SupportedLanguage.all().stream()
            .filter(language -> language != SupportedLanguage.KOREAN)
            .map(SupportedLanguage::getLanguageTag)
            .collect(Collectors.toUnmodifiableSet());

    private final TravelInfoMapper travelInfoMapper;
    /** 축제 글의 개최연도를 이 경로에서 바꾸지 못하게 확인할 때만 쓴다. */
    private final FestivalInfoMapper festivalInfoMapper;
    private final BookmarkMapper bookmarkMapper;
    private final InfoCategoryMapper infoCategoryMapper;
    private final PostContentSanitizer postContentSanitizer;
    private final FileUploadService fileUploadService;
    private final TravelInfoLocalizationService travelInfoLocalizationService;
    private final ReferenceNameLocalizationService referenceNameLocalizationService;
    /** STRUCTURED 본문 읽기·검사·정규화·파생 content. QUILL 경로는 쓰지 않는다. */
    private final StructuredContentService structuredContentService;

    @Transactional(readOnly = true)
    public List<AdminTravelInfoListItemDto> getAdminList(TravelInfoScope scope,
                                                         TravelInfoContentType contentType,
                                                         Long categoryId) {
        return travelInfoMapper.findAdminList(scope, contentType, categoryId);
    }

    /**
     * Home Hero. 관리자가 메인 추천으로 고른 일반 여행정보·여행가이드를 노출 순서대로 최대 limit 개.
     * 제목과 카테고리 이름은 공개 목록과 같은 규칙으로 요청 언어에 맞춘다. 고른 글이 없으면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public List<TravelInfoListItemDto> getHomeHeroItems(int limit, SupportedLanguage requestedLanguage) {
        List<TravelInfoListItemDto> items = travelInfoMapper.findHomeHeroItems(limit);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        localizePublicList(items, requestedLanguage);
        return items;
    }

    @Transactional(readOnly = true)
    public List<TravelInfoListItemDto> getPublicList(TravelInfoScope scope,
                                                      TravelInfoContentType contentType,
                                                      List<Long> categoryIds,
                                                      String keyword,
                                                      String eventStatus,
                                                      String sort,
                                                      long offset,
                                                      int limit) {
        return travelInfoMapper.findPublicList(
                scope, normalizePublicContentType(contentType), categoryIds,
                TravelInfoSearchKeyword.toLikeLiteral(keyword),
                TravelInfoSearchKeyword.toKoreanPrefixRegex(keyword),
                normalizeEventStatus(eventStatus), sort, offset, limit);
    }

    @Transactional(readOnly = true)
    public long countPublicList(TravelInfoScope scope,
                                TravelInfoContentType contentType,
                                List<Long> categoryIds,
                                String keyword,
                                String eventStatus) {
        return travelInfoMapper.countPublicList(
                scope, normalizePublicContentType(contentType), categoryIds,
                TravelInfoSearchKeyword.toLikeLiteral(keyword),
                TravelInfoSearchKeyword.toKoreanPrefixRegex(keyword),
                normalizeEventStatus(eventStatus));
    }

    /**
     * 정해 둔 행사 상태만 SQL 로 넘긴다. 그 밖의 값은 '전체' 와 같이 조건 없음으로 본다.
     *
     * <p>값을 SQL 문자열로 이어 붙이지 않고 정해진 분기에만 쓰므로 임의 값이 들어와도 안전하다.
     */
    private String normalizeEventStatus(String eventStatus) {
        if (eventStatus == null) {
            return null;
        }
        return switch (eventStatus) {
            case "ongoing", "upcoming", "ended" -> eventStatus;
            default -> null;
        };
    }

    /**
     * 공개 목록의 제목만 요청 언어로 바꿔 둔다. (목록 DTO 에는 본문이 없다)
     *
     * <p>번역은 목록 전체를 한 번에 읽는다. 조회 결과 DTO 는 이 요청에서만 쓰는 값이라
     * 표시할 제목을 그대로 담아도 관리자 화면이 쓰는 원문에는 영향이 없다.
     * 카테고리 이름은 아직 번역하지 않으므로 그대로 둔다.
     */
    @Transactional(readOnly = true)
    public void localizePublicList(List<TravelInfoListItemDto> travelInfoList,
                                   SupportedLanguage requestedLanguage) {
        if (travelInfoList == null || travelInfoList.isEmpty()) {
            // 볼 여행정보가 없으면 번역도 읽지 않는다.
            return;
        }

        Map<Long, String> baseTitles = new LinkedHashMap<>();
        for (TravelInfoListItemDto item : travelInfoList) {
            if (item != null && item.getId() != null) {
                baseTitles.putIfAbsent(item.getId(), item.getTitle());
            }
        }
        if (baseTitles.isEmpty()) {
            return;
        }

        Map<Long, TravelInfoTranslation> localized = travelInfoLocalizationService
                .resolveLocalizedContentByInfoIds(baseTitles, Map.of(), requestedLanguage);
        for (TravelInfoListItemDto item : travelInfoList) {
            if (item == null || item.getId() == null) {
                continue;
            }
            TravelInfoTranslation display = localized.get(item.getId());
            if (display != null && display.getTitle() != null) {
                item.setTitle(display.getTitle());
            }
        }

        localizeListCategoryNames(travelInfoList, requestedLanguage);
    }

    /**
     * 목록 카드의 카테고리 이름을 요청 언어로 바꾼다.
     *
     * <p>카테고리 번역도 목록 전체를 한 번에 읽는다. GENERAL / FESTIVAL 이 같은 카테고리
     * 번역 테이블을 쓰므로 유형을 나누지 않는다.
     */
    private void localizeListCategoryNames(List<TravelInfoListItemDto> travelInfoList,
                                           SupportedLanguage requestedLanguage) {
        Map<Long, String> baseCategoryNames = new LinkedHashMap<>();
        for (TravelInfoListItemDto item : travelInfoList) {
            if (item != null && item.getCategoryId() != null) {
                baseCategoryNames.putIfAbsent(item.getCategoryId(), item.getCategoryName());
            }
        }
        if (baseCategoryNames.isEmpty()) {
            return;
        }

        Map<Long, String> localizedNames = referenceNameLocalizationService
                .localizeInfoCategoryNames(baseCategoryNames, requestedLanguage);
        for (TravelInfoListItemDto item : travelInfoList) {
            if (item == null || item.getCategoryId() == null) {
                continue;
            }
            String name = localizedNames.get(item.getCategoryId());
            if (name != null) {
                item.setCategoryName(name);
            }
        }
    }

    /**
     * 공개 상세의 제목·본문을 요청 언어로 바꿔 둔다.
     *
     * <p>제목과 본문은 각각 따로 대체되므로 서로 다른 언어에서 올 수 있다.
     * 번역 본문도 원문과 똑같이 sanitize 를 거쳐 화면으로 나간다.
     * 카테고리 이름과 축제 상세정보는 아직 번역하지 않으므로 그대로 둔다.
     */
    @Transactional(readOnly = true)
    public void localizePublicDetail(TravelInfoDetailDto detail,
                                     SupportedLanguage requestedLanguage) {
        if (detail == null || detail.getId() == null) {
            return;
        }

        TravelInfoTranslation display;
        if (detail.getContentFormat() == TravelInfoContentFormat.STRUCTURED) {
            // 블록 글도 같은 번역 줄에서 고르므로 번역은 한 번만 읽어 둘 다에 쓴다.
            List<TravelInfoTranslation> translations =
                    travelInfoMapper.findTranslationsByInfoId(detail.getId());
            List<TravelInfoTranslation> safeTranslations = translations == null ? List.of() : translations;
            display = travelInfoLocalizationService.resolveLocalizedContent(detail.getId(),
                    detail.getTitle(), detail.getContent(), safeTranslations, requestedLanguage);
            localizeStructuredContent(detail, safeTranslations, requestedLanguage);
        } else {
            display = travelInfoLocalizationService.resolveLocalizedContent(
                    detail.getId(), detail.getTitle(), detail.getContent(), requestedLanguage);
        }
        if (display.getTitle() != null) {
            detail.setTitle(display.getTitle());
        }
        if (display.getContent() != null) {
            detail.setContent(postContentSanitizer.sanitize(display.getContent()));
        }
        detail.setCategoryName(referenceNameLocalizationService.localizeInfoCategoryName(
                detail.getCategoryId(), detail.getCategoryName(), requestedLanguage));
    }

    /**
     * 블록 글을 요청 언어로 바꾼다. 요청 언어 번역 글이 없는 칸은 원문(한국어) 글을 쓰고,
     * 이미지·블록 종류·순서는 언제나 원문 것이다. 한국어 요청이면 원문 그대로다.
     */
    private void localizeStructuredContent(TravelInfoDetailDto detail,
                                           List<TravelInfoTranslation> translations,
                                           SupportedLanguage requestedLanguage) {
        if (detail.getStructuredContent() == null || requestedLanguage == null
                || requestedLanguage == SupportedLanguage.KOREAN) {
            return;
        }
        TravelInfoTranslation requested = travelInfoLocalizationService.translationFor(
                travelInfoLocalizationService.orderedTranslations(translations),
                requestedLanguage.getLanguageTag());
        if (requested == null || requested.getStructuredText() == null) {
            return;
        }
        detail.setStructuredContent(structuredContentService.localize(
                detail.getStructuredContent(), requested.getStructuredText()));
    }

    @Transactional(readOnly = true)
    public void populatePublicListBookmarks(List<TravelInfoListItemDto> travelInfoList,
                                            Long currentUserId) {
        if (travelInfoList == null || travelInfoList.isEmpty()) {
            return;
        }
        travelInfoList.forEach(item -> item.setBookmarked(false));
        if (currentUserId == null) {
            return;
        }

        List<Long> infoIds = travelInfoList.stream()
                .map(TravelInfoListItemDto::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (infoIds.isEmpty()) {
            return;
        }

        Set<Long> bookmarkedIds = bookmarkMapper.findBookmarkedTargetIds(
                currentUserId, BookmarkTargetType.TRAVEL_INFO.name(), infoIds);
        Set<Long> safeBookmarkedIds = bookmarkedIds == null ? Set.of() : bookmarkedIds;
        travelInfoList.forEach(item -> item.setBookmarked(
                item.getId() != null && safeBookmarkedIds.contains(item.getId())));
    }

    @Transactional(readOnly = true)
    public void populatePublicDetailBookmark(TravelInfoDetailDto detail, Long currentUserId) {
        if (detail == null) {
            return;
        }
        detail.setBookmarked(false);
        if (currentUserId == null || detail.getId() == null) {
            return;
        }
        detail.setBookmarked(bookmarkMapper.findByUserAndTarget(
                currentUserId, BookmarkTargetType.TRAVEL_INFO.name(), detail.getId()) != null);
    }

    @Transactional
    public TravelInfoDetailDto getPublicDetail(Long id) {
        if (id == null || travelInfoMapper.incrementPublicViews(id) != 1) {
            throw notFound();
        }

        TravelInfoDetailDto detail = travelInfoMapper.findPublicDetailById(id);
        if (detail == null) {
            throw notFound();
        }
        detail.setContent(postContentSanitizer.sanitize(detail.getContent()));
        if (detail.getContentFormat() == TravelInfoContentFormat.STRUCTURED) {
            // 화면에는 JSON 문자열이 아니라 다시 검사한 블록을 넘긴다. 읽지 못하면 null 이고 파생 content 가 남는다.
            detail.setStructuredContent(structuredContentService.readStored(detail.getStructuredContentJson()));
        }

        if (detail.getContentType() == TravelInfoContentType.FESTIVAL) {
            List<InfoPeriod> periods = travelInfoMapper.findPeriodsByInfoId(id);
            detail.setPeriods(periods == null ? List.of() : periods.stream()
                    .map(period -> new TravelInfoPeriodDto(
                            period.getStartDate(), period.getEndDate()))
                    .toList());
        } else {
            detail.setPeriods(List.of());
        }
        return detail;
    }

    @Transactional(readOnly = true)
    public TravelInfo getById(Long id) {
        return requireTravelInfo(travelInfoMapper.findById(id));
    }

    @Transactional(readOnly = true)
    public String getThumbnailUrl(Long id) {
        InfoImage thumbnail = travelInfoMapper.findMainImageByInfoId(id);
        return thumbnail == null ? null : thumbnail.getImageUrl();
    }

    @Transactional(readOnly = true)
    public AdminTravelInfoDetailDto getAdminDetail(Long id) {
        TravelInfo travelInfo = requireTravelInfo(travelInfoMapper.findById(id));
        InfoCategory category = infoCategoryMapper.findById(travelInfo.getCategoryId());
        List<InfoPeriod> periods = travelInfo.getContentType() == TravelInfoContentType.FESTIVAL
                ? travelInfoMapper.findPeriodsByInfoId(id)
                : List.of();

        AdminTravelInfoDetailDto detail = new AdminTravelInfoDetailDto();
        detail.setId(travelInfo.getId());
        detail.setTitle(travelInfo.getTitle());
        detail.setContent(postContentSanitizer.sanitize(travelInfo.getContent()));
        detail.setContentFormat(travelInfo.getContentFormat());
        if (travelInfo.getContentFormat() == TravelInfoContentFormat.STRUCTURED) {
            detail.setStructuredContent(structuredContentService.readStored(travelInfo.getStructuredContent()));
        }
        detail.setScope(travelInfo.getScope());
        detail.setContentType(travelInfo.getContentType());
        detail.setCategoryId(travelInfo.getCategoryId());
        detail.setCategoryName(category == null ? null : category.getName());
        detail.setViews(travelInfo.getViews());
        detail.setCreatedAt(travelInfo.getCreatedAt());
        detail.setUpdatedAt(travelInfo.getUpdatedAt());
        detail.setPeriods(periods == null ? List.of() : periods);
        return detail;
    }

    @Transactional(readOnly = true)
    public TravelInfoForm getForm(Long id) {
        TravelInfo travelInfo = requireTravelInfo(travelInfoMapper.findById(id));
        travelInfo.setContent(postContentSanitizer.sanitize(travelInfo.getContent()));
        List<InfoPeriod> periods = travelInfoMapper.findPeriodsByInfoId(id);
        TravelInfoForm form = TravelInfoForm.from(travelInfo, periods);
        if (travelInfo.getContentFormat() == TravelInfoContentFormat.STRUCTURED) {
            // DB 가 다시 적은 JSON 모양이 아니라 서버 정규 JSON 으로 돌려준다.
            form.setStructuredContent(
                    structuredContentService.restoreContentJson(travelInfo.getStructuredContent()));
        }
        form.setTranslations(getTranslationForms(id));
        return form;
    }

    /**
     * 관리자 수정 화면 복원용. 저장된 줄이 있으면 슬롯에 채우고, 없으면 빈 슬롯을 돌려준다.
     *
     * <p>슬롯은 언어 코드로 찾아 채운다. 자리 번호에 뜻을 두지 않으므로 저장된 언어가
     * 몇 개든 나머지 언어는 빈 칸으로 남는다.
     */
    @Transactional(readOnly = true)
    public List<TravelInfoTranslationForm> getTranslationForms(Long travelInfoId) {
        List<TravelInfoTranslationForm> slots = TravelInfoTranslationForm.newTranslationSlots();
        if (travelInfoId == null) {
            return slots;
        }

        Map<String, TravelInfoTranslationForm> slotsByLanguage = new LinkedHashMap<>();
        for (TravelInfoTranslationForm slot : slots) {
            slotsByLanguage.putIfAbsent(slot.getLanguageCode(), slot);
        }

        List<TravelInfoTranslation> stored = travelInfoMapper.findTranslationsByInfoId(travelInfoId);
        if (stored != null) {
            for (TravelInfoTranslation translation : stored) {
                if (translation == null || translation.getLanguageCode() == null) {
                    continue;
                }
                TravelInfoTranslationForm slot = slotsByLanguage.get(translation.getLanguageCode());
                if (slot == null) {
                    // 슬롯에 없는 언어가 남아 있어도 화면에는 그리지 않는다.
                    continue;
                }
                slot.setTitle(translation.getTitle() == null ? "" : translation.getTitle());
                slot.setContent(translation.getContent() == null ? "" : translation.getContent());
                if (translation.getStructuredText() != null) {
                    slot.setStructuredText(
                            structuredContentService.restoreTextJson(translation.getStructuredText()));
                }
            }
        }
        return slots;
    }

    @Transactional
    public Long create(TravelInfoForm form, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("관리자 정보를 확인할 수 없습니다.");
        }

        ValidatedTravelInfo validated = validate(form);
        requireHomeFeaturedThumbnail(form, null);
        String newThumbnailUrl = saveNewThumbnail(form.getThumbnailFile());
        boolean lifecycleRegistered = false;
        try {
            lifecycleRegistered = registerFileLifecycle(newThumbnailUrl, List.of());

            TravelInfo travelInfo = new TravelInfo();
            travelInfo.setTitle(validated.title());
            travelInfo.setContent(validated.content());
            travelInfo.setContentFormat(validated.contentFormat());
            travelInfo.setStructuredContent(validated.structuredJson());
            travelInfo.setScope(form.getScope());
            travelInfo.setContentType(form.getContentType());
            travelInfo.setCategoryId(form.getCategoryId());
            travelInfo.setViews(0);
            travelInfo.setUserId(userId);

            if (travelInfoMapper.insertTravelInfo(travelInfo) != 1 || travelInfo.getId() == null) {
                throw new IllegalStateException("여행정보 저장에 실패했습니다.");
            }
            insertPeriods(travelInfo.getId(), validated.periods());
            // base 저장과 같은 트랜잭션에서 번역까지 끝낸다. ko 는 base 값으로 맞춰진다.
            saveValidatedTranslations(travelInfo.getId(), validated, form.getTranslations());
            if (newThumbnailUrl != null) {
                insertThumbnail(travelInfo.getId(), newThumbnailUrl);
            }
            if (form.getContentType() != TravelInfoContentType.FESTIVAL) {
                // 공용 insert 는 DB 기본값(미노출, 순서 1)으로 넣는다. 일반 정보·가이드만 입력값으로 맞춘다.
                travelInfoMapper.updateHomeFeatured(
                        travelInfo.getId(), form.isHomeFeatured(), form.getHomeFeaturedOrder());
            }
            return travelInfo.getId();
        } catch (RuntimeException exception) {
            if (!lifecycleRegistered) {
                deleteFileSafely(newThumbnailUrl);
            }
            throw exception;
        }
    }

    @Transactional
    public void update(Long id, TravelInfoForm form) {
        TravelInfo travelInfo = requireTravelInfo(travelInfoMapper.findByIdForUpdate(id));
        requireSameContentFormat(travelInfo, form);
        // 잠근 줄의 수정 전 본문 이미지. 수정 뒤 빠진 것만 정리 후보가 된다.
        Set<String> previousContentImages = contentImageUrls(travelInfo);
        ValidatedTravelInfo validated = validate(form);
        // 이 폼으로도 축제 기간을 고칠 수 있다. 축제 전용 화면과 같은 규칙을 여기서도 지킨다.
        requireSameFestivalEventYear(id, validated.periods());
        requireHomeFeaturedThumbnail(form, id);

        boolean replaceThumbnail = hasNewThumbnail(form.getThumbnailFile());
        boolean deleteThumbnail = !replaceThumbnail && form.isRemoveThumbnail();
        List<String> previousThumbnailUrls = replaceThumbnail || deleteThumbnail
                ? mainThumbnailUrls(id)
                : List.of();
        String newThumbnailUrl = replaceThumbnail ? saveNewThumbnail(form.getThumbnailFile()) : null;
        boolean lifecycleRegistered = false;

        try {
            lifecycleRegistered = registerFileLifecycle(newThumbnailUrl, previousThumbnailUrls);

            travelInfo.setTitle(validated.title());
            travelInfo.setContent(validated.content());
            travelInfo.setScope(form.getScope());
            travelInfo.setContentType(form.getContentType());
            travelInfo.setCategoryId(form.getCategoryId());

            if (travelInfoMapper.updateTravelInfo(travelInfo) != 1) {
                throw notFound();
            }
            // 공용 updateTravelInfo 가 파생 content 까지 바꾸고, 원문 블록 JSON 은 STRUCTURED 전용 update 가 바꾼다.
            if (validated.structured() != null
                    && travelInfoMapper.updateStructuredContent(id, validated.structuredJson()) != 1) {
                throw new IllegalStateException("구조화 콘텐츠 저장에 실패했습니다.");
            }
            // 위에서 잠그고 갱신한 같은 줄이라 결과 건수를 다시 확인하지 않는다.
            // 축제는 validate 에서 이미 노출이 꺼져 있고, 순서 입력이 없으므로 저장된 순서를 그대로 쓴다.
            travelInfoMapper.updateHomeFeatured(id, form.isHomeFeatured(),
                    form.getContentType() == TravelInfoContentType.FESTIVAL
                            ? travelInfo.getHomeFeaturedOrder()
                            : form.getHomeFeaturedOrder());
            travelInfoMapper.deletePeriodsByInfoId(id);
            insertPeriods(id, validated.periods());
            // base 수정과 같은 트랜잭션에서 번역까지 끝낸다. ko 는 base 값으로 맞춰진다.
            saveValidatedTranslations(id, validated, form.getTranslations());

            if (replaceThumbnail || deleteThumbnail) {
                travelInfoMapper.deleteMainImagesByInfoId(id);
                if (newThumbnailUrl != null) {
                    insertThumbnail(id, newThumbnailUrl);
                }
            }

            // DB 쓰기를 모두 마친 뒤 고른다. 새 본문에서 빠졌고 다른 글도 쓰지 않는 이미지만 commit 뒤에 지운다.
            if (validated.structured() != null) {
                Set<String> removedContentImages = StructuredContentImageCleanup.removedImages(previousContentImages,
                        structuredContentService.collectImageUrls(validated.structured().content()),
                        StructuredImagePolicy.TRAVEL_INFO);
                scheduleContentImageCleanup(removedContentImages);
            }

            if (!lifecycleRegistered) {
                deleteFilesSafely(previousThumbnailUrls);
            }
        } catch (RuntimeException exception) {
            if (!lifecycleRegistered) {
                deleteFileSafely(newThumbnailUrl);
            }
            throw exception;
        }
    }

    @Transactional
    public void delete(Long id) {
        TravelInfo travelInfo = requireTravelInfo(travelInfoMapper.findByIdForUpdate(id));
        Set<String> contentImages = contentImageUrls(travelInfo);
        List<String> previousThumbnailUrls = mainThumbnailUrls(id);
        boolean lifecycleRegistered = registerFileLifecycle(null, previousThumbnailUrls);
        bookmarkMapper.deleteByTarget(BookmarkTargetType.TRAVEL_INFO.name(), id);
        if (travelInfoMapper.deleteTravelInfo(id) != 1) {
            throw notFound();
        }
        // 줄을 지운 뒤에 센다. 다른 글이 아직 쓰는 이미지는 남기고, 나머지는 commit 뒤에 지운다.
        scheduleContentImageCleanup(contentImages);
        if (!lifecycleRegistered) {
            deleteFilesSafely(previousThumbnailUrls);
        }
    }

    /**
     * STRUCTURED 글이 쓰는 본문 이미지 url. QUILL 글이거나 저장된 JSON 을 읽지 못하면 빈 집합이다.
     * (읽지 못한 글의 파일은 지우지 않고 남긴다)
     */
    private Set<String> contentImageUrls(TravelInfo travelInfo) {
        if (travelInfo.getContentFormat() != TravelInfoContentFormat.STRUCTURED) {
            return Set.of();
        }
        return StructuredContentImageCleanup.imageUrls(structuredContentService,
                travelInfo.getStructuredContent(), StructuredImagePolicy.TRAVEL_INFO);
    }

    /**
     * 본문 이미지는 DB 변경이 commit 된 뒤에만 지운다. rollback 이면 아무것도 지우지 않는다.
     * (업로드 API 와 저장 트랜잭션은 따로라, 실패한 저장이 이미 올린 새 이미지를 지우지도 않는다)
     * 트랜잭션 밖에서 불리면 썸네일과 같이 바로 지운다.
     */
    private void scheduleContentImageCleanup(Set<String> imageUrls) {
        StructuredContentImageCleanup.afterCommit(imageUrls, StructuredImagePolicy.TRAVEL_INFO,
                url -> travelInfoMapper.countStructuredContentReferences(url) == 0,
                fileUploadService::deleteTravelInfoContentImage);
    }

    /**
     * 여행정보 번역을 언어 한 줄씩 저장한다. GENERAL / FESTIVAL 이 같이 쓰는 공통 API 다.
     *
     * <p>한국어는 화면의 제목·본문 입력이 그대로 ko 줄이 된다. 그래서 번역 입력에 ko 슬롯이
     * 섞여 들어와도 쓰지 않는다 — base 를 번역 입력으로 덮어쓰는 길은 두지 않는다.
     *
     * <p>나머지 언어는 제목·본문 중 하나라도 실제 값이 있으면 남기고, 둘 다 비면 그 언어 줄만
     * 지운다. 언어별로 따로 처리하므로 한 언어를 지워도 다른 언어 줄은 그대로다.
     *
     * @param baseTitle   travel_info 에 저장한 원문 제목
     * @param baseContent travel_info 에 저장한 원문 본문
     */
    @Transactional
    public void saveTranslations(Long travelInfoId,
                                 String baseTitle,
                                 String baseContent,
                                 List<TravelInfoTranslationForm> translationForms) {
        saveTranslationRows(travelInfoId, koreanTranslation(travelInfoId, baseTitle, baseContent),
                translationForms, form -> translationOf(travelInfoId, form));
    }

    /** 검증을 마친 작성 방식에 맞는 번역 저장으로 보낸다. */
    private void saveValidatedTranslations(Long travelInfoId,
                                           ValidatedTravelInfo validated,
                                           List<TravelInfoTranslationForm> translationForms) {
        if (validated.structured() == null) {
            saveTranslations(travelInfoId, validated.title(), validated.content(), translationForms);
            return;
        }
        saveStructuredTranslations(travelInfoId, validated.title(), validated.structured(),
                translationForms);
    }

    /**
     * STRUCTURED 글의 번역. 언어마다 structured_text(번역 글 정규 JSON)와 그 언어 기준 파생 content 를 함께 저장한다.
     *
     * <p>ko 줄은 base 제목과 base 파생 content 를 그대로 쓴다. (base JSON 이 이미 한국어 글이라 structured_text 는 비운다)
     * 파생 content 는 escape 를 마친 값이라 sanitizer 를 다시 거치지 않는다.
     * 번역 글은 원문 구조에 맞춰 정리한 뒤 블록 종류별 규칙으로 검사하므로, 잘못된 언어가 있으면 저장 전체가 되돌려진다.
     */
    private void saveStructuredTranslations(Long travelInfoId,
                                            String baseTitle,
                                            StructuredContentService.PreparedContent structured,
                                            List<TravelInfoTranslationForm> translationForms) {
        TravelInfoTranslation korean = new TravelInfoTranslation();
        korean.setTravelInfoId(travelInfoId);
        korean.setLanguageCode(KOREAN_CODE);
        korean.setTitle(nonBlankTitle(baseTitle));
        korean.setContent(structured.derivedContent());
        saveTranslationRows(travelInfoId, korean, translationForms,
                form -> structuredTranslationOf(travelInfoId, form, structured.content()));
    }

    /**
     * 언어 한 줄씩 저장하는 공통 흐름. ko 줄을 먼저 맞추고, 화면 슬롯 언어만 한 번씩 저장한다.
     *
     * @param translator 번역 입력 한 칸을 저장할 줄로 바꾼다. (작성 방식마다 다르다)
     */
    private void saveTranslationRows(Long travelInfoId,
                                     TravelInfoTranslation korean,
                                     List<TravelInfoTranslationForm> translationForms,
                                     Function<TravelInfoTranslationForm, TravelInfoTranslation> translator) {
        if (travelInfoId == null) {
            return;
        }

        // 기존 줄은 한 번만 읽고 언어 코드로 찾아 쓴다.
        Map<String, TravelInfoTranslation> existing = new LinkedHashMap<>();
        List<TravelInfoTranslation> stored = travelInfoMapper.findTranslationsByInfoId(travelInfoId);
        if (stored != null) {
            for (TravelInfoTranslation translation : stored) {
                if (translation != null && translation.getLanguageCode() != null) {
                    existing.putIfAbsent(translation.getLanguageCode(), translation);
                }
            }
        }

        saveTranslation(korean, existing.containsKey(KOREAN_CODE));

        if (translationForms == null) {
            return;
        }
        Set<String> handledLanguages = new HashSet<>();
        for (TravelInfoTranslationForm form : translationForms) {
            if (form == null || form.getLanguageCode() == null) {
                continue;
            }
            String languageCode = form.getLanguageCode();
            if (!SUPPORTED_TRANSLATION_CODES.contains(languageCode)) {
                // 화면이 정한 슬롯 언어만 저장한다. ko 슬롯과 임의 언어 코드는 무시한다.
                continue;
            }
            if (!handledLanguages.add(languageCode)) {
                // 같은 언어가 두 번 들어오면 앞의 값만 쓴다. (UNIQUE 충돌을 만들지 않는다)
                continue;
            }
            saveTranslation(translator.apply(form), existing.containsKey(languageCode));
        }
    }

    /** 값이 아예 없으면 그 언어 줄을 남기지 않는다. */
    private void saveTranslation(TravelInfoTranslation translation, boolean exists) {
        if (isEmpty(translation)) {
            if (exists) {
                travelInfoMapper.deleteTranslation(
                        translation.getTravelInfoId(), translation.getLanguageCode());
            }
            return;
        }
        if (exists) {
            travelInfoMapper.updateTranslation(translation);
        } else {
            travelInfoMapper.insertTranslation(translation);
        }
    }

    private TravelInfoTranslation koreanTranslation(Long travelInfoId,
                                                    String baseTitle,
                                                    String baseContent) {
        TravelInfoTranslation translation = new TravelInfoTranslation();
        translation.setTravelInfoId(travelInfoId);
        translation.setLanguageCode(KOREAN_CODE);
        translation.setTitle(nonBlankTitle(baseTitle));
        translation.setContent(nonBlankContent(baseContent));
        return translation;
    }

    private TravelInfoTranslation translationOf(Long travelInfoId,
                                                TravelInfoTranslationForm form) {
        TravelInfoTranslation translation = new TravelInfoTranslation();
        translation.setTravelInfoId(travelInfoId);
        translation.setLanguageCode(form.getLanguageCode());
        translation.setTitle(nonBlankTitle(form.getTitle()));
        translation.setContent(nonBlankContent(form.getContent()));
        return translation;
    }

    /**
     * STRUCTURED 번역 한 칸. Quill 본문 칸(content)은 쓰지 않는다.
     * 덮어쓸 글이 없으면 structured_text 와 content 를 모두 비워 원문(ko) 글로 대체되게 한다.
     */
    private TravelInfoTranslation structuredTranslationOf(Long travelInfoId,
                                                          TravelInfoTranslationForm form,
                                                          StructuredContent base) {
        StructuredContentService.PreparedText text = structuredContentService.prepareTranslation(
                base, form.getStructuredText(), languageLabel(form.getLanguageCode()));
        TravelInfoTranslation translation = new TravelInfoTranslation();
        translation.setTravelInfoId(travelInfoId);
        translation.setLanguageCode(form.getLanguageCode());
        translation.setTitle(nonBlankTitle(form.getTitle()));
        translation.setStructuredText(text.json());
        translation.setContent(text.derivedContent());
        return translation;
    }

    private String languageLabel(String languageCode) {
        return SupportedLanguage.fromLanguageTag(languageCode)
                .map(SupportedLanguage::getDisplayName)
                .orElse(languageCode);
    }

    private boolean isEmpty(TravelInfoTranslation translation) {
        return translation.getTitle() == null && translation.getContent() == null
                && translation.getStructuredText() == null;
    }

    private String nonBlankTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.strip();
    }

    /** 번역 본문도 원문과 같은 sanitize 를 거친다. 태그만 남은 Quill HTML 은 값이 없는 것으로 본다. */
    private String nonBlankContent(String content) {
        String sanitized = postContentSanitizer.sanitize(content);
        return TravelInfoContent.hasContent(sanitized) ? sanitized : null;
    }

    private boolean hasNewThumbnail(MultipartFile thumbnailFile) {
        return thumbnailFile != null && !thumbnailFile.isEmpty();
    }

    private String saveNewThumbnail(MultipartFile thumbnailFile) {
        if (!hasNewThumbnail(thumbnailFile)) {
            return null;
        }
        try {
            return fileUploadService.saveTravelInfoThumbnail(thumbnailFile);
        } catch (IllegalArgumentException exception) {
            throw new TravelInfoValidationException("thumbnailFile", exception.getMessage());
        }
    }

    private void insertThumbnail(Long infoId, String imageUrl) {
        InfoImage thumbnail = new InfoImage();
        thumbnail.setImageUrl(imageUrl);
        thumbnail.setIsMain(true);
        thumbnail.setOrderIndex(1);
        thumbnail.setInfoId(infoId);
        if (travelInfoMapper.insertInfoImage(thumbnail) != 1) {
            throw new IllegalStateException("여행정보 썸네일 저장에 실패했습니다.");
        }
    }

    private TravelInfoContentType normalizePublicContentType(TravelInfoContentType contentType) {
        return contentType == null ? TravelInfoContentType.GENERAL : contentType;
    }

    private List<String> mainThumbnailUrls(Long infoId) {
        List<String> urls = travelInfoMapper.findMainImageUrlsByInfoId(infoId);
        return urls == null ? List.of() : urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();
    }

    private boolean registerFileLifecycle(String newThumbnailUrl, List<String> previousThumbnailUrls) {
        if (newThumbnailUrl == null && previousThumbnailUrls.isEmpty()) {
            return false;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteFilesSafely(previousThumbnailUrls);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteFileSafely(newThumbnailUrl);
                }
            }
        });
        return true;
    }

    private void deleteFilesSafely(List<String> imageUrls) {
        imageUrls.forEach(this::deleteFileSafely);
    }

    private void deleteFileSafely(String imageUrl) {
        if (imageUrl == null) {
            return;
        }
        try {
            fileUploadService.deleteTravelInfoThumbnail(imageUrl);
        } catch (RuntimeException exception) {
            log.warn("여행정보 썸네일 파일을 정리하지 못했습니다: {}", imageUrl, exception);
        }
    }

    private ValidatedTravelInfo validate(TravelInfoForm form) {
        if (form == null) {
            throw new TravelInfoValidationException(null, "여행정보를 입력해 주세요.");
        }

        String title = form.getTitle() == null ? "" : form.getTitle().strip();
        form.setTitle(title);
        if (title.isEmpty()) {
            throw new TravelInfoValidationException("title", "제목을 입력해 주세요.");
        }
        if (title.length() > 255) {
            throw new TravelInfoValidationException("title", "제목은 255자 이하로 입력해 주세요.");
        }
        if (form.getContentType() == null) {
            throw new TravelInfoValidationException("contentType", "여행정보 유형을 선택해 주세요.");
        }
        if (form.getContentType() == TravelInfoContentType.GUIDE) {
            // 여행가이드는 지역에 매이지 않는다. 화면에서 값이 넘어와도 저장하지 않는다.
            form.setScope(null);
        } else if (form.getScope() == null) {
            throw new TravelInfoValidationException("scope", "국내/해외 범위를 선택해 주세요.");
        }

        String content;
        StructuredContentService.PreparedContent structured = null;
        if (form.getContentFormat() == null) {
            throw new TravelInfoValidationException("contentFormat", "작성 방식을 선택해 주세요.");
        }
        if (form.getContentFormat() == TravelInfoContentFormat.STRUCTURED) {
            // 1차 정책: 구조화 작성은 일반 여행정보·여행가이드만. 축제는 조용히 QUILL 로 바꾸지 않고 거부한다.
            if (form.getContentType() == TravelInfoContentType.FESTIVAL) {
                throw new TravelInfoValidationException("contentFormat",
                        "축제·행사는 구조화 에디터로 작성할 수 없습니다.");
            }
            // 검사를 마친 model 을 다시 쓴 정규 JSON 과, escape 를 마친 파생 content 를 쓴다.
            // 파생 content 는 Quill 용 sanitizer 를 다시 거치지 않는다. (4바이트 문자 숫자 참조가 풀리면 저장 실패)
            // 본문이 있는지는 블록 검사(블록 1개 이상, 블록별 필수 값)가 이미 정했다. 파생 content 는 검색/SEO 용이라
            // 글자가 없는 사진만의 글(alt·캡션을 비운 경우)이면 빈 값일 수 있고, 그것으로 저장을 막지 않는다.
            structured = structuredContentService.prepareContent(form.getStructuredContent());
            content = structured.derivedContent();
            form.setStructuredContent(structured.json());
        } else {
            content = postContentSanitizer.sanitize(form.getContent());
            form.setContent(content);
            if (!TravelInfoContent.hasContent(content)) {
                throw new TravelInfoValidationException("content", "본문을 입력해 주세요.");
            }
        }

        if (form.getCategoryId() == null) {
            throw new TravelInfoValidationException("categoryId", "정보 카테고리를 선택해 주세요.");
        }
        InfoCategory category = infoCategoryMapper.findById(form.getCategoryId());
        if (category == null) {
            throw new TravelInfoValidationException("categoryId", "존재하지 않는 정보 카테고리입니다.");
        }
        if (category.getContentType() != form.getContentType()) {
            throw new TravelInfoValidationException("categoryId",
                    "선택한 정보 카테고리의 유형이 여행정보 유형과 일치하지 않습니다.");
        }

        validateHomeFeatured(form);
        List<ValidatedPeriod> periods = validatePeriods(form);
        return new ValidatedTravelInfo(title, content, periods, form.getContentFormat(), structured);
    }

    /**
     * 작성 방식은 등록할 때 정해지고 수정으로 바꾸지 않는다. (QUILL ↔ STRUCTURED 모두 막는다)
     * 다른 방식으로 저장하면 원본이 사라지거나 파생 content 가 원본을 덮어쓰므로 조용히 맞추지 않고 거부한다.
     */
    private void requireSameContentFormat(TravelInfo stored, TravelInfoForm form) {
        if (form == null || stored.getContentFormat() != form.getContentFormat()) {
            throw new TravelInfoValidationException("contentFormat",
                    "작성 방식은 등록한 뒤 바꿀 수 없습니다.");
        }
    }

    /**
     * 메인 추천은 일반 여행정보와 여행가이드만 받는다.
     * 축제는 메인에 따로 섹션이 있으므로 화면에서 값이 넘어와도 저장하지 않는다.
     * 순서는 노출 여부와 상관없이 입력한 값을 저장하므로 언제나 확인한다.
     */
    private void validateHomeFeatured(TravelInfoForm form) {
        if (form.getContentType() == TravelInfoContentType.FESTIVAL) {
            form.setHomeFeatured(false);
            return;
        }
        Integer order = form.getHomeFeaturedOrder();
        if (order == null || order < 1) {
            throw new TravelInfoValidationException("homeFeaturedOrder",
                    "메인 노출 순서는 1 이상의 숫자로 입력해 주세요.");
        }
    }

    /**
     * 메인 추천은 대표 이미지를 그대로 쓰므로, 저장을 마쳤을 때 대표 이미지가 남아 있어야 한다.
     * 새 파일을 저장하기 전에 확인해서 거절된 요청이 업로드 파일을 남기지 않게 한다.
     *
     * @param existingId 수정이면 그 여행정보 번호, 신규 등록이면 null
     */
    private void requireHomeFeaturedThumbnail(TravelInfoForm form, Long existingId) {
        if (!form.isHomeFeatured() || hasNewThumbnail(form.getThumbnailFile())) {
            return;
        }
        // 새 파일이 없으면 저장된 대표 이미지를 지우지 않고 그대로 두는 경우만 통과한다.
        boolean keepsStoredThumbnail = existingId != null
                && !form.isRemoveThumbnail()
                && travelInfoMapper.findMainImageByInfoId(existingId) != null;
        if (!keepsStoredThumbnail) {
            throw new TravelInfoValidationException("homeFeatured",
                    "메인 추천 노출 시 대표 이미지가 필요합니다.");
        }
    }

    /**
     * 이미 개최연도를 가진 축제 글이면 그 연도를 벗어나는 기간 수정을 막는다.
     *
     * <p>festival_info.event_year 는 언제나 그 개최분 시작일의 연도다.
     * 이 폼으로 기간만 다음 해로 옮기면 지난 회차가 덮어써지고 두 값이 어긋난다.
     * 기간을 여러 개 넣는 경우에도 가장 이른 시작일이 그 개최분의 시작이다.
     */
    private void requireSameFestivalEventYear(Long id, List<ValidatedPeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            return;
        }
        FestivalInfo festivalInfo = festivalInfoMapper.findByInfoId(id);
        if (festivalInfo == null || festivalInfo.getEventYear() == null) {
            return;
        }
        int newEventYear = periods.stream()
                .map(ValidatedPeriod::startDate)
                .min(LocalDate::compareTo)
                .orElseThrow()
                .getYear();
        if (festivalInfo.getEventYear() != newEventYear) {
            throw new TravelInfoValidationException("periods",
                    "다른 연도 개최분은 새 축제로 등록해야 합니다. "
                            + "이 글은 " + festivalInfo.getEventYear() + "년 개최분입니다.");
        }
    }

    private List<ValidatedPeriod> validatePeriods(TravelInfoForm form) {
        if (form.getContentType() != TravelInfoContentType.FESTIVAL) {
            return List.of();
        }

        List<ValidatedPeriod> periods = new ArrayList<>();
        List<InfoPeriodForm> requestedPeriods = form.getPeriods() == null ? List.of() : form.getPeriods();
        for (InfoPeriodForm period : requestedPeriods) {
            if (period == null || (period.getStartDate() == null && period.getEndDate() == null)) {
                continue;
            }
            if (period.getStartDate() == null || period.getEndDate() == null) {
                throw new TravelInfoValidationException("periods", "축제 기간의 시작일과 종료일을 모두 입력해 주세요.");
            }
            if (period.getStartDate().isAfter(period.getEndDate())) {
                throw new TravelInfoValidationException("periods", "축제 기간의 시작일은 종료일보다 늦을 수 없습니다.");
            }
            periods.add(new ValidatedPeriod(period.getStartDate(), period.getEndDate()));
        }

        if (periods.isEmpty()) {
            throw new TravelInfoValidationException("periods", "축제 여행정보는 기간을 한 개 이상 입력해 주세요.");
        }

        Set<ValidatedPeriod> uniquePeriods = new HashSet<>();
        for (ValidatedPeriod period : periods) {
            if (!uniquePeriods.add(period)) {
                throw new TravelInfoValidationException("periods", "동일한 축제 기간을 중복해서 입력할 수 없습니다.");
            }
        }

        periods.sort(Comparator.comparing(ValidatedPeriod::startDate)
                .thenComparing(ValidatedPeriod::endDate));
        for (int index = 1; index < periods.size(); index++) {
            ValidatedPeriod previous = periods.get(index - 1);
            ValidatedPeriod current = periods.get(index);
            if (!current.startDate().isAfter(previous.endDate())) {
                throw new TravelInfoValidationException("periods", "서로 겹치는 축제 기간을 입력할 수 없습니다.");
            }
        }
        return periods;
    }

    private void insertPeriods(Long infoId, List<ValidatedPeriod> periods) {
        for (ValidatedPeriod validatedPeriod : periods) {
            InfoPeriod period = new InfoPeriod();
            period.setInfoId(infoId);
            period.setStartDate(validatedPeriod.startDate());
            period.setEndDate(validatedPeriod.endDate());
            if (travelInfoMapper.insertPeriod(period) != 1) {
                throw new IllegalStateException("여행정보 기간 저장에 실패했습니다.");
            }
        }
    }

    private TravelInfo requireTravelInfo(TravelInfo travelInfo) {
        if (travelInfo == null) {
            throw notFound();
        }
        return travelInfo;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "여행정보를 찾을 수 없습니다.");
    }

    /**
     * @param content    QUILL 은 sanitize 한 HTML, STRUCTURED 는 파생 HTML
     * @param structured STRUCTURED 일 때만 있다. (정규 JSON · 번역 기준 model · 파생 content)
     */
    private record ValidatedTravelInfo(String title, String content, List<ValidatedPeriod> periods,
                                       TravelInfoContentFormat contentFormat,
                                       StructuredContentService.PreparedContent structured) {

        String structuredJson() {
            return structured == null ? null : structured.json();
        }
    }

    private record ValidatedPeriod(LocalDate startDate, LocalDate endDate) {
    }
}
