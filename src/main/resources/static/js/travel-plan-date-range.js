(function () {
    'use strict';
    const pad = n => String(n).padStart(2, '0');
    const key = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    function parse(value) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return null;
        const [year, month, day] = value.split('-').map(Number);
        if (year < 1000) return null;
        const date = new Date(year, month - 1, day);
        return key(date) === value ? date : null;
    }
    function select(start, end, value) {
        if (!start || end) return [value, ''];
        return value < start ? [value, start] : [start, value];
    }
    function days(start, end) {
        const a = parse(start), b = parse(end);
        return a && b ? Math.round((Date.UTC(b.getFullYear(), b.getMonth(), b.getDate()) - Date.UTC(a.getFullYear(), a.getMonth(), a.getDate())) / 86400000) + 1 : 0;
    }
    function mount(doc) {
        const form = doc.querySelector('[data-plan-create]');
        if (!form) return;
        const trigger = form.querySelector('[data-range-open]');
        const startField = form.querySelector('[data-range-start]');
        const endField = form.querySelector('[data-range-end]');
        const refresh = () => {
            trigger.textContent = parse(startField.value) && parse(endField.value)
                ? `${startField.value.replaceAll('-', '.')} — ${endField.value.replaceAll('-', '.')}` : '날짜를 선택하세요 ▦';
            trigger.setAttribute('aria-label', `여행 날짜: ${trigger.textContent}`);
        };
        let dialog = null;
        trigger.addEventListener('click', () => {
            if (dialog) return;
            let start = parse(startField.value) ? startField.value : '';
            let end = parse(endField.value) ? endField.value : '';
            const initial = parse(start) || new Date();
            let month = new Date(initial.getFullYear(), initial.getMonth(), 1);
            if (month.getFullYear() === 9999 && month.getMonth() === 11) month.setMonth(10);
            const today = key(new Date());
            const previousOverflow = doc.body.style.overflow;
            dialog = doc.createElement('dialog');
            dialog.className = 'tp-range-dialog';
            dialog.setAttribute('aria-labelledby', 'tp-range-title');
            dialog.innerHTML = '<header><h2 id="tp-range-title">여행 날짜 선택</h2><button type="button" data-range-action="close" aria-label="닫기">×</button></header><div class="tp-range-navigation"><button type="button" data-range-action="previous" aria-label="이전 달">‹</button><p data-range-summary aria-live="polite"></p><button type="button" data-range-action="next" aria-label="다음 달">›</button></div><div class="tp-range-months" data-range-months></div><p class="tp-range-error" data-range-error role="status"></p><footer><button type="button" data-range-action="clear">날짜 지우기</button><button type="button" class="tp-range-confirm" data-range-action="confirm">확인</button></footer>';
            const query = s => dialog.querySelector(s);
            const render = () => {
                query('[data-range-summary]').textContent = !start ? '가는 날을 선택하세요.' : !end ? '돌아오는 날을 선택하세요. (당일 여행은 같은 날짜)' : `${start.replaceAll('-', '.')} — ${end.replaceAll('-', '.')} · ${days(start, end)}일`;
                query('[data-range-error]').textContent = '';
                query('[data-range-action="previous"]').disabled = month.getFullYear() === 1000 && month.getMonth() === 0;
                query('[data-range-action="next"]').disabled = month.getFullYear() === 9999 && month.getMonth() === 10;
                const months = query('[data-range-months]');
                months.replaceChildren();
                for (let i = 0; i < 2; i++) {
                    const date = new Date(month.getFullYear(), month.getMonth() + i, 1);
                    const section = doc.createElement('section');
                    const heading = doc.createElement('h3');
                    heading.textContent = `${date.getFullYear()}년 ${date.getMonth() + 1}월`;
                    section.append(heading);
                    const week = doc.createElement('div');
                    week.className = 'tp-range-week'; week.setAttribute('aria-hidden', 'true');
                    ['일', '월', '화', '수', '목', '금', '토'].forEach(text => { const span = doc.createElement('span'); span.textContent = text; week.append(span); });
                    section.append(week);
                    const grid = doc.createElement('div'); grid.className = 'tp-range-days';
                    for (let blank = 0; blank < date.getDay(); blank++) grid.append(doc.createElement('span'));
                    const count = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
                    for (let day = 1; day <= count; day++) {
                        const value = key(new Date(date.getFullYear(), date.getMonth(), day));
                        const button = doc.createElement('button');
                        button.type = 'button'; button.textContent = day; button.dataset.rangeDate = value;
                        button.setAttribute('aria-label', `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${day}일`);
                        button.setAttribute('aria-pressed', String(value === start || value === end));
                        if (value === today) button.setAttribute('aria-current', 'date');
                        if (start && end && value > start && value < end) button.className = 'is-in-range';
                        grid.append(button);
                    }
                    section.append(grid); months.append(section);
                }
            };
            dialog.addEventListener('click', event => {
                const dateButton = event.target.closest('[data-range-date]');
                if (dateButton) {
                    const value = dateButton.dataset.rangeDate;
                    [start, end] = select(start, end, value); render();
                    query(`[data-range-date="${value}"]`)?.focus(); return;
                }
                const action = event.target.closest('[data-range-action]')?.dataset.rangeAction;
                if (action === 'previous' || action === 'next') { month.setMonth(month.getMonth() + (action === 'previous' ? -1 : 1)); render(); }
                if (action === 'clear') { start = ''; end = ''; render(); }
                if (action === 'close') dialog.close();
                if (action === 'confirm') {
                    if (!start && !end) { startField.value = ''; endField.value = ''; refresh(); dialog.close(); return; }
                    if (!start || !end) { query('[data-range-error]').textContent = '시작일과 종료일을 모두 선택해 주세요.'; return; }
                    if (days(start, end) > 90) { query('[data-range-error]').textContent = '여행은 최대 90일까지 만들 수 있어요.'; return; }
                    startField.value = start; endField.value = end; refresh(); dialog.close();
                }
                if (event.target === dialog) {
                    const rect = dialog.getBoundingClientRect();
                    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
                }
            });
            dialog.addEventListener('close', () => { dialog.remove(); dialog = null; doc.body.style.overflow = previousOverflow; trigger.focus(); }, {once: true});
            render(); doc.body.append(dialog); dialog.showModal(); doc.body.style.overflow = 'hidden';
        });
        form.addEventListener('submit', event => {
            if (!parse(startField.value) || !parse(endField.value)) { event.preventDefault(); trigger.click(); return; }
            if (form.dataset.submitting) { event.preventDefault(); return; }
            form.dataset.submitting = 'true';
            form.querySelector('[type="submit"]').disabled = true;
        });
        doc.defaultView.addEventListener('pageshow', () => { delete form.dataset.submitting; form.querySelector('[type="submit"]').disabled = false; });
        refresh();
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {parse, select, days};
    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => mount(document));
        else mount(document);
    }
}());
