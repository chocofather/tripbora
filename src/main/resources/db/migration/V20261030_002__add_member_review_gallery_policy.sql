-- 기존 정책/동의 테이블 재사용. 기존 회원 및 기존 사진에는 동의를 부여하지 않는다.
INSERT INTO policy_versions
    (policy_type, version, requires_consent, is_required, requires_reconsent, effective_at, published_at, is_active)
VALUES
    ('PHOTO_UPLOAD_NOTICE', '20261030-v1', 0, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1),
    ('REVIEW_GALLERY', '20261030-v1', 1, 0, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1);

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'ko', '사진 업로드 및 공개 안내',
    '<p>댓글에 첨부한 사진은 관리자 심사 및 상세 갤러리 활용 동의 여부와 관계없이 댓글과 사진 후기에 공개됩니다. 본인이 촬영했거나 공개에 필요한 적법한 사용 권한을 가진 사진만 업로드해 주세요. 본인 댓글의 수정에서 사진을 삭제할 수 있습니다.</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261030-v1';

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'ko', '리뷰 사진의 상세 갤러리 활용 동의',
    '<p>선택 동의입니다. 동의 후 새로 업로드한 리뷰 사진 중 관리자가 승인한 사진을 TripBora 여행지·맛집 상세 갤러리에 표시할 수 있습니다. 본인이 촬영했거나 이러한 공개 및 활용에 필요한 적법한 사용 권한을 가진 사진에 적용됩니다. 기존 업로드 사진에는 소급 적용하지 않습니다. 거부해도 가입 및 일반 댓글 사진 업로드를 이용할 수 있습니다. 마이페이지의 내 리뷰 사진 관리에서 언제든 철회할 수 있으며 철회 즉시 갤러리 활용 대상에서 제외됩니다. 댓글에 첨부된 사진의 일반 공개는 유지되므로 공개를 중단하려면 사진 또는 댓글을 삭제해 주세요. 동의·거부·철회 결정은 버전 및 시각과 함께 기록합니다.</p>'
FROM policy_versions WHERE policy_type = 'REVIEW_GALLERY' AND version = '20261030-v1';
