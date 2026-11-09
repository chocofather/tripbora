package com.tripbora.service.comment;

import com.tripbora.dto.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewPhotoAdminService {
    private final DestinationCommentImageMapper images;
    private final ReviewImageService reviews;
    public record Queue(List<ReviewPhotoGroup> groups, boolean hasNext, Map<String,Long> counts) {}
    public record Result(int success, int failed) {}
    public record Destinations(List<ReviewDestinationAdminDto> destinations, boolean hasNext) {}
    public record Detail(ReviewDestinationAdminDto destination, List<ReviewImageAdminDto> photos, boolean hasNext) {}

    @Transactional(readOnly = true)
    public ReviewDestinationAdminDto summary(Long destinationId) {
        var row=destinationRows(List.of(destinationId)).get(destinationId);
        if(row==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"여행지를 찾을 수 없습니다.");
        return row;
    }

    @Transactional(readOnly = true)
    public Destinations destinations(String query, String sort, int page) {
        var ids = images.findAdminDestinationIds(query, sort, (long)Math.max(0,page)*20, 21);
        var selected = ids.stream().limit(20).toList();
        var rows = destinationRows(selected);
        return new Destinations(selected.stream().filter(rows::containsKey).map(rows::get).toList(), ids.size()>20);
    }

    private Map<Long,ReviewDestinationAdminDto> destinationRows(List<Long> ids) {
        var rows = new HashMap<Long,ReviewDestinationAdminDto>();
        if (ids.isEmpty()) return rows;
        images.findAdminDestinationMetadata(ids).forEach(row -> rows.put(row.getDestinationId(),row));
        images.findAdminDestinationStatistics(ids).forEach(stat -> {
            var row = rows.get(stat.getDestinationId());
            if (row == null) return;
            row.setTotal(stat.getTotal()); row.setPending(stat.getPending()); row.setApproved(stat.getApproved());
            row.setRejected(stat.getRejected()); row.setRevoked(stat.getRevoked()); row.setNone(stat.getNone());
            row.setReviewCount(stat.getReviewCount());
            row.setUserCount(stat.getUserCount());
        });
        return rows;
    }

    @Transactional(readOnly = true)
    public Detail destination(Long destinationId, String status, String sort, int page) {
        return destination(destinationId,status,sort,"all",page);
    }

    @Transactional(readOnly = true)
    public Detail destination(Long destinationId, String status, String sort, String analysis, int page) {
        var row = destinationRows(List.of(destinationId)).get(destinationId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"여행지를 찾을 수 없습니다.");
        var photos = "all".equals(analysis)
                ? images.findAdminDestinationPhotoPage(destinationId,status,sort,(long)Math.max(0,page)*50,51)
                : images.findAdminDestinationPhotoPageFiltered(destinationId,status,sort,analysis,(long)Math.max(0,page)*50,51);
        return new Detail(row,photos.stream().limit(50).toList(),photos.size()>50);
    }

    public Result batchForDestination(Long destinationId, List<Long> ids, Long adminId, String action, String reason) {
        if (ids == null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size()!=ids.size()
                || images.countAdminDestinationSelection(destinationId,ids)!=ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"현재 여행지의 사진만 1~100장 선택해 주세요.");
        return batch(ids,adminId,action,reason);
    }

    @Transactional(readOnly = true)
    public Queue list(String status, String query, int page) {
        var ids = images.findAdminReviewIds(status, query, (long)Math.max(0,page)*20, 21);
        var selected = ids.stream().limit(20).toList();
        var photos = selected.isEmpty() ? List.<ReviewImageAdminDto>of() : images.findAdminReviewPhotos(selected);
        var grouped = photos.stream().collect(Collectors.groupingBy(ReviewImageAdminDto::getCommentId));
        var cards = selected.stream().filter(grouped::containsKey)
                .map(id -> new ReviewPhotoGroup(grouped.get(id).get(0), grouped.get(id))).toList();
        var counts = new HashMap<String,Long>();
        images.countAdminStatuses(query).forEach(c -> counts.put(c.getStatus(), c.getCount()));
        return new Queue(cards, ids.size()>20, counts);
    }

    // 의도적으로 바깥 트랜잭션을 만들지 않는다. 기존 proxied review가 사진별 commit/rollback을 담당한다.
    public Result batch(List<Long> ids, Long adminId, String action, String reason) {
        if (ids == null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(Objects::isNull)
                || ids.stream().anyMatch(id -> id<=0) || new HashSet<>(ids).size()!=ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"사진을 1~100장 중복 없이 선택해 주세요.");
        if (!Set.of("APPROVE","REJECT","REVOKE").contains(action == null ? "" : action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하지 않는 심사입니다.");
        if (("REJECT".equals(action) && (reason == null || reason.isBlank())) || (reason != null && reason.length()>500))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"반려 사유를 1~500자로 입력해 주세요.");
        int success=0, failed=0;
        for (Long id : ids) {
            try { reviews.review(id,adminId,action,reason); success++; }
            catch (ResponseStatusException error) { if (error.getStatusCode().value()==403) throw error; failed++; }
            catch (DataAccessException error) { failed++; }
        }
        return new Result(success,failed);
    }
}
