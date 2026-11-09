(() => {
    const root = document.querySelector('[data-photo-queue]');
    if (!root) return;
    const form = root.querySelector('[data-batch-form]');
    const boxes = [...form.querySelectorAll('input[name="ids"]')];
    const error = root.querySelector('[data-batch-error]');
    const reject = root.querySelector('[data-reject-dialog]');
    const reason = root.querySelector('[data-reason]');
    let pending = null, busy = false;
    // 새 페이지 및 뒤로 가기 복원 시 브라우저가 체크 상태를 이어받지 않게 한다.
    boxes.forEach(box => { box.checked = false; });
    root.querySelector('[data-select-pending]').checked = false;
    const canApprove = box => box.closest('[data-status]').dataset.approvable === 'true';
    const update = () => {
        const selected = boxes.filter(b => b.checked && !b.disabled);
        root.querySelector('[data-selected-count]').textContent = selected.length;
        const restricted = selected.filter(b => !canApprove(b)).length;
        root.querySelector('[data-approve-selected]').disabled = !selected.length || !!restricted;
        root.querySelector('[data-approval-hint]').textContent = restricted ? `승인 자격 제한 ${restricted}장 포함 · 대기 사진 반려는 가능합니다.` : '';
    };
    boxes.forEach(box => box.addEventListener('change', update));
    root.querySelector('[data-select-pending]').addEventListener('change', event => {
        boxes.forEach(box => { box.checked = !box.disabled && box.closest('[data-status]').dataset.status === 'PENDING' && event.target.checked; });
        update();
    });
    function send(ids, action, note = '') {
        if (busy) return;
        busy = true;
        const payload = document.createElement('form');
        payload.method = 'post'; payload.setAttribute('action', form.getAttribute('action'));
        form.querySelectorAll('input[type="hidden"]').forEach(input => payload.append(input.cloneNode(true)));
        const add = (name, value) => { const input = document.createElement('input'); input.type = 'hidden'; input.name = name; input.value = value; payload.append(input); };
        ids.forEach(id => add('ids', id)); add('action', action); add('reason', note);
        form.querySelectorAll('button').forEach(button => { button.disabled = true; });
        document.body.append(payload); payload.submit();
    }
    form.addEventListener('submit', event => {
        event.preventDefault(); if (busy || !event.submitter) return;
        const button = event.submitter, action = button.value;
        let selected = boxes.filter(box => box.checked && !box.disabled);
        if (button.hasAttribute('data-single')) selected = [button.closest('[data-status]').querySelector('[name="ids"]')];
        if (button.hasAttribute('data-approve-card')) selected = [...button.closest('.review-photo-group').querySelectorAll('[data-status="PENDING"] [name="ids"]')].filter(canApprove);
        if (!selected.length) { error.textContent = '처리할 사진을 선택해 주세요.'; return; }
        if (selected.length > 100) { error.textContent = '한 번에 최대 100장까지 선택해 주세요.'; return; }
        if (action === 'APPROVE' && selected.some(box => !canApprove(box))) { error.textContent = '승인 자격 제한 사진을 제외해 주세요. 대기 사진은 반려할 수 있습니다.'; return; }
        if (selected.some(box => box.closest('[data-status]').dataset.status !== (action === 'REVOKE' ? 'APPROVED' : 'PENDING'))) {
            error.textContent = action === 'REVOKE' ? '승인된 사진만 선택해 주세요.' : '승인 대기 사진만 선택해 주세요.'; return;
        }
        error.textContent = ''; const ids = selected.map(box => box.value);
        if (action === 'REJECT') { pending = ids; reason.value = ''; root.querySelector('[data-reject-count]').textContent = `${ids.length}장 반려`; reject.showModal(); reason.focus(); }
        else if (action !== 'REVOKE' || confirm(`${ids.length}장의 갤러리 승인을 취소하시겠습니까?`)) send(ids, action);
    });
    root.querySelector('[data-reject-cancel]').addEventListener('click', () => { pending = null; reject.close(); });
    root.querySelector('[data-reject-confirm]').addEventListener('click', () => {
        if (!reason.value.trim()) { reason.setCustomValidity('반려 사유를 입력해 주세요.'); reason.reportValidity(); return; }
        if (pending) send(pending, 'REJECT', reason.value.trim());
    });
    reason.addEventListener('input', () => reason.setCustomValidity(''));
    const viewer = root.querySelector('[data-photo-dialog]');
    root.querySelectorAll('[data-enlarge]').forEach(button => button.addEventListener('click', () => {
        viewer.querySelector('img').src = button.dataset.url;
        const details = viewer.querySelector('[data-photo-details]');
        if (details) {
            details.replaceChildren();
            const info = button.closest('[data-status]').querySelector('[data-photo-info]');
            if (info) details.append(info.content.cloneNode(true));
        }
        viewer.showModal();
    }));
    root.querySelector('[data-photo-close]').addEventListener('click', () => viewer.close());
    window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
    update();
})();
