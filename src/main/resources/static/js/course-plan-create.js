(function () {
    'use strict';
    function mount(doc) {
        const dialog = doc.querySelector('#course-plan-dialog');
        const opener = doc.querySelector('[data-course-plan-open]');
        if (!dialog || !opener) return;
        const form = dialog.querySelector('[data-course-plan-form]');
        const submit = form.querySelector('[type="submit"]');
        const error = dialog.querySelector('[data-course-plan-error]');
        const title = form.elements.title;
        let loading = false, saving = false, ready = false, titleEdited = false;
        let previousOverflow = '';
        title.addEventListener('input', () => { titleEdited = true; });

        function login() {
            doc.defaultView.location.assign('/login?redirect=' + encodeURIComponent(dialog.dataset.returnUrl));
        }
        async function open() {
            if (!dialog.open) {
                previousOverflow = doc.body.style.overflow;
                dialog.showModal();
                doc.body.style.overflow = 'hidden';
            }
            if (loading || ready) return;
            loading = true; error.textContent = '코스 정보를 확인하고 있어요.';
            submit.disabled = true;
            try {
                const response = await fetch(dialog.dataset.templateUrl, {
                    headers: {Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest'}, credentials: 'same-origin'
                });
                if (response.status === 401) { login(); return; }
                const data = await response.json();
                if (!response.ok) throw new Error(data.message || '코스를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
                if (!titleEdited) title.value = data.title;
                form.elements.creationToken.value = data.creationToken;
                form.dataset.minDays = String(data.dayCount);
                dialog.querySelector('[data-course-plan-title]').textContent = data.title;
                dialog.querySelector('[data-course-plan-region]').textContent = data.regionLabel;
                dialog.querySelector('[data-course-plan-duration]').textContent = data.durationLabel;
                dialog.querySelector('[data-course-plan-description]').textContent = `전체 ${data.dayCount}일 일정을 새 여행으로 가져옵니다. 생성 후 자유롭게 수정할 수 있습니다.`;
                error.textContent = ''; ready = true;
            } catch (failure) {
                error.textContent = failure instanceof TypeError || failure instanceof SyntaxError
                    ? '코스를 불러오지 못했습니다. 창을 닫고 다시 시도해 주세요.' : failure.message;
            } finally {
                loading = false; submit.disabled = !ready;
            }
        }
        opener.addEventListener('click', event => { event.preventDefault(); open(); });
        dialog.querySelectorAll('[data-course-plan-close]').forEach(button => button.addEventListener('click', () => {
            if (!saving) dialog.close();
        }));
        dialog.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
        dialog.addEventListener('close', () => { doc.body.style.overflow = previousOverflow; opener.focus(); });
        form.addEventListener('submit', async event => {
            // 공통 날짜 선택기가 미입력/기간/중복 제출을 먼저 검사한다.
            if (event.defaultPrevented) return;
            event.preventDefault();
            if (!ready || saving) {
                if (!saving) delete form.dataset.submitting;
                submit.disabled = true;
                return;
            }
            saving = true; submit.disabled = true; error.textContent = '';
            const headers = {Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest'};
            const csrf = doc.querySelector('meta[name="_csrf"]')?.content;
            const csrfHeader = doc.querySelector('meta[name="_csrf_header"]')?.content;
            if (csrf && csrfHeader) headers[csrfHeader] = csrf;
            try {
                const response = await fetch(form.action, {
                    method: 'POST', headers, credentials: 'same-origin', body: new URLSearchParams(new FormData(form))
                });
                if (response.status === 401) { login(); return; }
                if (response.status === 403) throw new Error('로그인 또는 보안 확인이 만료되었습니다. 새로고침 후 다시 시도해 주세요.');
                const data = await response.json();
                if (!response.ok) throw new Error(data.errors?.map(item => item.message).join(' ') || data.message || '여행을 만들지 못했습니다. 입력 내용을 확인해 주세요.');
                if (!/^\/travel-plans\/\d+$/.test(data.redirectUrl)) throw new Error('생성 결과를 확인하지 못했습니다. 같은 창에서 다시 시도해 주세요.');
                doc.defaultView.location.assign(data.redirectUrl);
            } catch (failure) {
                error.textContent = failure instanceof TypeError || failure instanceof SyntaxError
                    ? '생성 결과를 확인하지 못했습니다. 같은 창에서 다시 시도해 주세요.' : failure.message;
            } finally {
                saving = false; delete form.dataset.submitting; submit.disabled = !ready;
            }
        });
        doc.defaultView.addEventListener('pageshow', () => {
            if (!saving) delete form.dataset.submitting;
            submit.disabled = !ready || saving;
        });
        if (new URLSearchParams(doc.defaultView.location.search).get('createPlan') === 'true') open();
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {mount};
    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => mount(document));
        else mount(document);
    }
}());
