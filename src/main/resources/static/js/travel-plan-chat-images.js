(function (root) {
    'use strict';
    const MAX_BYTES = 10 * 1024 * 1024;
    const TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
    let viewer = null;
    let viewerMessageId = null;
    let viewerCleanup = null;

    function validate(files) {
        if (files.length > 5) return '사진은 메시지당 최대 5장까지 첨부할 수 있어요.';
        for (const file of files) {
            if (!TYPES.has(file.type) || !/\.(jpe?g|png|webp)$/i.test(file.name)) return 'JPG, PNG, WebP 사진만 첨부할 수 있어요.';
            if (!file.size || file.size > MAX_BYTES) return '사진은 한 장당 10MB까지 첨부할 수 있어요.';
        }
        return '';
    }

    function safeImages(message, planId) {
        const id = message.messageId ?? message.id;
        if (!/^\d+$/.test(String(planId)) || !/^\d+$/.test(String(id))) return [];
        const pattern = new RegExp(`^/travel-plans/${planId}/chat/messages/${id}/images/[1-9][0-9]*$`);
        return (Array.isArray(message.images) ? message.images : []).slice(0, 5)
            .filter(image => pattern.test(image.url) && image.width > 0 && image.height > 0);
    }

    function button(doc, label, className) {
        const node = doc.createElement('button');node.type = 'button';node.className = className;
        node.setAttribute('aria-label', label);return node;
    }

    function closeViewerFor(messageId) {
        if (viewer && String(viewerMessageId) === String(messageId)) viewer.close();
    }

    function closeViewer() { viewer?.close(); }

    function openViewer(images, index, messageId, opener) {
        if (viewer) { viewer.close();viewerCleanup?.(); }
        const doc = opener.ownerDocument;
        const dialog = doc.createElement('dialog');dialog.className = 'tp-chat-image-viewer';
        dialog.setAttribute('aria-label', '채팅 사진 크게 보기');
        const header = doc.createElement('header');
        const counter = doc.createElement('p');counter.setAttribute('aria-live', 'polite');
        const close = button(doc, '사진 닫기', 'tp-chat-viewer-close');close.textContent = '×';
        header.append(counter, close);
        const stage = doc.createElement('div');stage.className = 'tp-chat-viewer-stage';
        const previous = button(doc, '이전 사진', 'tp-chat-viewer-previous');previous.textContent = '‹';
        const next = button(doc, '다음 사진', 'tp-chat-viewer-next');next.textContent = '›';
        const image = doc.createElement('img');image.alt = '채팅 첨부 사진';
        const status = doc.createElement('p');status.className = 'tp-chat-viewer-status';status.setAttribute('role','status');
        stage.append(previous, image, next);dialog.append(header,stage,status);
        const display = () => {
            image.hidden = false;status.textContent = '사진을 불러오는 중이에요.';
            image.src = images[index].url;counter.textContent = `${index + 1} / ${images.length}`;
            previous.disabled = index === 0;next.disabled = index === images.length - 1;
        };
        image.addEventListener('load', () => { status.textContent = ''; });
        image.addEventListener('error', () => { image.hidden = true;status.textContent = '사진을 불러오지 못했어요. 접근 권한 또는 연결 상태를 확인해 주세요.'; });
        previous.addEventListener('click', () => { if (index > 0) { index--;display(); } });
        next.addEventListener('click', () => { if (index < images.length - 1) { index++;display(); } });
        close.addEventListener('click', () => dialog.close());
        dialog.addEventListener('keydown', event => {
            if (event.key === 'ArrowLeft') { event.preventDefault();previous.click(); }
            if (event.key === 'ArrowRight') { event.preventDefault();next.click(); }
        });
        const overflow = doc.body.style.overflow;
        let cleaned = false;
        const cleanup = () => {
            if (cleaned) return;
            cleaned = true;
            dialog.remove();doc.body.style.overflow = overflow;
            if (viewer === dialog) { viewer = null;viewerMessageId = null;viewerCleanup = null; }
            if (opener.isConnected) opener.focus();
        };
        dialog.addEventListener('close', cleanup,{once:true});
        doc.body.append(dialog);viewer = dialog;viewerMessageId = messageId;viewerCleanup = cleanup;
        dialog.showModal();doc.body.style.overflow = 'hidden';display();
    }

    function render(message, bubble, planId) {
        const images = safeImages(message,planId);
        if (!images.length || message.deleted) return;
        const doc = bubble.ownerDocument;
        const grid = doc.createElement('div');grid.className = 'tp-chat-image-grid';
        grid.classList.toggle('is-single', images.length === 1);
        images.forEach((image,index) => {
            const tile = button(doc, `첨부 사진 ${index + 1} 크게 보기`, 'tp-chat-image-tile is-loading');
            const photo = doc.createElement('img');photo.alt = `첨부 사진 ${index + 1}`;
            photo.loading = 'lazy';photo.decoding = 'async';photo.width = image.width;photo.height = image.height;
            photo.addEventListener('load', () => tile.classList.remove('is-loading'));
            photo.addEventListener('error', () => { tile.classList.remove('is-loading');photo.hidden = true;tile.textContent = '사진을 불러오지 못했어요. 눌러서 다시 확인해 주세요.'; });
            photo.src = image.url;tile.append(photo);
            tile.addEventListener('click', () => openViewer(images,index,message.messageId ?? message.id,tile));
            grid.append(tile);
        });
        bubble.prepend(grid);
    }

    function mount({panel,input,planId,onMessage,onError,onClearError,onResize}) {
        const doc = panel.ownerDocument;
        const form = panel.querySelector('[data-travel-plan-chat-form]');
        const toggle = panel.querySelector('[data-chat-attach-toggle]');
        const menu = panel.querySelector('[data-chat-attach-menu]');
        const picker = panel.querySelector('[data-chat-image-files]');
        if (!form || !toggle || !menu || !picker) return null;
        const preview = doc.createElement('div');preview.className = 'tp-chat-image-preview';preview.hidden = true;
        preview.setAttribute('aria-label','전송할 사진');preview.setAttribute('data-chat-image-preview','');
        const status = doc.createElement('p');status.className = 'tp-chat-upload-status';status.hidden = true;
        status.setAttribute('role','status');status.setAttribute('data-chat-upload-status','');
        form.before(preview,status);
        let chosen = [], busy = false, uncertain = false, requestId = null, payloadText = null;
        const uuid = () => root.crypto.randomUUID();
        const closeMenu = () => { menu.hidden = true;toggle.setAttribute('aria-expanded','false'); };
        const release = () => { chosen.forEach(item => root.URL.revokeObjectURL(item.url));chosen = []; };
        function draw() {
            preview.replaceChildren();preview.hidden = !chosen.length;
            chosen.forEach((item,index) => {
                const tile = doc.createElement('div');tile.className = 'tp-chat-preview-tile';
                const img = doc.createElement('img');img.src = item.url;img.alt = `전송할 사진 ${index + 1}`;
                const remove = button(doc, `사진 ${index + 1} 첨부 취소`, 'tp-chat-preview-remove');remove.textContent = '×';remove.disabled = busy || uncertain;
                remove.addEventListener('click', () => { root.URL.revokeObjectURL(item.url);chosen.splice(index,1);requestId = null;draw(); });
                tile.append(img,remove);preview.append(tile);
            });
        }
        function lock() {
            form.querySelectorAll('button').forEach(node => { node.disabled = busy || (uncertain && node.type !== 'submit'); });
            input.readOnly = busy || uncertain;
            panel.querySelector('[data-travel-plan-chat-send]').textContent = busy ? '전송 중' : uncertain ? '재시도' : '전송';
            preview.querySelectorAll('button').forEach(node => { node.disabled = busy || uncertain; });
            form.setAttribute('aria-busy', String(busy));
        }
        toggle.addEventListener('click', event => {
            event.stopPropagation();const willOpen = menu.hidden;menu.hidden = !willOpen;toggle.setAttribute('aria-expanded',String(willOpen));
        });
        panel.querySelector('[data-chat-attach-pick]').addEventListener('click', () => { closeMenu();picker.click(); });
        doc.addEventListener('click', event => { if (!event.target.closest('.tp-chat-attach')) closeMenu(); });
        picker.addEventListener('change', () => {
            if (busy || uncertain) { picker.value = '';return; }
            const files = [...chosen.map(item => item.file),...picker.files];picker.value = '';
            const error = validate(files);if (error) { onError(error);return; }
            release();chosen = files.map(file => ({file,url:root.URL.createObjectURL(file)}));
            onClearError?.();
            requestId = null;status.hidden = true;draw();
        });
        input.addEventListener('input', () => { if (!busy && !uncertain) requestId = null; });
        root.addEventListener('beforeunload', event => { if (busy || uncertain) { event.preventDefault();event.returnValue = ''; } });

        async function send(content) {
            if (busy || !chosen.length) return;
            busy = true;closeMenu();onClearError?.();
            requestId ||= uuid();if (!uncertain) payloadText = content;
            lock();status.hidden = false;status.textContent = '사진을 전송하고 있어요…';
            const data = new root.FormData();data.append('content',payloadText);data.append('clientRequestId',requestId);
            chosen.forEach(item => data.append('images',item.file));
            const headers = {'X-Requested-With':'XMLHttpRequest'};
            const token = doc.querySelector('meta[name="_csrf"]')?.content;
            const header = doc.querySelector('meta[name="_csrf_header"]')?.content;
            if (token && header) headers[header] = token;
            try {
                const response = await root.fetch(`/travel-plans/${planId}/chat/image-messages`,{method:'POST',headers,body:data});
                const result = await response.json().catch(() => null);
                if (!response.ok) {
                    uncertain = uncertain || response.status >= 500;
                    throw new Error(result?.message || (uncertain ? '전송 결과를 확인하지 못했어요. 재시도하면 같은 요청을 확인합니다.' : '사진을 보내지 못했어요. 형식·크기·접근 권한을 확인해 주세요.'));
                }
                if (!result?.id || !Array.isArray(result.images)) { uncertain = true;throw new Error('전송 결과를 확인하지 못했어요. 재시도해 주세요.'); }
                uncertain = false;onMessage(result);release();requestId = null;payloadText = null;
                input.value = '';onResize();draw();status.hidden = true;
            } catch (error) {
                // 응답이 유실되면 본문을 고정하고 동일 UUID로만 재시도한다.
                if (error instanceof TypeError) uncertain = true;
                status.textContent = uncertain ? '전송 결과 확인이 필요해요. 재시도해 주세요. 같은 사진이 중복 저장되지 않습니다.' : '사진을 보내지 못했어요. 첨부를 확인한 후 다시 전송해 주세요.';
                onError(error.message || '사진을 보내지 못했어요.');
            } finally { busy = false;lock();if (!uncertain) input.focus(); }
        }
        return {hasFiles:()=>chosen.length>0,isBusy:()=>busy,send,closeMenu};
    }
    const api = {validate,safeImages,mount,render,closeViewerFor,closeViewer};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    root.TravelPlanChatImages = api;
}(typeof window === 'undefined' ? globalThis : window));
