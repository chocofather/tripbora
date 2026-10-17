(function () {
    'use strict';
    const pad = value => String(value).padStart(2, '0');
    const isoDate = date => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
    function parseDate(value) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return null;
        const [year, month, day] = value.split('-').map(Number);
        const date = new Date(year, month - 1, day);
        return isoDate(date) === value ? date : null;
    }
    function timeParts(value) {
        const match = /^(\d{2}):(\d{2})(?::\d{2})?$/.exec(value || '');
        return match && +match[1] < 24 && +match[2] < 60 ? match.slice(1, 3) : ['', ''];
    }
    function label(date, time, withDate) {
        const parsed = parseDate(date);
        const dateLabel = parsed ? `${parsed.getMonth() + 1}월 ${parsed.getDate()}일 (${['일', '월', '화', '수', '목', '금', '토'][parsed.getDay()]})` : '';
        const [hour, minute] = timeParts(time);
        return [withDate ? dateLabel : '', hour ? `${hour}:${minute}` : withDate && parsed ? '시간 미정' : ''].filter(Boolean).join(' · ') || (withDate ? '날짜 · 시간 선택' : '시간 선택');
    }
    function commitFields(fields, values, EventClass) {
        fields.forEach((field, index) => {
            if (!field || field.value === values[index]) return;
            field.value = values[index];
            field.dispatchEvent(new EventClass('input', {bubbles: true}));
            field.dispatchEvent(new EventClass('change', {bubbles: true}));
        });
    }
    function mount(doc) {
        let active = null;
        function field(form, name) {
            return name ? Array.from(form.querySelectorAll('input[name]')).find(input => input.name === name) : null;
        }
        function fields(trigger) {
            const form = trigger.closest('form');
            return form ? [field(form, trigger.dataset.dateField), field(form, trigger.dataset.timeField)] : [];
        }
        function refresh(root = doc) {
            const triggers = Array.from(root.querySelectorAll('[data-datetime-picker]'));
            if (root.matches?.('[data-datetime-picker]')) triggers.unshift(root);
            triggers.forEach(trigger => {
                const [date, time] = fields(trigger);
                trigger.textContent = label(date?.value, time?.value, !!trigger.dataset.dateField);
                trigger.setAttribute('aria-label', `${trigger.dataset.pickerTitle || '일시'}: ${trigger.textContent}`);
            });
        }
        function close() {
            if (!active) return;
            const previous = active;
            active = null;
            previous.overlay.remove();
            doc.body.style.overflow = previous.overflow;
            if (previous.trigger.isConnected) previous.trigger.focus();
        }
        function open(trigger) {
            close();
            const [dateField, timeField] = fields(trigger);
            if (!timeField || timeField.disabled) return;
            const planner = trigger.closest('[data-trip-start]') || doc.querySelector('[data-trip-start]');
            const tripStart = planner?.dataset.tripStart || '';
            const tripEnd = planner?.dataset.tripEnd || '';
            const today = isoDate(new Date());
            const start = parseDate(dateField?.value) || parseDate(tripStart) || new Date();
            const overlay = doc.createElement('div');
            overlay.className = 'tp-picker-overlay';
            overlay.innerHTML = '<section class="tp-picker" role="dialog" aria-modal="true" aria-labelledby="tp-picker-title"><header><h2 id="tp-picker-title"></h2><button type="button" data-picker-action="cancel" aria-label="닫기">×</button></header><div data-picker-calendar><div class="tp-picker-month"><button type="button" data-picker-action="previous" aria-label="이전 달">‹</button><strong data-picker-month aria-live="polite"></strong><button type="button" data-picker-action="next" aria-label="다음 달">›</button></div><div class="tp-picker-weekdays" aria-hidden="true"><span>일</span><span>월</span><span>화</span><span>수</span><span>목</span><span>금</span><span>토</span></div><div class="tp-picker-days" data-picker-days></div><div class="tp-picker-calendar-footer"><span>● 여행 기간</span><button type="button" data-picker-action="today">오늘</button></div></div><div class="tp-picker-time"><label>시<input type="text" inputmode="numeric" maxlength="2" autocomplete="off" data-picker-hour aria-label="시" placeholder="00-23"></label><span aria-hidden="true">:</span><label>분<input type="text" inputmode="numeric" maxlength="2" autocomplete="off" data-picker-minute aria-label="분" placeholder="00-59"></label></div><p class="tp-picker-error" role="status" data-picker-error></p><footer><button type="button" data-picker-action="clear">초기화</button><button type="button" data-picker-action="cancel">취소</button><button type="button" class="tp-picker-confirm" data-picker-action="confirm">확인</button></footer></section>';
            const query = selector => overlay.querySelector(selector);
            query('#tp-picker-title').textContent = trigger.dataset.pickerTitle || '일시 선택';
            query('[data-picker-calendar]').hidden = !dateField;
            const [hour, minute] = timeParts(timeField.value);
            const hourInput = query('[data-picker-hour]');
            const minuteInput = query('[data-picker-minute]');
            hourInput.value = hour;
            minuteInput.value = minute;
            const summary = doc.createElement('p');
            summary.className = 'tp-picker-selection';
            summary.setAttribute('data-picker-selection', '');
            summary.setAttribute('aria-live', 'polite');
            const timeControls = query('.tp-picker-time');
            timeControls.parentNode.insertBefore(summary, timeControls);
            active = {trigger, overlay, overflow: doc.body.style.overflow};
            let selectedDate = dateField?.value || '';
            let month = new Date(start.getFullYear(), start.getMonth(), 1);
            function renderSelection() {
                const date = parseDate(selectedDate);
                const dateText = date ? `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${date.getDate()}일` : '날짜 미정';
                const timeText = validPart(hourInput.value, 23) && validPart(minuteInput.value, 59) ? `${pad(hourInput.value)}:${pad(minuteInput.value)}` : '시간 미정';
                summary.textContent = dateField ? `${dateText} · ${timeText}` : timeText;
            }
            function validPart(value, maximum) {
                return /^\d{1,2}$/.test(value) && Number(value) <= maximum;
            }
            [[hourInput, 23], [minuteInput, 59]].forEach(([input, maximum]) => {
                input.addEventListener('input', () => {
                    input.value = input.value.replace(/\D/g, '').slice(0, 2);
                    query('[data-picker-error]').textContent = '';
                    renderSelection();
                    if (input === hourInput && input.value.length === 2 && validPart(input.value, maximum)) minuteInput.focus();
                });
                input.addEventListener('blur', () => {
                    if (validPart(input.value, maximum)) input.value = pad(input.value);
                    renderSelection();
                });
                input.addEventListener('change', renderSelection);
            });
            function renderCalendar() {
                renderSelection();
                query('[data-picker-month]').textContent = `${month.getFullYear()}년 ${month.getMonth() + 1}월`;
                const grid = query('[data-picker-days]');
                grid.replaceChildren();
                for (let offset = 0; offset < month.getDay(); offset++) grid.append(doc.createElement('span'));
                const count = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
                for (let day = 1; day <= count; day++) {
                    const value = isoDate(new Date(month.getFullYear(), month.getMonth(), day));
                    const button = doc.createElement('button');
                    button.type = 'button'; button.dataset.pickerDate = value; button.textContent = day;
                    button.setAttribute('aria-label', `${month.getFullYear()}년 ${month.getMonth() + 1}월 ${day}일`);
                    button.setAttribute('aria-pressed', String(value === selectedDate));
                    if (value === today) button.setAttribute('aria-current', 'date');
                    if (tripStart && tripEnd && value >= tripStart && value <= tripEnd) button.classList.add('is-trip-date');
                    grid.append(button);
                }
            }
            overlay.addEventListener('click', event => {
                if (event.target === overlay) { close(); return; }
                const dateButton = event.target.closest('[data-picker-date]');
                if (dateButton) { selectedDate = dateButton.dataset.pickerDate; renderCalendar(); query(`[data-picker-date="${selectedDate}"]`).focus(); return; }
                const action = event.target.closest('[data-picker-action]')?.dataset.pickerAction;
                if (action === 'cancel') close();
                if (action === 'previous' || action === 'next') { month = new Date(month.getFullYear(), month.getMonth() + (action === 'next' ? 1 : -1), 1); renderCalendar(); }
                if (action === 'today') { selectedDate = today; month = new Date(new Date().getFullYear(), new Date().getMonth(), 1); renderCalendar(); }
                if (action === 'clear') { selectedDate = ''; hourInput.value = ''; minuteInput.value = ''; query('[data-picker-error]').textContent = ''; renderCalendar(); }
                if (action === 'confirm') {
                    if (!!hourInput.value !== !!minuteInput.value) { query('[data-picker-error]').textContent = '시와 분을 모두 입력해 주세요.'; return; }
                    if ((hourInput.value || minuteInput.value) && (!validPart(hourInput.value, 23) || !validPart(minuteInput.value, 59))) {
                        query('[data-picker-error]').textContent = '시는 00~23, 분은 00~59로 입력해 주세요.';
                        (!validPart(hourInput.value, 23) ? hourInput : minuteInput).focus();
                        return;
                    }
                    const selectedTime = hourInput.value ? `${pad(hourInput.value)}:${pad(minuteInput.value)}` : '';
                    commitFields([dateField, timeField], [selectedDate, selectedTime], doc.defaultView.Event);
                    refresh(trigger.closest('form'));
                    close();
                }
            });
            overlay.addEventListener('keydown', event => {
                if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
                if (event.key === 'Tab') {
                    const focusable = Array.from(overlay.querySelectorAll('button, input')).filter(element => !element.closest('[hidden]'));
                    const first = focusable[0], last = focusable[focusable.length - 1];
                    if (event.shiftKey && doc.activeElement === first) { event.preventDefault(); last.focus(); }
                    else if (!event.shiftKey && doc.activeElement === last) { event.preventDefault(); first.focus(); }
                }
            });
            renderCalendar();
            doc.body.append(overlay);
            doc.body.style.overflow = 'hidden';
            (dateField ? query('[aria-pressed="true"]') || query('[data-picker-date]') : hourInput).focus();
        }
        doc.addEventListener('click', event => {
            const trigger = event.target.closest('[data-datetime-picker]');
            if (trigger) { event.preventDefault(); open(trigger); }
        });
        function scheduleUpdated() {
            if (active && (!active.trigger.isConnected || !active.trigger.closest('form')?.isConnected)) close();
            refresh();
        }
        doc.addEventListener('schedule-updated', scheduleUpdated);
        doc.addEventListener('travelplan:schedule-updated', scheduleUpdated);
        doc.addEventListener('travelplan:editor-opened', () => refresh());
        doc.addEventListener('reset', () => doc.defaultView.setTimeout(() => refresh(), 0));
        refresh();
        return {refresh, close};
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {parseDate, timeParts, isoDate, label, commitFields, mount};
    if (typeof document !== 'undefined') window.TravelPlanDateTimePicker = mount(document);
})();
