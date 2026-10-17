(function (root) {
    'use strict';

    function normalize(value) {
        return String(value || '').normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/[\s\p{P}]/gu, '');
    }

    function searchAirports(airports, query, limit = 8) {
        const needle = normalize(query);
        if (!needle) return [];
        return airports.map((airport, order) => {
            const primary = [airport.iata, airport.name?.ko, airport.name?.en, airport.city?.ko, airport.city?.en].map(normalize);
            const secondary = [airport.country?.ko, airport.country?.en, ...(airport.aliases || [])].map(normalize);
            const score = primary[0] === needle ? 0 : primary.some(value => value.startsWith(needle)) ? 1
                : primary.some(value => value.includes(needle)) ? 2 : secondary.some(value => value.startsWith(needle)) ? 3
                : secondary.some(value => value.includes(needle)) ? 4 : 5;
            return {airport, order, score};
        }).filter(match => match.score < 5).sort((a, b) => a.score - b.score || a.order - b.order)
            .slice(0, limit).map(match => match.airport);
    }

    function airportLabel(airport) {
        return `${airport.name.ko || airport.name.en} (${airport.iata})`;
    }

    function mount(doc, options = {}) {
        const win = doc.defaultView;
        const fetchData = options.fetch || win.fetch.bind(win);
        let dataPromise, active = null, timer, revision = 0, nextId = 0, committing = false;
        const composing = new WeakSet();
        const selector = '[data-airport-input]';

        function eligible(input) {
            return input?.matches?.(selector) && !input.disabled && !input.readOnly
                && input.closest('form')?.dataset.editorType === 'FLIGHT';
        }

        function prepare(input) {
            input.setAttribute('role', 'combobox');
            input.setAttribute('aria-autocomplete', 'list');
            input.setAttribute('aria-expanded', 'false');
            input.setAttribute('autocomplete', 'off');
        }

        function close() {
            revision++;
            win.clearTimeout(timer);
            if (!active) return;
            active.input.setAttribute('aria-expanded', 'false');
            active.input.removeAttribute('aria-activedescendant');
            active.input.removeAttribute('aria-controls');
            active.list.remove();
            active = null;
        }

        function position() {
            if (!active) return;
            if (!active.input.isConnected || !eligible(active.input)) return close();
            const rect = active.input.getBoundingClientRect();
            if (!rect.width || !rect.height || rect.bottom <= 0 || rect.top >= win.innerHeight) return close();
            const width = Math.min(rect.width, Math.max(0, win.innerWidth - 16));
            active.list.style.left = `${Math.max(8, Math.min(rect.left, win.innerWidth - width - 8))}px`;
            active.list.style.top = `${rect.bottom + 4}px`;
            active.list.style.width = `${width}px`;
            active.list.style.maxHeight = `${Math.max(0, Math.min(280, win.innerHeight - rect.bottom - 12))}px`;
        }

        function highlight(index) {
            active.index = index;
            Array.from(active.list.children).forEach((option, i) => option.setAttribute('aria-selected', String(i === index)));
            const option = active.list.children[index];
            active.input.setAttribute('aria-activedescendant', option.id);
            option.scrollIntoView?.({block: 'nearest'});
        }

        function choose(index) {
            if (!active || !active.results[index]) return;
            const {input, results} = active;
            input.value = airportLabel(results[index]);
            close();
            committing = true;
            try {
                input.dispatchEvent(new win.Event('input', {bubbles: true}));
                input.dispatchEvent(new win.Event('change', {bubbles: true}));
            } finally { committing = false; }
        }

        async function query(input) {
            close();
            if (!eligible(input) || !normalize(input.value) || composing.has(input)) return;
            prepare(input);
            const request = revision;
            const value = input.value;
            timer = win.setTimeout(async () => {
                try {
                    dataPromise ||= fetchData('/data/airports.json').then(response => {
                        if (!response.ok) throw new Error('Airport data unavailable');
                        return response.json();
                    }).then(data => data.airports);
                    const airports = await dataPromise;
                    if (request !== revision || !input.isConnected || !eligible(input)
                        || doc.activeElement !== input || input.value !== value || composing.has(input)) return;
                    const results = searchAirports(airports, value);
                    if (!results.length) return;
                    const list = doc.createElement('div');
                    list.id = `travel-plan-airports-${++nextId}`;
                    list.className = 'travel-plan-airport-list';
                    list.setAttribute('role', 'listbox');
                    list.setAttribute('aria-label', '공항 검색 결과');
                    results.forEach((airport, index) => {
                        const option = doc.createElement('div');
                        option.id = `${list.id}-${index}`;
                        option.setAttribute('role', 'option');
                        option.setAttribute('aria-selected', 'false');
                        const name = doc.createElement('span');
                        name.className = 'travel-plan-airport-name';
                        name.textContent = airportLabel(airport);
                        const detail = doc.createElement('span');
                        detail.className = 'travel-plan-airport-detail';
                        detail.textContent = [airport.city.ko || airport.city.en, airport.country.ko || airport.country.en,
                            airport.name.en].filter(Boolean).join(' · ');
                        option.append(name, detail);
                        option.addEventListener('pointerdown', event => {
                            event.preventDefault();
                            choose(index);
                        });
                        list.append(option);
                    });
                    active = {input, list, results, index: -1};
                    doc.body.append(list);
                    input.setAttribute('aria-controls', list.id);
                    input.setAttribute('aria-expanded', 'true');
                    position();
                } catch (_) {
                    // Manual entry remains available when the static catalogue cannot load.
                }
            }, 150);
        }

        doc.addEventListener('focusin', event => {
            if (eligible(event.target)) { prepare(event.target); query(event.target); }
        });
        doc.addEventListener('input', event => {
            if (!committing && eligible(event.target)) query(event.target);
        });
        doc.addEventListener('compositionstart', event => {
            if (!eligible(event.target)) return;
            composing.add(event.target);
            close();
        });
        doc.addEventListener('compositionend', event => {
            composing.delete(event.target);
            if (eligible(event.target)) query(event.target);
        });
        // Capture precedes the scheduler's per-input Enter/save and Escape/cancel handlers.
        doc.addEventListener('keydown', event => {
            if (!active || event.target !== active.input || !eligible(event.target)
                || event.isComposing || event.keyCode === 229 || composing.has(event.target)) return;
            if (!['ArrowDown', 'ArrowUp', 'Enter', 'Escape'].includes(event.key)) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            if (event.key === 'Escape') return close();
            if (event.key === 'Enter') return choose(active.index < 0 ? 0 : active.index);
            const length = active.results.length;
            highlight(event.key === 'ArrowDown' ? (active.index + 1) % length
                : (active.index < 0 ? length - 1 : (active.index - 1 + length) % length));
        }, true);
        doc.addEventListener('pointerdown', event => {
            if (active && event.target !== active.input && !active.list.contains(event.target)) close();
            else if (!active && !eligible(event.target)) close();
        }, true);
        doc.addEventListener('focusout', event => {
            if (eligible(event.target)) close();
        });
        doc.addEventListener('reset', close, true);
        ['travelplan:editor-opened', 'travelplan:editor-idle', 'travelplan:schedule-updated'].forEach(type => doc.addEventListener(type, close));
        win.addEventListener('resize', position);
        doc.addEventListener('scroll', position, true);
        return {close};
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = {normalize, searchAirports, airportLabel, mount};
    if (root.document) root.document.addEventListener('DOMContentLoaded', () => mount(root.document));
})(typeof window === 'undefined' ? globalThis : window);
