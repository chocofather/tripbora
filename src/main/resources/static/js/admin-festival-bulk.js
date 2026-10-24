(function () {
    'use strict';
    const JOB_KEY = 'tripbora.festival.bulk.request';
    const statusLabel = value => ({READY: '등록 가능', NEEDS_REVIEW: '확인 필요', DUPLICATE: '중복',
        PENDING: '대기 중', RUNNING: '처리 중', SUCCESS: '성공', FAILED: '실패', COMPLETE: '완료'}[value] || '확인 필요');
    const keyOf = item => `${item.contentId}:${String(item.eventStartDate || '').slice(0, 4)}`;
    const eligible = item => item.registrationStatus === 'UNREGISTERED'
        && /^\d+$/.test(String(item.contentId || '')) && /^\d{4}-?\d{2}-?\d{2}$/.test(String(item.eventStartDate || ''));

    function createSelection() {
        const items = new Map();
        return {items, add(item) {
            const key = keyOf(item);
            if (!eligible(item) || (!items.has(key) && items.size >= 30)) return false;
            if (!items.has(key)) items.set(key, {item, poster: null, preview: null});
            return true;
        }, remove(item) { items.delete(keyOf(item)); }};
    }

    function createJobClient(request, storage, uuid, headers) {
        let id = storage.getItem(JOB_KEY) || null;
        let complete = false;
        let missing = false;
        async function recover() {
            if (!id) return null;
            missing = false;
            const response = await request(`/admin/api/kto/festivals/bulk/jobs/${encodeURIComponent(id)}`, {headers: {Accept: 'application/json'}});
            if (response.status === 404) {
                missing = true;
                throw Error('결과 확인 필요: 서버 재시작 또는 결과 보관기간 만료로 작업 결과가 없습니다. 등록 목록을 확인해 주세요. 새 등록 요청은 전송하지 않았습니다.');
            }
            const payload = await response.json();
            if (!response.ok) throw Error(payload.message || '작업 결과를 확인하지 못했습니다. 결과 확인을 다시 눌러 주세요.');
            complete = payload.status === 'COMPLETE';
            return payload;
        }
        return {requestId: () => id, isMissing: () => missing, recover, beginNew(confirmedMissing = false) {
            if (!complete && !(missing && confirmedMissing)) throw Error('작업 완료 또는 결과 없음과 등록 목록 확인이 필요합니다.');
            storage.removeItem(JOB_KEY); id = null; complete = false; missing = false;
        }, async start(tokens) {
            if (id) throw Error('기존 작업 결과를 먼저 확인해 주세요.');
            id = uuid();
            // Persist BEFORE the POST. Any uncertain response is recovered with GET only.
            storage.setItem(JOB_KEY, id);
            try {
                const response = await request('/admin/api/kto/festivals/bulk/start', {
                    method: 'POST', headers, body: JSON.stringify({requestId: id, previewTokens: tokens})
                });
                const payload = await response.json();
                if (!response.ok) throw Error(payload.message || '등록 요청 결과를 확인하지 못했습니다.');
                complete = payload.status === 'COMPLETE';
                return payload;
            } catch (_) { return recover(); }
        }};
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = {createSelection, createJobClient};
    if (typeof document === 'undefined') return;
    document.addEventListener('DOMContentLoaded', init);

    function init() {
        const root = document.querySelector('[data-festival-bulk]');
        const form = document.getElementById('festival-create-form');
        if (!root || !form) return;
        const selection = createSelection();
        const controls = document.querySelector('[data-festival-registration-modes]');
        const rows = root.querySelector('[data-bulk-rows]');
        const summary = root.querySelector('[data-bulk-summary]');
        const status = root.querySelector('[data-bulk-status]');
        const previewButton = root.querySelector('[data-bulk-preview]');
        const confirmButton = root.querySelector('[data-bulk-confirm]');
        const startButton = root.querySelector('[data-bulk-start]');
        const confirmation = root.querySelector('[data-bulk-confirmation]');
        const recoverButton = root.querySelector('[data-bulk-recover]');
        const newButton = root.querySelector('[data-bulk-new]');
        const dialog = root.querySelector('dialog');
        const dialogItems = root.querySelector('[data-bulk-poster-items]');
        const dialogStatus = root.querySelector('[data-bulk-poster-status]');
        const sequenceButton = root.querySelector('[data-bulk-poster-sequence]');
        const posterTitle = root.querySelector('#festival-bulk-poster-title');
        const posterProgress = root.querySelector('[data-bulk-poster-progress]');
        const posterSteps = root.querySelector('[data-bulk-poster-steps]');
        const posterContent = root.querySelector('[data-bulk-poster-content]');
        const posterPrevious = root.querySelector('[data-bulk-poster-previous]');
        const posterNext = root.querySelector('[data-bulk-poster-next]');
        const posterMissing = root.querySelector('[data-bulk-poster-missing]');
        const largeImage = root.querySelector('[data-bulk-large-image]');
        const cache = new Map();
        const imageRequests = new Map();
        const imageErrors = new Map();
        let posterKeys = [];
        let posterIndex = 0;
        let continuousPosters = false;
        const cards = new Map();
        let currentItems = [];
        let mode = 'single';
        let busy = false;
        let job = null;
        let dialogGeneration = 0;
        let pollTimer;
        let client;
        const headers = {Accept: 'application/json', 'Content-Type': 'application/json'};
        const csrf = document.querySelector('meta[name="_csrf"]')?.content || form.querySelector('input[name="_csrf"]')?.value;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        if (csrf) headers[csrfHeader] = csrf;
        try {
            client = createJobClient(fetch, sessionStorage, () => crypto.randomUUID(), headers);
        } catch (_) {
            status.textContent = '세션 저장소를 사용할 수 없어 안전한 일괄등록을 시작할 수 없습니다.';
            root.querySelectorAll('button').forEach(button => button.disabled = true);
            return;
        }

        function element(tag, text, className) {
            const node = document.createElement(tag);
            if (text != null) node.textContent = String(text);
            if (className) node.className = className;
            return node;
        }
        function button(text, action) {
            const node = element('button', text, 'admin-btn is-small');
            node.type = 'button';
            node.disabled = busy || Boolean(client.requestId());
            node.addEventListener('click', action);
            return node;
        }
        function safeUrl(value) {
            try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''; }
            catch (_) { return ''; }
        }
        function invalidate() {
            confirmation.hidden = true;
            selection.items.forEach(entry => entry.preview = null);
        }
        function setBusy(value) {
            busy = value;
            root.setAttribute('aria-busy', String(value));
            controls.querySelectorAll('button').forEach(node => node.disabled = value);
            render();
        }
        function render() {
            rows.replaceChildren();
            const entries = [...selection.items.values()];
            const ready = entries.filter(entry => entry.preview?.status === 'READY' && entry.preview.token);
            summary.textContent = `전체 선택 ${entries.length}/30건 · 포스터 완료 ${entries.filter(entry => entry.poster).length}건 · 등록 가능 ${ready.length}건 · 중복 ${entries.filter(entry => entry.preview?.status === 'DUPLICATE').length}건 · 확인 필요 ${entries.filter(entry => !entry.poster || entry.preview?.status === 'NEEDS_REVIEW').length}건`;
            entries.forEach(entry => {
                const row = element('article', null, 'festival-bulk-row');
                const title = entry.preview?.title || entry.item.title;
                row.append(element('strong', title || '제목 없음'));
                row.append(element('p', `${entry.preview?.startDate || entry.item.eventStartDate || ''} ~ ${entry.preview?.endDate || entry.item.eventEndDate || ''}`));
                row.append(element('p', entry.preview?.address || entry.item.address || '지역 확인 필요'));
                if (entry.poster && safeUrl(entry.poster.imageUrl)) {
                    const img = element('img'); img.src = safeUrl(entry.poster.imageUrl); img.alt = `${title} 선택 포스터`; img.loading = 'lazy'; row.append(img);
                }
                row.append(element('p', entry.poster ? `포스터 선택 완료 · ${entry.poster.licenseType || '라이선스 미표기'}` : '포스터 미선택 — 등록 대상에서 제외'));
                row.append(button(entry.poster ? '포스터 재선택' : '포스터 선택', () => openPosters(entry)));
                row.append(button('선택 해제', () => { selection.remove(entry.item); invalidate(); render(); }));
                if (entry.preview) {
                    row.append(element('p', statusLabel(entry.preview.status)));
                    [...(entry.preview.errors || []), ...(entry.preview.warnings || [])].forEach(message => row.append(element('p', message)));
                }
                rows.append(row);
            });
            cards.forEach(({checkbox, item}) => {
                checkbox.checked = selection.items.has(keyOf(item));
                checkbox.disabled = busy || Boolean(client.requestId()) || !eligible(item);
            });
            root.querySelectorAll('[data-bulk-select-page], [data-bulk-clear]').forEach(node => node.disabled = busy || Boolean(client.requestId()));
            previewButton.disabled = busy || Boolean(client.requestId()) || !entries.some(entry => entry.poster);
            sequenceButton.disabled = busy || Boolean(client.requestId()) || !entries.length;
            confirmButton.disabled = busy || Boolean(client.requestId()) || !ready.length;
            startButton.disabled = busy || Boolean(client.requestId()) || !ready.length;
            recoverButton.hidden = !client.requestId();
            recoverButton.disabled = busy;
            newButton.hidden = job?.status !== 'COMPLETE' && !client.isMissing();
            newButton.textContent = client.isMissing() ? '등록 목록 확인 후 새 작업' : '완료 결과 확인 후 새 작업';
            newButton.disabled = busy;
        }
        controls.querySelectorAll('[data-festival-registration-mode]').forEach(node => node.addEventListener('click', () => {
            if (busy) return;
            mode = node.dataset.festivalRegistrationMode;
            form.classList.toggle('is-festival-bulk', mode === 'bulk');
            root.hidden = mode !== 'bulk';
            controls.querySelectorAll('button').forEach(button => button.setAttribute('aria-pressed', String(button === node)));
            render();
        }));
        // Shared search remains the sole source of candidates; its single-item action is untouched.
        window.TripBoraFestivalBulk = {attach(card, item) {
            const label = element('label', null, 'festival-bulk-check');
            const checkbox = element('input'); checkbox.type = 'checkbox';
            checkbox.setAttribute('aria-label', `${item.title || '행사'} 일괄등록 선택`);
            label.append(checkbox, element('span', item.registrationStatus === 'UNKNOWN' ? '확인 필요' : '일괄 선택'));
            checkbox.addEventListener('change', () => {
                if (busy || client.requestId()) return render();
                if (checkbox.checked && !selection.add(item)) status.textContent = '등록 여부와 시작일이 확인된 미등록 축제를 최대 30건 선택할 수 있습니다.';
                if (!checkbox.checked) selection.remove(item);
                invalidate(); render();
            });
            cards.set(card, {checkbox, item}); card.append(label); render();
        }, isBulk: () => mode === 'bulk'};
        document.addEventListener('festival:candidates-rendered', event => {
            currentItems = event.detail.items;
            cards.forEach((_, card) => { if (!card.isConnected) cards.delete(card); });
            render();
        });
        root.querySelector('[data-bulk-select-page]').addEventListener('click', () => {
            currentItems.forEach(item => selection.add(item)); invalidate(); render();
            if (selection.items.size === 30) status.textContent = '최대 30건까지 선택할 수 있습니다.';
        });
        root.querySelector('[data-bulk-clear]').addEventListener('click', () => { selection.items.clear(); invalidate(); render(); });

        function updatePosterNavigation() {
            const entries = posterKeys.map(key => selection.items.get(key));
            const focusedStep = [...posterSteps.querySelectorAll('button')].indexOf(document.activeElement);
            const completed = entries.filter(entry => entry?.poster).length;
            posterTitle.textContent = entries[posterIndex]?.item.title || '행사 포스터 선택';
            posterProgress.textContent = `포스터 선택 ${posterIndex + 1} / ${entries.length} · 선택 완료 ${completed}건 · 미선택 ${entries.length - completed}건${completed === entries.length ? ' · 전체 선택 완료' : ''}`;
            posterPrevious.disabled = posterIndex === 0;
            posterNext.textContent = posterIndex === entries.length - 1 ? '선택 마치기' : '다음 축제';
            posterMissing.disabled = !entries.some((entry, index) => index !== posterIndex && !entry?.poster);
            posterSteps.replaceChildren();
            entries.forEach((entry, index) => {
                const state = entry?.poster ? '포스터 선택 완료'
                    : imageErrors.has(String(entry?.item.contentId)) ? '사진 조회 실패 · 포스터 미선택' : '포스터 미선택';
                const step = button(`${index + 1}${entry?.poster ? ' ✓' : ''}`, () => {
                    showPoster(index);
                    posterSteps.querySelector('[aria-current="step"]')?.focus();
                });
                step.setAttribute('aria-label', `${index + 1}. ${entry?.item.title || '행사'} — ${state}`);
                step.title = `${entry?.item.title || '행사'} · ${state}`;
                if (index === posterIndex) step.setAttribute('aria-current', 'step');
                if (entry?.poster) step.classList.add('is-complete');
                if (imageErrors.has(String(entry?.item.contentId))) step.classList.add('is-error');
                posterSteps.append(step);
            });
            if (focusedStep >= 0) posterSteps.querySelectorAll('button')[focusedStep]?.focus();
            revealPosterStep();
        }

        function revealPosterStep() {
            const currentStep = posterSteps.querySelector('[aria-current="step"]');
            if (currentStep) {
                const stepRect = currentStep.getBoundingClientRect();
                const stripRect = posterSteps.getBoundingClientRect();
                if (stepRect.left < stripRect.left) posterSteps.scrollLeft -= stripRect.left - stepRect.left;
                else if (stepRect.right > stripRect.right) posterSteps.scrollLeft += stepRect.right - stripRect.right;
            }
        }

        async function posterCandidates(id) {
            if (cache.has(id)) return cache.get(id);
            if (imageRequests.has(id)) return imageRequests.get(id);
            const request = (async () => {
                const response = await fetch(`/admin/api/kto/festivals/images?${new URLSearchParams({contentId: id})}`, {headers: {Accept: 'application/json'}});
                const payload = await response.json();
                if (!response.ok) throw Error(payload.message || '포스터 후보를 불러오지 못했습니다.');
                const items = Array.isArray(payload.items) ? payload.items : [];
                cache.set(id, items);
                imageErrors.delete(id);
                return items;
            })();
            imageRequests.set(id, request);
            try { return await request; }
            finally { imageRequests.delete(id); }
        }

        function openPosters(entry) {
            if (busy || client.requestId()) return;
            continuousPosters = !entry;
            posterKeys = entry ? [keyOf(entry.item)] : [...selection.items.keys()];
            if (!posterKeys.length) return;
            const missing = posterKeys.findIndex(key => !selection.items.get(key)?.poster);
            posterIndex = missing < 0 ? 0 : missing;
            if (!dialog.open) dialog.showModal();
            showPoster(posterIndex);
        }

        async function showPoster(index) {
            const entry = selection.items.get(posterKeys[index]);
            if (!entry || !dialog.open) return;
            posterIndex = index;
            const generation = ++dialogGeneration;
            updatePosterNavigation();
            dialogItems.replaceChildren(); largeImage.hidden = true; largeImage.removeAttribute('src');
            posterContent.scrollTop = 0;
            dialogItems.setAttribute('aria-busy', 'true');
            dialogStatus.textContent = `${entry.item.title || '행사'} 포스터 후보를 불러오는 중입니다.`;
            const id = String(entry.item.contentId);
            try {
                const items = await posterCandidates(id);
                if (generation !== dialogGeneration || !dialog.open) return;
                updatePosterNavigation();
                dialogStatus.textContent = items.length ? '라이선스와 출처를 확인하고 포스터 한 장을 선택하세요.' : '이미지 없음: 이 행사는 포스터가 없어 일괄등록에서 제외됩니다.';
                dialogItems.append(element('p', `출처: 한국관광공사 · TourAPI (contentId: ${id})`));
                items.forEach(item => {
                    const option = element('div', null, 'admin-kto-festival-image-option');
                    const imageUrl = safeUrl(item.thumbnailUrl || item.imageUrl);
                    if (imageUrl) {
                        const image = element('img'); image.src = imageUrl; image.alt = item.imageName || '포스터 후보'; image.loading = 'lazy'; option.append(image);
                    }
                    const body = element('div', null, 'admin-kto-festival-image-option-body');
                    body.append(element('strong', item.imageName || item.imageRole || '축제 이미지'));
                    const licenseLabel = {KOGL_TYPE_1: '공공누리 제1유형', KOGL_TYPE_3: '공공누리 제3유형'}[item.licenseType];
                    body.append(element('span', licenseLabel || item.licenseType || '라이선스 미표기', 'admin-kto-festival-image-option-meta'));
                    if (safeUrl(item.imageUrl)) {
                        const source = element('a', '원본 제공 이미지');
                        source.href = safeUrl(item.imageUrl); source.target = '_blank'; source.rel = 'noopener noreferrer';
                        body.append(source);
                    }
                    const radio = element('input'); radio.type = 'radio'; radio.setAttribute('name', 'festival-bulk-poster');
                    radio.setAttribute('aria-label', item.imageName || item.imageRole || '축제 이미지');
                    radio.checked = entry.poster?.selectionKey === item.selectionKey;
                    radio.disabled = !item.selectable || !item.selectionKey || !safeUrl(item.imageUrl);
                    radio.addEventListener('change', () => {
                        if (!radio.checked) return;
                        entry.poster = item; invalidate(); render();
                        updatePosterNavigation();
                        dialogStatus.textContent = '포스터 선택 완료. 사진을 확인한 뒤 다음 축제로 이동하거나 선택을 마치세요.';
                    });
                    const choice = element('label', null, 'admin-kto-festival-image-option-select');
                    choice.append(radio, element('span', radio.disabled ? (item.unavailableReason || '선택 불가') : '이 포스터 선택'));
                    body.append(choice);
                    option.classList.toggle('is-disabled', radio.disabled);
                    // 링크·미리보기·기본 라디오/label 동작은 카드 선택과 독립적으로 처리한다.
                    option.addEventListener('click', event => {
                        if (radio.disabled || event.target.closest('a, button, input, label')) return;
                        radio.focus();
                        radio.click();
                    });
                    radio.addEventListener('keydown', event => {
                        if (event.key !== 'Enter') return;
                        event.preventDefault();
                        radio.click();
                    });
                    option.append(body);
                    const enlarge = button('큰 미리보기', () => {
                        largeImage.src = safeUrl(item.imageUrl); largeImage.alt = item.imageName || '포스터 큰 미리보기'; largeImage.hidden = false;
                    });
                    enlarge.disabled = !safeUrl(item.imageUrl); option.append(enlarge); dialogItems.append(option);
                });
                dialogItems.append(button('포스터 선택 해제', () => {
                    entry.poster = null; invalidate(); render();
                    if (continuousPosters) showPoster(posterIndex);
                    else dialog.close();
                }));
            } catch (error) {
                imageErrors.set(id, error.message || '사진 조회 실패');
                if (generation !== dialogGeneration || !dialog.open) return;
                updatePosterNavigation();
                dialogStatus.textContent = `${imageErrors.get(id)} 다른 축제로 이동하거나 다시 시도할 수 있습니다.`;
                dialogItems.append(button('사진 다시 불러오기', () => showPoster(posterIndex)));
            } finally {
                if (generation === dialogGeneration) dialogItems.setAttribute('aria-busy', 'false');
            }
        }
        sequenceButton.addEventListener('click', () => openPosters());
        window.addEventListener('resize', () => { if (dialog.open) revealPosterStep(); });
        posterPrevious.addEventListener('click', () => { if (posterIndex > 0) showPoster(posterIndex - 1); });
        posterNext.addEventListener('click', () => {
            if (posterIndex === posterKeys.length - 1) dialog.close();
            else showPoster(posterIndex + 1);
        });
        posterMissing.addEventListener('click', () => {
            for (let step = 1; step < posterKeys.length; step++) {
                const index = (posterIndex + step) % posterKeys.length;
                if (!selection.items.get(posterKeys[index])?.poster) { showPoster(index); break; }
            }
        });
        root.querySelector('[data-bulk-poster-close]').addEventListener('click', () => dialog.close());
        dialog.addEventListener('close', () => {
            dialogGeneration += 1; largeImage.removeAttribute('src');
            if (continuousPosters) sequenceButton.focus();
            else {
                const index = [...selection.items.keys()].indexOf(posterKeys[posterIndex]);
                rows.children[index]?.querySelector('button')?.focus();
            }
        });
        previewButton.addEventListener('click', async () => {
            if (busy || client.requestId()) return;
            invalidate(); setBusy(true);
            try {
                for (const entry of selection.items.values()) {
                    if (!entry.poster) continue;
                    status.textContent = `${entry.item.title} 등록 정보를 확인하고 있습니다.`;
                    try {
                        const response = await fetch('/admin/api/kto/festivals/bulk/preview', {method: 'POST', headers,
                            body: JSON.stringify({contentId: String(entry.item.contentId), eventYear: Number(String(entry.item.eventStartDate).slice(0, 4)), posterSelection: entry.poster.selectionKey})});
                        const payload = await response.json();
                        if (!response.ok) throw Error(payload.message || '사전 검증 실패');
                        entry.preview = payload;
                    } catch (error) { entry.preview = {status: 'NEEDS_REVIEW', errors: [error.message]}; }
                }
                status.textContent = '사전 검증이 끝났습니다. 포스터와 오류를 확인한 뒤 등록을 진행하세요.';
            } finally { setBusy(false); }
        });
        confirmButton.addEventListener('click', () => {
            confirmation.hidden = false;
            root.querySelector('[data-bulk-confirm-count]').textContent = `${[...selection.items.values()].filter(entry => entry.preview?.status === 'READY' && entry.preview.token).length}건을 선택한 포스터 한 장과 함께 등록합니다. 중복·확인 필요·포스터 미선택 행사는 제외됩니다.`;
        });
        root.querySelector('[data-bulk-cancel]').addEventListener('click', () => confirmation.hidden = true);
        function showJob(snapshot) {
            job = snapshot;
            newButton.hidden = snapshot.status !== 'COMPLETE';
            rows.replaceChildren();
            status.textContent = `서버 처리 ${snapshot.processed}/${snapshot.total}건 · 성공 ${snapshot.success}건 · 실패 ${snapshot.failed}건 · 중복 ${snapshot.duplicate}건`;
            summary.textContent = snapshot.status === 'COMPLETE' ? '일괄등록 처리 완료' : '일괄등록 진행 중';
            (snapshot.items || []).forEach(item => {
                const row = element('article', null, 'festival-bulk-row');
                row.append(element('strong', item.title), element('p', statusLabel(item.status)), element('p', item.message || ''), element('p', item.warning || ''));
                if (/^\d+$/.test(String(item.festivalId || ''))) {
                    const link = element('a', '등록된 축제 확인'); link.href = `/admin/festivals/${item.festivalId}/edit`; row.append(link);
                }
                rows.append(row);
            });
            if (snapshot.status === 'RUNNING') pollTimer = setTimeout(recover, 1500);
        }
        async function recover() {
            if (busy) return;
            clearTimeout(pollTimer); setBusy(true);
            let snapshot;
            let failure;
            try { snapshot = await client.recover(); }
            catch (error) { failure = error.message || '결과 확인 필요: 결과 확인을 다시 눌러 주세요.'; }
            finally {
                setBusy(false);
                if (snapshot) showJob(snapshot);
                else status.textContent = failure;
            }
        }
        recoverButton.addEventListener('click', recover);
        newButton.addEventListener('click', () => {
            try {
                const missing = client.isMissing();
                if (missing && !window.confirm('등록 목록에서 이전 작업의 등록 여부를 직접 확인하셨나요? 이전 결과는 다시 확인할 수 없으며, 새 작업은 중복 등록 여부를 다시 검증해야 합니다.')) return;
                client.beginNew(missing); job = null; selection.items.clear(); invalidate(); render();
                status.textContent = '새 작업을 준비합니다. 등록 여부를 갱신하려면 다시 검색해 주세요.';
            } catch (error) { status.textContent = error.message; }
        });
        startButton.addEventListener('click', async () => {
            if (busy || client.requestId() || confirmation.hidden) return;
            const tokens = [...selection.items.values()].filter(entry => entry.preview?.status === 'READY' && entry.preview.token).map(entry => entry.preview.token);
            if (!tokens.length) return;
            setBusy(true); confirmation.hidden = true;
            try { job = await client.start(tokens); }
            catch (error) { status.textContent = error.message || '결과 확인 필요: 결과 확인을 눌러 주세요.'; }
            finally { setBusy(false); if (job) showJob(job); }
        });
        form.addEventListener('submit', event => { if (mode === 'bulk' || busy) event.preventDefault(); });
        render();
        if (client.requestId()) {
            controls.querySelector('[data-festival-registration-mode="bulk"]').click();
            recover();
        }
    }
})();
