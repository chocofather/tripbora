package com.tripbora.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 공개 최상위 글 전체와 그중 별점 평가가 있는 글을 구분한다. */
@Data
public class DestinationReviewStatistics {
    private int reviewCount;
    private int ratingCount;
    private BigDecimal averageRating;
    private int rating1Count;
    private int rating2Count;
    private int rating3Count;
    private int rating4Count;
    private int rating5Count;

    /** index 0 = 1점, index 4 = 5점. 0건에서도 항상 다섯 값을 반환한다. */
    public List<Integer> getDistribution() {
        return List.of(rating1Count, rating2Count, rating3Count, rating4Count, rating5Count);
    }
}
