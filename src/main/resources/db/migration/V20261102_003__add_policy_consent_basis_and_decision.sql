-- NULL 동의 기준은 해당 버전 자신으로 해석한다. 과거 플래그만 보고 호환 관계를 추정하지 않는다.
ALTER TABLE policy_versions
    ADD COLUMN consent_basis_version_id BIGINT NULL,
    ADD CONSTRAINT fk_policy_consent_basis
        FOREIGN KEY (consent_basis_version_id) REFERENCES policy_versions (id) ON DELETE RESTRICT;

-- 기존 agreed/버전/시각/출처는 보존한다. 과거 false의 의도는 NULL(미확인)로 남긴다.
ALTER TABLE user_policy_consents
    ADD COLUMN decision_action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_policy_consent_decision_action
        CHECK (decision_action IS NULL
            OR (decision_action = 'AGREE' AND agreed = 1)
            OR (decision_action IN ('DECLINE', 'WITHDRAW') AND agreed = 0)),
    ADD INDEX idx_user_policy_consent_latest (user_id, policy_version_id, id);
