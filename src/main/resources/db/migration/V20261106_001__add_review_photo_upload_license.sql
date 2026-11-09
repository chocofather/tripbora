-- 기존 사진/선택 동의/정책 원문은 보존하며 이용조건 근거를 역기입하지 않는다.
-- 각 DDL은 독립 커밋될 수 있다. 실패 시 실제 스키마와 Flyway 이력을 확인한 뒤 복구한다.
ALTER TABLE destination_comment_images
    ADD COLUMN gallery_license_policy_id BIGINT NULL,
    ADD COLUMN gallery_license_accepted_at DATETIME(6) NULL;

ALTER TABLE destination_comment_images
    ADD CONSTRAINT fk_destination_comment_image_license_policy
        FOREIGN KEY (gallery_license_policy_id) REFERENCES policy_versions (id) ON DELETE RESTRICT;

ALTER TABLE destination_comment_images
    ADD CONSTRAINT chk_destination_comment_image_license_pair
        -- gallery_consent_id는 ON DELETE SET NULL FK이므로 CHECK에서 참조하지 않는다.
        -- 신규 이용조건과 과거 동의 ID의 혼합은 공통 런타임 SQL에서도 거부한다.
        CHECK ((gallery_license_policy_id IS NULL AND gallery_license_accepted_at IS NULL)
            OR (gallery_license_policy_id IS NOT NULL AND gallery_license_accepted_at IS NOT NULL
                AND gallery_consent_at IS NULL
                AND gallery_consent_version IS NULL));

-- 컬럼 변경과 기존 CHECK 교체를 한 ALTER에 묶지 않는다.
ALTER TABLE destination_comment_images
    DROP CHECK chk_destination_comment_image_gallery_consent;

ALTER TABLE destination_comment_images
    ADD CONSTRAINT chk_destination_comment_image_gallery_consent
        CHECK (gallery_status NOT IN ('PENDING', 'APPROVED')
            OR (gallery_consent_at IS NOT NULL AND gallery_consent_version IS NOT NULL
                AND gallery_consent_withdrawn_at IS NULL)
            OR (gallery_license_policy_id IS NOT NULL AND gallery_license_accepted_at IS NOT NULL));

-- 가입 필수/선택 동의가 아닌 새 사진 업로드 이용조건이다. 과거 REVIEW_GALLERY는 변경하지 않는다.
INSERT INTO policy_versions
    (policy_type, version, requires_consent, is_required, requires_reconsent, effective_at, published_at, is_active)
VALUES
    ('PHOTO_UPLOAD_NOTICE', '20261106-v1', 0, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1);

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'ko', '리뷰 사진 업로드 이용조건',
    '<p>사진의 저작권은 회원 또는 원래 권리자에게 있습니다. 본인이 촬영했거나 아래 범위의 이용을 허락할 적법한 권한이 있는 사진만 업로드해 주세요. 업로드 시 회원은 TripBora에 서비스 안에서 사진을 게시·복제하고 화면 크기에 맞게 조정할 수 있는 비독점적 이용권을 허락합니다.</p><p>이용 범위는 리뷰·사진 후기 표시와 관리자 심사 후 해당 여행지 상세 갤러리에 표시하는 것입니다. 이 이용권은 해당 리뷰가 공개 상태로 유지되는 동안에만 유효합니다. 회원은 사진 또는 리뷰를 삭제할 수 있으며, 삭제나 비공개·차단 시 갤러리 공개 이용도 중단됩니다. 영구 보유 권한이나 삭제 금지를 뜻하지 않습니다.</p><p>외부 광고, 제3자 제공, 별도의 상업적 재사용은 이 허락에 포함되지 않으며 필요한 경우 별도 허락을 받아야 합니다. 이 조건은 지금 새로 업로드하는 사진에만 적용하고 기존 사진에 소급 적용하지 않습니다. 갤러리에는 기존 관리자 승인 절차를 통과한 사진만 표시합니다. 가입 시 필수 또는 선택 동의를 추가하는 조건이 아닙니다.</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261106-v1';

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'en', 'Review photo upload terms',
    '<p>Copyright remains with the member or original rights holder. Upload only photos you took or have lawful authority to license for the uses described here. By uploading, you grant TripBora a non-exclusive license to post, reproduce and resize the photos within its service.</p><p>The permitted uses are displaying reviews and photo reviews, and displaying photos in the relevant destination gallery after moderator approval. The license lasts only while the review remains public and maintained. You may delete the photo or review; deletion, making it private or moderation blocking also ends public gallery use. This does not grant perpetual retention rights or prohibit deletion.</p><p>External advertising, provision to third parties and separate commercial reuse are excluded and require separate permission where needed. These terms apply only to photos newly uploaded now, never retroactively to existing photos. Existing moderator approval is required before gallery display. These terms do not add a required or optional consent at signup.</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261106-v1';

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'ja', 'レビュー写真アップロード利用条件',
    '<p>写真の著作権は会員または元の権利者に帰属します。ご自身で撮影した写真、または以下の利用を許諾する適法な権限のある写真のみアップロードしてください。アップロードにより、会員はTripBoraに対し、サービス内で写真を掲載・複製し、表示サイズを調整する非独占的利用権を許諾します。</p><p>利用範囲はレビュー・写真レビューの表示と、管理者の審査後に該当旅行先の詳細ギャラリーに表示することです。利用権は当該レビューが公開状態で維持される間に限り有効です。写真やレビューは削除でき、削除・非公開・公開制限によりギャラリーでの公開利用も終了します。永久保有権の付与や削除の禁止を意味しません。</p><p>外部広告、第三者への提供、別途の商業的再利用は含まれず、必要な場合は別途許諾を得なければなりません。この条件は今回新たにアップロードする写真にのみ適用し、既存写真には遡及適用しません。ギャラリー掲載には従来どおり管理者の承認が必要です。会員登録時の必須・任意同意を追加する条件ではありません。</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261106-v1';

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'zh-CN', '点评照片上传使用条件',
    '<p>照片版权归会员或原权利人所有。请仅上传本人拍摄或依法有权许可下述使用的照片。上传时，会员授予TripBora在服务内发布、复制并调整照片显示尺寸的非独占使用许可。</p><p>许可范围仅限于展示点评、照片点评，以及经管理员审核后在相应目的地详情图库中展示。许可仅在该点评保持公开并继续保留期间有效。会员可以删除照片或点评；删除、转为非公开或被限制公开后，图库中的公开使用也将停止。这不授予永久保留权，也不禁止删除。</p><p>外部广告、向第三方提供及另行商业再利用均不在许可范围内，必要时须另行取得许可。本条件仅适用于本次新上传的照片，不追溯适用于已有照片。图库展示仍须经过现有管理员批准流程。本条件不新增注册时的必选或可选同意。</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261106-v1';

INSERT INTO policy_translations (policy_version_id, locale, title, content)
SELECT id, 'zh-TW', '評論照片上傳使用條件',
    '<p>照片著作權屬於會員或原權利人。請僅上傳本人拍攝或依法有權授權下述使用的照片。上傳時，會員授予TripBora在服務內刊登、重製並調整照片顯示尺寸的非專屬使用授權。</p><p>授權範圍僅限於顯示評論、照片評論，以及經管理員審查後在相應目的地詳細圖庫中顯示。授權僅在該評論維持公開並繼續保留期間有效。會員可以刪除照片或評論；刪除、改為非公開或遭限制公開後，圖庫中的公開使用也將停止。這不授予永久保留權，也不禁止刪除。</p><p>外部廣告、提供予第三方及另行商業再利用均不在授權範圍內，必要時須另行取得授權。本條件僅適用於本次新上傳的照片，不溯及既有照片。圖庫顯示仍須通過現有管理員核准程序。本條件不新增註冊時的必要或選擇性同意。</p>'
FROM policy_versions WHERE policy_type = 'PHOTO_UPLOAD_NOTICE' AND version = '20261106-v1';
