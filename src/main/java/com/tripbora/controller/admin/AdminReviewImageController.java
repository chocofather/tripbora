package com.tripbora.controller.admin;

import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.comment.ReviewImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/review-images")
public class AdminReviewImageController {
    private static final Set<String> STATUSES = Set.of("NONE", "PENDING", "APPROVED", "REJECTED", "REVOKED");
    private final ReviewImageService reviewImageService;
    private final com.tripbora.service.comment.ReviewPhotoAdminService queue;
    private final com.tripbora.service.comment.ReviewGalleryPriorityService priorities;
    private final com.tripbora.repository.comment.ReviewPhotoAnalysisMapper analysisMapper;
    private final com.tripbora.service.comment.ReviewDuplicateService duplicates;
    private final com.tripbora.service.comment.ReviewPhotoWorkflowService workflow;

    @GetMapping("/destinations/{destinationId}/workflow")
    @ResponseBody
    public com.tripbora.service.comment.ReviewPhotoWorkflowService.View workflow(@PathVariable Long destinationId,
            @RequestParam(defaultValue="all") String section,@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="0") int page) {
        workflowFilter(section,status);
        return workflow.view(destinationId,section,status,Math.max(0,page));
    }
    public record ApproveRequest(java.util.Map<Long,String> expected) {}
    public record DecisionRequest(Long anchorId,String kind,java.util.List<Long> selected,java.util.Map<Long,String> expected) {}
    public record BatchRequest(String action,java.util.Map<Long,String> expected) {}
    public record RejectGroupRequest(Long anchorId,String kind,java.util.Map<Long,String> expected) {}

    @PostMapping("/destinations/{destinationId}/workflow/batch")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> batchModerate(@PathVariable Long destinationId,@RequestBody BatchRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if(admin==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return workflowResult(()->workflow.batchModerate(destinationId,request.action(),request.expected(),admin.getId()));
    }
    @PostMapping("/destinations/{destinationId}/workflow/reject-group")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> rejectGroup(@PathVariable Long destinationId,@RequestBody RejectGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if(admin==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return workflowResult(()->workflow.rejectGroup(destinationId,request.anchorId(),request.kind(),request.expected(),admin.getId()));
    }

    @PostMapping("/destinations/{destinationId}/workflow/approve")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> approveClean(@PathVariable Long destinationId,@RequestBody ApproveRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if(admin==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return workflowResult(()->workflow.approveClean(destinationId,request.expected(),admin.getId()));
    }
    @PostMapping("/destinations/{destinationId}/workflow/decide")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> decide(@PathVariable Long destinationId,@RequestBody DecisionRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if(admin==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return workflowResult(()->workflow.decide(destinationId,request.anchorId(),request.kind(),request.selected(),request.expected(),admin.getId()));
    }
    private org.springframework.http.ResponseEntity<?> workflowResult(Runnable action) {
        try {
            action.run();
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("message","선택한 묶음 전체 처리 완료. 원본 리뷰 사진은 유지됩니다."));
        } catch(ResponseStatusException e) {
            return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(java.util.Map.of("message","처리 0건 · 묶음 전체 취소: "+(e.getReason()==null?"심사 조건을 다시 확인하세요.":e.getReason())));
        } catch(org.springframework.dao.DataAccessException e) {
            return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message","처리 0건 · 충돌 또는 저장 실패로 묶음 전체를 취소했습니다. 새로고침 후 확인하세요."));
        }
    }
    private void workflowFilter(String section,String status) {
        if(!Set.of("clean","groups","limited","all").contains(section))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"올바르지 않은 사진 분류입니다.");
        if(!"ALL".equals(status))status(status);
    }

    public record Comparison(java.util.List<com.tripbora.dto.ReviewPhotoMatch> photos, boolean hasNext,
                             java.util.Map<String,Object> counts) {}
    @GetMapping("/destinations/{destinationId}/photos/{imageId}/comparison")
    @ResponseBody
    public Comparison comparison(@PathVariable Long destinationId,@PathVariable Long imageId,
            @RequestParam(defaultValue="exact") String kind,@RequestParam(defaultValue="0") int page) {
        if(!Set.of("exact","similar").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=analysisMapper.groupPage(destinationId,imageId,kind,(long)Math.max(0,page)*100,101);
        return new Comparison(rows.stream().limit(100).toList(),rows.size()>100,analysisMapper.groupCounts(destinationId,imageId,kind));
    }
    public record KeepRequest(Long representative, java.util.Map<Long,String> expected) {}
    @PostMapping("/destinations/{destinationId}/duplicates/keep")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> keep(@PathVariable Long destinationId,@RequestBody KeepRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if(admin==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            duplicates.keep(destinationId,request.representative(),request.expected(),admin.getId());
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("message","선택 묶음 전체 처리 완료. 원본 리뷰 사진은 유지됩니다."));
        } catch (ResponseStatusException e) {
            return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(java.util.Map.of("message","이 묶음 처리 0건 · 전체 취소: "+e.getReason()));
        } catch(org.springframework.dao.DataAccessException e) {
            return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message","이 묶음 처리 0건 · 충돌 또는 저장 실패로 전체 취소했습니다. 새로고침 후 확인하세요."));
        }
    }

    @GetMapping("/destinations")
    public String destinations(@RequestParam(defaultValue="") String q,
                               @RequestParam(defaultValue="pending") String sort,
                               @RequestParam(defaultValue="0") int page, Model model) {
        if(q.length()>100 || !Set.of("pending","latest").contains(sort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"검색 조건이 올바르지 않습니다.");
        var result = queue.destinations(q.strip(),sort,Math.max(0,page));
        model.addAttribute("destinations",result.destinations());
        model.addAttribute("hasNext",result.hasNext());
        model.addAttribute("q",q.strip()); model.addAttribute("sort",sort); model.addAttribute("page",Math.max(0,page));
        return "admin/review-images/destinations";
    }

    @GetMapping("/destinations/{destinationId}")
    public String destination(@PathVariable Long destinationId, @RequestParam(defaultValue="PENDING") String status,
                              @RequestParam(defaultValue="latest") String sort,
                              @RequestParam(defaultValue="all") String analysis,
                              @RequestParam(defaultValue="all") String section,
                              @RequestParam(defaultValue="0") int page, Model model) {
        if (!"ALL".equals(status)) status(status);
        photoSort(sort);
        analysisFilter(analysis);
        if("duplicates".equals(analysis))section="groups";
        if(Set.of("quality","unprocessed").contains(analysis))section="limited";
        workflowFilter(section,status);
        model.addAttribute("destination",queue.summary(destinationId)); model.addAttribute("destinationId",destinationId);
        model.addAttribute("section",section);
        model.addAttribute("status",status); model.addAttribute("page",Math.max(0,page)); model.addAttribute("sort",sort);
        model.addAttribute("analysis",analysis);
        return "admin/review-images/destination-detail";
    }

    @PostMapping("/destinations/{destinationId}/batch")
    public String destinationBatch(@PathVariable Long destinationId, @RequestParam java.util.List<Long> ids,
                                   @RequestParam String action, @RequestParam(required=false) String reason,
                                   @RequestParam(defaultValue="ALL") String status, @RequestParam(defaultValue="0") int page,
                                   @RequestParam(defaultValue="latest") String sort,
                                   @RequestParam(defaultValue="all") String analysis,
                                   @AuthenticationPrincipal CustomUserDetails admin, RedirectAttributes redirect) {
        if(admin==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!"ALL".equals(status)) status(status);
        photoSort(sort);
        analysisFilter(analysis);
        try {
            var result=queue.batchForDestination(destinationId,ids,admin.getId(),action,reason);
            redirect.addFlashAttribute("notice","성공 " + result.success() + "건 · 실패 " + result.failed() + "건 (동의·공개·심사 상태가 변경된 사진은 처리하지 않습니다.)");
        } catch(ResponseStatusException error) {
            if(error.getStatusCode().value()==403) throw error;
            redirect.addFlashAttribute("error",error.getReason());
        }
        redirect.addAttribute("status",status); redirect.addAttribute("page",Math.max(0,page));
        redirect.addAttribute("sort",sort);
        if (!"all".equals(analysis)) redirect.addAttribute("analysis",analysis);
        return "redirect:/admin/review-images/destinations/"+destinationId;
    }

    public record Matches(java.util.List<com.tripbora.dto.ReviewPhotoMatch> photos,boolean hasNext) {}

    @GetMapping("/destinations/{destinationId}/photos/{imageId}/matches")
    @ResponseBody
    public Matches matches(@PathVariable Long destinationId,@PathVariable Long imageId,
                           @RequestParam(defaultValue="0") int page) {
        var photos=analysisMapper.matches(destinationId,imageId,(long)Math.max(0,page)*20,21);
        return new Matches(photos.stream().limit(20).toList(),photos.size()>20);
    }

    @PostMapping("/destinations/{destinationId}/photos/{imageId}/priority")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> priority(@PathVariable Long destinationId,@PathVariable Long imageId,
            @RequestParam(required=false) Integer priority,@RequestParam long version,
            @AuthenticationPrincipal CustomUserDetails admin) {
        if (admin==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            priorities.setPriority(destinationId,imageId,admin.getId(),priority,version);
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch(ResponseStatusException error) {
            return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                    .body(java.util.Map.of("message",error.getReason()==null?"우선순위를 저장하지 못했습니다.":error.getReason()));
        }
    }

    private void analysisFilter(String analysis) {
        if (!Set.of("all","duplicates","quality","unprocessed").contains(analysis))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"올바르지 않은 사진 분석 필터입니다.");
    }

    private void photoSort(String sort) {
        if (!Set.of("latest","oldest").contains(sort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"올바르지 않은 사진 정렬입니다.");
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "PENDING") String status,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "") String q, Model model) {
        String filter = status(status);
        int currentPage = Math.max(0, page);
        if (q.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"검색어는 100자 이하입니다.");
        var result = queue.list(filter, q.strip(), currentPage);
        model.addAttribute("groups", result.groups());
        model.addAttribute("counts", result.counts());
        model.addAttribute("q", q.strip());
        model.addAttribute("status", filter);
        model.addAttribute("page", currentPage);
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageTitle", "리뷰 사진 관리");
        return "admin/review-images/list";
    }

    @PostMapping("/batch")
    public String batch(@RequestParam java.util.List<Long> ids, @RequestParam String action,
                        @RequestParam(required=false) String reason,
                        @RequestParam(defaultValue="PENDING") String status,
                        @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int page,
                        @AuthenticationPrincipal CustomUserDetails admin, RedirectAttributes redirect) {
        if (admin == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String filter=status(status);
        try {
            var result=queue.batch(ids,admin.getId(),action,reason);
            redirect.addFlashAttribute("notice","성공 " + result.success() + "건 · 실패 " + result.failed() + "건 (이미 처리되었거나 동의·공개 상태가 변경된 사진은 처리하지 않습니다.)");
        } catch(ResponseStatusException error) {
            if(error.getStatusCode().value()==403) throw error;
            redirect.addFlashAttribute("error",error.getReason());
        }
        redirect.addAttribute("status",filter); redirect.addAttribute("q",q.length()>100?"":q);
        redirect.addAttribute("page",Math.max(0,page));
        return "redirect:/admin/review-images";
    }

    @PostMapping("/{imageId}/review")
    public String review(@PathVariable Long imageId, @RequestParam String action,
                         @RequestParam(required = false) String reason,
                         @RequestParam(defaultValue = "PENDING") String status,
                         @AuthenticationPrincipal CustomUserDetails admin,
                         RedirectAttributes redirect) {
        if (admin == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            reviewImageService.review(imageId, admin.getId(), action, reason);
            redirect.addFlashAttribute("notice", "사진 심사 결과를 저장했습니다.");
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 403) throw exception;
            redirect.addFlashAttribute("error", exception.getReason());
        }
        redirect.addAttribute("status", status(status));
        return "redirect:/admin/review-images";
    }

    private String status(String value) {
        if (!STATUSES.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 상태입니다.");
        return value;
    }
}
