package com.tripbora.service.destination;

import com.tripbora.dto.DestinationGalleryImage;
import com.tripbora.dto.DestinationGalleryPage;
import com.tripbora.dto.DestinationGalleryPosition;
import com.tripbora.dto.DestinationGalleryReviewCandidate;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.file.FileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 현재 공개 권한을 매 요청 SQL에서 확인하는 live keyset 갤러리.
 * 스냅샷이 아니므로 지나간 위치에 새로 승인/이동된 사진은 처음부터 다시 읽어야 한다.
 * 이전 방향도 DB를 다시 조회하며, 파일이 없는 후보를 포함해 최대 300개 위치만 전진한다.
 */
@Service
@RequiredArgsConstructor
public class DestinationGalleryService {
    public static final int PAGE_SIZE = 30;
    public static final int MAX_CANDIDATES = 300;
    private final DestinationMapper destinations;
    private final DestinationCommentImageMapper reviews;
    private final FileUploadService files;

    public DestinationGalleryPage page(Long destinationId, String filterValue, String encodedCursor) {
        Filter filter;
        try { filter = filterValue == null ? Filter.ALL : Filter.valueOf(filterValue); }
        catch (IllegalArgumentException exception) { throw invalidCursor(); }
        Cursor cursor = encodedCursor == null ? null : decode(encodedCursor, destinationId, filter);
        if (destinationId == null || destinationId <= 0 || !destinations.isPublicDestination(destinationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        boolean reverse = cursor != null && cursor.reverse;
        String section = cursor == null ? firstSection(filter, reverse) : cursor.position.getSection();
        DestinationGalleryPosition anchor = cursor == null ? null : cursor.position;
        DestinationGalleryPosition first = null, last = null;
        List<DestinationGalleryImage> items = new ArrayList<>(PAGE_SIZE);
        int scanned = 0;
        while (section != null && scanned < MAX_CANDIDATES && items.size() < PAGE_SIZE) {
            int limit = Math.min(PAGE_SIZE + 1, MAX_CANDIDATES - scanned);
            List<Candidate> batch = candidates(destinationId, section, anchor, reverse, limit);
            for (Candidate candidate : batch) {
                scanned++;
                if (first == null) first = candidate.position;
                last = candidate.position;
                anchor = candidate.position;
                if (available(candidate.image)) items.add(candidate.image);
                if (items.size() == PAGE_SIZE || scanned == MAX_CANDIDATES) break;
            }
            if (items.size() == PAGE_SIZE || scanned == MAX_CANDIDATES) break;
            if (batch.size() < limit) {
                section = followingSection(filter, section, reverse);
                anchor = null;
            }
        }
        if (reverse) Collections.reverse(items);
        // 삭제/권리 변경으로 요청 구간이 사라져도 반대 방향으로 복귀할 수 있다.
        if (first == null) {
            String opposite = cursor != null && hasCandidates(destinationId, filter, cursor.position, !reverse)
                    ? encode(destinationId, filter, !reverse, cursor.position) : null;
            return new DestinationGalleryPage(items, reverse ? opposite : null, reverse ? null : opposite);
        }
        DestinationGalleryPosition lower = reverse ? last : first;
        DestinationGalleryPosition upper = reverse ? first : last;
        // EXISTS는 파일을 읽거나 사진 목록을 적재하지 않고 진짜 SQL 경계를 판정한다.
        String next = hasCandidates(destinationId, filter, upper, false) ? encode(destinationId, filter, false, upper) : null;
        String previous = hasCandidates(destinationId, filter, lower, true) ? encode(destinationId, filter, true, lower) : null;
        return new DestinationGalleryPage(items, next, previous);
    }

    private List<Candidate> candidates(Long id, String section, DestinationGalleryPosition anchor, boolean reverse, int limit) {
        if ("A".equals(section)) {
            return destinations.findGalleryCandidates(id, anchor, reverse, limit).stream().map(image -> {
                var position = new DestinationGalleryPosition();
                position.setSection("A"); position.setImageId(image.getId()); position.setOrderIndex(image.getOrderIndex());
                return new Candidate(position, DestinationGalleryImage.administrator(image));
            }).toList();
        }
        return reviews.findGalleryCandidates(id, anchor, reverse, limit).stream().map(photo ->
                new Candidate(reviewPosition(photo), DestinationGalleryImage.traveler(photo))).toList();
    }

    private DestinationGalleryPosition reviewPosition(DestinationGalleryReviewCandidate photo) {
        var position = new DestinationGalleryPosition();
        position.setSection("R"); position.setImageId(photo.getId()); position.setPriority(photo.getGalleryPriority());
        position.setReviewCreatedAt(photo.getReviewCreatedAt()); position.setCommentId(photo.getCommentId());
        position.setDisplayOrder(photo.getDisplayOrder());
        return position;
    }

    private boolean hasCandidates(Long id, Filter filter, DestinationGalleryPosition anchor, boolean reverse) {
        String section = anchor.getSection();
        if (hasInSection(id, section, anchor, reverse)) return true;
        String following = followingSection(filter, section, reverse);
        return following != null && hasInSection(id, following, null, reverse);
    }

    private boolean hasInSection(Long id, String section, DestinationGalleryPosition anchor, boolean reverse) {
        return "A".equals(section) ? destinations.hasGalleryCandidates(id, anchor, reverse)
                : reviews.hasGalleryCandidates(id, anchor, reverse);
    }

    private boolean available(DestinationGalleryImage image) {
        if (image.isTravelerPhoto()) return files.commentFileExists(image.getImageUrl());
        String url = image.getImageUrl();
        if (url == null) return false;
        if (url.startsWith("/")) return files.destinationFileExists(url);
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) { return false; }
    }

    private String firstSection(Filter filter, boolean reverse) {
        return filter == Filter.ADMIN ? "A" : filter == Filter.TRAVELER ? "R" : reverse ? "R" : "A";
    }

    private String followingSection(Filter filter, String section, boolean reverse) {
        if (filter != Filter.ALL) return null;
        return !reverse && "A".equals(section) ? "R" : reverse && "R".equals(section) ? "A" : null;
    }

    private String encode(Long id, Filter filter, boolean reverse, DestinationGalleryPosition position) {
        String tail = "A".equals(position.getSection()) ? position.getOrderIndex() + "|||"
                : "|" + (position.getPriority() == null ? "" : position.getPriority()) + "|"
                    + position.getReviewCreatedAt() + "|" + position.getCommentId() + ":" + position.getDisplayOrder();
        String value = "1|" + id + "|" + filter + "|" + (reverse ? "P" : "N") + "|"
                + position.getSection() + "|" + position.getImageId() + "|" + tail;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String value, Long id, Filter filter) {
        try {
            if (value.length() > 1024 || !value.matches("[A-Za-z0-9_-]+")) throw invalidCursor();
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 10 || !"1".equals(fields[0]) || !Long.valueOf(fields[1]).equals(id)
                    || !filter.name().equals(fields[2]) || !("N".equals(fields[3]) || "P".equals(fields[3]))) throw invalidCursor();
            var position = new DestinationGalleryPosition();
            position.setSection(fields[4]); position.setImageId(Long.valueOf(fields[5]));
            if (position.getImageId() <= 0) throw invalidCursor();
            if ("A".equals(fields[4])) {
                if (filter == Filter.TRAVELER || !fields[7].isEmpty() || !fields[8].isEmpty() || !fields[9].isEmpty()) throw invalidCursor();
                position.setOrderIndex(Integer.valueOf(fields[6]));
            } else if ("R".equals(fields[4])) {
                if (filter == Filter.ADMIN || !fields[6].isEmpty()) throw invalidCursor();
                Integer priority = fields[7].isEmpty() ? null : Integer.valueOf(fields[7]);
                if (priority != null && (priority < 1 || priority > 30)) throw invalidCursor();
                position.setPriority(priority);
                position.setReviewCreatedAt(LocalDateTime.parse(fields[8]));
                if (position.getReviewCreatedAt().getYear() < 1000 || position.getReviewCreatedAt().getYear() > 9999) throw invalidCursor();
                String[] review = fields[9].split(":", -1);
                if (review.length != 2) throw invalidCursor();
                position.setCommentId(Long.valueOf(review[0])); position.setDisplayOrder(Integer.valueOf(review[1]));
                if (position.getCommentId() <= 0 || position.getDisplayOrder() < 1 || position.getDisplayOrder() > 30) throw invalidCursor();
            } else throw invalidCursor();
            return new Cursor("P".equals(fields[3]), position);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) { throw invalidCursor(); }
    }

    private ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 갤러리 필터 또는 커서입니다.");
    }

    private enum Filter { ALL, ADMIN, TRAVELER }
    private record Cursor(boolean reverse, DestinationGalleryPosition position) {}
    private record Candidate(DestinationGalleryPosition position, DestinationGalleryImage image) {}
}
