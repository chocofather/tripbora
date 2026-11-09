package com.tripbora.service.comment;

import com.tripbora.dto.ReviewPhotoMatch;
import com.tripbora.repository.comment.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReviewDuplicateService {
    private final ReviewPhotoAnalysisMapper analysis;
    private final DestinationCommentImageMapper images;
    private final ReviewImageService reviews;

    // 한 묶음은 전부 성공하거나 전부 rollback. 기존 심사와 동일한 회원→여행지→리뷰 잠금 순서.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void keep(Long destinationId, Long representative, Map<Long,String> expected, Long adminId) {
        if (representative == null || expected == null || expected.size()<2 || expected.size()>100 || !expected.containsKey(representative)
                || expected.keySet().stream().anyMatch(id->id==null || id<=0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"대표 포함 2~100장을 선택하세요. 초과 그룹은 나누어 처리하세요.");
        var ids = expected.keySet().stream().sorted().toList();
        var hints = analysis.selection(destinationId,ids,false);
        hints.stream().map(ReviewPhotoMatch::getUserId).filter(Objects::nonNull).distinct().sorted().forEach(images::lockUser);
        images.lockDestination(destinationId);
        hints.stream().map(ReviewPhotoMatch::getCommentId).distinct().sorted().forEach(images::lockComment);
        var rows = analysis.selection(destinationId,ids,true);
        if (rows.size()!=ids.size()) conflict("사진 소속·분석 상태가 변경되었습니다.");
        var keep = rows.stream().filter(p->p.getId().equals(representative)).findFirst().orElseThrow();
        if (keep.getSha256()==null || rows.stream().anyMatch(p->!keep.getSha256().equals(p.getSha256())))
            conflict("같은 여행지의 동일 파일만 처리할 수 있습니다.");
        if (rows.stream().anyMatch(p->!Objects.equals(expected.get(p.getId()),p.getGalleryStatus())))
            conflict("다른 심사 또는 동의 변경이 있습니다. 새로고침 후 다시 선택하세요.");
        reviews.review(representative,adminId,"APPROVED".equals(keep.getGalleryStatus())?"KEEP":"APPROVE",null);
        for (var row:rows) {
            if (row.getId().equals(representative)) continue;
            String action=switch(row.getGalleryStatus()) {case "PENDING" -> "REJECT"; case "APPROVED" -> "REVOKE"; default -> null;};
            if(action!=null) reviews.review(row.getId(),adminId,action,"동일 파일 중복 · 대표 사진 #"+representative+" 유지");
        }
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
