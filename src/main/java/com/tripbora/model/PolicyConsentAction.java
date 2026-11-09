package com.tripbora.model;

/** 새 결정의 의미. agreed=false만으로 과거 거절과 철회를 구별하지 않는다. */
public enum PolicyConsentAction {
    AGREE,
    DECLINE,
    WITHDRAW
}
