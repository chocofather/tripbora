document.addEventListener('DOMContentLoaded', () => {
    const panel = document.querySelector('[data-admin-festival-autofill]');
    const form = document.getElementById('festival-create-form');
    if (!panel || !form) return;

    // 한국어 원본 편집기 초기화는 /js/admin-festival-form.js 가 등록·수정 양쪽에서 맡는다.
    // 여기서는 TourAPI 자동입력만 다룬다. (등록 화면 전용)
    const scope = document.getElementById('festival-scope');
    const title = document.getElementById('festival-title');
    const category = document.getElementById('festival-category');
    const eventStartDate = document.getElementById('festival-start-date');
    const eventEndDate = document.getElementById('festival-end-date');
    const eventPlace = document.getElementById('festival-event-place');
    const address = document.getElementById('festival-address');
    const playTime = document.getElementById('festival-play-time');
    const useTime = document.getElementById('festival-use-time');
    const sponsor1 = document.getElementById('festival-sponsor1');
    const sponsor1Tel = document.getElementById('festival-sponsor1-tel');
    const sponsor2 = document.getElementById('festival-sponsor2');
    const sponsor2Tel = document.getElementById('festival-sponsor2-tel');
    const contactTel = document.getElementById('festival-contact-tel');
    const homepageUrl = document.getElementById('festival-homepage-url');
    const ktoFestivalContentId = document.getElementById('kto-festival-content-id');
    const thumbnailSelection = document.getElementById('kto-festival-thumbnail-selection');
    const searchModeButtons = panel.querySelectorAll('[data-festival-search-mode]');
    const searchPanels = panel.querySelectorAll('[data-festival-search-panel]');
    const keyword = panel.querySelector('[data-festival-search-keyword]');
    const startDate = panel.querySelector('[data-festival-search-start-date]');
    const endDate = panel.querySelector('[data-festival-search-end-date]');
    const keywordSearchButton = panel.querySelector('[data-festival-keyword-search-button]');
    const periodSearchButton = panel.querySelector('[data-festival-period-search-button]');
    const periodYear = panel.querySelector('[data-festival-period-year]');
    const monthButtons = panel.querySelectorAll('[data-festival-month]');
    const directPeriodToggle = panel.querySelector('[data-festival-direct-period-toggle]');
    const directPeriodPanel = panel.querySelector('[data-festival-direct-period-panel]');
    const status = panel.querySelector('[data-festival-status]');
    const results = panel.querySelector('[data-festival-results]');
    const pagination = panel.querySelector('[data-festival-pagination]');
    const unregisteredOnly = panel.querySelector('[data-festival-unregistered-only]');
    const imagePicker = panel.querySelector('[data-festival-image-picker]');
    const imagePickerStatus = panel.querySelector('[data-festival-image-picker-status]');
    const imagePickerItems = panel.querySelector('[data-festival-image-picker-items]');
    const editorElement = document.getElementById('festival-editor');

    if (!scope || !title || !category || !eventStartDate || !eventEndDate || !eventPlace || !address
        || !playTime || !useTime || !sponsor1 || !sponsor1Tel || !sponsor2 || !sponsor2Tel || !contactTel
        || !homepageUrl || !ktoFestivalContentId || !thumbnailSelection || !keyword || !startDate || !endDate || !keywordSearchButton || !periodSearchButton
        || !periodYear || !directPeriodToggle || !directPeriodPanel || !status || !results || !pagination || !unregisteredOnly || !imagePicker
        || !imagePickerStatus || !imagePickerItems || !editorElement) return;

    const PAGE_SIZE = 20;
    unregisteredOnly.checked = false;
    const managedValues = new Map();
    let lastSelectedContentId = null;
    let searchRequestGeneration = 0;
    let detailRequestGeneration = 0;
    let activeSearch = null;

    searchModeButtons.forEach(button => button.addEventListener('click', () => {
        setSearchMode(button.dataset.festivalSearchMode);
    }));
    keywordSearchButton.addEventListener('click', searchByKeyword);
    periodSearchButton.addEventListener('click', searchByPeriod);
    monthButtons.forEach(button => button.addEventListener('click', () => {
        searchByMonth(Number(button.dataset.festivalMonth), button);
    }));
    directPeriodToggle.addEventListener('click', toggleDirectPeriod);
    unregisteredOnly.addEventListener('change', () => {
        if (activeSearch) requestCandidates(1);
    });
    [startDate, endDate, periodYear].forEach(input => input.addEventListener('change', () => {
        resetSearchResults();
        monthButtons.forEach(button => button.classList.remove('active'));
        setStatus('검색 기간이 변경되었습니다. 다시 검색해 주세요.');
    }));
    keyword.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            searchByKeyword();
        }
    });
    populatePeriodYears(new Date().getFullYear());
    setSearchMode('keyword');

    function setSearchMode(mode) {
        if (mode !== 'keyword' && mode !== 'period') return;
        resetSearchResults();
        detailRequestGeneration += 1;
        searchModeButtons.forEach(button => {
            const active = button.dataset.festivalSearchMode === mode;
            button.classList.toggle('active', active);
            button.setAttribute('aria-selected', String(active));
        });
        searchPanels.forEach(searchPanel => {
            searchPanel.hidden = searchPanel.dataset.festivalSearchPanel !== mode;
        });
        clearImagePicker();
        setStatus(mode === 'keyword'
            ? '축제·행사명을 입력한 뒤 검색해 주세요.'
            : '검색 시작일을 선택한 뒤 검색해 주세요.');
    }

    async function searchByKeyword() {
        const normalizedKeyword = keyword.value.trim();
        resetSearchResults();
        if (!normalizedKeyword) {
            setStatus('축제·행사명을 입력해 주세요.', true);
            keyword.focus();
            return;
        }

        activeSearch = {
            path: '/admin/api/kto/festivals/search-by-keyword',
            filters: {keyword: normalizedKeyword},
            button: keywordSearchButton
        };
        await requestCandidates(1);
    }

    async function searchByPeriod() {
        const normalizedStartDate = startDate.value.trim();
        const normalizedEndDate = endDate.value.trim();
        if (!normalizedStartDate) {
            resetSearchResults();
            setStatus('검색 시작일을 선택해 주세요.', true);
            startDate.focus();
            return;
        }
        if (normalizedEndDate && normalizedEndDate < normalizedStartDate) {
            resetSearchResults();
            setStatus('검색 종료일은 시작일보다 빠를 수 없습니다.', true);
            endDate.focus();
            return;
        }

        await searchByPeriodRange(normalizedStartDate, normalizedEndDate, periodSearchButton);
    }

    async function searchByMonth(month, button) {
        const year = Number(periodYear.value);
        if (!Number.isInteger(year) || month < 1 || month > 12) return;

        const range = monthDateRange(year, month);
        startDate.value = range.startDate;
        endDate.value = range.endDate;
        monthButtons.forEach(monthButton => {
            monthButton.classList.toggle('active', monthButton === button);
        });
        await searchByPeriodRange(range.startDate, range.endDate, button);
    }

    async function searchByPeriodRange(eventStartDate, eventEndDate, button) {
        resetSearchResults();
        activeSearch = {
            path: '/admin/api/kto/festivals/search',
            filters: eventEndDate ? {eventStartDate, eventEndDate} : {eventStartDate},
            button
        };
        await requestCandidates(1);
    }

    function populatePeriodYears(currentYear) {
        for (let year = currentYear - 5; year <= currentYear + 5; year += 1) {
            const option = document.createElement('option');
            option.value = String(year);
            option.textContent = `${year}년`;
            option.selected = year === currentYear;
            periodYear.append(option);
        }
    }

    function toggleDirectPeriod() {
        const expanded = directPeriodToggle.getAttribute('aria-expanded') !== 'true';
        directPeriodToggle.setAttribute('aria-expanded', String(expanded));
        directPeriodPanel.hidden = !expanded;
    }

    function monthDateRange(year, month) {
        const start = new Date(year, month - 1, 1);
        const end = new Date(year, month, 0);
        return {
            startDate: formatDate(start),
            endDate: formatDate(end)
        };
    }

    function formatDate(date) {
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    }

    function clearCandidateResults() {
        results.replaceChildren();
        document.dispatchEvent(new CustomEvent('festival:candidates-rendered', {detail: {items: []}}));
        pagination.replaceChildren();
        pagination.hidden = true;
    }

    function resetSearchResults() {
        searchRequestGeneration += 1;
        activeSearch = null;
        keywordSearchButton.disabled = false;
        periodSearchButton.disabled = false;
        monthButtons.forEach(button => button.disabled = false);
        clearCandidateResults();
    }

    async function requestCandidates(requestedPage) {
        if (!activeSearch) return;
        const search = activeSearch;
        const params = new URLSearchParams({
            ...search.filters,
            pageNo: String(requestedPage),
            numOfRows: String(PAGE_SIZE),
            unregisteredOnly: String(unregisteredOnly.checked)
        });
        const requestGeneration = ++searchRequestGeneration;
        detailRequestGeneration += 1;
        clearCandidateResults();
        setSearchLoading(search.button, true, '축제·행사를 검색하고 등록 여부를 확인하고 있습니다.');
        try {
            const response = await fetch(`${search.path}?${params.toString()}`,
                {headers: {Accept: 'application/json'}});
            const payload = await response.json();
            if (requestGeneration !== searchRequestGeneration) return;
            if (!response.ok) throw new Error(payload.message || '축제·행사를 검색하지 못했습니다.');
            renderCandidates(payload);
        } catch (error) {
            if (requestGeneration === searchRequestGeneration) setStatus(error.message || '축제·행사를 검색하지 못했습니다.', true);
        } finally {
            if (requestGeneration === searchRequestGeneration) search.button.disabled = false;
        }
    }

    function renderCandidates(payload) {
        const items = Array.isArray(payload.items) ? payload.items : [];
        const totalCount = Number(payload.totalCount);
        const numOfRows = Number(payload.numOfRows);
        const pageNo = Number(payload.pageNo);
        if (!Number.isInteger(totalCount) || totalCount < 0
            || !Number.isInteger(numOfRows) || numOfRows < 1
            || !Number.isInteger(pageNo) || pageNo < 1
            || items.some(item => !['REGISTERED', 'UNREGISTERED', 'UNKNOWN'].includes(item.registrationStatus)
                || (item.registrationStatus === 'REGISTERED' && !/^\d+$/.test(String(item.registeredFestivalId))))) {
            throw new Error('축제 검색 결과 또는 등록 상태를 확인하지 못했습니다. 다시 검색해 주세요.');
        }

        if (!items.length) {
            setStatus(totalCount ? `총 ${totalCount}건 / 현재 페이지에 표시할 결과가 없습니다.`
                : (unregisteredOnly.checked ? '미등록 축제가 없습니다.' : '검색 결과가 없습니다.'));
        } else {
            const first = (pageNo - 1) * numOfRows + 1;
            const last = Math.min(totalCount, first + items.length - 1);
            setStatus(`총 ${totalCount}건 / ${first}~${last}건`);
            items.forEach(item => results.append(createCandidate(item)));
        }
        renderPagination(pageNo, numOfRows, totalCount);
        document.dispatchEvent(new CustomEvent('festival:candidates-rendered', {detail: {items}}));
    }

    function renderPagination(pageNo, numOfRows, totalCount) {
        const totalPages = Math.ceil(totalCount / numOfRows);
        if (totalPages <= 1) return;

        pagination.hidden = false;
        pagination.append(pageButton('이전', pageNo - 1, pageNo === 1));
        const firstPage = Math.max(1, Math.min(pageNo - 2, totalPages - 4));
        const lastPage = Math.min(totalPages, firstPage + 4);
        if (firstPage > 1) {
            pagination.append(pageButton('1', 1));
            if (firstPage > 2) pagination.append(pageGap());
        }
        for (let page = firstPage; page <= lastPage; page += 1) {
            pagination.append(pageButton(String(page), page, page === pageNo));
        }
        if (lastPage < totalPages) {
            if (lastPage < totalPages - 1) pagination.append(pageGap());
            pagination.append(pageButton(String(totalPages), totalPages));
        }
        pagination.append(pageButton('다음', pageNo + 1, pageNo >= totalPages));
    }

    function pageButton(label, pageNo, disabled = false) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.disabled = disabled;
        if (/^\d+$/.test(label)) button.setAttribute('aria-label', `${label}페이지`);
        if (disabled && label === String(pageNo)) button.setAttribute('aria-current', 'page');
        if (!disabled) button.addEventListener('click', () => requestCandidates(pageNo));
        return button;
    }

    function pageGap() {
        const gap = document.createElement('span');
        gap.textContent = '…';
        gap.setAttribute('aria-hidden', 'true');
        return gap;
    }

    function createCandidate(item) {
        const card = document.createElement('article');
        card.className = 'admin-kto-festival-candidate';
        const media = document.createElement('div');
        media.className = 'admin-kto-festival-candidate-media';
        const thumbnailUrl = safeHttpUrl(item.firstImage || item.firstImage2);
        if (thumbnailUrl) {
            const image = document.createElement('img');
            image.src = thumbnailUrl;
            image.alt = '';
            image.loading = 'lazy';
            media.append(image);
        } else {
            const placeholder = document.createElement('span');
            placeholder.textContent = '이미지 없음';
            media.append(placeholder);
        }
        const body = document.createElement('div');
        body.className = 'admin-kto-festival-candidate-body';
        const candidateTitle = document.createElement('strong');
        candidateTitle.textContent = hasText(item.title) ? item.title : '제목 없음';
        body.append(candidateTitle);
        const period = formatPeriod(item.eventStartDate, item.eventEndDate);
        if (period) body.append(createMetaText(period));
        if (hasText(item.address)) body.append(createMetaText(item.address));
        if (hasText(item.categoryName)) {
            const categoryBadge = document.createElement('span');
            categoryBadge.className = 'admin-kto-festival-category-badge';
            categoryBadge.textContent = item.categoryName;
            body.append(categoryBadge);
        }
        if (item.registrationStatus === 'REGISTERED' || item.registrationStatus === 'UNKNOWN') {
            const registrationBadge = document.createElement('span');
            registrationBadge.className = 'admin-kto-festival-registration-badge';
            if (item.registrationStatus === 'REGISTERED') {
                registrationBadge.textContent = '등록 완료';
            } else {
                registrationBadge.classList.add('is-unknown');
                registrationBadge.textContent = '등록 여부 확인 불가';
            }
            body.append(registrationBadge);
        }
        if (item.registrationStatus === 'REGISTERED') {
            const editLink = document.createElement('a');
            editLink.className = 'admin-btn is-small';
            editLink.href = `/admin/festivals/${item.registeredFestivalId}/edit`;
            editLink.textContent = '수정';
            card.append(media, body, editLink);
            if (window.TripBoraFestivalBulk) window.TripBoraFestivalBulk.attach(card, item);
            return card;
        }
        if (item.registrationStatus === 'UNKNOWN') {
            card.append(media, body);
            if (window.TripBoraFestivalBulk) window.TripBoraFestivalBulk.attach(card, item);
            return card;
        }
        const selectButton = document.createElement('button');
        selectButton.type = 'button';
        selectButton.className = 'admin-btn is-small is-primary';
        selectButton.textContent = '선택';
        selectButton.dataset.festivalSingleSelect = '';
        selectButton.disabled = !hasText(item.contentId);
        selectButton.addEventListener('click', () => loadDetail(item));
        card.append(media, body, selectButton);
        if (window.TripBoraFestivalBulk) window.TripBoraFestivalBulk.attach(card, item);
        return card;
    }

    async function loadDetail(item) {
        if (window.TripBoraFestivalBulk?.isBulk()) return;
        if (!hasText(item.contentId)) return;
        const requestGeneration = ++detailRequestGeneration;
        clearImagePicker();
        setStatus(`${item.title || '선택한 행사'} 상세 정보를 불러오고 있습니다.`);
        try {
            const params = new URLSearchParams({contentId: item.contentId});
            const response = await fetch(`/admin/api/kto/festivals/detail?${params.toString()}`, {headers: {Accept: 'application/json'}});
            const detail = await response.json();
            if (requestGeneration !== detailRequestGeneration) return;
            if (!response.ok) throw new Error(detail.message || '축제·행사 상세 정보를 불러오지 못했습니다.');
            const categoryResolved = applyAutofill(detail);
            const replacingPrevious = lastSelectedContentId !== null && lastSelectedContentId !== item.contentId;
            lastSelectedContentId = item.contentId;
            await loadThumbnailCandidates(item.contentId, requestGeneration);
            if (!categoryResolved && hasText(detail.categoryName)) {
                setStatus(`${detail.categoryName} 카테고리를 찾지 못했습니다. 카테고리를 직접 선택해 주세요.`, true);
                return;
            }
            setStatus(`${detail.title || item.title || '선택한 행사'} 정보를 ${replacingPrevious ? '새 후보 기준으로 갱신했습니다.' : '빈 항목에 입력했습니다.'}`);
        } catch (error) {
            if (requestGeneration === detailRequestGeneration) setStatus(error.message || '축제·행사 상세 정보를 불러오지 못했습니다.', true);
        }
    }

    async function loadThumbnailCandidates(contentId, requestGeneration) {
        imagePicker.hidden = false;
        imagePickerStatus.textContent = '선택 가능한 이미지를 불러오고 있습니다.';
        try {
            const params = new URLSearchParams({contentId});
            const response = await fetch(`/admin/api/kto/festivals/images?${params.toString()}`,
                {headers: {Accept: 'application/json'}});
            const payload = await response.json();
            if (requestGeneration !== detailRequestGeneration) return;
            if (!response.ok) throw new Error(payload.message || '이미지 목록을 불러오지 못했습니다.');
            renderThumbnailCandidates(Array.isArray(payload.items) ? payload.items : []);
        } catch (error) {
            if (requestGeneration !== detailRequestGeneration) return;
            imagePickerItems.replaceChildren();
            imagePickerStatus.textContent = error.message || '이미지 목록을 불러오지 못했습니다.';
            imagePickerStatus.classList.add('is-error');
        }
    }

    function renderThumbnailCandidates(items) {
        imagePickerItems.replaceChildren();
        imagePickerStatus.classList.remove('is-error');
        if (!items.length) {
            imagePickerStatus.textContent = '선택 가능한 TourAPI 이미지가 없습니다.';
            return;
        }
        imagePickerStatus.textContent = '목록 썸네일로 사용할 이미지를 한 장 선택할 수 있습니다.';
        items.forEach(item => imagePickerItems.append(createThumbnailCandidate(item)));
    }

    function createThumbnailCandidate(item) {
        const option = document.createElement('label');
        option.className = 'admin-kto-festival-image-option';
        const imageUrl = safeHttpUrl(item.imageUrl);
        if (imageUrl) {
            const image = document.createElement('img');
            image.src = imageUrl;
            image.alt = hasText(item.imageName) ? item.imageName : `${item.imageRole || '축제'} 이미지`;
            image.loading = 'lazy';
            option.append(image);
        } else {
            const placeholder = document.createElement('span');
            placeholder.className = 'admin-kto-festival-image-placeholder';
            placeholder.textContent = '이미지 없음';
            option.append(placeholder);
        }

        const body = document.createElement('span');
        body.className = 'admin-kto-festival-image-option-body';
        const role = document.createElement('strong');
        role.textContent = item.imageRole || 'TourAPI 이미지';
        body.append(role);
        if (hasText(item.imageName)) body.append(createThumbnailMeta(item.imageName));
        if (hasText(item.licenseType)) body.append(createThumbnailMeta(licenseLabel(item.licenseType)));

        const radio = document.createElement('input');
        radio.type = 'radio';
        radio.name = 'festival-thumbnail-picker';
        radio.value = hasText(item.selectionKey) ? item.selectionKey : '';
        radio.disabled = !item.selectable || !hasText(item.selectionKey);
        radio.checked = !radio.disabled && thumbnailSelection.value === radio.value;
        radio.addEventListener('change', () => {
            if (radio.checked) thumbnailSelection.value = radio.value;
        });
        const selectionLabel = document.createElement('span');
        selectionLabel.className = 'admin-kto-festival-image-option-select';
        selectionLabel.append(radio, document.createTextNode('목록 썸네일로 사용'));
        body.append(selectionLabel);
        if (!item.selectable) {
            const reason = document.createElement('span');
            reason.className = 'admin-kto-festival-image-option-reason';
            reason.textContent = item.unavailableReason || '선택할 수 없는 이미지입니다.';
            body.append(reason);
            option.classList.add('is-disabled');
        }
        option.append(body);
        return option;
    }

    function createThumbnailMeta(value) {
        const element = document.createElement('span');
        element.className = 'admin-kto-festival-image-option-meta';
        element.textContent = value;
        return element;
    }

    function licenseLabel(value) {
        if (value === 'KOGL_TYPE_1') return '공공누리 제1유형';
        if (value === 'KOGL_TYPE_3') return '공공누리 제3유형';
        return value;
    }

    function clearImagePicker() {
        thumbnailSelection.value = '';
        imagePicker.hidden = true;
        imagePickerItems.replaceChildren();
        imagePickerStatus.textContent = '';
        imagePickerStatus.classList.remove('is-error');
    }

    function applyAutofill(detail) {
        ktoFestivalContentId.value = hasText(detail.contentId) ? String(detail.contentId).trim() : '';
        setManagedField('scope', scope, 'DOMESTIC');
        setManagedField('title', title, detail.title);
        setManagedField('eventStartDate', eventStartDate, detail.eventStartDate);
        setManagedField('eventEndDate', eventEndDate, detail.eventEndDate);
        const categoryOption = findFestivalCategory(detail.categoryName);
        if (categoryOption) setManagedField('categoryId', category, categoryOption.value);
        setManagedEditor(detail.overview);
        setManagedField('eventPlace', eventPlace, detail.eventPlace);
        setManagedField('address', address, detail.address);
        setManagedField('playTime', playTime, detail.playTime);
        setManagedField('useTime', useTime, detail.useTimeFestival);
        setManagedField('sponsor1', sponsor1, detail.sponsor1);
        setManagedField('sponsor1Tel', sponsor1Tel, detail.sponsor1Tel);
        setManagedField('sponsor2', sponsor2, detail.sponsor2);
        setManagedField('sponsor2Tel', sponsor2Tel, detail.sponsor2Tel);
        setManagedField('contactTel', contactTel, detail.tel);
        setManagedField('homepageUrl', homepageUrl, detail.eventHomepage || detail.homepage);
        announceKoreanAutofill(detail);
        return Boolean(categoryOption);
    }

    /**
     * 국문 자동입력이 끝났음을 알린다. 외국어 자동입력이 이 신호를 받아 이어서 돈다.
     * 국문 저장 흐름은 그대로이며, 여기서는 알리기만 한다.
     */
    function announceKoreanAutofill(detail) {
        const contentId = ktoFestivalContentId.value.trim();
        if (!contentId) return;
        document.dispatchEvent(new CustomEvent('festival:korean-autofill-applied', {
            detail: {contentId, title: title.value.trim(), koreanTitle: detail.title}
        }));
    }

    function findFestivalCategory(categoryName) {
        if (!hasText(categoryName)) return null;
        const expectedName = normalizeCategoryName(categoryName);
        return Array.from(category.options).find(option => normalizeCategoryName(option.textContent) === expectedName) || null;
    }

    function setManagedField(fieldKey, element, nextValue) {
        if (!element || !hasText(nextValue)) return false;
        const normalizedValue = String(nextValue).trim();
        const currentValue = element.value.trim();
        const mayReplace = !currentValue || (managedValues.has(fieldKey) && currentValue === managedValues.get(fieldKey));
        if (!mayReplace) return false;
        element.value = normalizedValue;
        managedValues.set(fieldKey, element.value.trim());
        element.dispatchEvent(new Event('input', {bubbles: true}));
        element.dispatchEvent(new Event('change', {bubbles: true}));
        return true;
    }

    function setManagedEditor(value) {
        if (!hasText(value)) return false;
        const quill = editorElement.quillEditorInstance;
        if (!quill) return false;
        const currentHtml = quill.getSemanticHTML();
        const editorIsEmpty = !quill.getText().trim() && !quill.root.querySelector('img[src]');
        const mayReplace = editorIsEmpty || (managedValues.has('content') && currentHtml === managedValues.get('content'));
        if (!mayReplace) return false;
        const container = document.createElement('div');
        String(value).split(/\r?\n/).forEach(line => {
            if (!line.trim()) return;
            const paragraph = document.createElement('p');
            paragraph.textContent = line.trim();
            container.append(paragraph);
        });
        const delta = quill.clipboard.convert({html: container.innerHTML, text: ''});
        quill.setContents(delta, 'silent');
        managedValues.set('content', quill.getSemanticHTML());
        return true;
    }

    function normalizeCategoryName(value) {
        return String(value || '').trim().replace(/\s+\(숨김\)$/, '');
    }

    function formatPeriod(first, second) {
        if (!hasText(first)) return hasText(second) ? second : '';
        if (!hasText(second) || first === second) return first;
        return `${first} ~ ${second}`;
    }

    function createMetaText(value) {
        const element = document.createElement('span');
        element.className = 'admin-kto-festival-candidate-meta';
        element.textContent = value;
        return element;
    }

    function safeHttpUrl(value) {
        if (!hasText(value)) return null;
        try {
            const url = new URL(String(value).trim());
            if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) return null;
            return url.toString();
        } catch {
            return null;
        }
    }

    function hasText(value) {
        return value !== null && value !== undefined && String(value).trim() !== '';
    }

    function setSearchLoading(button, loading, message) {
        button.disabled = loading;
        setStatus(message);
    }

    function setStatus(message, isError = false) {
        status.textContent = message;
        status.classList.toggle('is-error', isError);
    }
});
