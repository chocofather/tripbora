// 내 리뷰 사진 삭제와 과거 선택 동의의 갤러리 활용 철회를 담당한다.
async function request(url, options = {}) {
    const headers = new Headers(options.headers || {});
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (token && header && options.method && options.method !== 'GET') headers.set(header, token);
    const response = await fetch(url, {...options, headers, credentials: 'same-origin', cache: 'no-store'});
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || body.detail || '사진을 삭제하지 못했습니다. 다시 시도해 주세요.');
    }
    return response.status === 204 ? null : response.json();
}

document.querySelectorAll('[data-review-photo-delete]').forEach(button => {
    button.addEventListener('click', async () => {
        if (!confirm('댓글의 사진을 삭제하시겠습니까?')) return;
        button.disabled = true;
        try {
            await request(`/comments/${button.dataset.commentId}/images/${button.dataset.imageId}`, {method: 'DELETE'});
            window.location.reload();
        } catch (error) { button.disabled = false; alert(error.message); }
    });
});

document.querySelectorAll('[data-legacy-gallery-withdrawal]').forEach(scope => {
    const button = scope.querySelector('[data-legacy-gallery-withdraw]');
    if (!button) return;
    const status = scope.querySelector('[data-legacy-gallery-status]');
    const error = scope.querySelector('[data-legacy-gallery-error]');
    button.addEventListener('click', async () => {
        if (button.disabled || button.hidden) return;
        if (!confirm('과거에 선택 동의한 사진의 갤러리 활용을 철회하시겠습니까? 원본 리뷰와 사진은 유지되며, 새 업로드 이용조건으로 등록한 사진에는 적용되지 않습니다.')) return;
        button.disabled = true;
        scope.setAttribute('aria-busy', 'true');
        error.hidden = true;
        error.textContent = '';
        status.textContent = '과거 사진 갤러리 활용 동의를 철회하고 있습니다.';
        let response;
        try {
            const headers = new Headers({'Accept': 'application/json', 'Content-Type': 'application/json'});
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (token && header) headers.set(header, token);
            response = await fetch('/mypage/gallery-consent', {
                method: 'PUT', headers, credentials: 'same-origin', cache: 'no-store',
                body: JSON.stringify({action: 'WITHDRAW', policyVersionId: Number(scope.dataset.policyId),
                    version: scope.dataset.policyVersion})
            });
            if (response.redirected || response.status === 401 || response.status === 403)
                throw new Error();
            if (response.status === 409)
                throw new Error();
            if (!response.ok || !response.headers.get('Content-Type')?.includes('application/json'))
                throw new Error();
            const result = await response.json();
            if (result?.agreed !== false || (result?.decisionAction !== 'WITHDRAW' && result?.status !== 'WITHDRAWN'))
                throw new Error();
            button.hidden = true;
            status.textContent = '과거 사진 갤러리 활용 동의 철회가 완료되었습니다.';
            window.location.reload();
        } catch {
            button.disabled = false;
            status.textContent = '철회가 완료되지 않았습니다.';
            error.textContent = response?.redirected || response?.status === 401 || response?.status === 403
                ? '로그인 상태 또는 요청 권한을 확인한 뒤 다시 시도해 주세요.'
                : response?.status === 409
                    ? '동의 상태가 변경되었습니다. 페이지를 새로고침한 뒤 다시 확인해 주세요.'
                    : '철회를 확인하지 못했습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.';
            error.hidden = false;
        } finally {
            scope.setAttribute('aria-busy', 'false');
        }
    });
});
