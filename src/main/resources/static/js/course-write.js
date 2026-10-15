document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('course-form');
    if (!form) return;

    window.initQuillEditor(
        '#editor',
        'content-input',
        'course-form',
        form.dataset.initialContentId || undefined
    );
    const searchInput = document.getElementById('destination-search-input');
    const searchButton = document.getElementById('destination-search-button');
    const searchResults = document.getElementById('destination-search-results');
    const selectedList = document.getElementById('selected-destinations');
    const hiddenInputs = document.getElementById('destination-id-inputs');
    const message = document.getElementById('course-write-message');
    const submitButton = document.getElementById('course-submit-button');
    const countryIdInput = document.getElementById('course-country-id');
    const countryHelper = document.getElementById('course-country-helper');
    const scopeButtons = Array.from(document.querySelectorAll('[data-country-scope]'));
    const overseasCountryField = document.getElementById('overseas-country-field');
    const overseasCountryInput = document.getElementById('overseas-country-input');
    const countryListbox = document.getElementById('overseas-country-listbox');
    const countryOptions = Array.from(document.querySelectorAll('[data-country-id][role="option"]'));
    const countryOptionEmpty = document.getElementById('country-option-empty');
    const useCountrySelection = form.dataset.countryRestriction === 'true' && countryIdInput !== null;

    function readInitialItem(element) {
        return {
            id: Number(element.dataset.destinationId),
            name: element.dataset.name,
            regionName: element.dataset.regionName,
            parentRegionName: element.dataset.parentRegionName,
            countryId: element.dataset.countryId,
            countryName: element.dataset.countryName,
            thumbnailUrl: element.dataset.thumbnailUrl,
            imageNoDerivatives: element.dataset.imageNoDerivatives === 'true',
            itemType: element.dataset.itemType,
            startTime: element.dataset.startTime || '',
            memo: element.dataset.memo || ''
        };
    }
    const days = Array.from(document.querySelectorAll('[data-initial-day]')).map(element => ({
        id: element.dataset.dayId || '',
        title: element.dataset.title || '',
        items: Array.from(element.querySelectorAll('[data-initial-destination]'))
            .map(readInitialItem)
            .filter(item => Number.isSafeInteger(item.id) && item.id > 0)
    }));
    if (days.length === 0) days.push({title: '', items: []});
    const dayTabs = document.getElementById('course-day-tabs');
    const addDayButton = document.getElementById('course-add-day');
    const searchPanel = document.getElementById('course-search-panel');
    const searchTarget = document.getElementById('course-search-target');
    const searchMessage = document.getElementById('course-search-message');
    // Day/Item DOM은 접거나 탭을 바꿔도 보존한다. 전송값은 기존 days 상태에서 생성한다.
    const dayViews = new Map();
    const itemViews = new Map();
    let activeDayIndex = 0;
    let editingItem = null;
    let addDestinationButton;
    let latestResults = [];
    let searchAddButtons = [];
    let debounceTimer;
    let activeSearchController;
    let searchRequestNumber = 0;
    let activeCountryScope = '';
    let activeCountryId = countryIdInput?.value || '';
    let activeCountryName = form.dataset.initialCountryName || '';
    let countryCombobox;

    function countryIdOf(destination) {
        const countryId = Number(destination?.countryId);
        return Number.isSafeInteger(countryId) && countryId > 0 ? countryId : null;
    }

    function selectedCourseCountryId() {
        const countryId = Number(countryIdInput?.value);
        return Number.isSafeInteger(countryId) && countryId > 0 ? countryId : null;
    }

    function updateCountrySearchState() {
        if (!useCountrySelection) return;
        const countrySelected = selectedCourseCountryId() !== null;
        searchInput.disabled = !countrySelected;
        searchButton.disabled = !countrySelected;
        countryHelper.hidden = countrySelected;
        if (!countrySelected) {
            activeSearchController?.abort();
            latestResults = [];
            searchResults.replaceChildren();
        }
    }

    function applyCountrySelection(scope, countryId, countryName) {
        activeCountryScope = scope;
        activeCountryId = countryId ? String(countryId) : '';
        activeCountryName = countryName || '';
        countryIdInput.value = activeCountryId;

        scopeButtons.forEach(button => {
            const active = button.dataset.countryScope === scope;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-pressed', String(active));
        });

        if (overseasCountryField) {
            overseasCountryField.hidden = scope !== 'overseas';
        }
        countryCombobox?.setSelected(activeCountryId, activeCountryName, scope === 'overseas');

        latestResults = [];
        searchResults.replaceChildren();
        updateCountrySearchState();
        if (selectedCourseCountryId() !== null && !searchPanel.hidden) {
            searchDestinations();
        } else if (selectedCourseCountryId() === null) {
            showMessage('해외 국가를 선택해 주세요.');
        }
    }

    function requestCountryChange(scope, countryId, countryName) {
        const nextCountryId = countryId ? String(countryId) : '';
        if (activeCountryScope === scope && activeCountryId === nextCountryId) return true;

        if (days.some(day => day.items.length > 0)) {
            const confirmed = window.confirm(
                '국가를 변경하면 현재 선택한 여행지가 모두 제거됩니다. 변경하시겠습니까?'
            );
            if (!confirmed) return false;
            days.forEach(day => day.items.splice(0, day.items.length));
            editingItem = null;
            renderSelectedDestinations();
        }

        applyCountrySelection(scope, nextCountryId, countryName);
        return true;
    }

    function showMessage(text, isError = false) {
        message.textContent = text;
        message.classList.toggle('is-error', isError);
        searchMessage.textContent = text;
        searchMessage.classList.toggle('is-error', isError);
    }

    // 공공누리 제3유형(변경금지)은 잘라 보이지 않게 원본 비율 전체를 담는다.
    function markNoDerivatives(image, destination) {
        if (destination.imageNoDerivatives) image.classList.add('is-no-derivatives');
    }

    function createImage(url, alt) {
        const image = document.createElement('img');
        image.src = url || '/images/default.png';
        image.alt = alt;
        image.loading = 'lazy';
        image.addEventListener('error', () => {
            if (!image.src.endsWith('/images/default.png')) {
                image.src = '/images/default.png';
            }
        });
        return image;
    }

    function regionText(destination) {
        return [destination.parentRegionName, destination.regionName]
            .filter(value => value && value.trim())
            .join(' ');
    }

    function itemTypeLabel(destination) {
        const type = destination.itemType || destination.type;
        if (type === 'STAY' || type === 'ACCOMMODATION') return '숙소';
        if (['FOOD', 'RESTAURANTS', 'CAFE'].includes(type)) return '음식';
        return '장소';
    }

    function appendTypeLabel(info, destination) {
        const type = document.createElement('span');
        type.className = 'course-item-type';
        type.textContent = itemTypeLabel(destination);
        info.append(type);
    }

    function isDestinationSelected(destination) {
        return days.some(day => day.items.some(item => Number(item.id) === Number(destination.id)));
    }

    function renderSearchResults(results) {
        const scrollTop = searchResults.scrollTop;
        searchResults.replaceChildren();
        searchAddButtons = [];
        if (results.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'picker-empty';
            empty.textContent = '검색 결과가 없습니다.';
            searchResults.append(empty);
            return;
        }

        results.forEach(destination => {
            const card = document.createElement('article');
            card.className = 'destination-result-card';

            const image = createImage(destination.thumbnailUrl, `${destination.name || '여행지'} 썸네일`);
            image.className = 'destination-card-image';
            markNoDerivatives(image, destination);

            const info = document.createElement('div');
            info.className = 'destination-card-info';
            appendTypeLabel(info, destination);
            const name = document.createElement('h3');
            name.textContent = destination.name || '이름 없는 여행지';
            info.append(name);

            const region = regionText(destination);
            if (region) {
                const regionElement = document.createElement('p');
                regionElement.className = 'destination-card-region';
                regionElement.textContent = `지역 ${region}`;
                info.append(regionElement);
            }
            if (destination.shortDescription && destination.shortDescription.trim()) {
                const description = document.createElement('p');
                description.className = 'destination-card-description';
                description.textContent = destination.shortDescription;
                info.append(description);
            }

            const addButton = document.createElement('button');
            addButton.type = 'button';
            addButton.className = 'destination-add-button';
            const selected = isDestinationSelected(destination);
            addButton.textContent = selected ? '추가됨' : '추가';
            addButton.disabled = selected || days[activeDayIndex].items.length >= 50;
            addButton.addEventListener('click', () => addDestination(destination));
            searchAddButtons.push(addButton);

            card.append(image, info, addButton);
            searchResults.append(card);
        });
        searchResults.scrollTop = scrollTop;
    }

    function renderDayTabs() {
        dayTabs.replaceChildren();
        days.forEach((day, index) => {
            const tab = createControlButton(`Day ${index + 1} · ${day.items.length}곳`, false,
                () => selectDay(index, true), 'course-day-tab');
            tab.id = `course-day-tab-${index}`;
            tab.setAttribute('role', 'tab');
            tab.setAttribute('aria-controls', `course-day-panel-${index}`);
            tab.setAttribute('aria-selected', String(index === activeDayIndex));
            tab.tabIndex = index === activeDayIndex ? 0 : -1;
            tab.addEventListener('keydown', event => {
                let next = index;
                if (event.key === 'ArrowRight') next = (index + 1) % days.length;
                else if (event.key === 'ArrowLeft') next = (index + days.length - 1) % days.length;
                else if (event.key === 'Home') next = 0;
                else if (event.key === 'End') next = days.length - 1;
                else return;
                event.preventDefault();
                selectDay(next, true);
            });
            dayTabs.append(tab);
        });
    }

    function selectDay(index, focusTab = false) {
        if (!days[index]) return;
        closeSearch(false);
        editingItem = null;
        activeDayIndex = index;
        renderSelectedDestinations();
        const tab = document.getElementById(`course-day-tab-${index}`);
        if (focusTab) tab?.focus();
        tab?.scrollIntoView({block: 'nearest', inline: 'nearest'});
    }

    function renderSelectedDestinations() {
        renderDayTabs();
        const total = days.reduce((count, day) => count + day.items.length, 0);
        document.getElementById('selected-course-count').textContent = `${days.length}일 · ${total}곳`;
        for (const [day, view] of dayViews) {
            if (!days.includes(day)) {
                view.section.remove();
                dayViews.delete(day);
            }
        }
        for (const destination of itemViews.keys()) {
            if (!days.some(day => day.items.includes(destination))) itemViews.delete(destination);
        }
        days.forEach((day, dayIndex) => {
            if (!dayViews.has(day)) dayViews.set(day, createDayView(day));
            const view = dayViews.get(day);
            view.section.id = `course-day-panel-${dayIndex}`;
            view.section.hidden = dayIndex !== activeDayIndex;
            view.section.setAttribute('aria-labelledby', `course-day-tab-${dayIndex}`);
            view.label.textContent = `Day ${dayIndex + 1}`;
            view.label.htmlFor = `day-title-${dayIndex}`;
            view.title.id = view.label.htmlFor;
            view.remove.disabled = days.length === 1;
            view.summary.setAttribute('aria-label', `Day ${dayIndex + 1} 더 보기`);
            const rows = day.items.map((destination, index) => {
                if (!itemViews.has(destination)) itemViews.set(destination, createItemView(destination));
                const row = itemViews.get(destination);
                row.order.textContent = String(index + 1);
                updateItemSummary(destination);
                updateItemEditor(destination, day, index);
                return row.item;
            });
            if (!rows.length) {
                const empty = document.createElement('li');
                empty.className = 'picker-empty selected-empty';
                empty.textContent = '아직 정한 장소가 없어요. 첫 여행지를 추가해 보세요.';
                rows.push(empty);
            }
            view.list.replaceChildren(...rows);
            if (view.section.parentElement !== selectedList) selectedList.append(view.section);
            if (dayIndex === activeDayIndex) addDestinationButton = view.add;
        });
        dayViews.get(days[activeDayIndex]).section.append(searchPanel);
        addDestinationButton.setAttribute('aria-expanded', String(!searchPanel.hidden));
        addDayButton.disabled = days.length >= 90;
        searchTarget.textContent = `Day ${activeDayIndex + 1}에 추가 · ${days[activeDayIndex].items.length}/50곳`;
        syncPayload(); renderSearchResults(latestResults);
    }

    function createDayView(day) {
        const section = document.createElement('section');
        section.className = 'course-day-editor';
        section.setAttribute('role', 'tabpanel');
        const heading = document.createElement('div');
        heading.className = 'course-day-heading';
        const label = document.createElement('label');
        const title = document.createElement('input');
        title.type = 'text'; title.maxLength = 100;
        title.placeholder = '오늘의 제목을 적어보세요 (선택)'; title.value = day.title;
        title.addEventListener('input', () => { day.title = title.value; syncPayload(); });
        const menu = document.createElement('details');
        menu.className = 'course-day-menu';
        const summary = document.createElement('summary');
        summary.textContent = '⋯';
        const remove = createControlButton('Day 삭제', days.length === 1, () => {
            if (days.length === 1) return;
            if (!window.confirm('이 Day와 포함된 여행지를 삭제하시겠습니까?')) return;
            const dayIndex = days.indexOf(day);
            days.splice(dayIndex, 1);
            selectDay(Math.min(dayIndex, days.length - 1), true);
        }, 'remove');
        menu.append(summary, remove);
        heading.append(label, title, menu);
        const list = document.createElement('ol');
        list.className = 'selected-destinations course-day-body';
        const add = createControlButton('+ 여행지 추가', false, () => {
            if (searchPanel.hidden) openSearch();
            else closeSearch();
        }, 'course-add-destination');
        add.setAttribute('aria-controls', 'course-search-panel');
        add.setAttribute('aria-expanded', 'false');
        section.append(heading, list, add);
        return {section, label, title, summary, remove, list, add};
    }

    function createItemView(destination) {
        const item = document.createElement('li');
        item.className = 'selected-destination-card';
        const order = document.createElement('span');
        order.className = 'selected-order';
        const image = createImage(destination.thumbnailUrl, `${destination.name || '여행지'} 썸네일`);
        image.className = 'selected-card-image'; markNoDerivatives(image, destination);
        const info = document.createElement('div'); info.className = 'selected-card-info';
        const name = document.createElement('strong'); name.textContent = destination.name || '이름 없는 여행지';
        const region = document.createElement('span'); region.className = 'selected-card-region';
        info.append(name, region);
        const preview = document.createElement('p');
        preview.className = 'course-item-preview';
        info.append(preview);
        const edit = createControlButton('⋯', false, () => toggleItem(destination), 'course-item-edit');
        edit.id = `course-item-edit-${destination.id}`;
        edit.setAttribute('aria-label', `${destination.name || '여행지'} 상세 편집`);
        const editor = document.createElement('div');
        editor.className = 'course-item-inline-editor';
        editor.id = `course-item-editor-${destination.id}`;
        editor.hidden = true;
        editor.setAttribute('role', 'region');
        editor.setAttribute('aria-label', `${destination.name || '여행지'} 방문 정보`);
        edit.setAttribute('aria-controls', editor.id);
        edit.setAttribute('aria-expanded', 'false');
        item.append(order, image, info, edit, editor);
        item.addEventListener('click', event => {
            if (editor.contains(event.target) || event.target.closest('button, input, textarea, select, a, label, summary')) return;
            toggleItem(destination);
        });
        return {item, order, region, preview, edit, editor};
    }

    function openSearch() {
        if (useCountrySelection && selectedCourseCountryId() === null) {
            showMessage('코스 국가를 먼저 선택해 주세요.', true);
            (activeCountryScope === 'overseas' ? overseasCountryInput : scopeButtons[0])?.focus();
            return;
        }
        searchPanel.hidden = false;
        addDestinationButton.setAttribute('aria-expanded', 'true');
        searchDestinations();
    }

    function closeSearch(focusButton = true) {
        searchPanel.hidden = true;
        window.clearTimeout(debounceTimer);
        activeSearchController?.abort();
        searchRequestNumber++;
        addDestinationButton?.setAttribute('aria-expanded', 'false');
        if (focusButton) addDestinationButton?.focus();
    }

    function toggleItem(destination) {
        editingItem = editingItem === destination ? null : destination;
        days[activeDayIndex].items.forEach((item, index) => updateItemEditor(item, days[activeDayIndex], index));
    }

    function updateItemSummary(destination) {
        const view = itemViews.get(destination);
        view.region.textContent = [itemTypeLabel(destination), regionText(destination), destination.startTime].filter(Boolean).join(' · ');
        view.preview.textContent = destination.memo || '';
        view.preview.hidden = !destination.memo;
    }

    function updateItemEditor(destination, day, index) {
        const view = itemViews.get(destination);
        const expanded = editingItem === destination;
        view.editor.hidden = !expanded;
        view.edit.textContent = expanded ? '⌃' : '⋯';
        view.edit.setAttribute('aria-expanded', String(expanded));
        view.item.classList.toggle('is-editing', expanded);
        if (expanded && !view.controls) createItemEditor(destination, view);
        if (!view.controls) return;
        view.controls.up.disabled = index === 0;
        view.controls.down.disabled = index === day.items.length - 1;
        const options = days.map((otherDay, otherIndex) => {
            const option = document.createElement('option'); option.value = String(otherIndex);
            option.textContent = `Day ${otherIndex + 1}${otherDay.title ? ` · ${otherDay.title}` : ''}`;
            option.disabled = otherDay !== day && otherDay.items.length >= 50;
            return option;
        });
        view.controls.moveSelect.replaceChildren(...options);
        view.controls.moveSelect.value = String(days.indexOf(day));
        view.controls.moveSelect.disabled = days.length === 1;
    }

    function createItemEditor(destination, view) {
        const fields = document.createElement('div'); fields.className = 'course-item-fields';
        const timeRange = document.createElement('div'); timeRange.className = 'course-item-time-range';
        ['startTime'].forEach(field => {
            const label = document.createElement('label'); label.textContent = '시작 시간 (선택)';
            const input = document.createElement('input'); input.type = 'time';
            input.id = `course-item-start-time-${destination.id}`;
            input.step = '60'; input.value = destination[field] || '';
            // 기존 form 밖 편집과 같이 Enter로 코스 저장이 실행되지 않게 한다.
            input.addEventListener('keydown', event => {
                if (event.key === 'Enter') event.preventDefault();
            });
            input.addEventListener('input', () => { destination[field] = input.value; updateItemSummary(destination); syncPayload(); });
            label.append(input); timeRange.append(label);
        });
        const memoLabel = document.createElement('label'); memoLabel.className = 'course-item-memo-field'; memoLabel.textContent = '방문 메모 (선택)';
        const input = document.createElement('textarea'); input.maxLength = 1000; input.rows = 3;
        input.placeholder = '예약 정보나 기억할 내용을 적어보세요 (최대 1,000자)'; input.value = destination.memo || '';
        input.addEventListener('input', () => { destination.memo = input.value; updateItemSummary(destination); syncPayload(); });
        memoLabel.append(input);
        const help = document.createElement('p'); help.className = 'course-field-help';
        help.textContent = '입력한 내용은 바로 반영됩니다. 시작 시간은 비워 둘 수 있어요.';
        fields.append(timeRange, memoLabel, help);
        const controls = document.createElement('div'); controls.className = 'course-item-order-controls';
        const moveOrder = offset => {
            const dayIndex = days.findIndex(day => day.items.includes(destination));
            moveDestination(dayIndex, days[dayIndex].items.indexOf(destination), offset);
            const control = offset < 0 ? up : down;
            (control.disabled ? (offset < 0 ? down : up) : control).focus();
        };
        const up = createControlButton('위로', false, () => moveOrder(-1));
        const down = createControlButton('아래로', false, () => moveOrder(1));
        controls.append(up, down);
        const moveLabel = document.createElement('label'); moveLabel.className = 'course-item-move-field'; moveLabel.textContent = '다른 Day로 이동';
        const moveSelect = document.createElement('select'); moveSelect.setAttribute('aria-label', '이동할 Day');
        moveSelect.addEventListener('change', () => {
            const day = days.find(day => day.items.includes(destination));
            const targetIndex = Number(moveSelect.value); const target = days[targetIndex];
            if (!target || target === day || target.items.length >= 50) return;
            target.items.push(day.items.splice(day.items.indexOf(destination), 1)[0]);
            selectDay(targetIndex, true);
            showMessage(`Day ${targetIndex + 1}의 마지막 순서로 이동했습니다.`);
        });
        moveLabel.append(moveSelect);
        const remove = createControlButton('일정에서 제거', false, () => {
            const dayIndex = days.findIndex(day => day.items.includes(destination));
            removeDestination(dayIndex, days[dayIndex].items.indexOf(destination));
            addDestinationButton?.focus();
        }, 'remove');
        const close = createControlButton('편집 닫기', false, () => {
            toggleItem(destination);
            view.edit.focus();
        });
        const actions = document.createElement('div'); actions.className = 'course-item-inline-actions';
        actions.append(remove, close);
        view.editor.append(fields, controls, moveLabel, actions);
        view.controls = {up, down, moveSelect};
    }

    function syncPayload() {
        hiddenInputs.replaceChildren();
        function addInput(name, value) {
            const hidden = document.createElement('input');
            hidden.type = 'hidden';
            hidden.name = name;
            hidden.value = String(value ?? '');
            hiddenInputs.append(hidden);
        }
        days.forEach((day, dayIndex) => {
            const prefix = `days[${dayIndex}]`;
            if (day.id) addInput(`${prefix}.id`, day.id);
            addInput(`${prefix}.dayNumber`, dayIndex + 1);
            addInput(`${prefix}.title`, day.title);
            day.items.forEach((item, itemIndex) => {
                const itemPrefix = `${prefix}.items[${itemIndex}]`;
                addInput(`${itemPrefix}.destinationId`, item.id);
                addInput(`${itemPrefix}.displayOrder`, itemIndex + 1);
                ['startTime', 'memo'].forEach(field =>
                    addInput(`${itemPrefix}.${field}`, item[field]));
            });
        });
    }

    function createControlButton(label, disabled, action, extraClass = '') {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.setAttribute('aria-label', label);
        button.title = label;
        const mobileSymbol = {'위로': '↑', '아래로': '↓', '제거': '×'}[label];
        if (mobileSymbol) button.dataset.mobileSymbol = mobileSymbol;
        button.disabled = disabled;
        button.className = `order-control ${extraClass}`.trim();
        button.addEventListener('click', action);
        return button;
    }

    function addDestination(destination) {
        if (isDestinationSelected(destination)) {
            showMessage('이미 선택한 여행지입니다.', true);
            return;
        }
        if (days[activeDayIndex].items.length >= 50) {
            showMessage('Day당 여행지는 최대 50곳까지 추가할 수 있습니다.', true);
            return;
        }

        if (useCountrySelection) {
            const selectedCountryId = selectedCourseCountryId();
            if (selectedCountryId === null) {
                showMessage('코스 국가를 먼저 선택해 주세요.', true);
                (activeCountryScope === 'overseas' ? overseasCountryInput : scopeButtons[0])?.focus();
                return;
            }
            const destinationCountryId = countryIdOf(destination);
            if (destinationCountryId === null) {
                showMessage('여행지의 국가 정보를 확인할 수 없습니다.', true);
                return;
            }
            if (selectedCountryId !== destinationCountryId) {
                showMessage('선택한 국가와 여행지의 국가가 일치하지 않습니다.', true);
                return;
            }
        }

        days[activeDayIndex].items.push({...destination, startTime: '', memo: ''});
        showMessage(`${destination.name || '여행지'}을(를) 코스에 추가했습니다.`);
        renderSelectedDestinations();
        const resultIndex = latestResults.findIndex(result => Number(result.id) === Number(destination.id));
        const next = [...searchAddButtons.slice(resultIndex + 1), ...searchAddButtons.slice(0, resultIndex + 1)]
            .find(button => !button.disabled);
        (next || searchInput).focus();
    }

    function moveDestination(dayIndex, index, offset) {
        const selectedDestinations = days[dayIndex].items;
        const targetIndex = index + offset;
        if (targetIndex < 0 || targetIndex >= selectedDestinations.length) return;
        [selectedDestinations[index], selectedDestinations[targetIndex]] =
            [selectedDestinations[targetIndex], selectedDestinations[index]];
        renderSelectedDestinations();
    }

    function removeDestination(dayIndex, index) {
        const [removed] = days[dayIndex].items.splice(index, 1);
        if (editingItem === removed) editingItem = null;
        showMessage(`${removed.name || '여행지'}을(를) 코스에서 제거했습니다.`);
        renderSelectedDestinations();
    }

    async function searchDestinations() {
        const countryId = selectedCourseCountryId();
        if (useCountrySelection && countryId === null) {
            updateCountrySearchState();
            showMessage('코스 국가를 먼저 선택해 주세요.', true);
            return;
        }

        const requestNumber = ++searchRequestNumber;
        activeSearchController?.abort();
        activeSearchController = new AbortController();
        showMessage('여행지를 검색하고 있습니다.');

        try {
            const params = new URLSearchParams({
                q: searchInput.value.trim(),
                size: '20'
            });
            if (useCountrySelection) {
                params.set('countryId', String(countryId));
            }
            const response = await fetch(
                `/api/search?${params.toString()}`,
                {signal: activeSearchController.signal, headers: {'Accept': 'application/json'}}
            );
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const results = await response.json();
            if (requestNumber !== searchRequestNumber) return;
            latestResults = Array.isArray(results) ? results : [];
            renderSearchResults(latestResults);
            showMessage(`${latestResults.length}개의 여행지를 찾았습니다.`);
        } catch (error) {
            if (error.name === 'AbortError') return;
            if (requestNumber !== searchRequestNumber) return;
            latestResults = [];
            renderSearchResults(latestResults);
            showMessage('여행지 검색 중 오류가 발생했습니다.', true);
        }
    }

    addDayButton.addEventListener('click', () => {
        if (days.length >= 90) return;
        days.push({title: '', items: []});
        selectDay(days.length - 1);
        document.getElementById(`day-title-${activeDayIndex}`)?.focus();
    });

    document.getElementById('course-search-close').addEventListener('click', () => closeSearch());

    searchInput.addEventListener('input', () => {
        window.clearTimeout(debounceTimer);
        debounceTimer = window.setTimeout(searchDestinations, 250);
    });
    searchInput.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            window.clearTimeout(debounceTimer);
            searchDestinations();
        }
    });
    searchButton.addEventListener('click', () => {
        window.clearTimeout(debounceTimer);
        searchDestinations();
    });

    countryCombobox = window.CountryCombobox?.init({
        root: overseasCountryInput?.closest('.country-combobox'),
        input: overseasCountryInput,
        listbox: countryListbox,
        options: countryOptions,
        empty: countryOptionEmpty,
        isDisabled: () => overseasCountryField?.hidden,
        getSelectedName: () => activeCountryScope === 'overseas' ? activeCountryName : '',
        onSelect: (countryId, countryName) => requestCountryChange('overseas', countryId, countryName)
    });

    scopeButtons.forEach(button => {
        button.addEventListener('click', () => {
            const scope = button.dataset.countryScope;
            if (scope === 'domestic') {
                requestCountryChange(scope, button.dataset.countryId, button.dataset.countryName);
                return;
            }
            if (scope === 'overseas' && activeCountryScope === 'overseas') {
                overseasCountryInput?.focus();
                countryCombobox?.filter();
                return;
            }
            if (scope === 'overseas' && requestCountryChange(scope, '', '')) {
                overseasCountryInput?.focus();
                countryCombobox?.filter();
            }
        });
    });

    form.addEventListener('submit', event => {
        submitButton.disabled = false;
        if (event.defaultPrevented) return;

        if (useCountrySelection && selectedCourseCountryId() === null) {
            event.preventDefault();
            showMessage('코스 국가를 선택해 주세요.', true);
            (activeCountryScope === 'overseas' ? overseasCountryInput : scopeButtons[0])?.focus();
            return;
        }

        if (days.some(day => day.items.length === 0)) {
            event.preventDefault();
            selectDay(days.findIndex(day => day.items.length === 0));
            showMessage('각 Day에 여행지를 한 곳 이상 추가해 주세요.', true);
            addDestinationButton?.focus();
            return;
        }
        syncPayload();
        submitButton.disabled = true;
    });

    window.addEventListener('pageshow', () => {
        submitButton.disabled = false;
    });

    renderSelectedDestinations();
    const initialCountryId = selectedCourseCountryId();
    if (useCountrySelection && initialCountryId !== null) {
        const domesticCountryButton = scopeButtons.find(button =>
            button.dataset.countryScope === 'domestic'
            && button.dataset.countryId === String(initialCountryId)
        );
        applyCountrySelection(
            domesticCountryButton ? 'domestic' : 'overseas',
            String(initialCountryId),
            activeCountryName || domesticCountryButton?.dataset.countryName || ''
        );
    } else if (!useCountrySelection) {
        updateCountrySearchState();
    } else {
        updateCountrySearchState();
        showMessage('코스 국가를 먼저 선택해 주세요.');
    }
});
