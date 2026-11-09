package com.tripbora.service.policy;

public enum PolicyConsentStatus {
    CURRENT_AGREED,
    LEGACY_ONLY_VALID,
    NEW_CONSENT_REQUIRED,
    DECLINED,
    WITHDRAWN,
    NO_RECORD,
    NO_ACTIVE_POLICY
}
