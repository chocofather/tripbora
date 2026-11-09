package com.tripbora.dto;

import lombok.Getter;
import lombok.Setter;

/** 심사 트랜잭션 내부에서만 사용하는 현재 분석/동의 상태. */
@Getter @Setter
public class ReviewPhotoWorkflowPhoto extends ReviewPhotoMatch {
    private String analysisState;
    private Long analysisDestinationId;
    private Long perceptualHash;
    private Integer hashBand0;
    private Integer hashBand1;
    private Integer hashBand2;
    private Integer hashBand3;
    private boolean duplicate;
}
