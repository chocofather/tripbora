(() => {
    const root = document.querySelector('[data-photo-workflow]');
    if (!root) return;
    const $ = selector => root.querySelector(selector);
    const grid = $('[data-workflow-grid]'), compare = $('[data-compare-dialog]'), detail = $('[data-detail-dialog]');
    const cleanSelected = new Set(), use = new Set();
    const labels = {PENDING:'대기', APPROVED:'승인 완료', REJECTED:'반려', REVOKED:'철회·승인 취소', NONE:'미동의'};
    let section = root.dataset.section || 'all', status = root.dataset.status || 'PENDING', page = Number(root.dataset.page) || 0;
    let photos = [], groups = [], comparison = [], currentGroup = null, chunk = 0, hasChunk = false;
    let busy = false, loading = false, validMain = false, validComparison = false, preferredExactId = null, mainVersion = 0, compareVersion = 0;
    let completedChunks = 0, completedPhotos = 0;
    const node = (tag, text, className) => {
        const item = document.createElement(tag); if (text !== undefined) item.textContent = text; if (className) item.className = className; return item;
    };
    function button(text, action, className = 'admin-btn') {
        const item = node('button', text, className); item.type = 'button'; item.addEventListener('click', action); return item;
    }
    function image(photo) {
        const item = node('img'); item.src = photo.imageUrl; item.alt = `리뷰 사진 #${photo.id}`; item.loading = 'lazy'; return item;
    }
    function active(photo) { return ['PENDING','APPROVED'].includes(photo.galleryStatus); }
    function eligible(photo) { return photo.eligible === true && photo.eligibilityReason == null; }
    function restriction(photo) { return photo.eligibilityMessage || '심사 자격을 확인할 수 없습니다. 새로고침 후 확인하세요.'; }
    function canUse(photo) { return active(photo) && eligible(photo) && photo.approvable !== false; }
    function canReject(photo) { return photo.galleryStatus === 'PENDING'; }
    function canProcess(photo) { return canReject(photo) || photo.galleryStatus === 'APPROVED'; }
    function decisionRows() { return comparison.filter(canProcess); }
    function referenceReason(photo) {
        if(photo.eligibilityMessage) return photo.eligibilityMessage;
        return {NONE:'갤러리 활용 미동의',REJECTED:'갤러리 활용 반려 완료',REVOKED:'갤러리 활용 철회·승인 취소 완료'}[photo.galleryStatus] || restriction(photo);
    }
    function deduplicate(rows) { return [...new Map(rows.map(row => [row.id, row])).values()]; }
    function csrfHeaders(json = true) {
        const token = $('[data-workflow-csrf] input[name="_csrf"]');
        if (!token) throw Error('보안 토큰이 없습니다. 페이지를 새로고침하세요.');
        return {Accept:'application/json', 'X-CSRF-TOKEN':token.value, ...(json ? {'Content-Type':'application/json'} : {})};
    }
    async function get(url) {
        const response = await fetch(url, {headers:{Accept:'application/json'}, cache:'no-store'});
        if (!response.ok) {
            const body=await response.json().catch(()=>({}));
            throw Error(body?.message || (response.status===403?'접근 권한 또는 로그인 상태를 확인하세요.':'사진 목록을 불러오지 못했습니다. 새로고침 후 확인하세요.'));
        }
        return response.json();
    }
    async function post(url, body) {
        let response, data;
        try {
            response = await fetch(url, {method:'POST', headers:csrfHeaders(), body:JSON.stringify(body)});
        } catch (error) {
            throw Error('응답이 중단되어 처리 여부를 확인할 수 없습니다. 다시 실행하기 전에 새로고침하여 결과를 확인하세요.');
        }
        data=await response.json().catch(()=>null);
        if (!response.ok) throw Error(data?.message || (response.status===403?'접근 권한·로그인 상태·보안 토큰을 확인한 뒤 새로고침하세요.':`요청 실패 (HTTP ${response.status}). 새로고침 후 확인하세요.`));
        if (!data || typeof data.message!=='string' || !data.message) throw Error('처리 결과가 불명확합니다. 다시 실행하기 전에 새로고침하여 확인하세요.');
        return data;
    }
    function expected(rows) { return Object.fromEntries(rows.map(photo => [photo.id,photo.galleryStatus])); }
    function selectionSummary() {
        const rows = photos.filter(photo => cleanSelected.has(photo.id)), pending = photos.filter(canReject);
        const blocked = rows.filter(photo => !canUse(photo));
        const disabled = busy || loading || !validMain || !rows.length || rows.length > 100;
        $('[data-clean-approve]').textContent = `${section==='clean'?'중복 후보 없는 사진':'선택 사진'} ${rows.length}장 일괄 승인`;
        $('[data-clean-approve]').disabled = disabled || !!blocked.length;
        $('[data-clean-reject]').textContent = `선택 사진 ${rows.length}장 일괄 반려`;
        $('[data-clean-reject]').disabled = disabled;
        $('[data-batch-blocked]').textContent = blocked.length ? `승인 자격 제한 ${blocked.length}장 포함 · 반려 가능. 승인하려면 제외하세요.` : '현재 페이지 선택만 처리합니다.';
        $('[data-clean-all]').checked = pending.length > 0 && rows.length === pending.length;
        $('[data-clean-all]').indeterminate = rows.length > 0 && rows.length < pending.length;
        $('[data-clean-all]').disabled = busy || loading || !pending.length;
    }
    function renderPhoto(photo) {
        const card = node('article', undefined, 'review-workflow-photo'); card.dataset.status = photo.galleryStatus; card.dataset.id = photo.id;
        const preview = button('', () => showDetail(photo), 'review-workflow-preview'); preview.append(image(photo)); card.append(preview);
        const caption = node('div', undefined, 'review-workflow-caption');
        if (canReject(photo)) {
            const label = node('label'), check = node('input'); check.type = 'checkbox'; check.checked = cleanSelected.has(photo.id); check.dataset.cleanSelect = photo.id;
            check.addEventListener('change', () => { if(busy||loading){check.checked=cleanSelected.has(photo.id);return;}if(check.checked) cleanSelected.add(photo.id); else cleanSelected.delete(photo.id); selectionSummary(); });
            label.append(check, ` #${photo.id} · 대기`); caption.append(label);
        } else caption.append(node('strong', `#${photo.id} · ${labels[photo.galleryStatus] || photo.galleryStatus}`));
        caption.append(node('small', photo.nickname || '알 수 없는 회원'));
        if (canReject(photo) && !canUse(photo)) caption.append(node('small',`승인 불가 · 반려 가능: ${restriction(photo)}`,'review-workflow-restriction'));
        if (section === 'limited') caption.append(node('small', photo.analysisState === 'HASH_ONLY' ? '유사도 분석 불가 · 동일 파일만 확인 가능' : photo.analysisState === 'FAILED' ? '분석 실패' : photo.analysisState !== 'READY' ? '분석 대기·진행 중' : '동의·공개·심사 자격 확인 필요'));
        if (section !== 'clean' && (photo.exactCount > 0 || photo.similarCount > 0)) {
            caption.append(button('관련 사진 비교', () => openGroup({anchorId:photo.id,kind:photo.exactCount>0?'exact':'similar',key:`photo:${photo.id}`})));
        }
        card.append(caption); grid.append(card);
    }
    function renderGroup(group) {
        const card = node('article', undefined, 'review-workflow-group'); card.dataset.group = group.key;
        const open = button('', () => openGroup(group), 'review-workflow-group-open');
        const previews = node('div', undefined, 'review-workflow-group-previews');
        group.photos.forEach(photo => previews.append(image(photo))); open.append(previews);
        open.append(node('strong', `${group.kind === 'exact' ? '동일 파일' : '유사 후보'} · ${group.total}장`));
        const count=Number(group.reviewable ?? group.photos.filter(canProcess).length);
        open.append(node('span', `심사 가능 ${count}장 · 참고 사진 포함`));
        open.append(node('span', count>0?'비교하고 사용할 사진 선택 →':'참고 사진 비교 →')); card.append(open); grid.append(card);
    }
    async function loadMain() {
        const version = ++mainVersion; loading = true; validMain = false; selectionSummary();
        grid.setAttribute('aria-busy','true'); $('[data-workflow-error]').textContent = '';
        $('[data-workflow-prev]').disabled = true; $('[data-workflow-next]').disabled = true;
        try {
            const body = await get(`${root.dataset.base}/workflow?section=${section}&status=${status}&page=${page}`);
            if (version !== mainVersion) return;
            photos = deduplicate(body.photos); groups = body.groups; cleanSelected.clear(); validMain = true;
            if (page > 0 && !photos.length && !groups.length) {page--; return loadMain();}
            photos.forEach(photo => {if(section==='clean') cleanSelected.add(photo.id);});
            grid.replaceChildren(); if (section==='groups') groups.forEach(renderGroup); else photos.forEach(renderPhoto);
            if (!grid.children.length) grid.append(node('p', '해당 조건의 사진이 없습니다.'));
            for (const key of ['clean','groups','limited']) $(`[data-count="${key}"]`).textContent = `${body.counts[key] || 0}장`;
            root.querySelectorAll('[data-section]').forEach(tab => tab.setAttribute('aria-current',tab.dataset.section===section?'page':'false'));
            $('[data-clean-toolbar]').hidden = section === 'groups';
            $('[data-workflow-description]').textContent = section==='clean' ? '현재 페이지 최대 100장만 선택됩니다. 전체 필터 결과는 페이지별로 나누어 처리하세요. 제외할 사진의 체크를 해제할 수 있습니다.' : section==='groups' ? '동일 파일은 SHA-256 일치, 유사 후보는 기준 사진과 dHash 거리 3 이하입니다. 관련 사진을 비교해 승인·반려하세요.' : section==='limited' ? '분석 대기·실패·유사도 분석 불가 또는 승인 자격 제한 사진입니다. 대기 사진은 반려할 수 있으며, 승인 자격이 있으면 분석 전에도 직접 승인할 수 있습니다.' : '대기 사진을 선택해 한 번에 승인·반려하세요. 분석 결과는 판단을 돕는 참고 정보입니다.';
            $('[data-workflow-total]').textContent = `상단 수치는 선택 상태의 사진 수 · ${section==='groups'?groups.length+'개 그룹':photos.length+'장'} 표시`;
            $('[data-workflow-page]').textContent = `${page+1}페이지`;
            $('[data-workflow-prev]').hidden = page===0; $('[data-workflow-next]').hidden = !body.hasNext;
            $('[data-workflow-status]').value = status;
            const current = new URL(location.href); current.searchParams.set('section',section); current.searchParams.set('status',status); current.searchParams.set('page',page); history.replaceState(null,'',current);
        } catch(error) {if(version===mainVersion) $('[data-workflow-error]').textContent=error.message;}
        finally {if(version===mainVersion){loading=false;grid.removeAttribute('aria-busy');$('[data-workflow-prev]').disabled=false;$('[data-workflow-next]').disabled=false;selectionSummary();}}
    }
    async function moderateSelected(action) {
        if (busy || loading || !validMain) return;
        const rows = photos.filter(photo => cleanSelected.has(photo.id));
        if (!rows.length || rows.length>100) return;
        if (action==='APPROVE' && rows.some(photo=>!canUse(photo))) return;
        const ids=rows.map(photo=>photo.id);
        const label=action==='APPROVE'?'승인':'반려';
        if (!confirm(`현재 페이지 사진 ${rows.length}장 ${label}\nID 범위 #${Math.min(...ids)}~#${Math.max(...ids)}\n실제 ID·상태: ${rows.map(p=>`#${p.id}(${p.galleryStatus})`).join(', ')}\n전체 필터 결과는 처리하지 않습니다. 원본 리뷰 사진은 유지됩니다.\n${label}하시겠습니까?`)) return;
        busy=true;selectionSummary();
        try {const fast=section==='clean'&&action==='APPROVE';const body=await post(`${root.dataset.base}/workflow/${fast?'approve':'batch'}`,{...(fast?{}:{action}),expected:expected(rows)});$('[data-workflow-notice]').textContent=body.message;await loadMain();}
        catch(error){$('[data-workflow-error]').textContent=error.message;}
        finally{busy=false;selectionSummary();}
    }
    function updateDecision() {
        const kept=decisionRows().filter(photo=>use.has(photo.id));
        const rejected=comparison.filter(photo=>!use.has(photo.id)&&photo.galleryStatus==='PENDING'&&canProcess(photo));
        const revoked=comparison.filter(photo=>!use.has(photo.id)&&photo.galleryStatus==='APPROVED'&&canProcess(photo));
        const restricted=comparison.filter(photo=>!canProcess(photo));
        const describe=rows=>rows.map(p=>`#${p.id}(${p.galleryStatus})`).join(', ');
        $('[data-compare-selection]').textContent=`사용 ${kept.length}장 [${describe(kept)}] · 반려 ${rejected.length}장 [${describe(rejected)}] · 승인 취소 ${revoked.length}장 [${describe(revoked)}]${restricted.length?` · 참고 ${restricted.length}장 [${describe(restricted)}]은 처리 제외`:''}${currentGroup?.kind==='exact'&&kept.length>1?' · 동일 파일은 1장 유지를 권장합니다.':''}`;
        $('[data-compare-decide]').textContent=`선택 ${kept.length}장 승인 · 나머지 ${rejected.length+revoked.length}장 반려${revoked.length?` (승인 취소 ${revoked.length}장 포함)`:''}`;
        $('[data-compare-decide]').disabled=busy || !validComparison || !kept.length || comparison.length>100;
        $('[data-compare-decide]').hidden=!comparison.some(canProcess);
        $('[data-compare-decision]').hidden=!comparison.some(canProcess);
        const pending=comparison.filter(canReject);
        $('[data-compare-reject-all]').textContent=`그룹 전체 반려 · 현재 묶음 대기 ${pending.length}장`;
        $('[data-compare-reject-all]').disabled=busy || !validComparison || !pending.length || comparison.length>100;
        $('[data-compare-decide]').setAttribute('aria-disabled',String($('[data-compare-decide]').disabled));
        const blocked=$('[data-compare-blocked]');
        blocked.textContent=!comparison.some(canProcess)?'처리 가능한 사진이 없습니다. 참고 사진은 변경하지 않습니다.':!kept.length?'사용 선택 0장입니다. 대기 사진을 모두 반려하려면 별도의 ‘그룹 전체 반려’를 선택하세요. 기존 승인 사진은 유지됩니다.':'';
        blocked.hidden=!!kept.length;
        root.querySelectorAll('[data-use-photo]').forEach(item=>{const checked=use.has(Number(item.dataset.usePhoto));item.checked=checked;const card=item.closest('[data-use-card]');card.classList.toggle('is-selected',checked);card.querySelector('[data-use-status]').textContent=item.disabled?'승인 불가':checked?'✓ 선택됨':'선택 안 됨';});
    }
    function renderComparison(photo) {
        const card=node('article',undefined,'review-workflow-photo');card.dataset.status=photo.galleryStatus;
        const reviewable=canProcess(photo),body=node(reviewable?'label':'div',undefined,'review-comparison-body');
        if(reviewable) {
            card.dataset.useCard=photo.id;
            const header=node('span',undefined,'review-comparison-selection'),check=node('input');check.type='checkbox';check.dataset.usePhoto=photo.id;
            check.setAttribute('aria-label',`사진 #${photo.id} 메인 갤러리 사용`);check.checked=use.has(photo.id);
            check.disabled=!canUse(photo);
            check.addEventListener('change',()=>{if(busy||!validComparison){check.checked=use.has(photo.id);return;}if(check.checked)use.add(photo.id);else use.delete(photo.id);updateDecision();});
            const state=node('span',check.checked?'✓ 선택됨':'선택 안 됨');state.dataset.useStatus='';header.append(check,state);body.append(header);
            card.addEventListener('click',event=>{if(event.target.closest('label,button,a,input')||check.disabled||busy||!validComparison)return;check.click();});
        } else {card.classList.add('is-reference');card.dataset.referencePhoto=photo.id;}
        const preview=image(photo);preview.className='review-comparison-image';body.append(preview);
        body.append(node('strong',`#${photo.id} · ${photo.galleryStatus==='APPROVED'?'이미 승인됨':labels[photo.galleryStatus]||photo.galleryStatus}`));
        body.append(node('small',`${photo.nickname||'알 수 없는 회원'} · 리뷰 #${photo.commentId}`));
        if(reviewable&&!canUse(photo))body.append(node('small',`승인 불가 · ${canReject(photo)?'반려 가능':'승인 취소 가능'}: ${restriction(photo)}`,'review-workflow-restriction'));
        if(!reviewable)body.append(node('small',`${referenceReason(photo)} · 참고용 / 심사 제외`,'review-workflow-restriction'));
        if(currentGroup.kind==='exact')body.append(node('small','SHA-256 일치 · 동일 파일'));
        if(currentGroup.kind==='similar')body.append(node('small',photo.id===currentGroup.anchorId?'비교 기준 사진':`dHash 거리 ${photo.distance}/64 · 유사 후보`));
        card.append(body,button('확대·정보',()=>showDetail(photo,!reviewable)));
        $(reviewable?'[data-compare-reviewable-grid]':'[data-compare-reference-grid]').append(card);
    }
    function clearComparisonGrids() {
        $('[data-compare-reviewable-grid]').replaceChildren();$('[data-compare-reference-grid]').replaceChildren();
        $('[data-compare-reviewable-section]').hidden=true;$('[data-compare-reference-section]').hidden=true;$('[data-compare-empty]').hidden=true;$('[data-compare-controls]').hidden=true;
    }
    async function loadComparison() {
        const version=++compareVersion;validComparison=false;comparison=[];use.clear();updateDecision();clearComparisonGrids();$('[data-compare-error]').textContent='';
        $('[data-compare-prev]').disabled=true;$('[data-compare-next]').disabled=true;
        try {
            const body=await get(`${root.dataset.base}/photos/${currentGroup.anchorId}/comparison?kind=${currentGroup.kind}&page=${chunk}`);
            if(version!==compareVersion)return;
            if(!body || !Array.isArray(body.photos) || !body.counts || typeof body.counts!=='object')
                throw Error('비교 응답을 확인할 수 없습니다. 모달을 다시 열거나 새로고침하세요.');
            comparison=deduplicate(body.photos);hasChunk=body.hasNext;
            const approved=comparison.filter(photo=>photo.galleryStatus==='APPROVED'&&canUse(photo));
            if(currentGroup.kind==='exact') {
                const recommended=preferredExactId!==null?comparison.find(photo=>photo.id===preferredExactId&&canUse(photo))
                    :approved[0]||(Number(body.counts.reviewableApproved ?? body.counts.approved)===0?comparison.find(photo=>photo.galleryStatus==='PENDING'&&canUse(photo)):null);
                if(recommended)use.add(recommended.id);
            }
            else approved.forEach(photo=>use.add(photo.id));
            comparison.forEach(renderComparison);
            const reviewable=comparison.filter(canProcess),references=comparison.filter(photo=>!canProcess(photo));
            $('[data-compare-reviewable-section]').hidden=!reviewable.length;$('[data-compare-reference-section]').hidden=!references.length;
            $('[data-compare-reviewable-count]').textContent=`${reviewable.length}장`;$('[data-compare-reference-count]').textContent=`${references.length}장`;
            $('[data-compare-controls]').hidden=!comparison.some(canUse);$('[data-compare-empty]').hidden=!!reviewable.length;
            const reasons=new Map();references.forEach(photo=>{const reason=referenceReason(photo);reasons.set(reason,(reasons.get(reason)||0)+1);});
            $('[data-compare-empty-reasons]').textContent=`참고 사진 ${references.length}장 · ${[...reasons].map(([reason,count])=>`${reason} ${count}장`).join(' · ')}`;
            const c=body.counts;$('[data-compare-title]').textContent=`${currentGroup.kind==='exact'?'동일 파일':'유사 후보'} · ${reviewable.length?'사용할 사진 선택':'참고 사진 비교'}`;
            $('[data-compare-description]').textContent=!reviewable.length?'동일·유사 관계의 참고 사진을 비교·확대할 수 있습니다. 참고 사진은 심사하지 않습니다.':!comparison.some(canUse)?'현재 승인 가능한 사진은 없습니다. 대기 사진은 아래 ‘그룹 전체 반려’로 정리할 수 있습니다.':currentGroup.kind==='exact'?'같은 파일은 대표 1장 유지를 권장합니다. 선택은 갤러리 우선순위와 별개입니다.':'기준 사진과 직접 비교한 후보입니다. 서로 다른 사진일 수 있으므로 여러 장을 선택할 수 있습니다.';
            $('[data-compare-statistics]').textContent=`전체 ${c.total}장 · 승인 ${c.approved} · 대기 ${c.pending} · 반려 ${c.rejected} · 취소 ${c.revoked}`;
            $('[data-compare-chunk]').textContent=(Number(c.total)>100?'100장 초과 그룹: 현재 묶음 최대 100장만 심사합니다. 다른 묶음은 변경하지 않습니다. ':'')
                +(completedChunks?`이 그룹에서 ${completedChunks}개 묶음 · ${completedPhotos}장 처리 완료. `:'')
                +(currentGroup.kind==='exact'&&preferredExactId!==null&&!comparison.some(photo=>photo.id===preferredExactId)?`이전 묶음의 사진 #${preferredExactId}를 유지합니다. 현재 묶음의 대기 사진은 ‘그룹 전체 반려’로 정리하세요.`:'');
            $('[data-compare-page]').textContent=`${chunk+1}번째 묶음 · ${comparison.length}장`;
            $('[data-compare-prev]').hidden=chunk===0;$('[data-compare-next]').hidden=!hasChunk;validComparison=true;updateDecision();
        }catch(error){if(version===compareVersion){validComparison=false;comparison=[];use.clear();clearComparisonGrids();$('[data-compare-error]').textContent=error.message;updateDecision();}}
        finally{if(version===compareVersion){$('[data-compare-prev]').disabled=false;$('[data-compare-next]').disabled=false;}}
    }
    function openGroup(group) {if(busy)return;currentGroup=group;preferredExactId=null;completedChunks=0;completedPhotos=0;chunk=0;if(!compare.open)compare.showModal();loadComparison();}
    async function decide(rejectAll=false) {
        if(busy||!comparison.length||$(rejectAll?'[data-compare-reject-all]':'[data-compare-decide]').disabled)return;
        const rows=rejectAll?comparison.filter(canReject):decisionRows();
        const confirmation=rejectAll?`그룹 전체 반려: 현재 묶음 PENDING ${rows.length}장\n대상 ${rows.map(p=>`#${p.id}(${p.galleryStatus})`).join(', ')}\n사용 선택과 관계없이 위 대기 사진만 반려합니다. 기존 APPROVED 및 참고 사진은 유지합니다.`:$('[data-compare-selection]').textContent;
        if(!confirm(`${confirmation}\n현재 묶음만 처리하며 다른 묶음은 별도 확인합니다. 참고 사진은 변경하지 않습니다.\n원본 리뷰가 공개 상태라면 사진 공개는 유지됩니다.\n최종 처리하시겠습니까?`))return;
        busy=true;updateDecision();
        const finished=currentGroup.key;
        try {
            const body=await post(`${root.dataset.base}/workflow/${rejectAll?'reject-group':'decide'}`,{anchorId:currentGroup.anchorId,kind:currentGroup.kind,...(rejectAll?{}:{selected:[...use]}),expected:expected(rows)});
            completedChunks++;completedPhotos+=rows.length;
            if(!rejectAll&&currentGroup.kind==='exact'&&use.size)preferredExactId=[...use][0];
            $('[data-workflow-notice]').textContent=body.message;
            if(hasChunk){chunk++;await loadComparison();await loadMain();}
            else {compare.close();compareVersion++;await loadMain();const next=groups.find(group=>group.key!==finished&&Number(group.pending)>0);if(status==='PENDING'&&next)openAfterProcessing(next);}
        }catch(error){$('[data-compare-error]').textContent=(completedChunks?`앞선 ${completedChunks}개 묶음 · ${completedPhotos}장은 처리 완료되었습니다. 현재 묶음: `:'')+error.message;}
        finally{busy=false;updateDecision();selectionSummary();}
    }
    function openAfterProcessing(group) {currentGroup=group;preferredExactId=null;completedChunks=0;completedPhotos=0;chunk=0;compare.showModal();loadComparison();}
    async function manual(photo,action) {
        if(busy)return;
        const reason=action==='REJECT'?prompt('갤러리 활용 반려 사유 (필수, 500자 이내)'):'';
        if(action==='REJECT'&&(!reason?.trim()||reason.length>500))return;
        if(!confirm(`사진 #${photo.id} ${action==='APPROVE'?'승인':action==='REJECT'?'반려':'승인 취소'} · 원본 리뷰 사진은 유지됩니다.`))return;
        const form=node('form');form.method='post';form.action=`${root.dataset.base}/batch`;
        const token=$('[data-workflow-csrf] input[name="_csrf"]');if(!token)return;
        for(const [name,value] of Object.entries({ids:photo.id,action,reason,status, [token.name]:token.value})){const input=node('input');input.type='hidden';input.name=name;input.value=value;form.append(input);}
        busy=true;document.body.append(form);form.submit();
    }
    function showDetail(photo,referenceOnly=false) {
        if(busy)return;
        $('[data-detail-image]').src=photo.imageUrl;$('[data-detail-original]').href=photo.imageUrl;
        $('[data-detail-info]').textContent=`사진 #${photo.id} · ${photo.nickname||'알 수 없는 회원'} · 리뷰 #${photo.commentId} · ${labels[photo.galleryStatus]||photo.galleryStatus}${photo.reviewReason?' · '+photo.reviewReason:''}`;
        const review=new URL(root.dataset.public,location.href);review.searchParams.set('commentId',photo.commentId);$('[data-detail-review]').href=review.href;
        const actions=$('[data-detail-actions]');actions.replaceChildren();$('[data-detail-error]').textContent='';$('[data-manual-actions]').open=false;
        $('[data-manual-actions]').hidden=referenceOnly;
        if(!referenceOnly&&photo.galleryStatus==='PENDING') {
            if(canUse(photo))actions.append(button('개별 승인',()=>manual(photo,'APPROVE')));
            else actions.append(node('p',`승인 불가: ${restriction(photo)}`));
            actions.append(button('개별 반려',()=>manual(photo,'REJECT')));
        }
        if(!referenceOnly&&photo.galleryStatus==='APPROVED') {
            actions.append(button('승인 취소',()=>manual(photo,'REVOKE')));
            // 비교 결과에 우선순위 버전이 없는 경우 전체 사진 목록에서 조정한다.
            if(photo.galleryPriorityVersion!==undefined) {
                const label=node('label','갤러리 순서 '),input=node('input');input.type='number';input.min='1';input.max='30';input.value=photo.galleryPriority||'';label.append(input);actions.append(label);
                const save=async(clear)=>{if(busy)return;if(!clear&&(!input.value||!input.checkValidity())){input.reportValidity();return;}busy=true;try{const token=$('[data-workflow-csrf] input[name="_csrf"]');const body=new URLSearchParams({version:photo.galleryPriorityVersion,[token.name]:token.value});if(!clear)body.set('priority',input.value);const response=await fetch(`${root.dataset.base}/photos/${photo.id}/priority`,{method:'POST',headers:csrfHeaders(false),body});if(!response.ok){const data=await response.json().catch(()=>({}));throw Error(data.message||'순서를 저장하지 못했습니다.');}detail.close();await loadMain();}catch(error){$('[data-detail-error]').textContent=error.message;}finally{busy=false;}};
                actions.append(button('순서 저장',()=>save(false)),button('순서 해제',()=>save(true)));
            }
        }
        if(!actions.children.length)actions.append(node('p','이 상태에서는 심사할 수 없습니다.'));
        detail.showModal();
    }
    root.querySelectorAll('[data-section]').forEach(tab=>tab.addEventListener('click',()=>{if(busy)return;section=tab.dataset.section;page=0;loadMain();}));
    $('[data-workflow-status]').addEventListener('change',event=>{if(busy)return;status=event.target.value;page=0;if(status!=='PENDING')section='all';loadMain();});
    $('[data-workflow-all]').addEventListener('click',()=>{if(busy)return;section='all';page=0;loadMain();});
    $('[data-workflow-prev]').addEventListener('click',()=>{if(busy)return;page=Math.max(0,page-1);loadMain();});
    $('[data-workflow-next]').addEventListener('click',()=>{if(busy)return;page++;loadMain();});
    $('[data-clean-all]').addEventListener('change',event=>{if(busy||loading)return;photos.filter(canReject).forEach(photo=>{if(event.target.checked)cleanSelected.add(photo.id);else cleanSelected.delete(photo.id);});grid.querySelectorAll('[data-clean-select]').forEach(check=>{check.checked=cleanSelected.has(Number(check.dataset.cleanSelect));});selectionSummary();});
    $('[data-clean-approve]').addEventListener('click',()=>moderateSelected('APPROVE'));$('[data-clean-reject]').addEventListener('click',()=>moderateSelected('REJECT'));
    $('[data-compare-decide]').addEventListener('click',()=>decide());$('[data-compare-reject-all]').addEventListener('click',()=>decide(true));
    $('[data-use-all]').addEventListener('click',()=>{if(busy)return;comparison.filter(canUse).forEach(photo=>use.add(photo.id));updateDecision();});
    $('[data-use-none]').addEventListener('click',()=>{if(busy)return;use.clear();updateDecision();});
    $('[data-compare-prev]').addEventListener('click',()=>{if(busy)return;chunk=Math.max(0,chunk-1);loadComparison();});
    $('[data-compare-next]').addEventListener('click',()=>{if(busy)return;chunk++;loadComparison();});
    $('[data-compare-close]').addEventListener('click',()=>{if(busy)return;compareVersion++;compare.close();});
    $('[data-compare-back]').addEventListener('click',()=>{if(busy)return;compareVersion++;compare.close();});
    compare.addEventListener('cancel',event=>{if(busy)event.preventDefault();else compareVersion++;});
    $('[data-detail-close]').addEventListener('click',()=>{if(!busy)detail.close();});detail.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
    window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
    loadMain();
})();
