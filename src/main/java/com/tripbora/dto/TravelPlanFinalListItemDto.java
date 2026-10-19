package com.tripbora.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import com.tripbora.model.TravelPlanRole;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * 완료된 여행 목록의 한 줄.
 *
 * <p>목록에서는 많이 보여 주지 않는다. 어떤 여행이었고 언제였는지, 몇 명이 함께했는지까지다.
 * 날짜별 일정은 눌러서 들어갔을 때 읽는다.
 *
 * <p>모두 최종본에서 읽는다. 원본 방을 다시 들여다보지 않는다.
 */
@Data
@NoArgsConstructor
public class TravelPlanFinalListItemDto {
    /** 예전 공동 편집방 번호. 최종본 상세로 가는 길에 쓴다. */
    private Long travelPlanId;
    private Long snapshotId;
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private String representativeImageUrl;
    private int memberCount;
    /** 완료 시점에 저장된 현재 사용자의 역할. */
    private TravelPlanRole role;
    private Timestamp finalizedAt;
}
