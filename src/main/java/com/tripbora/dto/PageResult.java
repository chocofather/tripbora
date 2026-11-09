package com.tripbora.dto;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private List<T> content;       // 실제 데이터 목록 (댓글 등)
    private int totalElements;     // 페이징 대상 전체 개수
    private int currentPage;       // 현재 페이지
    private int pageSize;          // 페이지당 개수
    private int totalCommentCount; // 대댓글을 포함한 활성 댓글 전체 개수
    private int reviewCount; // 여행지 공개 최상위 글 수. 답글 및 숨김 안내 자리는 제외
    private DestinationReviewStatistics reviewStatistics;

    public PageResult(List<T> content, int totalElements, int currentPage, int pageSize) {
        this(content, totalElements, currentPage, pageSize, totalElements);
    }

    public PageResult(List<T> content, int totalElements, int currentPage, int pageSize,
                      int totalCommentCount) {
        this.content = content;
        this.totalElements = totalElements;
        this.currentPage = currentPage;
        this.pageSize = pageSize;
        this.totalCommentCount = totalCommentCount;
    }

    public boolean isLast() {
        return ((long) currentPage + 1L) * pageSize >= totalElements;
    }
}
