document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('course-form');
    if (!form) return;

    if (form.dataset.contentEditor !== 'none') {
        window.initQuillEditor(
            '#editor',
            'content-input',
            'course-form',
            form.dataset.initialContentId || undefined
        );
    }
    const searchInput = document.getElementById('destination-search-input');
    const searchButton = document.getElementById('destination-search-button');
    const searchResults = document.getElementById('destination-search-results');
    const selectedList = document.getElementById('selected-destinations');
    const hiddenInputs = document.getElementById('destination-id-inputs');
    const message = document.getElementById('course-write-message');
    const submitButton = document.getElementById('course-submit-button');
    const countryOptions = Array.from(document.querySelectorAll('[data-course-country]'));

    function countryOption(countryId) {
        return countryOptions.find(option => String(option.dataset.countryId) === String(countryId));
    }

    let nextClientId = 0;
    const directTypes = {PLACE: '장소', STAY: '숙소', FOOD: '식당·카페', TRANSPORT: '교통', ETC: '기타'};
    function readInitialItem(element) {
        return {
            id: element.dataset.destinationId ? Number(element.dataset.destinationId) : null,
            clientId: ++nextClientId,
            itemId: element.dataset.itemId || '',
            name: element.dataset.name,
            regionName: element.dataset.regionName,
            locationLabel: element.dataset.locationLabel,
            parentRegionName: element.dataset.parentRegionName,
            countryId: element.dataset.countryId,
            countryName: element.dataset.countryName || countryOption(element.dataset.countryId)?.dataset.countryName || '',
            countryCode: element.dataset.countryCode || countryOption(element.dataset.countryId)?.dataset.countryCode || '',
            thumbnailUrl: element.dataset.thumbnailUrl,
            imageNoDerivatives: element.dataset.imageNoDerivatives === 'true',
            itemType: element.dataset.itemType,
            address: element.dataset.address || '',
            latitude: element.dataset.latitude || '',
            longitude: element.dataset.longitude || '',
            startTime: element.dataset.startTime || '',
            memo: element.dataset.memo || ''
        };
    }
    const days = Array.from(document.querySelectorAll('[data-initial-day]')).map(element => ({
        id: element.dataset.dayId || '',
        title: element.dataset.title || '',
        items: Array.from(element.querySelectorAll('[data-initial-destination]'))
            .map(readInitialItem)
            .filter(item => item.id === null || (Number.isSafeInteger(item.id) && item.id > 0))
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
    const directDraft = {itemType: '', name: '', address: '', latitude: '', longitude: '', startTime: '', memo: '', countryId: '', countryCode: '', countryName: ''};
    const draftView = createDirectFields(directDraft, true);
    draftView.container.className += ' course-direct-draft';
    const directAdd = createControlButton('일정에 추가', false, () => {
        if (days[activeDayIndex].items.length >= 50) {
            showMessage('Day당 여행지는 최대 50곳까지 추가할 수 있습니다.', true); return;
        }
        const error = directItemError(directDraft);
        if (error) { showMessage(error, true); return; }
        days[activeDayIndex].items.push({...directDraft, id: null, clientId: ++nextClientId, name: directDraft.name.trim()});
        Object.keys(directDraft).forEach(key => { directDraft[key] = ''; });
        draftView.reset(); renderSelectedDestinations();
        showMessage('직접 입력한 일정을 추가했습니다.');
    }, 'course-direct-add');
    draftView.container.append(directAdd);
    const modes = document.createElement('div'); modes.className = 'course-add-modes';
    modes.setAttribute('role', 'group'); modes.setAttribute('aria-label', '일정 추가 방식');
    const linkedMode = createControlButton('TripBora 여행지', false, () => setAddMode(false));
    const directMode = createControlButton('직접 추가', false, () => setAddMode(true));
    modes.append(linkedMode, directMode);
    searchPanel.insertBefore(modes, document.getElementById('course-linked-search'));
    searchPanel.insertBefore(draftView.container, document.getElementById('course-search-message'));
    let searchScope = '';
    let regionRequestNumber = 0;
    const geography = document.createElement('div'); geography.className = 'course-search-geography';
    const scopes = document.createElement('div'); scopes.className = 'course-search-scopes';
    scopes.setAttribute('role', 'group'); scopes.setAttribute('aria-label', '여행지 검색 범위');
    const searchCountry = document.createElement('select'); searchCountry.setAttribute('aria-label', '검색 국가');
    const searchRegion = document.createElement('select'); searchRegion.setAttribute('aria-label', '검색 지역');
    const scopeButtons = [];
    function resetSelect(select, label) {
        const option = document.createElement('option'); option.value = ''; option.textContent = label;
        select.replaceChildren(option); select.value = '';
    }
    async function loadSearchRegions(countryId) {
        const version = ++regionRequestNumber;
        resetSelect(searchRegion, '지역 전체'); searchRegion.hidden = !countryId;
        if (!countryId) return;
        searchRegion.disabled = true;
        try {
            const response = await fetch(`/api/search/regions?countryId=${encodeURIComponent(countryId)}`);
            if (!response.ok) throw new Error('지역 조회 실패');
            const regions = await response.json();
            if (version !== regionRequestNumber) return;
            regions.forEach(region => {
                const option = document.createElement('option'); option.value = String(region.id);
                option.textContent = region.regionName; searchRegion.append(option);
            });
            searchRegion.hidden = regions.length === 0;
        } catch (error) {
            if (version === regionRequestNumber) showMessage('지역 목록을 불러오지 못했습니다. 국가 또는 장소명으로 검색해 주세요.', true);
        } finally { if (version === regionRequestNumber) searchRegion.disabled = false; }
    }
    ['', 'domestic', 'overseas'].forEach((value, index) => {
        const control = createControlButton(['전체', '국내', '해외'][index], false, () => {
            searchScope = value;
            scopeButtons.forEach(([scope, button]) => button.setAttribute('aria-pressed', String(scope === value)));
            resetSelect(searchCountry, '국가 전체');
            countryOptions.filter(option => value === 'domestic' ? option.dataset.countryCode === 'KR' : option.dataset.countryCode !== 'KR')
                .forEach(source => {
                    const option = document.createElement('option'); option.value = source.dataset.countryId;
                    option.textContent = source.dataset.countryName; searchCountry.append(option);
                });
            searchCountry.hidden = value !== 'overseas';
            const domestic = countryOptions.find(option => option.dataset.countryCode === 'KR');
            if (value === 'domestic') searchCountry.value = domestic?.dataset.countryId || '';
            loadSearchRegions(value === 'domestic' ? searchCountry.value : '');
            searchDestinations();
        });
        control.setAttribute('aria-pressed', String(value === '')); scopeButtons.push([value, control]); scopes.append(control);
    });
    resetSelect(searchCountry, '국가 전체'); searchCountry.hidden = true;
    resetSelect(searchRegion, '지역 전체'); searchRegion.hidden = true;
    searchCountry.addEventListener('change', () => { loadSearchRegions(searchCountry.value); searchDestinations(); });
    searchRegion.addEventListener('change', searchDestinations);
    geography.append(scopes, searchCountry, searchRegion);
    searchPanel.insertBefore(geography, document.getElementById('course-linked-search'));
    function setAddMode(direct) {
        draftView.container.hidden = !direct;
        geography.hidden = direct;
        const heading = document.getElementById('destination-search-title');
        if (heading) heading.textContent = direct ? '직접 일정 추가' : '여행지 검색';
        [searchInput, searchButton, searchResults].forEach(element => { element.hidden = direct; });
        const searchFields = document.getElementById('course-linked-search');
        if (searchFields) searchFields.hidden = direct;
        linkedMode.setAttribute('aria-pressed', String(!direct)); directMode.setAttribute('aria-pressed', String(direct));
        linkedMode.classList.toggle('is-active', !direct); directMode.classList.toggle('is-active', direct);
    }
    setAddMode(false);

    function directItemError(item) {
        if (!countryOption(item.countryId)) return '일정의 국가를 선택해 주세요.';
        if (!Object.hasOwn(directTypes, item.itemType)) return '일정 유형을 선택해 주세요.';
        if (!item.name?.trim() || item.name.length > 255) return '장소명을 255자 이내로 입력해 주세요.';
        if ((item.address || '').length > 500 || (item.memo || '').length > 1000) return '주소는 500자, 메모는 1,000자 이내로 입력해 주세요.';
        const hasLatitude = item.latitude !== '' && item.latitude != null;
        const hasLongitude = item.longitude !== '' && item.longitude != null;
        if (hasLatitude !== hasLongitude || (hasLatitude && (!Number.isFinite(Number(item.latitude)) || !Number.isFinite(Number(item.longitude)) || Math.abs(Number(item.latitude)) > 90 || Math.abs(Number(item.longitude)) > 180))) return '위도와 경도를 올바른 범위로 함께 설정해 주세요.';
        return '';
    }

    function createDirectFields(item, draft = false, onChange = () => {}) {
        const container = document.createElement('div'); container.className = 'course-direct-fields';
        const inputs = {};
        const types = document.createElement('div'); types.className = 'course-direct-types';
        types.setAttribute('role', 'group'); types.setAttribute('aria-label', '일정 유형');
        const typeButtons = [];
        const refreshTypes = () => typeButtons.forEach(([value, button]) => button.setAttribute('aria-pressed', String(item.itemType === value)));
        Object.entries(directTypes).forEach(([value, title]) => {
            const button = createControlButton(title, false, () => { item.itemType = value; refreshTypes(); onChange(); });
            typeButtons.push([value, button]); types.append(button);
        });
        refreshTypes(); container.append(types);
        const countryLabel = document.createElement('label'); countryLabel.textContent = '국가'; countryLabel.className = 'course-direct-country';
        const countrySelect = document.createElement('select'); countrySelect.dataset.directField = 'countryId';
        countrySelect.setAttribute('aria-required', 'true');
        countrySelect.addEventListener('keydown', event => { if (event.key === 'Enter') event.preventDefault(); });
        const emptyCountry = document.createElement('option'); emptyCountry.value = ''; emptyCountry.textContent = '국가 선택';
        countrySelect.append(emptyCountry);
        countryOptions.forEach(source => {
            const option = document.createElement('option'); option.value = source.dataset.countryId;
            option.textContent = source.dataset.countryName; countrySelect.append(option);
        });
        countrySelect.value = item.countryId || '';
        countryLabel.append(countrySelect); container.append(countryLabel); inputs.countryId = countrySelect;
        const details = document.createElement('div'); details.className = 'course-direct-details';
        details.id = `course-direct-details-${draft ? 'draft' : item.clientId}`; details.hidden = true;
        const toggle = createControlButton('+ 상세정보', false, () => {
            details.hidden = !details.hidden;
            toggle.textContent = details.hidden ? '+ 상세정보' : '− 상세정보';
            toggle.setAttribute('aria-expanded', String(!details.hidden));
        }, 'course-direct-details-toggle');
        toggle.setAttribute('aria-expanded', 'false'); toggle.setAttribute('aria-controls', details.id);
        const definitions = [['name', '장소명', 'text', 255], ['address', '주소 (선택)', 'text', 500],
            ['startTime', '시작 시간 (선택)', 'time'], ['memo', '메모 (선택)', 'textarea', 1000]];
        definitions.forEach(([field, text, type, maxLength]) => {
            const label = document.createElement('label'); label.textContent = text;
            const input = document.createElement(type === 'textarea' ? 'textarea' : 'input');
            if (type !== 'textarea') input.type = type;
            input.dataset.directField = field;
            if (maxLength) input.maxLength = maxLength;
            input.value = item[field] || '';
            if (field === 'name') input.placeholder = '호텔이나 장소명을 입력하세요';
            input.addEventListener('input', () => { item[field] = input.value; onChange(); });
            input.addEventListener('keydown', event => { if (event.key === 'Enter' && type !== 'textarea') event.preventDefault(); });
            label.append(input); (field === 'name' ? container : details).append(label); inputs[field] = input;
        });
        container.append(toggle, details);
        const location = document.createElement('div'); location.className = 'course-direct-location'; details.append(location);
        const locationChanged = () => { inputs.address.value = item.address || ''; onChange(); };
        countrySelect.addEventListener('change', () => {
            if (String(item.countryId || '') !== countrySelect.value) {
                item.latitude = null; item.longitude = null;
                if (item.locationAddress && item.address === item.locationAddress) {
                    item.address = ''; inputs.address.value = '';
                }
                item.locationAddress = null;
            }
            item.countryId = countrySelect.value;
            const selected = countryOption(item.countryId);
            item.countryCode = selected?.dataset.countryCode || '';
            item.countryName = selected?.dataset.countryName || '';
            window.CourseLocation?.attach(location, item, locationChanged);
            onChange();
        });
        window.CourseLocation?.attach(location, item, locationChanged);
        return {container, inputs, reset() {
            Object.entries(inputs).forEach(([field, input]) => { input.value = item[field] || ''; });
            refreshTypes(); details.hidden = true; toggle.textContent = '+ 상세정보'; toggle.setAttribute('aria-expanded', 'false');
            location.replaceChildren(); window.CourseLocation?.attach(location, item, locationChanged);
        }};
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
        if (destination.locationLabel) return destination.locationLabel;
        return [destination.parentRegionName, destination.regionName]
            .filter(value => value && value.trim())
            .join(' ');
    }

    function itemTypeLabel(destination) {
        const type = destination.itemType || destination.type;
        if (type === 'STAY' || type === 'ACCOMMODATION') return '숙소';
        if (['FOOD', 'RESTAURANTS', 'CAFE'].includes(type)) return '식당·카페';
        if (type === 'TRANSPORT') return '교통';
        if (type === 'ETC') return '기타';
        return '장소';
    }

    function appendTypeLabel(info, destination) {
        const type = document.createElement('span');
        type.className = 'course-item-type';
        type.textContent = itemTypeLabel(destination);
        info.append(type);
    }

    function isDestinationSelected(destination) {
        return destination.id != null && days.some(day => day.items.some(item => item.id != null && Number(item.id) === Number(destination.id)));
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
        if (destination.id === null) item.classList.add('is-direct');
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
        edit.id = `course-item-edit-${destination.clientId}`;
        edit.setAttribute('aria-label', `${destination.name || '여행지'} 상세 편집`);
        const editor = document.createElement('div');
        editor.className = 'course-item-inline-editor';
        editor.id = `course-item-editor-${destination.clientId}`;
        editor.hidden = true;
        editor.setAttribute('role', 'region');
        editor.setAttribute('aria-label', `${destination.name || '여행지'} 방문 정보`);
        edit.setAttribute('aria-controls', editor.id);
        edit.setAttribute('aria-expanded', 'false');
        item.append(order);
        if (destination.id !== null) item.append(image);
        item.append(info, edit, editor);
        item.addEventListener('click', event => {
            if (editor.contains(event.target) || event.target.closest('button, input, textarea, select, a, label, summary')) return;
            toggleItem(destination);
        });
        return {item, order, name, region, preview, edit, editor};
    }

    function openSearch() {
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
        view.name.textContent = destination.name || '이름 없는 여행지';
        view.region.textContent = [itemTypeLabel(destination), destination.id === null ? (destination.address || destination.countryName) : regionText(destination), destination.startTime].filter(Boolean).join(' · ');
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
        if (destination.id === null) {
            fields.append(createDirectFields(destination, false, () => { updateItemSummary(destination); syncPayload(); }).container);
        }
        const timeRange = document.createElement('div'); timeRange.className = 'course-item-time-range';
        ['startTime'].forEach(field => {
            const label = document.createElement('label'); label.textContent = '시작 시간 (선택)';
            const input = document.createElement('input'); input.type = 'time';
            input.id = `course-item-start-time-${destination.clientId}`;
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
        if (destination.id !== null) fields.append(timeRange, memoLabel, help);
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
                if (item.id === null) {
                    ['countryId', 'itemType', 'address', 'latitude', 'longitude'].forEach(field => addInput(`${itemPrefix}.${field}`, item[field]));
                    addInput(`${itemPrefix}.placeName`, item.name);
                }
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

        days[activeDayIndex].items.push({...destination, clientId: ++nextClientId, startTime: '', memo: ''});
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
        const requestNumber = ++searchRequestNumber;
        activeSearchController?.abort();
        activeSearchController = new AbortController();
        if (!searchInput.value.trim() && !searchScope && !searchCountry.value && !searchRegion.value) {
            latestResults = []; searchResults.replaceChildren(); searchAddButtons = [];
            showMessage('지역을 선택하거나 장소명을 검색해 주세요.'); return;
        }
        showMessage('여행지를 검색하고 있습니다.');
        // 새 범위의 응답을 기다리는 동안 이전 지역 결과를 추가하지 않게 한다.
        latestResults = []; searchResults.replaceChildren(); searchAddButtons = [];

        try {
            const params = new URLSearchParams({
                q: searchInput.value.trim(),
                size: '20'
            });
            if (searchScope) params.set('scope', searchScope);
            if (searchCountry.value) params.set('countryId', searchCountry.value);
            if (searchRegion.value) params.set('regionId', searchRegion.value);
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

    form.addEventListener('submit', event => {
        submitButton.disabled = false;
        if (event.defaultPrevented) return;

        if (days.some(day => day.items.length === 0)) {
            event.preventDefault();
            selectDay(days.findIndex(day => day.items.length === 0));
            showMessage('각 Day에 여행지를 한 곳 이상 추가해 주세요.', true);
            addDestinationButton?.focus();
            return;
        }
        for (let dayIndex = 0; dayIndex < days.length; dayIndex++) {
            const invalid = days[dayIndex].items.find(item => item.id === null && directItemError(item));
            if (invalid) {
                event.preventDefault(); selectDay(dayIndex); editingItem = invalid; renderSelectedDestinations();
                showMessage(directItemError(invalid), true); return;
            }
        }
        syncPayload();
        submitButton.disabled = true;
    });

    window.addEventListener('pageshow', () => {
        submitButton.disabled = false;
    });

    renderSelectedDestinations();
});
