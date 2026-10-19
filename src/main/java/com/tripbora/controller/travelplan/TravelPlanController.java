package com.tripbora.controller.travelplan;

import com.tripbora.dto.TravelPlanAlternativeForm;
import com.tripbora.dto.TravelPlanCreateForm;
import com.tripbora.dto.TravelPlanItemCreateForm;
import com.tripbora.dto.TravelPlanDetailDto;
import com.tripbora.dto.TravelPlanItemUpdateForm;
import com.tripbora.dto.TravelPlanListItemDto;
import com.tripbora.dto.TravelPlanListPageDto;
import com.tripbora.dto.TravelPlanMembersDto;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.TravelPlanRole;
import com.tripbora.service.travelplan.TravelPlanAccessNotice;
import com.tripbora.service.travelplan.TravelPlanConflictException;
import com.tripbora.service.travelplan.TravelPlanDeleteService;
import com.tripbora.service.travelplan.TravelPlanFinalDeleteService;
import com.tripbora.service.travelplan.TravelPlanFinalReadService;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanInvitationService;
import com.tripbora.service.travelplan.TravelPlanService;
import com.tripbora.service.travelplan.TravelPlanValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.PropertyAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DefaultBindingErrorProcessor;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;

@Controller
@RequestMapping("/travel-plans")
@RequiredArgsConstructor
public class TravelPlanController {

    private static final String FORM_ATTRIBUTE = "travelPlanCreateForm";
    private static final String LIST_VIEW = "travelplan/list";
    private static final String DETAIL_VIEW = "travelplan/detail";
    private static final String DAY_DETAIL_VIEW = "travelplan/day-detail";
    /** 실시간 갱신이 가져가는 DAY 한 구역. 처음 그릴 때와 같은 fragment 다. */
    private static final String DAY_FRAGMENT_VIEW =
            "travelplan/fragments/schedule-day :: scheduleDay(plan=${plan}, days=${days},"
                    + " day=${day}, dayItems=${dayItems},"
                    + " alternativesByItemId=${alternativesByItemId}, dayOpen=${dayOpen})";
    /** 재연결 뒤 한 번에 따라잡을 때 쓰는 DAY 전체 묶음. */
    private static final String SCHEDULE_FRAGMENT_VIEW =
            "travelplan/fragments/schedule-day :: scheduleDays(plan=${plan}, days=${days},"
                    + " itemsByDayId=${itemsByDayId},"
                    + " alternativesByItemId=${alternativesByItemId})";
    /** 참여자 팝오버의 속. 처음 그릴 때와 실시간 갱신이 같은 fragment 를 쓴다. */
    private static final String MEMBERS_FRAGMENT_VIEW =
            "travelplan/fragments/members :: membersBody(planId=${planId},"
                    + " currentMember=${currentMember}, members=${members},"
                    + " pastMembers=${pastMembers}, memberCount=${memberCount},"
                    + " memberLimit=${memberLimit})";
    /** 방장에게만 있는 상단 액션. 방장이 바뀌면 이 조각만 다시 받아 간다. */
    private static final String OWNER_ACTIONS_FRAGMENT_VIEW =
            "travelplan/fragments/owner-actions :: ownerActions(travelPlan=${travelPlan},"
                    + " travelPlanInviteUrl=${travelPlanInviteUrl},"
                    + " travelPlanInviteActive=${travelPlanInviteActive},"
                    + " travelPlanInviteIssued=${travelPlanInviteIssued})";
    private static final String ITEM_FORM_ATTRIBUTE = "travelPlanItemCreateForm";

    /** 완료된 여행 한 쪽에 담는 수. 진행 중인 여행은 쪽을 나누지 않는다. */
    private static final int LIST_PAGE_SIZE = 8;

    /** 폼에 실제로 있는 필드만 필드 오류로 남길 수 있다. */
    private static final Set<String> FORM_FIELDS =
            Set.of("title", "startDate", "endDate", "displayName", "sourceCourseId");
    private static final String COURSE_SUBMISSIONS = TravelPlanController.class.getName() + ".courseSubmissions";
    private record CourseSubmission(Long userId, Long courseId, Long planId) { }

    /** 날짜로 읽을 수 없는 값이 왔을 때 그 칸에 내보낼 말. */
    private static final Map<String, String> DATE_BIND_MESSAGES = Map.of(
            "startDate", "여행 시작일을 올바른 날짜로 선택해 주세요.",
            "endDate", "여행 종료일을 올바른 날짜로 선택해 주세요.");

    private final TravelPlanService travelPlanService;
    private final TravelPlanInvitationService travelPlanInvitationService;
    /** 완료된 여행은 최종본에서만 읽는다. */
    private final TravelPlanFinalReadService travelPlanFinalReadService;
    /** 완료된 여행 지우기. 마지막 한 사람이 지우면 그 여행 자체가 사라진다. */
    private final TravelPlanFinalDeleteService travelPlanFinalDeleteService;
    /** 진행 중인 방 통째로 지우기. 방장만 할 수 있고 되돌릴 수 없다. */
    private final TravelPlanDeleteService travelPlanDeleteService;

    /**
     * 함께 계획하기 목록.
     *
     * <p>진행 중인 여행과 완료된 여행을 한 화면에 위아래로 함께 보여 준다.
     * 한쪽이 비어 있어도 구역은 그대로 두고 비었다는 말만 적는다.
     *
     * <p>쪽을 나누는 것은 계속 쌓이는 완료된 여행뿐이다. 진행 중인 여행은
     * 보통 몇 건뿐이라 전부 보여 준다. 그래서 주소에 남는 것도 완료된 여행의
     * 쪽 번호 하나뿐이고, 그 값은 여기서 안전한 값으로 다듬는다.
     */
    @GetMapping
    public String listWithCourse(@RequestParam(name = "completedPage", required = false) String completedPage,
                                 @RequestParam(name = "sourceCourseId", required = false) String sourceCourseId,
                                 @AuthenticationPrincipal CustomUserDetails userDetails,
                                 Model model, HttpSession session) {
        if (sourceCourseId != null) {
            try {
                Long courseId = Long.valueOf(sourceCourseId);
                var course = travelPlanService.getCourseTemplate(courseId);
                var form = new TravelPlanCreateForm();
                form.setTitle(course.getTitle());
                form.setSourceCourseId(courseId);
                form.setCreationToken(issueCourseToken(session, userDetails.getId(), courseId));
                model.addAttribute(FORM_ATTRIBUTE, form);
                model.addAttribute("sourceCourse", course);
            } catch (NumberFormatException | TravelPlanValidationException exception) {
                model.addAttribute("courseImportNotice", "가져올 여행코스를 찾을 수 없습니다. 코스를 다시 선택하거나 일반 여행계획을 만들어 주세요.");
            }
        }
        return list(completedPage, userDetails, model);
    }

    /** 상세의 모달에서 원본을 확인한다. 공개 조회와 달리 생성 준비는 인증이 필요하다. */
    @GetMapping("/course-template")
    public ResponseEntity<?> courseTemplate(@RequestParam Long sourceCourseId,
                                            @AuthenticationPrincipal CustomUserDetails userDetails,
                                            HttpSession session) {
        try {
            var course = travelPlanService.getCourseTemplate(sourceCourseId);
            return ResponseEntity.ok(Map.of(
                    "title", course.getTitle(), "regionLabel", course.getCountryLabel(),
                    "dayCount", course.getDayCount(), "durationLabel", course.getDurationLabel(),
                    "creationToken", issueCourseToken(session, userDetails.getId(), sourceCourseId)));
        } catch (TravelPlanValidationException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
        }
    }

    private String issueCourseToken(HttpSession session, Long userId, Long courseId) {
        synchronized (session) {
            Map<String, CourseSubmission> submissions = courseSubmissions(session);
            if (submissions.size() >= 20) submissions.remove(submissions.keySet().iterator().next());
            String token = UUID.randomUUID().toString();
            submissions.put(token, new CourseSubmission(userId, courseId, null));
            return token;
        }
    }

    private String list(String completedPage, CustomUserDetails userDetails, Model model) {
        Long userId = userDetails.getId();
        if (!model.containsAttribute(FORM_ATTRIBUTE)) {
            model.addAttribute(FORM_ATTRIBUTE, new TravelPlanCreateForm());
        }
        var form = (TravelPlanCreateForm) model.getAttribute(FORM_ATTRIBUTE);
        if (form.getSourceCourseId() != null && !model.containsAttribute("sourceCourse")) {
            try {
                model.addAttribute("sourceCourse", travelPlanService.getCourseTemplate(form.getSourceCourseId()));
            } catch (TravelPlanValidationException exception) {
                model.addAttribute("courseImportNotice", exception.getMessage());
            }
        }

        List<TravelPlanListItemDto> activePlans = travelPlanService.getActivePlans(userId);

        // 완료된 여행은 최종본에서만 읽는다. 원본 방을 다시 들여다보지 않는다.
        int completedCount = travelPlanFinalReadService.countCompletedPlans(userId);
        // 한 건도 없어도 1쪽은 있는 것으로 본다. (쪽 번호는 1부터)
        int totalPages = Math.max(1, (completedCount + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int currentPage = Math.min(Math.max(parsePage(completedPage), 1), totalPages);

        model.addAttribute("travelPlans", activePlans);
        model.addAttribute("completedTravelPlans", travelPlanFinalReadService.getCompletedPlans(
                userId, (currentPage - 1) * LIST_PAGE_SIZE, LIST_PAGE_SIZE));
        model.addAttribute("travelPlanPage", new TravelPlanListPageDto(
                activePlans.size(), completedCount, currentPage, totalPages, LIST_PAGE_SIZE));
        return LIST_VIEW;
    }

    /** 주소에서 받은 쪽 번호. 숫자가 아니면 첫 쪽으로 본다(범위는 부른 쪽에서 맞춘다). */
    private int parsePage(String page) {
        if (page == null) {
            return 1;
        }
        try {
            return Integer.parseInt(page.strip());
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    /**
     * 진행 중인 여행 계획을 통째로 지운다. 방장만 할 수 있다.
     *
     * <p>화면에 버튼이 보였는지는 여기서 보지 않는다. 방장인지도, 아직 진행 중인 방인지도
     * Service 가 다시 확인한다. 방장이 아니면 그 방이 있는지조차 알리지 않는다(404).
     *
     * <p>참여자를 한 명씩 내보내는 것이 아니라 방이 사라진다. 같은 방을 열어 두고 있던
     * 사람들은 실시간 알림을 받고 각자 목록으로 나온다.
     */
    @PostMapping("/{travelPlanId:\\d+}/delete")
    public String deletePlan(@PathVariable Long travelPlanId,
                             @AuthenticationPrincipal CustomUserDetails userDetails,
                             RedirectAttributes redirectAttributes) {
        travelPlanDeleteService.deletePlan(userDetails.getId(), travelPlanId);
        redirectAttributes.addFlashAttribute("travelPlanMessage", "여행 계획을 삭제했습니다.");
        // 방이 없어졌으므로 돌아갈 곳은 목록뿐이다.
        return "redirect:/travel-plans";
    }

    /**
     * 완료된 여행 상세. 읽기 전용이다.
     *
     * <p>완료 시점에 함께했던 사람만 볼 수 있고, 방장이든 아니든 같은 최종본을 본다.
     * 그 외에는 최종본의 존재 자체를 알리지 않는다.
     */
    @GetMapping("/{travelPlanId:\\d+}/final")
    public String finalDetail(@PathVariable Long travelPlanId,
                              @AuthenticationPrincipal CustomUserDetails userDetails,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        try {
            var finalPlan = travelPlanFinalReadService.getCompletedPlanDetail(
                    userDetails.getId(), travelPlanId);
            model.addAttribute("travelPlan", finalPlan.toPlannerDetail());
            model.addAttribute("finalViewerIsOwner", finalPlan.getMembers().stream().anyMatch(member ->
                    userDetails.getId().equals(member.getUserId())
                            && member.getRole() == TravelPlanRole.OWNER));
            model.addAttribute("planReadonly", true);
        } catch (ResponseStatusException exception) {
            // 여기서도 흰 오류 화면을 보여 주지 않는다.
            redirectAttributes.addFlashAttribute("travelPlanNotice",
                    TravelPlanAccessNotice.NO_ACCESS.message());
            return "redirect:/travel-plans";
        }
        return DETAIL_VIEW;
    }

    /**
     * 완료된 여행을 지운다.
     *
     * <p>함께한 사람이 아직 보관 중이면 내 목록에서만 치우고 최종본과 원본 방은 그대로 둔다.
     * 내가 마지막 한 사람이었으면 이제 아무도 볼 수 없는 여행이라 실제로 사라진다.
     * 어느 쪽이든 화면에 하는 말은 같다 — 남이 아직 보관 중인지는 알릴 일이 아니다.
     *
     * <p>지울 수 있는 것은 자기 것뿐이라 방장이라도 남의 완료본은 지울 수 없다.
     */
    @PostMapping("/{travelPlanId:\\d+}/final/delete")
    public String deleteFinalForMe(@PathVariable Long travelPlanId,
                                   @AuthenticationPrincipal CustomUserDetails userDetails,
                                   RedirectAttributes redirectAttributes) {
        try {
            travelPlanFinalDeleteService.deleteForMe(userDetails.getId(), travelPlanId);
            redirectAttributes.addFlashAttribute("travelPlanNotice",
                    "완료된 여행을 내 목록에서 삭제했습니다.");
        } catch (ResponseStatusException exception) {
            // 자기 것이 아니면 그 최종본이 있는지조차 알리지 않는다.
            redirectAttributes.addFlashAttribute("travelPlanNotice",
                    TravelPlanAccessNotice.NO_ACCESS.message());
        }
        return "redirect:/travel-plans";
    }

    /**
     * 날짜 칸의 변환 오류를 이 화면의 말로 바꾼다.
     *
     * <p>연도를 5자리로 적는 것처럼 날짜로 읽을 수조차 없는 값은 Service 까지 오지 못하고
     * 바인딩 단계에서 걸린다. 기본 문구는 사람이 읽을 것이 못 되므로,
     * 다른 검증 오류와 같은 자리에 같은 말투로 나가게 한다.
     */
    @InitBinder(FORM_ATTRIBUTE)
    void useReadableDateErrors(WebDataBinder binder) {
        binder.setBindingErrorProcessor(new DefaultBindingErrorProcessor() {
            @Override
            public void processPropertyAccessException(PropertyAccessException exception,
                                                       BindingResult bindingResult) {
                String field = exception.getPropertyName();
                String message = field == null ? null : DATE_BIND_MESSAGES.get(field);
                if (message == null) {
                    super.processPropertyAccessException(exception, bindingResult);
                    return;
                }
                // 적었던 값은 그대로 두고 문구만 바꾼다.
                bindingResult.addError(new FieldError(bindingResult.getObjectName(), field,
                        exception.getValue(), true,
                        new String[]{"travelPlan.invalid"}, null, message));
            }
        });
    }

    // 방 생성 폼
    @GetMapping("/new")
    public String newForm(Model model) {
        return "redirect:/travel-plans#travel-plan-create";
    }

    // 방 생성 처리
    @PostMapping(params = "sourceCourseId", headers = "X-Requested-With=XMLHttpRequest")
    public ResponseEntity<?> createFromCourseModal(@ModelAttribute(FORM_ATTRIBUTE) TravelPlanCreateForm form,
                                                   BindingResult bindingResult,
                                                   @AuthenticationPrincipal CustomUserDetails userDetails,
                                                   HttpSession session) {
        if (!bindingResult.hasErrors()) {
            try {
                if (form.getSourceCourseId() == null) {
                    throw new TravelPlanValidationException("sourceCourseId", "가져올 코스를 다시 선택해 주세요.");
                }
                Long planId = createFromCourse(session, userDetails.getId(), form);
                return ResponseEntity.ok(Map.of("redirectUrl", "/travel-plans/" + planId));
            } catch (TravelPlanValidationException exception) {
                rejectValidation(bindingResult, exception);
            }
        }
        List<Map<String, String>> errors = bindingResult.getAllErrors().stream()
                .map(error -> Map.of("field", error instanceof FieldError field ? field.getField() : "",
                        "message", error.getDefaultMessage() == null ? "입력 내용을 확인해 주세요." : error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    @PostMapping
    public String create(@ModelAttribute(FORM_ATTRIBUTE) TravelPlanCreateForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal CustomUserDetails userDetails,
                         Model model, HttpSession session) {
        // 날짜 형식 오류처럼 바인딩 단계에서 걸린 값은 서비스로 넘기지 않는다.
        if (bindingResult.hasErrors()) {
            return list(null, userDetails, model);
        }

        Long travelPlanId;
        try {
            travelPlanId = form.getSourceCourseId() != null
                    ? createFromCourse(session, userDetails.getId(), form)
                    : travelPlanService.createPlan(
                    userDetails.getId(),
                    form.getTitle(),
                    form.getStartDate(),
                    form.getEndDate(),
                    form.getDisplayName());
        } catch (TravelPlanValidationException exception) {
            rejectValidation(bindingResult, exception);
            return list(null, userDetails, model);
        }

        return "redirect:/travel-plans/" + travelPlanId;
    }

    /** 같은 폼의 재전송은 이미 생성한 방으로 보낸다. 실패한 요청은 재시도할 수 있다. */
    private Long createFromCourse(HttpSession session, Long userId, TravelPlanCreateForm form) {
        synchronized (session) {
            Map<String, CourseSubmission> submissions = courseSubmissions(session);
            CourseSubmission submission = submissions.get(form.getCreationToken());
            if (submission == null || !userId.equals(submission.userId())
                    || !form.getSourceCourseId().equals(submission.courseId())) {
                throw new TravelPlanValidationException("sourceCourseId", "코스 가져오기 화면이 만료되었습니다. 코스에서 다시 시작해 주세요.");
            }
            if (submission.planId() != null) return submission.planId();
            Long planId = travelPlanService.createPlan(userId, form.getTitle(), form.getStartDate(),
                    form.getEndDate(), form.getDisplayName(), form.getSourceCourseId());
            submissions.put(form.getCreationToken(), new CourseSubmission(userId, submission.courseId(), planId));
            return planId;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, CourseSubmission> courseSubmissions(HttpSession session) {
        var submissions = (Map<String, CourseSubmission>) session.getAttribute(COURSE_SUBMISSIONS);
        if (submissions == null) {
            submissions = new LinkedHashMap<>();
            session.setAttribute(COURSE_SUBMISSIONS, submissions);
        }
        return submissions;
    }

    // 방 기본 상세. 접근 권한은 Service 가 사용자 기준으로 확인한다.
    @GetMapping("/{travelPlanId:\\d+}")
    public String detail(@PathVariable Long travelPlanId,
                         @AuthenticationPrincipal CustomUserDetails userDetails,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (!model.containsAttribute(ITEM_FORM_ATTRIBUTE)) {
            model.addAttribute(ITEM_FORM_ATTRIBUTE, new TravelPlanItemCreateForm());
        }
        try {
            model.addAttribute("travelPlan",
                    travelPlanService.getActivePlanDetail(userDetails.getId(), travelPlanId));
        } catch (ResponseStatusException exception) {
            return redirectWithNotice(userDetails.getId(), travelPlanId, redirectAttributes);
        }
        addInviteState(model, userDetails.getId(), travelPlanId);
        return DETAIL_VIEW;
    }

    /**
     * 공동 편집방을 열지 못했다. 흰 오류 화면 대신 왜 못 여는지를 알리고 목록으로 보낸다.
     *
     * <p>무엇을 알릴지는 Service 가 정한다. 완료된 여행에 함께했던 사람에게만
     * 완료됐다고 알려 주고, 그 밖에는 방이 있는지조차 알리지 않는다.
     *
     * <p>완료된 여행 전용 화면이 생기면 여기 보낼 곳만 바꾸면 된다.
     */
    private String redirectWithNotice(Long userId, Long travelPlanId,
                                      RedirectAttributes redirectAttributes) {
        TravelPlanAccessNotice notice =
                travelPlanService.explainInaccessiblePlan(userId, travelPlanId);

        /*
          완료된 여행에 함께했던 사람이라면 볼 것이 있다.
          예전 편집방 주소로 들어와도 목록에서 다시 찾게 하지 않고 최종본으로 보낸다.
        */
        if (notice == TravelPlanAccessNotice.COMPLETED_PARTICIPANT) {
            return "redirect:/travel-plans/" + travelPlanId + "/final";
        }
        redirectAttributes.addFlashAttribute("travelPlanNotice", notice.message());
        return "redirect:/travel-plans";
    }

    /**
     * OWNER 의 초대 영역 상태.
     * 살아 있는 링크는 저장된 암호문을 풀어 지금 요청 기준 주소로 다시 만들어 준다.
     * 예전 방식으로 만들어져 풀 수 없는 링크는 주소 없이 "켜져 있음" 만 알린다.
     */
    private void addInviteState(Model model, Long userId, Long travelPlanId) {
        model.addAttribute("travelPlanInviteActive",
                travelPlanInvitationService.hasActiveInvitation(userId, travelPlanId));
        travelPlanInvitationService.findActiveInviteToken(userId, travelPlanId)
                .ifPresent(rawToken -> model.addAttribute("travelPlanInviteUrl",
                        ServletUriComponentsBuilder.fromCurrentContextPath()
                                .path("/travel-plans/invitations/")
                                .path(rawToken)
                                .toUriString()));
    }

    /** DAY 제목을 공동 편집 버전과 함께 저장한다. */
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/title")
    public ResponseEntity<?> updateDayTitle(@PathVariable Long travelPlanId,
                                            @PathVariable Long dayId,
                                            @RequestParam(required = false) String title,
                                            @RequestParam(required = false) Integer titleVersion,
                                            @AuthenticationPrincipal CustomUserDetails userDetails) {
        try {
            TravelPlanDay day = travelPlanService.updateDayTitle(
                    userDetails.getId(), travelPlanId, dayId, title, titleVersion);
            return ResponseEntity.ok(new DayTitleResponse(day.getTitle(), day.getTitleVersion()));
        } catch (TravelPlanValidationException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        } catch (TravelPlanConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", exception.getMessage()));
        }
    }

    public record DayTitleResponse(String title, Integer titleVersion) {}

    /** DAY 한 구역을 최초 화면과 같은 fragment로 다시 그린다. 비참여자는 Service에서 거부한다. */
    @GetMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/fragment")
    public String dayFragment(@PathVariable Long travelPlanId,
                              @PathVariable Long dayId,
                              @AuthenticationPrincipal CustomUserDetails userDetails,
                              Model model) {
        TravelPlanDetailDto detail =
                travelPlanService.getActivePlanDetail(userDetails.getId(), travelPlanId);
        TravelPlanDay day = detail.getDays().stream()
                .filter(candidate -> candidate.getId().equals(dayId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."));

        model.addAttribute("plan", detail.getPlan());
        model.addAttribute("days", detail.getDays());
        model.addAttribute("day", day);
        model.addAttribute("dayItems", detail.getItemsByDayId().get(dayId));
        model.addAttribute("alternativesByItemId", detail.getAlternativesByItemId());
        // 실시간 갱신에서는 입력칸을 열어 두지 않는다.
        model.addAttribute("dayOpen", false);
        model.addAttribute(ITEM_FORM_ATTRIBUTE, new TravelPlanItemCreateForm());
        return DAY_FRAGMENT_VIEW;
    }

    /**
     * DAY 전체를 한 번에 다시 그려 준다. 끊겼다 다시 붙은 화면이 밀린 변경을 한 번에 따라잡는다.
     * DAY 수만큼 요청이 나가지 않도록 평소 갱신과 달리 통째로 준다.
     */
    @GetMapping("/{travelPlanId:\\d+}/schedule/fragment")
    public String scheduleFragment(@PathVariable Long travelPlanId,
                                   @AuthenticationPrincipal CustomUserDetails userDetails,
                                   Model model) {
        TravelPlanDetailDto detail =
                travelPlanService.getActivePlanDetail(userDetails.getId(), travelPlanId);

        model.addAttribute("plan", detail.getPlan());
        model.addAttribute("days", detail.getDays());
        model.addAttribute("itemsByDayId", detail.getItemsByDayId());
        model.addAttribute("alternativesByItemId", detail.getAlternativesByItemId());
        model.addAttribute(ITEM_FORM_ATTRIBUTE, new TravelPlanItemCreateForm());
        return SCHEDULE_FRAGMENT_VIEW;
    }

    /**
     * 참여자 명단만 다시 그려 준다. 명단이 바뀌었다는 실시간 알림을 받은 화면이 이 조각을 갈아 끼운다.
     *
     * <p>"참여자 N/8" 의 N 도 여기서 센 값이 함께 나간다.
     * 화면이 사람 수를 더하거나 빼지 않으므로 같은 알림을 두 번 받아도 숫자가 어긋나지 않는다.
     * 접근 권한은 상세 화면과 똑같이 Service 가 확인한다(비참여자는 404).
     */
    @GetMapping("/{travelPlanId:\\d+}/members/fragment")
    public String membersFragment(@PathVariable Long travelPlanId,
                                  @AuthenticationPrincipal CustomUserDetails userDetails,
                                  Model model) {
        TravelPlanMembersDto members =
                travelPlanService.getActivePlanMembers(userDetails.getId(), travelPlanId);

        model.addAttribute("planId", members.getPlan().getId());
        model.addAttribute("currentMember", members.getCurrentMember());
        model.addAttribute("members", members.getMembers());
        model.addAttribute("pastMembers", members.getPastMembers());
        model.addAttribute("memberCount", members.getMemberCount());
        model.addAttribute("memberLimit", members.getMemberLimit());
        return MEMBERS_FRAGMENT_VIEW;
    }

    /**
     * 방장에게만 있는 상단 액션을 다시 그려 준다.
     * 방장이 바뀌었다는 실시간 알림을 받은 화면이 이 조각을 갈아 끼운다.
     *
     * <p>지금 누가 방장인지는 언제나 여기서 다시 읽는다. 화면이 짐작하지 않는다.
     * 방장이 아닌 사람에게는 빈 조각이 나가므로 그 화면에서 액션이 사라진다.
     * 접근 권한은 상세 화면과 똑같이 Service 가 확인한다(비참여자는 404).
     */
    @GetMapping("/{travelPlanId:\\d+}/owner-actions/fragment")
    public String ownerActionsFragment(@PathVariable Long travelPlanId,
                                       @AuthenticationPrincipal CustomUserDetails userDetails,
                                       Model model) {
        Long userId = userDetails.getId();
        // 참여자 명단만 읽는 가벼운 조회로 충분하다. 일정까지 다시 읽지 않는다.
        model.addAttribute("travelPlan",
                travelPlanService.getActivePlanMembers(userId, travelPlanId));
        // 초대 영역은 방장일 때만 채워진다(그 안에서 OWNER 를 다시 확인한다).
        addInviteState(model, userId, travelPlanId);
        return OWNER_ACTIONS_FRAGMENT_VIEW;
    }

    // A 일정 수정 (방의 ACTIVE 멤버면 누구나)
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/update")
    public String updateItem(@PathVariable Long travelPlanId,
                             @PathVariable Long dayId,
                             @PathVariable Long itemId,
                             @ModelAttribute TravelPlanItemUpdateForm form,
                             @AuthenticationPrincipal CustomUserDetails userDetails,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             RedirectAttributes redirectAttributes) throws IOException {
        try {
            if (form.getDetails() != null) {
                travelPlanService.updateTypedItem(userDetails.getId(), travelPlanId, dayId, itemId,
                        form.getContent(), form.getVersion(), form.getItemType(), form.getStartTime(), form.getDetails());
            } else if (form.getItemType() == null && form.getStartTime() == null) {
                travelPlanService.updateItem(userDetails.getId(), travelPlanId, dayId, itemId,
                        form.getContent(), form.getVersion());
            } else {
                travelPlanService.updateTypedItem(userDetails.getId(), travelPlanId, dayId, itemId,
                        form.getContent(), form.getVersion(), form.getItemType(), form.getStartTime());
            }
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            if (isAjax(request)) {
                // 입력을 날리지 않도록 화면이 그대로 이어 가고 사유만 알린다.
                return writeError(response, exception.getMessage());
            }
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
            return redirectToDay(travelPlanId, dayId);
        }
        // 화면이 스스로 갱신하므로 새로고침 없이 끝난다.
        return isAjax(request) ? noContent(response) : redirectToDay(travelPlanId, dayId);
    }

    // A 일정 삭제 (방의 ACTIVE 멤버면 누구나)
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/delete")
    public String deleteItem(@PathVariable Long travelPlanId,
                             @PathVariable Long dayId,
                             @PathVariable Long itemId,
                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelPlanService.deleteItem(userDetails.getId(), travelPlanId, dayId, itemId);
        return redirectToDay(travelPlanId, dayId);
    }

    // A + 대안 전체 삭제 (방의 ACTIVE 멤버면 누구나)
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/delete-group")
    public String deleteItemGroup(@PathVariable Long travelPlanId,
                                  @PathVariable Long dayId,
                                  @PathVariable Long itemId,
                                  @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelPlanService.deleteItemGroup(userDetails.getId(), travelPlanId, dayId, itemId);
        return redirectToDay(travelPlanId, dayId);
    }

    // 대안(B/C) 추가
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/alternatives")
    public String addAlternative(@PathVariable Long travelPlanId,
                                 @PathVariable Long dayId,
                                 @PathVariable Long itemId,
                                 @ModelAttribute TravelPlanAlternativeForm form,
                                 @AuthenticationPrincipal CustomUserDetails userDetails,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 RedirectAttributes redirectAttributes) throws IOException {
        try {
            travelPlanService.addAlternative(userDetails.getId(), travelPlanId, dayId, itemId,
                    form.getConditionLabel(), form.getContent());
        } catch (TravelPlanValidationException exception) {
            if (isAjax(request)) {
                return writeError(response, exception.getMessage());
            }
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
            return redirectToDay(travelPlanId, dayId);
        }
        return isAjax(request) ? noContent(response) : redirectToDay(travelPlanId, dayId);
    }

    // 대안(B/C) 수정
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}"
            + "/alternatives/{alternativeId:\\d+}/update")
    public String updateAlternative(@PathVariable Long travelPlanId,
                                    @PathVariable Long dayId,
                                    @PathVariable Long itemId,
                                    @PathVariable Long alternativeId,
                                    @ModelAttribute TravelPlanAlternativeForm form,
                                    @AuthenticationPrincipal CustomUserDetails userDetails,
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    RedirectAttributes redirectAttributes) throws IOException {
        try {
            travelPlanService.updateAlternative(userDetails.getId(), travelPlanId, dayId, itemId,
                    alternativeId, form.getConditionLabel(), form.getContent(), form.getVersion());
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            if (isAjax(request)) {
                return writeError(response, exception.getMessage());
            }
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
            return redirectToDay(travelPlanId, dayId);
        }
        return isAjax(request) ? noContent(response) : redirectToDay(travelPlanId, dayId);
    }

    // 대안(B/C) 삭제
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}"
            + "/alternatives/{alternativeId:\\d+}/delete")
    public String deleteAlternative(@PathVariable Long travelPlanId,
                                    @PathVariable Long dayId,
                                    @PathVariable Long itemId,
                                    @PathVariable Long alternativeId,
                                    @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelPlanService.deleteAlternative(
                userDetails.getId(), travelPlanId, dayId, itemId, alternativeId);
        return redirectToDay(travelPlanId, dayId);
    }

    // 같은 DAY 안에서 한 칸 위로
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/move-up")
    public String moveItemUp(@PathVariable Long travelPlanId,
                             @PathVariable Long dayId,
                             @PathVariable Long itemId,
                             @RequestParam Integer version,
                             @AuthenticationPrincipal CustomUserDetails userDetails,
                             RedirectAttributes redirectAttributes) {
        try {
            travelPlanService.moveItemUp(userDetails.getId(), travelPlanId, dayId, itemId, version);
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
        }
        return redirectToDay(travelPlanId, dayId);
    }

    // 같은 DAY 안에서 한 칸 아래로
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/move-down")
    public String moveItemDown(@PathVariable Long travelPlanId,
                               @PathVariable Long dayId,
                               @PathVariable Long itemId,
                               @RequestParam Integer version,
                               @AuthenticationPrincipal CustomUserDetails userDetails,
                               RedirectAttributes redirectAttributes) {
        try {
            travelPlanService.moveItemDown(
                    userDetails.getId(), travelPlanId, dayId, itemId, version);
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
        }
        return redirectToDay(travelPlanId, dayId);
    }

    // 다른 DAY 의 마지막으로 이동
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items/{itemId:\\d+}/move")
    public String moveItemToDay(@PathVariable Long travelPlanId,
                                @PathVariable Long dayId,
                                @PathVariable Long itemId,
                                @RequestParam Long targetDayId,
                                @RequestParam Integer version,
                                @AuthenticationPrincipal CustomUserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        try {
            travelPlanService.moveItemToDay(
                    userDetails.getId(), travelPlanId, dayId, itemId, targetDayId, version);
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            redirectAttributes.addFlashAttribute("travelPlanError", exception.getMessage());
            return redirectToDay(travelPlanId, dayId);
        }
        // 옮겨 간 DAY 자리에서 결과를 보여 준다.
        return redirectToDay(travelPlanId, targetDayId);
    }

    private String redirectToDay(Long travelPlanId, Long dayId) {
        return "redirect:/travel-plans/" + travelPlanId + "#day-" + dayId;
    }

    // DAY 편집 화면
    @GetMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}")
    public String dayDetail(@PathVariable Long travelPlanId,
                            @PathVariable Long dayId,
                            @AuthenticationPrincipal CustomUserDetails userDetails,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        if (!model.containsAttribute(ITEM_FORM_ATTRIBUTE)) {
            model.addAttribute(ITEM_FORM_ATTRIBUTE, new TravelPlanItemCreateForm());
        }
        try {
            model.addAttribute("travelPlanDay",
                    travelPlanService.getActiveDayDetail(userDetails.getId(), travelPlanId, dayId));
        } catch (ResponseStatusException exception) {
            // DAY 화면도 같은 안내를 거쳐 목록으로 보낸다.
            return redirectWithNotice(userDetails.getId(), travelPlanId, redirectAttributes);
        }
        return DAY_DETAIL_VIEW;
    }

    // A 일정 추가
    @PostMapping("/{travelPlanId:\\d+}/days/{dayId:\\d+}/items")
    public String addItem(@PathVariable Long travelPlanId,
                          @PathVariable Long dayId,
                          @ModelAttribute(ITEM_FORM_ATTRIBUTE) TravelPlanItemCreateForm form,
                          BindingResult bindingResult,
                          @AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) throws IOException {
        try {
            if (form.isEmptyCard()) {
                if (bindingResult.hasFieldErrors("beforeItemId")) {
                    throw new TravelPlanValidationException("beforeItemId", "삽입할 위치를 다시 선택해 주세요.");
                }
                Long itemId = form.getBeforeItemId() == null
                        ? travelPlanService.addEmptyTypedItem(
                                userDetails.getId(), travelPlanId, dayId, form.getItemType())
                        : travelPlanService.addEmptyTypedItem(
                                userDetails.getId(), travelPlanId, dayId, form.getItemType(), form.getBeforeItemId());
                if (isAjax(request)) {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"itemId\":" + itemId + "}");
                    return null;
                }
            } else if (form.getDetails() != null) {
                travelPlanService.addTypedItem(userDetails.getId(), travelPlanId, dayId,
                        form.getContent(), form.getItemType(), form.getStartTime(), form.getDetails());
            } else if (form.getItemType() == null && form.getStartTime() == null) {
                travelPlanService.addItem(userDetails.getId(), travelPlanId, dayId, form.getContent());
            } else {
                travelPlanService.addTypedItem(userDetails.getId(), travelPlanId, dayId,
                        form.getContent(), form.getItemType(), form.getStartTime());
            }
        } catch (TravelPlanValidationException | TravelPlanConflictException exception) {
            if (isAjax(request)) {
                return writeError(response, exception.getMessage());
            }
            bindingResult.rejectValue("content", "travelPlan.invalid", exception.getMessage());
            // 편집 화면을 그대로 다시 그리고, 문제가 난 DAY 의 입력칸만 열어 둔다.
            model.addAttribute("travelPlan",
                    travelPlanService.getActivePlanDetail(userDetails.getId(), travelPlanId));
            addInviteState(model, userDetails.getId(), travelPlanId);
            model.addAttribute("openDayId", dayId);
            return DETAIL_VIEW;
        }
        // 화면이 스스로 갱신하므로 새로고침 없이 끝난다.
        // 스크립트가 없는 경우에는 지금까지처럼 그 DAY 자리로 돌아온다.
        return isAjax(request) ? noContent(response) : redirectToDay(travelPlanId, dayId);
    }

    /** 화면이 직접 보낸 저장인지. 그렇다면 redirect 대신 결과만 돌려준다. */
    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    private String noContent(HttpServletResponse response) {
        response.setStatus(HttpStatus.NO_CONTENT.value());
        return null;
    }

    /** 500 HTML 이 화면에 그대로 박히지 않도록 사유만 짧게 돌려준다. */
    private String writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.CONFLICT.value());
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message == null ? "" : message);
        return null;
    }

    private void rejectValidation(BindingResult bindingResult,
                                  TravelPlanValidationException exception) {
        String field = exception.getField();
        if (field == null || !FORM_FIELDS.contains(field)) {
            bindingResult.reject("travelPlan.invalid", exception.getMessage());
            return;
        }
        bindingResult.rejectValue(field, "travelPlan.invalid", exception.getMessage());
    }
}
