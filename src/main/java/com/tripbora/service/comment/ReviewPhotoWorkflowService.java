package com.tripbora.service.comment;

import com.tripbora.dto.*;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.ReviewPhotoAnalysisMapper;
import com.tripbora.repository.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 기존 개별 심사를 재사용하는, 최대 100장 단위의 원자적 심사 workflow. */
@Service
@RequiredArgsConstructor
public class ReviewPhotoWorkflowService {
    private static final Set<String> STATUSES=Set.of("ALL","NONE","PENDING","APPROVED","REJECTED","REVOKED");
    private static final String EXCLUDED_REASON="관리자 그룹 비교 심사에서 미선택";
    private final ReviewPhotoAnalysisMapper analysis;
    private final DestinationCommentImageMapper images;
    private final ReviewImageService reviews;
    private final UserMapper users;

    public record View(Map<String,Long> counts,List<ReviewImageAdminDto> photos,List<Group> groups,boolean hasNext) {}
    public record Group(Long anchorId,String kind,String key,long total,long pending,long approved,long reviewable,List<ReviewPhotoMatch> photos) {}

    @Transactional(readOnly=true)
    public View view(Long destinationId,String section,String status,int page) {
        requireDestination(destinationId);
        String filter=status==null || status.isBlank()?"PENDING":status;
        String tab=section==null || section.isBlank()?"all":section;
        if(!STATUSES.contains(filter) || !Set.of("clean","groups","limited","all").contains(tab) || page<0 || page>100000)
            bad("지원하지 않는 목록 조건입니다.");
        Map<String,Object> aggregate=analysis.workflowCounts(destinationId,filter);
        var counts=new LinkedHashMap<String,Long>();
        for(String key:List.of("clean","groups","limited")) {
            Object value=aggregate==null?null:aggregate.get(key);
            counts.put(key,value instanceof Number n?n.longValue():0L);
        }
        if(!"groups".equals(tab)) {
            var rows=analysis.workflowPhotos(destinationId,tab,filter,(long)page*100,101);
            return new View(counts,List.copyOf(rows.subList(0,Math.min(rows.size(),100))),List.of(),rows.size()>100);
        }
        var rows=analysis.workflowGroups(destinationId,filter,(long)page*20,21);
        var anchors=rows.subList(0,Math.min(rows.size(),20));
        var previews=anchors.isEmpty()?List.<ReviewPhotoWorkflowPreview>of():analysis.workflowPreviews(destinationId,anchors);
        var groups=new ArrayList<Group>();
        for(var row:anchors) {
            List<ReviewPhotoMatch> photos=previews.stream()
                    .filter(p->Objects.equals(p.getAnchorId(),row.getAnchorId()) && Objects.equals(p.getKind(),row.getKind()))
                    .limit(4).map(p->(ReviewPhotoMatch)p).toList();
            groups.add(new Group(row.getAnchorId(),row.getKind(),row.getGroupKey(),row.getTotal(),row.getPending(),row.getApproved(),row.getReviewable(),photos));
        }
        return new View(counts,List.of(),List.copyOf(groups),rows.size()>20);
    }

    // READ_COMMITTED는 잠금 전 hint 읽기의 오래된 RR snapshot을 재검증에 재사용하지 않게 한다.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void approveClean(Long destinationId,Map<Long,String> expected,Long adminId) {
        validateExpected(destinationId,expected);requireAdmin(adminId);
        var rows=lock(destinationId,expected.keySet());
        verifyStatuses(rows,expected);
        if(rows.stream().anyMatch(p->!ready(p,destinationId) || !Boolean.TRUE.equals(p.getEligible()) || p.getEligibilityReason()!=null || p.isDuplicate() || !"PENDING".equals(p.getGalleryStatus())))
            conflict("사진 분석·동의 또는 중복 관계가 변경되었습니다. 새로고침 후 다시 선택하세요.");
        for(var row:rows) reviews.review(row.getId(),adminId,"APPROVE",null);
    }

    /** 분석 결과와 관계없이 현재 화면에서 명시적으로 선택한 대기 사진만 처리한다. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void batchModerate(Long destinationId,String action,Map<Long,String> expected,Long adminId) {
        validateExpected(destinationId,expected);requireAdmin(adminId);
        if(!Set.of("APPROVE","REJECT").contains(action==null?"":action)) bad("지원하지 않는 일괄 심사입니다.");
        var rows=lock(destinationId,expected.keySet());
        verifyStatuses(rows,expected);
        for(var row:rows) {
            if(!"PENDING".equals(row.getGalleryStatus())) conflict("대기 사진만 일괄 심사할 수 있습니다.");
            if("APPROVE".equals(action) && (!row.isApprovable() || row.getUserId()==null))
                conflict("사진 #"+row.getId()+": "+row.getEligibilityMessage()+" 새로고침 후 다시 확인하세요.");
        }
        for(var row:rows) reviews.review(row.getId(),adminId,action,"REJECT".equals(action)?"관리자 일괄 심사에서 반려":null);
    }

    /** 표시된 PENDING chunk만 전체 반려한다. 승인 사진이나 다음 페이지는 변경하지 않는다. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void rejectGroup(Long destinationId,Long anchorId,String kind,Map<Long,String> expected,Long adminId) {
        validateExpected(destinationId,expected);requireAdmin(adminId);
        validateGroup(anchorId,kind);
        if(expected.values().stream().anyMatch(status->!"PENDING".equals(status))) bad("전체 반려는 현재 표시된 대기 사진만 처리할 수 있습니다.");
        var ids=new TreeSet<>(expected.keySet());ids.add(anchorId);
        var locked=lock(destinationId,ids);
        var anchor=locked.stream().filter(p->p.getId().equals(anchorId)).findFirst().orElseThrow();
        var rows=locked.stream().filter(p->expected.containsKey(p.getId())).toList();
        verifyStatuses(rows,expected);
        for(var row:rows) if(!related(anchor,row,kind,destinationId)) conflict("그룹 기준 사진의 직접 관계 또는 분석 상태가 변경되었습니다.");
        requireComparisonGroup(destinationId,anchorId,kind);
        for(var row:rows) reviews.review(row.getId(),adminId,"REJECT","관리자 그룹 비교 심사에서 전체 반려");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void decide(Long destinationId,Long anchorId,String kind,List<Long> selected,Map<Long,String> expected,Long adminId) {
        validateExpected(destinationId,expected);
        requireAdmin(adminId);
        validateGroup(anchorId,kind);
        if(selected==null || selected.isEmpty() || selected.size()>100 || selected.stream().anyMatch(id->id==null || !expected.containsKey(id))) bad("사용할 사진을 한 장 이상 선택해 주세요. 전체 반려는 별도 요청으로 처리해 주세요.");
        if(new HashSet<>(selected).size()!=selected.size()) bad("선택한 사진 ID가 중복되었습니다.");
        var ids=new TreeSet<>(expected.keySet());ids.add(anchorId);
        var locked=lock(destinationId,ids);
        var anchor=locked.stream().filter(p->p.getId().equals(anchorId)).findFirst().orElseThrow();
        var rows=locked.stream().filter(p->expected.containsKey(p.getId())).toList();
        verifyStatuses(rows,expected);
        for(var row:rows) {
            if(!related(anchor,row,kind,destinationId)) conflict("그룹 기준 사진의 직접 관계 또는 분석 상태가 변경되었습니다.");
            if(selected.contains(row.getId()) && Set.of("PENDING","APPROVED").contains(row.getGalleryStatus()) && (!row.isApprovable() || row.getUserId()==null))
                conflict("사진 #"+row.getId()+": "+row.getEligibilityMessage()+" 새로고침 후 다시 확인하세요.");
            if(selected.contains(row.getId()) && !Set.of("PENDING","APPROVED").contains(row.getGalleryStatus()))
                bad("대기 또는 승인 사진만 사용할 사진으로 선택할 수 있습니다.");
        }
        if(rows.stream().noneMatch(ReviewPhotoMatch::isReviewable)) bad("현재 심사 가능한 사진이 없습니다. 참고 사진은 처리하지 않습니다.");
        requireComparisonGroup(destinationId,anchorId,kind);
        // 모든 상태/관계 검증을 마친 뒤에만 기존 proxied 심사를 호출한다.
        for(var row:rows) {
            boolean use=selected.contains(row.getId());
            String action=switch(row.getGalleryStatus()) {
                case "PENDING" -> use?"APPROVE":"REJECT";
                case "APPROVED" -> use?"KEEP":"REVOKE";
                default -> null;
            };
            if(action!=null) reviews.review(row.getId(),adminId,action,use?null:EXCLUDED_REASON);
        }
    }

    private void validateGroup(Long anchorId,String kind) {
        if(anchorId==null || anchorId<=0 || !Set.of("exact","similar").contains(kind==null?"":kind)) bad("그룹 기준을 확인해 주세요.");
    }

    private void requireComparisonGroup(Long destinationId,Long anchorId,String kind) {
        var counts=analysis.groupCounts(destinationId,anchorId,kind);
        Object groupTotal=counts==null?null:counts.get("total");
        if(!(groupTotal instanceof Number total) || total.longValue()<2)
            conflict("비교 그룹 관계가 변경되었습니다. 새로고침 후 다시 선택하세요.");
    }

    private List<ReviewPhotoWorkflowPhoto> lock(Long destinationId,Collection<Long> ids) {
        var sorted=ids.stream().sorted().toList();
        var hints=analysis.workflowSelection(destinationId,sorted,false);
        if(hints.size()!=ids.size() || hints.stream().anyMatch(p->p.getCommentId()==null))
            conflict("사진 소속 또는 회원 상태가 변경되었습니다.");
        hints.stream().map(ReviewPhotoWorkflowPhoto::getUserId).filter(Objects::nonNull).distinct().sorted().forEach(images::lockUser);
        images.lockDestination(destinationId);
        hints.stream().map(ReviewPhotoWorkflowPhoto::getCommentId).distinct().sorted().forEach(images::lockComment);
        var rows=analysis.workflowSelection(destinationId,sorted,true);
        if(rows.size()!=sorted.size() || !new HashSet<>(rows.stream().map(ReviewPhotoWorkflowPhoto::getId).toList()).equals(new HashSet<>(sorted)))
            conflict("사진 소속 또는 분석 상태가 변경되었습니다.");
        var owners=new HashMap<Long,ReviewPhotoWorkflowPhoto>();hints.forEach(p->owners.put(p.getId(),p));
        for(var row:rows) {
            var hint=owners.get(row.getId());
            if(!Objects.equals(hint.getUserId(),row.getUserId()) || !Objects.equals(hint.getCommentId(),row.getCommentId()))
                conflict("사진 소속이 변경되었습니다.");
        }
        return rows;
    }

    private boolean related(ReviewPhotoWorkflowPhoto anchor,ReviewPhotoWorkflowPhoto row,String kind,Long destinationId) {
        if(!analyzed(anchor,destinationId) || !analyzed(row,destinationId)) return false;
        if("exact".equals(kind)) return anchor.getSha256().equals(row.getSha256());
        if(!ready(anchor,destinationId) || !ready(row,destinationId)) return false;
        return anchor.getId().equals(row.getId()) || (!anchor.getSha256().equals(row.getSha256())
                && (Objects.equals(anchor.getHashBand0(),row.getHashBand0()) || Objects.equals(anchor.getHashBand1(),row.getHashBand1())
                    || Objects.equals(anchor.getHashBand2(),row.getHashBand2()) || Objects.equals(anchor.getHashBand3(),row.getHashBand3()))
                && Long.bitCount(anchor.getPerceptualHash() ^ row.getPerceptualHash())<=3);
    }

    private boolean analyzed(ReviewPhotoWorkflowPhoto p,Long destinationId) {
        return Objects.equals(p.getAnalysisDestinationId(),destinationId) && Set.of("READY","HASH_ONLY").contains(p.getAnalysisState()==null?"":p.getAnalysisState())
                && p.getSha256()!=null && p.getSha256().matches("[0-9a-fA-F]{64}");
    }
    private boolean ready(ReviewPhotoWorkflowPhoto p,Long destinationId) {
        return analyzed(p,destinationId) && "READY".equals(p.getAnalysisState()) && p.getPerceptualHash()!=null
                && Objects.equals(p.getHashBand0(),ReviewPhotoAnalyzer.band(p.getPerceptualHash(),0))
                && Objects.equals(p.getHashBand1(),ReviewPhotoAnalyzer.band(p.getPerceptualHash(),1))
                && Objects.equals(p.getHashBand2(),ReviewPhotoAnalyzer.band(p.getPerceptualHash(),2))
                && Objects.equals(p.getHashBand3(),ReviewPhotoAnalyzer.band(p.getPerceptualHash(),3));
    }
    private void verifyStatuses(List<ReviewPhotoWorkflowPhoto> rows,Map<Long,String> expected) {
        if(rows.size()!=expected.size() || rows.stream().anyMatch(p->!Objects.equals(expected.get(p.getId()),p.getGalleryStatus())))
            conflict("다른 심사 또는 동의 변경이 있습니다. 새로고침 후 다시 선택하세요.");
    }
    private void validateExpected(Long destinationId,Map<Long,String> expected) {
        requireDestination(destinationId);
        if(expected==null || expected.isEmpty() || expected.size()>100 || expected.keySet().stream().anyMatch(id->id==null || id<=0)
                || expected.values().stream().anyMatch(s->s==null || "ALL".equals(s) || !STATUSES.contains(s)))
            bad("현재 표시된 1~100장의 ID와 상태를 확인해 주세요.");
    }
    private void requireDestination(Long destinationId) { if(destinationId==null || destinationId<=0) bad("여행지를 확인해 주세요."); }
    private void requireAdmin(Long adminId) {
        var admin=adminId==null?null:users.findById(adminId);
        if(admin==null || admin.getUserRole()!=UserRole.ADMIN || admin.getStatus()!=UserStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자만 심사할 수 있습니다.");
    }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
