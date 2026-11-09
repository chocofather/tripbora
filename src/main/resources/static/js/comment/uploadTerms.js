const termsByForm = new WeakMap();
const policyUrl = '/review-photo-terms';
const unavailableMessage = '사진 업로드 조건을 확인하지 못했습니다. 다시 확인하거나 사진 없이 리뷰를 등록해 주세요.';

/** 사진을 첨부하는 모든 작성·수정 폼에 같은 범위의 이용조건을 표시한다. */
export function uploadTermsMarkup() {
    return `<section class="review-photo-upload-terms" data-photo-upload-terms aria-label="리뷰 사진 업로드 조건">
      <strong>사진을 첨부하기 전에 확인해 주세요</strong>
      <p>사진의 권리는 회원님에게 있습니다. 직접 촬영했거나 적법한 사용 권한이 있는 사진만 올려 주세요.</p>
      <p>사진을 첨부해 리뷰를 등록·저장하면, 리뷰가 공개되는 동안 TripBora에 서비스 내 게시·복제·크기 조정 및 갤러리 표시에 필요한 비독점적 이용을 허용합니다. 여행지 갤러리에는 승인된 사진만 표시됩니다.</p>
      <p>외부 광고, 제3자 제공, 별도 상업적 재사용은 이 조건에 포함되지 않습니다. 이 조건을 원하지 않으면 사진을 첨부하지 않아도 텍스트 리뷰를 작성할 수 있습니다.</p>
      <div class="review-photo-upload-terms-meta">
        <a data-photo-upload-link href="${policyUrl}" target="_blank" rel="noopener noreferrer">사진 업로드 이용조건 전문</a>
        <span data-photo-upload-version>현재 버전 확인 중</span>
        <button type="button" data-photo-upload-retry hidden>다시 확인</button>
      </div>
      <p data-photo-upload-status role="status" aria-live="polite"></p>
    </section>`;
}

function ensureTerms(form) {
    let state = termsByForm.get(form);
    if (state) return state;
    if (!form.querySelector('[data-photo-upload-terms]')) {
        const holder = document.createElement('div');
        holder.innerHTML = uploadTermsMarkup();
        const controls = form.querySelector('.comment-controls');
        form.insertBefore(holder.firstElementChild, controls);
    }
    const surface = form.querySelector('[data-photo-upload-terms]');
    state = {surface, policy: null, pending: null};
    termsByForm.set(form, state);
    surface.querySelector('[data-photo-upload-retry]').addEventListener('click', () => refreshPhotoUploadTerms(form));
    return state;
}

async function loadTerms(form, changed) {
    const state = ensureTerms(form);
    if (state.pending) return state.pending;
    state.policy = null;
    const status = state.surface.querySelector('[data-photo-upload-status]');
    const version = state.surface.querySelector('[data-photo-upload-version]');
    const retry = state.surface.querySelector('[data-photo-upload-retry]');
    version.textContent = '현재 버전 확인 중';
    status.textContent = changed ? '사진 업로드 조건이 변경되었을 수 있어 현재 조건을 다시 확인하고 있습니다.' : '';
    retry.hidden = true;
    state.pending = (async () => {
        try {
            const response = await fetch(policyUrl, {
                headers: {'Accept': 'application/json'}, credentials: 'same-origin', cache: 'no-store'
            });
            const contentType = response.headers.get('Content-Type') || '';
            if (!response.ok || response.redirected || !contentType.includes('application/json')) throw new Error(unavailableMessage);
            const policy = await response.json();
            if (policy.available !== true || !Number.isSafeInteger(policy.policyVersionId) || policy.policyVersionId <= 0
                || typeof policy.version !== 'string' || !policy.version.trim()
                || typeof policy.contentHtml !== 'string' || !policy.contentHtml.trim()) throw new Error(unavailableMessage);
            state.policy = policy;
            version.textContent = `현재 버전: ${policy.version}`;
            state.surface.querySelector('[data-photo-upload-link]').href = `${policyUrl}?versionId=${policy.policyVersionId}`;
            status.textContent = changed ? '현재 사진 업로드 조건을 확인한 뒤 등록·저장을 다시 눌러 주세요. 자동으로 제출하지 않습니다.' : '';
            return true;
        } catch (error) {
            version.textContent = '현재 버전 확인 필요';
            status.textContent = unavailableMessage;
            retry.hidden = false;
            return false;
        } finally {
            state.pending = null;
        }
    })();
    return state.pending;
}

export function initPhotoUploadTerms(form) {
    const state = ensureTerms(form);
    return state.policy ? Promise.resolve(true) : loadTerms(form, false);
}

/** 409 후 최신 안내만 갱신한다. 업로드 재시도는 회원의 다음 제출에 맡긴다. */
export function refreshPhotoUploadTerms(form) {
    return loadTerms(form, true);
}

/** 기존 사진을 보관·삭제하거나 글만 수정할 때는 새 이용조건 근거를 보내지 않는다. */
export function appendPhotoUploadPolicy(form, data) {
    data.delete('photoUploadPolicyVersionId');
    if (!data.getAll('images').some(file => typeof file === 'object' && file != null && file.size > 0)) return;
    const state = termsByForm.get(form);
    if (!state?.policy || state.pending) {
        const message = state?.pending ? '사진 업로드 조건을 확인하고 있습니다. 잠시 후 등록·저장을 다시 눌러 주세요.' : unavailableMessage;
        throw new Error(message);
    }
    data.append('photoUploadPolicyVersionId', String(state.policy.policyVersionId));
}
