(function (root) {
    const clamp = value => Math.max(0, Math.min(100, Math.round(value)));
    // 레이아웃의 소수점 반올림으로 생기는 0.5px 이하 차이는 잘리는 축으로 취급하지 않는다.
    const hasOverflow = value => value > 0.5;

    function coverOverflow(imageWidth, imageHeight, frameWidth, frameHeight) {
        if (!imageWidth || !imageHeight || !frameWidth || !frameHeight) return {x: 0, y: 0};
        const scale = Math.max(frameWidth / imageWidth, frameHeight / imageHeight);
        return {x: Math.max(0, imageWidth * scale - frameWidth),
            y: Math.max(0, imageHeight * scale - frameHeight)};
    }

    // object-position 이동은 넘치는 길이에 대한 비율이므로 포인터 이동과 부호가 반대다.
    function draggedPosition(start, delta, overflow) {
        return hasOverflow(overflow) ? clamp(start - delta / overflow * 100) : 50;
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = {coverOverflow, draggedPosition};
    if (!root.document) return;

    const dialog = document.getElementById('image-position-dialog');
    if (!dialog) return;
    const stage = dialog.querySelector('[data-position-stage]');
    const xSlider = dialog.querySelector('#image-position-x');
    const ySlider = dialog.querySelector('#image-position-y');
    const xOutput = dialog.querySelector('[data-position-x-output]');
    const yOutput = dialog.querySelector('[data-position-y-output]');
    const reset = dialog.querySelector('[data-position-reset]');
    const cancel = dialog.querySelector('[data-position-cancel]');
    const save = dialog.querySelector('[data-position-save]');
    const status = dialog.querySelector('[data-position-status]');
    let image, opener, marker, saved, draft, originalDraggable, previousOverflow;
    let overflow = {x: 0, y: 0};
    let ready = false, busy = false, drag = null;

    function apply(position) {
        image.style.setProperty('--card-position-x', `${position.x}%`);
        image.style.setProperty('--card-position-y', `${position.y}%`);
    }

    function render() {
        apply(draft);
        xSlider.value = draft.x;
        ySlider.value = draft.y;
        xOutput.value = `${draft.x}%`;
        yOutput.value = `${draft.y}%`;
        xSlider.disabled = !ready || busy || !hasOverflow(overflow.x);
        ySlider.disabled = !ready || busy || !hasOverflow(overflow.y);
        save.disabled = reset.disabled = !ready || busy;
        cancel.disabled = busy;
    }

    function refresh() {
        if (!image || !dialog.open) return;
        drag = null;
        ready = image.complete && image.naturalWidth > 0 && image.naturalHeight > 0
            && !image.getAttribute('src').includes('/images/default.png');
        const frame = stage.getBoundingClientRect();
        overflow = coverOverflow(image.naturalWidth, image.naturalHeight, frame.width, frame.height);
        if (!hasOverflow(overflow.x)) draft.x = 50;
        if (!hasOverflow(overflow.y)) draft.y = 50;
        status.textContent = ready ? '' : '이미지를 불러온 뒤 위치를 조절할 수 있습니다.';
        render();
    }

    document.querySelectorAll('[data-image-position-open]').forEach(button => {
        button.addEventListener('click', () => {
            if (dialog.open) return;
            const photo = button.closest('.admin-destination-image-card')
                ?.querySelector('.admin-destination-image-preview img');
            if (!photo) return;
            opener = button;
            image = photo;
            saved = {x: clamp(Number(button.dataset.positionX ?? 50)), y: clamp(Number(button.dataset.positionY ?? 50))};
            draft = {...saved};
            marker = document.createComment('대표 이미지 원래 위치');
            image.before(marker);
            originalDraggable = image.getAttribute('draggable');
            image.draggable = false;
            image.addEventListener('load', refresh);
            image.addEventListener('error', refresh);
            // 이미 로드한 요소 자체를 이동한다. src/srcset을 바꾸거나 새 이미지를 요청하지 않는다.
            stage.appendChild(image);
            previousOverflow = document.documentElement.style.overflow;
            document.documentElement.style.overflow = 'hidden';
            dialog.showModal();
            refresh();
        });
    });

    [xSlider, ySlider].forEach((slider, index) => slider.addEventListener('input', () => {
        if (!ready || busy || slider.disabled) return;
        draft[index === 0 ? 'x' : 'y'] = clamp(Number(slider.value));
        render();
    }));
    reset.addEventListener('click', () => {
        if (!ready || busy) return;
        draft = {x: 50, y: 50};
        render();
    });

    stage.addEventListener('pointerdown', event => {
        if (!ready || busy || !event.isPrimary || event.button !== 0) return;
        event.preventDefault();
        drag = {id: event.pointerId, x: event.clientX, y: event.clientY, position: {...draft}, overflow: {...overflow}};
        stage.setPointerCapture(event.pointerId);
    });
    stage.addEventListener('pointermove', event => {
        if (!drag || event.pointerId !== drag.id) return;
        draft = {x: draggedPosition(drag.position.x, event.clientX - drag.x, drag.overflow.x),
            y: draggedPosition(drag.position.y, event.clientY - drag.y, drag.overflow.y)};
        render();
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(type => stage.addEventListener(type, event => {
        if (drag?.id !== event.pointerId) return;
        drag = null;
        if (stage.hasPointerCapture(event.pointerId)) stage.releasePointerCapture(event.pointerId);
    }));
    new ResizeObserver(refresh).observe(stage);

    cancel.addEventListener('click', () => { if (!busy) dialog.close(); });
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    dialog.addEventListener('click', event => { if (event.target === dialog && !busy) dialog.close(); });
    dialog.addEventListener('close', () => {
        if (!image) return;
        apply(saved);
        image.removeEventListener('load', refresh);
        image.removeEventListener('error', refresh);
        if (originalDraggable === null) image.removeAttribute('draggable');
        else image.setAttribute('draggable', originalDraggable);
        marker.replaceWith(image);
        document.documentElement.style.overflow = previousOverflow;
        image = null;
        drag = null;
        opener.focus();
    });

    save.addEventListener('click', async () => {
        if (!ready || busy) return;
        busy = true;
        drag = null;
        render();
        status.textContent = '저장 중입니다.';
        try {
            const token = document.querySelector("meta[name='_csrf']")?.content;
            const header = document.querySelector("meta[name='_csrf_header']")?.content;
            if (!token || !header) throw new Error('보안 토큰을 확인할 수 없습니다. 새로고침 후 다시 저장해 주세요.');
            const response = await fetch(opener.dataset.positionUrl, {
                method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json', Accept: 'application/json', [header]: token},
                body: JSON.stringify(draft)
            });
            const payload = (response.headers.get('Content-Type') || '').includes('application/json')
                ? await response.json() : null;
            if (!response.ok || response.redirected || !payload || payload.x !== draft.x || payload.y !== draft.y) {
                throw new Error(payload?.message || '저장하지 못했습니다. 로그인 상태와 대표 이미지를 확인한 뒤 다시 시도해 주세요.');
            }
            saved = {...draft};
            opener.dataset.positionX = saved.x;
            opener.dataset.positionY = saved.y;
            busy = false;
            dialog.close();
        } catch (error) {
            busy = false;
            render();
            status.textContent = error.message || '저장하지 못했습니다. 다시 시도해 주세요.';
        }
    });
})(typeof window === 'undefined' ? globalThis : window);
