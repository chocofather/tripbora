(function (root) {
  'use strict';
  const MAX_BYTES = 1048576;
  const LANGUAGES = {ko: '한국어 원문', en: '영어', ja: '일본어', 'zh-CN': '중국어 간체', 'zh-TW': '중국어 번체'};
  const TEXT_FIELDS = {name: '이름', shortDescription: '한 줄 소개', description: '상세 설명'};
  const STATES = {MATCHED: '대상 확인됨', NEEDS_MATCH: '같은 여행지 확인 필요', NOT_FOUND: '대상 없음', INVALID: '오류', CONFLICT: '충돌 — 미리보기 갱신 필요'};

  function visibleChanges(preview) { return (preview.changes || []).filter(change => !change.path.startsWith('info.translations.ko.')); }
  function fieldsOf(row) {
    const allowed = new Set(visibleChanges(row.preview).filter(change => change.kind !== 'UNCHANGED').map(change => change.path));
    const fields = [...row.fields].filter(path => allowed.has(path));
    if (fields.includes('latitude') || fields.includes('longitude')) {
      for (const path of ['latitude', 'longitude']) if (!fields.includes(path)) fields.push(path);
    }
    return fields;
  }
  function countsOf(rows) {
    const counts = {auto: 0, replace: 0, retained: 0};
    for (const row of rows) for (const change of visibleChanges(row.preview)) {
      if (change.kind === 'UNCHANGED' || !row.fields.has(change.path)) counts.retained++;
      else if (change.kind === 'AUTO_ADD') counts.auto++;
      else counts.replace++;
    }
    return counts;
  }
  function selection(row) {
    const awaitingFirstMatch = !row.hasMatchedPreview && row.preview.status !== 'MATCHED' && !visibleChanges(row.preview).length;
    return {index: row.preview.index, destinationId: row.destinationId ?? null, confirmedMatch: Boolean(row.confirmedMatch), fields: awaitingFirstMatch ? null : fieldsOf(row), replaceRelations: Boolean(row.replaceRelations)};
  }
  function invalidate(row) { row.token = null; row.result = null; }
  function canApply(row) {
    const matched = row.preview.status === 'MATCHED' || Boolean(row.preview.status === 'INVALID' && row.dirty && row.hasMatchedPreview);
    return matched && Boolean(row.token || row.previewReady) && row.destinationId != null && fieldsOf(row).length > 0 && !row.result && !row.pendingSave;
  }
  function groupOf(path) {
    const translation = /^translations\.([^.]+)\./.exec(path);
    if (translation) return translation[1] === 'ko' ? '한국어 원문' : `${LANGUAGES[translation[1]] || translation[1]} 번역`;
    if (path.startsWith('info.translations.')) return '사실정보 번역';
    if (path.startsWith('info.')) return '사실정보';
    if (['categories', 'mainCategory', 'amenities'].includes(path)) return '카테고리·편의시설';
    return '기본 정보';
  }
  function fieldLabel(change) {
    const match = /^translations\.([^.]+)\.([^.]+)$/.exec(change.path);
    return match ? `${LANGUAGES[match[1]] || match[1]} · ${TEXT_FIELDS[match[2]] || match[2]}` : change.label || change.path;
  }
  function validateDocument(text) {
    if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error('JSON은 최대 1MB까지 받을 수 있습니다.');
    let parsed;
    try { parsed = JSON.parse(text); } catch (_) { throw new Error('JSON 문법을 확인해 주세요.'); }
    if (!Array.isArray(parsed.destinations) || parsed.destinations.length < 1 || parsed.destinations.length > 30) throw new Error('destinations는 1~30개여야 합니다.');
    return parsed;
  }
  // Aborting the browser wait does not cancel a server transaction. Never retry apply automatically.
  async function requestJson(path, body, {fetcher = root.fetch.bind(root), timeoutMs = 45000, headers = {}, onRequest = () => {}} = {}) {
    const controller = new AbortController();
    const requestId = root.crypto?.randomUUID?.();
    onRequest(requestId);
    const saving = path === 'apply';
    const error = message => Object.assign(new Error(message + (saving ? ' 저장 성공 여부가 불확실합니다. 저장 결과 확인을 눌러 주세요.' : ' 다시 시도해 주세요.') + (requestId ? ` 요청 번호: ${requestId}` : '')), {uncertain: saving});
    let timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => { controller.abort(); reject(error('서버 응답이 지연되어 대기를 종료했습니다.')); }, timeoutMs);
    });
    try {
      return await Promise.race([deadline, (async () => {
        let response;
        try {
          response = await fetcher(`/admin/api/destinations/import-json/update/${path}`, {
            method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json', ...(requestId ? {'X-Import-Request-Id': requestId} : {}), ...headers},
            body: JSON.stringify(body), signal: controller.signal
          });
        } catch (_) { throw error('네트워크 연결을 확인해 주세요.'); }
        if ((response.redirected && new URL(response.url).pathname.startsWith('/login')) || [401, 403].includes(response.status)) {
          throw Object.assign(new Error('로그인·관리자 권한 또는 CSRF 인증을 확인한 뒤 다시 접속해 주세요.'), {uncertain: false});
        }
        let payload;
        try { payload = await response.json(); } catch (_) { throw error(`서버 응답을 읽지 못했습니다. (HTTP ${response.status})`); }
        if (!response.ok) throw error(payload?.message || `서버 오류가 발생했습니다. (HTTP ${response.status})`);
        if (!payload || (path === 'preview' ? !Array.isArray(payload.rows) : typeof payload.status !== 'string')) throw error('서버 응답 형식이 올바르지 않습니다.');
        return payload;
      })()]);
    } finally { clearTimeout(timer); }
  }
  const api = {selection, fieldsOf, countsOf, invalidate, canApply, groupOf, fieldLabel, validateDocument, requestJson};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.TripBoraDestinationJsonUpdate = api;
  if (typeof document === 'undefined') return;

  document.addEventListener('DOMContentLoaded', () => {
    const workspace = document.querySelector('[data-json-workspace]');
    const page = document.querySelector('[data-json-update]');
    if (!workspace || !page) return;
    const $ = selector => page.querySelector(selector);
    const input = $('[data-update-text]');
    const status = $('[data-update-status]');
    let rows = [], snapshot = null, previewToken = null, commonFields = null, running = false, activeStep = 1;
    let busyTimer = null, activeRequestId = null;
    function busy(message) {
      const started = Date.now();
      const update = () => {
        const seconds = Math.floor((Date.now() - started) / 1000);
        status.textContent = `${message} · 경과 ${seconds}초${seconds >= 10 ? ' · 응답이 지연되고 있습니다. 중복 요청하지 말고 기다려 주세요.' : ''}${activeRequestId ? ` · 요청 번호: ${activeRequestId}` : ''}`;
      };
      clearInterval(busyTimer); update(); busyTimer = setInterval(update, 1000);
      page.setAttribute('aria-busy', 'true');
      status.scrollIntoView({block: 'nearest'});
    }
    function stopBusy() { clearInterval(busyTimer); busyTimer = null; page.setAttribute('aria-busy', 'false'); }
    workspace.querySelectorAll('[name="json-mode"]').forEach(radio => radio.addEventListener('change', () => {
      workspace.querySelector('[data-json-import]').hidden = radio.value !== 'import';
      page.hidden = radio.value !== 'update';
    }));
    function node(tag, className, text) {
      const item = document.createElement(tag);
      if (className) item.className = className;
      if (text != null) item.textContent = text;
      return item;
    }
    function checkbox(text, checked, onChange, disabled) {
      const label = node('label', 'admin-json-update-check');
      const check = node('input'); check.type = 'checkbox'; check.checked = checked; check.disabled = Boolean(disabled || running);
      check.addEventListener('change', () => onChange(check.checked));
      label.append(check, document.createTextNode(text)); return label;
    }
    function stale(row) {
      invalidate(row); row.dirty = true;
      if (rows.length === 1 && row.hasMatchedPreview) row.selected = true;
      status.textContent = '변경 항목을 반영했습니다. 최종 업데이트 시 선택 내용을 서버에서 검증합니다.';
      syncRow(row); renderSummary();
    }
    async function post(path, body) {
      const csrf = document.querySelector('meta[name="_csrf"]')?.content;
      const header = document.querySelector('meta[name="_csrf_header"]')?.content;
      return requestJson(path, body, {headers: csrf && header ? {[header]: csrf} : {}, onRequest: id => { activeRequestId = id; }});
    }
    function grouped(changes, renderField, container) {
      const groups = new Map();
      for (const change of changes) {
        const group = groupOf(change.path);
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(change);
      }
      for (const [title, entries] of groups) {
        const details = node('details', 'admin-json-update-group');
        details.open = !title.includes('번역');
        details.append(node('summary', null, `${title} (${entries.length})`));
        let filled = false;
        function fill() { if (!filled) { filled = true; entries.forEach(change => details.append(renderField(change))); } }
        if (details.open) fill(); else details.addEventListener('toggle', () => { if (details.open) fill(); });
        container.append(details);
      }
    }
    function renderCommon() {
      const fields = new Map();
      rows.forEach(row => visibleChanges(row.preview).filter(change => change.kind !== 'UNCHANGED').forEach(change => {
        const previous = fields.get(change.path);
        fields.set(change.path, {change, selected: row.fields.has(change.path) && (previous?.selected ?? true)});
      }));
      const container = $('[data-update-common]'); container.replaceChildren();
      if (commonFields == null) commonFields = new Set([...fields].filter(([, entry]) => entry.selected).map(([path]) => path));
      grouped([...fields.values()].map(entry => entry.change), change => {
        const label = checkbox(fieldLabel(change), commonFields.has(change.path), checked => { if (checked) commonFields.add(change.path); else commonFields.delete(change.path); });
        label.querySelector('input').dataset.commonPath = change.path;
        return label;
      }, container);
    }
    function changeCard(row, change) {
      const selected = row.fields.has(change.path);
      const card = node('div', `admin-json-update-change${selected ? ' is-selected' : ''}`);
      const label = checkbox(`${fieldLabel(change)} · ${change.kind === 'UNCHANGED' ? '동일한 값' : selected ? '선택됨' : '유지'}`, selected, checked => {
        if (checked) row.fields.add(change.path); else row.fields.delete(change.path);
        stale(row);
      }, change.kind === 'UNCHANGED');
      row.fieldControls.set(change.path, {card, label, input: label.querySelector('input'), change}); card.append(label);
      function values() {
        const container = node('div', 'admin-json-update-values');
        for (const [heading, value] of [['현재', change.beforeText ?? '(없음)'], ['JSON 적용 후', change.afterText ?? '(없음)']]) {
          const column = node('div'); column.append(node('strong', null, heading), node('pre', null, value)); container.append(column);
        }
        return container;
      }
      if (change.path.endsWith('.description') || Math.max(String(change.afterText || '').length, String(change.beforeText || '').length) > 300) {
        const details = node('details'); details.append(node('summary', null, '본문 비교 펼치기'));
        details.addEventListener('toggle', () => { if (details.open && !details.querySelector('pre')) details.append(values()); }); card.append(details);
      } else card.append(values());
      return card;
    }
    function renderRow(row) {
      const preview = row.preview;
      const card = node('article', 'admin-json-update-row');
      row.dom = card; row.fieldControls = new Map();
      const heading = node('div', 'admin-json-update-row-head');
      const title = `${preview.index + 1}. ${preview.name || preview.key || '(이름 없음)'}`;
      row.pickLabel = rows.length === 1 ? node('strong', null, title) : checkbox(title, row.selected, checked => { row.selected = checked; renderSummary(); }, preview.status !== 'MATCHED');
      row.stateLabel = node('span', null, STATES[preview.status] || preview.status);
      heading.append(row.pickLabel, row.stateLabel);
      card.append(heading);
      if (preview.message) card.append(node('p', 'admin-json-update-note', preview.message));
      if (row.destinationId != null) card.append(node('p', null, `대상 여행지 #${row.destinationId}${preview.contentId ? ` · contentId ${preview.contentId}` : ''}`));
      if (preview.status === 'NEEDS_MATCH' && (preview.candidates || []).length) {
        const candidates = node('div', 'admin-json-update-candidate');
        preview.candidates.forEach(candidate => {
          const item = node('div', 'admin-json-update-candidate-choice');
          item.append(node('p', null, `#${candidate.destinationId} ${candidate.name || ''} · ${candidate.message || ''}`));
          const confirm = node('button', 'admin-btn is-small', '이 여행지로 업데이트'); confirm.type = 'button';
          confirm.dataset.candidateId = String(candidate.destinationId);
          confirm.addEventListener('click', () => {
            if (running) return;
            row.destinationId = candidate.destinationId; row.confirmedMatch = true; row.selected = true;
            stale(row); analyze();
          });
          item.append(confirm); candidates.append(item);
        });
        card.append(candidates);
      }
      card.append(checkbox('JSON 목록으로 교체(제거 허용)', row.replaceRelations, checked => { row.replaceRelations = checked; stale(row); analyze(); }));
      for (const warning of preview.warnings || []) card.append(node('p', 'admin-json-update-warning', `주의: ${warning}`));
      row.staleLabel = node('p', 'admin-json-update-note', '선택 내용을 반영했습니다. 최종 업데이트 시 검증합니다.'); card.append(row.staleLabel);
      for (const [kind, title] of [['AUTO_ADD', '자동 보강 — 비어 있는 값'], ['REPLACE', '기존 값 교체 — 직접 선택'], ['UNCHANGED', '변경 없음 — 유지']]) {
        const changes = visibleChanges(preview).filter(change => (change.kind || 'REPLACE') === kind);
        if (!changes.length) continue;
        const details = node('details', 'admin-json-update-kind'); details.open = kind !== 'UNCHANGED';
        details.append(node('summary', null, `${title} (${changes.length})`));
        let filled = false;
        function fill(sync) { if (!filled) { filled = true; grouped(changes, change => changeCard(row, change), details); if (sync) syncRow(row); } }
        if (details.open) fill(false); else details.addEventListener('toggle', () => { if (details.open) fill(true); });
        card.append(details);
      }
      if ((preview.evidence || []).length) {
        const details = node('details', 'admin-json-import-evidence'); details.append(node('summary', null, `근거 ${preview.evidence.length}건`));
        preview.evidence.forEach(evidence => {
          const item = node('p');
          if (/^https?:\/\//i.test(evidence.url || '')) {
            const link = node('a', null, evidence.url); link.href = evidence.url; link.target = '_blank'; link.rel = 'noopener noreferrer'; item.append(link);
          } else item.textContent = evidence.url || '(주소 없음)';
          item.append(document.createTextNode(` · ${(evidence.fields || []).join(', ')}`)); details.append(item);
        }); card.append(details);
      }
      row.outcome = node('p', 'admin-json-update-outcome'); card.append(row.outcome);
      row.checkResult = node('button', 'admin-btn is-small', '저장 결과 확인'); row.checkResult.type = 'button';
      row.checkResult.addEventListener('click', () => checkSaveResult(row)); card.append(row.checkResult);
      row.inspect = node('a', 'admin-btn is-small', '여행지에서 실제 반영 상태 확인');
      row.inspect.href = `/admin/destinations/edit/${row.destinationId}`; row.inspect.target = '_blank'; row.inspect.rel = 'noopener'; card.append(row.inspect);
      return card;
    }
    function syncRow(row) {
      if (!row.dom) return;
      row.dom.querySelectorAll('input,select,button').forEach(control => { control.disabled = running || row.pendingSave; });
      const pick = row.pickLabel.querySelector('input');
      if (pick) { pick.checked = row.selected; pick.disabled = running || !row.hasMatchedPreview || row.pendingSave; }
      row.stateLabel.textContent = STATES[row.preview.status] || row.preview.status;
      if (row.confirmLabel) { const check = row.confirmLabel.querySelector('input'); check.checked = row.confirmedMatch; check.disabled = running || row.destinationId == null; }
      row.staleLabel.hidden = !row.dirty || Boolean(row.result);
      for (const [path, {card, label, input: check, change}] of row.fieldControls) {
        const selected = row.fields.has(path) && change.kind !== 'UNCHANGED';
        check.checked = selected;
        check.disabled = running || row.pendingSave || change.kind === 'UNCHANGED';
        label.lastChild.textContent = `${fieldLabel(change)} · ${change.kind === 'UNCHANGED' ? '동일한 값' : selected ? '선택됨' : '유지'}`;
        card.classList.toggle('is-selected', selected);
      }
      row.outcome && (row.outcome.hidden = !row.result);
      if (row.result && row.outcome) row.outcome.textContent = `${row.result.status}: ${row.result.message || ''}`;
      if (row.checkResult) { row.checkResult.hidden = !row.pendingSave; row.checkResult.disabled = running; }
      if (row.inspect) row.inspect.hidden = !row.pendingSave;
    }
    function render() {
      const body = $('[data-update-rows]');
      rows.forEach((row, index) => {
        const element = row.dom || renderRow(row);
        if (!body.children[index]) body.append(element);
        else if (body.children[index] !== element) body.children[index].replaceWith(element);
      });
      while (body.children.length > rows.length) body.lastElementChild.remove();
      rows.forEach(syncRow); renderSummary();
    }
    function renderSummary() {
      const picked = rows.filter(row => row.selected && canApply(row));
      const counts = countsOf(rows);
      const candidates = {AUTO_ADD:0, REPLACE:0, UNCHANGED:0};
      rows.forEach(row => visibleChanges(row.preview).forEach(change => { if (change.kind in candidates) candidates[change.kind]++; }));
      const conflicts = rows.filter(row => ['CONFLICT','NEEDS_MATCH','INVALID','NOT_FOUND'].includes(row.preview.status)).length;
      $('[data-update-summary]').textContent = `총 ${rows.length}건 · 자동 추가 대상 ${candidates.AUTO_ADD}개 · 기존 교체 후보 ${candidates.REPLACE}개 · 변경 없음 ${candidates.UNCHANGED}개 · 충돌·확인 필요 ${conflicts}건 | 선택 여행지 ${picked.length}건 · 선택 자동 보강 ${counts.auto}개 · 선택 교체 ${counts.replace}개 · 유지 ${counts.retained}개`;
      const unresolved = rows.some(row => row.pendingSave);
      $('[data-update-apply]').disabled = running || unresolved || !picked.length || input.value !== snapshot;
      $('[data-update-preview]').disabled = running || unresolved;
      input.disabled = running; $('[data-update-file]').disabled = running;
      page.querySelectorAll('[data-update-common] input, [data-update-common-apply], [data-update-select-all], [data-update-clear]').forEach(control => { control.disabled = running; });
      page.querySelectorAll('[data-update-rows] select').forEach(control => { control.disabled = running; });
      workspace.querySelectorAll('[name="json-mode"]').forEach(control => { control.disabled = running; });
      for (const selector of ['[data-update-select-all]', '[data-update-clear]', '.admin-json-update-common']) $(selector).hidden = rows.length < 2;
      page.querySelectorAll('[data-update-step]').forEach(step => {
        const current = Number(step.dataset.updateStep) === activeStep;
        step.classList.toggle('is-current', current);
        if (current) step.setAttribute('aria-current', 'step'); else step.removeAttribute('aria-current');
      });
      for (const selector of ['[data-update-preview]', '[data-update-apply]']) {
        const button = $(selector); button.dataset.idleLabel ||= button.textContent;
        button.textContent = running ? '처리 중…' : button.dataset.idleLabel;
      }
    }
    function sameComparison(before, after) {
      return before.status === after.status && before.destinationId === after.destinationId && before.message === after.message
        && JSON.stringify(before.warnings || []) === JSON.stringify(after.warnings || [])
        && before.changes.length === after.changes.length && before.changes.every((change, index) => {
          const next = after.changes[index];
          return change.path === next.path && change.kind === next.kind && change.beforeText === next.beforeText && change.afterText === next.afterText && change.removedCount === next.removedCount;
        });
    }
    async function refreshPreview() {
      const json = input.value;
      validateDocument(json);
      const prior = snapshot === json ? rows : [];
      const payload = await post('preview', {json, previewToken: prior.length ? previewToken : null, selections: prior.map(selection)});
      const errors = payload.fileErrors || [];
      $('[data-update-errors]').replaceChildren(...errors.map(error => node('li', null, `${error.path || ''}: ${error.message}`)));
      $('[data-update-errors]').hidden = !errors.length;
      if (errors.length) throw new Error('분석하지 못했습니다. 오류를 확인한 뒤 JSON 다시 분석을 눌러 주세요. 기존 선택은 유지됩니다.');
      rows = (payload.rows || []).map(item => {
        const previous = prior.find(row => row.preview.index === item.index);
        const row = previous || {};
        if (!previous || !sameComparison(previous.preview, item)) row.dom = null;
        Object.assign(row, {
          preview: item, hasMatchedPreview: previous?.hasMatchedPreview || item.status === 'MATCHED',
          previewReady: Boolean(payload.previewToken), dirty: false,
          fields: new Set(visibleChanges(item).filter(change => change.kind !== 'UNCHANGED' && (change.selected ?? change.kind === 'AUTO_ADD')).map(change => change.path)),
          destinationId: item.destinationId ?? previous?.destinationId ?? null,
          confirmedMatch: previous?.confirmedMatch || false, replaceRelations: previous?.replaceRelations || false,
          selected: previous ? previous.selected : item.status === 'MATCHED', token: item.token, result: null
        });
        return row;
      });
      if (!prior.length) commonFields = null;
      previewToken = payload.previewToken; snapshot = json; activeStep = 2;
      $('[data-update-results]').hidden = false; renderCommon(); render();
    }
    async function analyze() {
      if (running || rows.some(row => row.pendingSave)) return;
      running = true; busy('미리보기 처리 중…');
      try {
        render(); await refreshPreview();
        status.textContent = '분석했습니다. 확인된 여행지는 업데이트 대상에 포함했습니다. 변경 내용을 선택하고 최종 업데이트를 누르세요.';
      } catch (error) { previewToken = null; rows.forEach(row => { invalidate(row); row.previewReady = false; }); status.textContent = error.message; }
      finally { stopBusy(); running = false; render(); }
    }
    async function apply() {
      if (running || rows.some(row => row.pendingSave) || input.value !== snapshot) return;
      const targets = rows.filter(row => row.selected && canApply(row));
      if (!targets.length) return;
      running = true; busy('선택 항목 검증 중…'); activeStep = 3;
      try {
        render();
        if (targets.some(row => row.dirty || !row.token)) {
          await refreshPreview(); activeStep = 3;
          if (targets.some(row => !canApply(row) || !row.token)) {
            activeStep = 2;
            status.textContent = '선택한 여행지의 검증 결과를 확인해 주세요. 저장하지 않았습니다.';
            return;
          }
        }
        render();
        const warnings = targets.flatMap(row => (row.preview.warnings || []).map(warning => `${row.preview.name}: ${warning}`));
        const removals = targets.filter(row => row.replaceRelations).length;
        const removedItems = targets.reduce((sum, row) => sum + visibleChanges(row.preview).filter(change => row.fields.has(change.path)).reduce((total, change) => total + (change.removedCount || 0), 0), 0);
        const counts = countsOf(targets);
        if (!root.confirm(`${targets.length}개 여행지를 업데이트할까요?\n자동 보강 ${counts.auto}개 · 기존 값 교체 ${counts.replace}개 · 유지 ${counts.retained}개\n기존 목록 제거 ${removedItems}개 · 목록 교체(기존 항목 제거 허용): ${removals}건\n${warnings.length ? `출처·검수 경고:\n${warnings.join('\n')}` : '검수 경고 없음'}\n선택하지 않은 항목은 유지됩니다.`)) { activeStep = 2; status.textContent = '저장을 취소했습니다. 변경 내용을 계속 선택할 수 있습니다.'; return; }
        previewToken = null;
        for (const row of targets) {
          busy(`${row.preview.name || row.preview.key} 업데이트 저장 중…`);
          row.receiptToken = row.token;
          try {
            row.result = await post('apply', {token: row.token, confirmed: true, acknowledgeWarnings: true, confirmRelationRemoval: row.replaceRelations});
            row.pendingSave = ['UNKNOWN', 'RUNNING'].includes(row.result.status);
          }
          catch (error) {
            row.pendingSave = Boolean(error.uncertain);
            row.result = {status: row.pendingSave ? 'UNKNOWN' : 'FAILED', message: error.message};
          }
          row.token = null; row.selected = false;
          if (row.result.status === 'CONFLICT') row.preview.status = 'CONFLICT';
          render();
          if (row.pendingSave) break;
        }
        status.textContent = rows.some(row => row.pendingSave) ? '저장 응답을 확인하지 못했습니다. 자동 재시도하지 않습니다. 저장 결과 확인을 눌러 주세요.' : '업데이트 처리가 끝났습니다. 여행지별 결과를 확인하세요. 충돌·실패 항목은 미리보기를 다시 갱신하세요.';
      } catch (error) {
        previewToken = null;
        rows.filter(row => !row.result).forEach(row => { row.token = null; row.dirty = true; });
        status.textContent = `처리 오류: ${error.message}. 여행지별 결과를 확인하세요.`;
      } finally { stopBusy(); running = false; render(); }
    }
    async function checkSaveResult(row) {
      if (running || !row.pendingSave) return;
      running = true; busy('저장 결과 확인 중…');
      try {
        render();
        row.result = await post('status', {token: row.receiptToken});
        if (['RUNNING', 'UNKNOWN', 'NOT_STARTED'].includes(row.result.status)) {
          status.textContent = row.result.message;
        } else {
          row.pendingSave = false; previewToken = null;
          status.textContent = '저장 결과를 확인했습니다. 여행지별 결과를 확인하고 새로 미리보기 하세요.';
        }
      } catch (error) { status.textContent = `${error.message} 저장을 다시 요청하지 말고 결과 확인을 재시도하세요.`; }
      finally { stopBusy(); running = false; render(); }
    }
    $('[data-update-file]').addEventListener('change', async event => {
      const file = event.target.files?.[0]; if (!file) return;
      if (running || rows.some(row => row.pendingSave)) { event.target.value = ''; return; }
      running = true; activeStep = 1; busy('JSON 파일 읽는 중…');
      let loaded = false;
      try {
        render();
        if (!/\.json$/i.test(file.name) || file.size > MAX_BYTES) throw new Error('.json 파일(최대 1MB)을 선택해 주세요.');
        input.value = await file.text(); $('[data-update-file-name]').textContent = file.name;
        rows = []; snapshot = null; previewToken = null; commonFields = null; loaded = true;
        $('[data-update-results]').hidden = true;
      } catch (error) { status.textContent = error.message; }
      finally { event.target.value = ''; running = false; stopBusy(); render(); }
      if (loaded) await analyze();
    });
    input.addEventListener('input', () => { rows.forEach(row => { invalidate(row); row.previewReady = false; }); status.textContent = 'JSON 원문을 수정했습니다. JSON 다시 분석을 눌러 주세요.'; render(); });
    $('[data-update-preview]').addEventListener('click', analyze);
    $('[data-update-apply]').addEventListener('click', apply);
    $('[data-update-common-apply]').addEventListener('click', () => {
      const paths = [...(commonFields || [])];
      rows.forEach(row => { row.fields = new Set(paths.filter(path => visibleChanges(row.preview).some(change => change.path === path && change.kind !== 'UNCHANGED'))); invalidate(row); row.dirty = true; });
      status.textContent = '공통 선택을 반영했습니다. 최종 업데이트 시 검증합니다.'; render();
    });
    $('[data-update-select-all]').addEventListener('click', () => { rows.forEach(row => { row.selected = canApply(row); }); render(); });
    $('[data-update-clear]').addEventListener('click', () => { rows.forEach(row => { row.selected = false; }); render(); });
    root.addEventListener('beforeunload', event => { if (running) event.preventDefault(); });
    renderSummary();
  });
})(typeof window !== 'undefined' ? window : globalThis);
