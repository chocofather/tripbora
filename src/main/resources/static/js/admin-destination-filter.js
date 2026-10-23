document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("admin-destination-filter-form");
    if (!form) return;

    const SEARCH_DEBOUNCE_MS = 500;
    const KEYWORD_FOCUS_KEY = "tripbora.adminDestinationKeywordFocus";

    const typeInput = document.getElementById("destination-filter-type");
    const keywordInput = document.getElementById("destination-keyword-filter");
    const continentSelect = document.getElementById("destination-continent-filter");
    const countrySelect = document.getElementById("destination-country-filter");
    const citySelect = document.getElementById("destination-city-filter");
    const regionSelect = document.getElementById("destination-region-filter");
    const districtSelect = document.getElementById("destination-district-filter");
    const destinationTypeSelect = document.getElementById("destination-type-filter");
    const sortSelect = document.getElementById("destination-sort-filter");
    const dataStatusSelect = document.getElementById("destination-data-status-filter");
    const visibilitySelect = document.getElementById("destination-visibility-filter");

    let searchTimer = null;
    let composing = false;

    // 검색/조회 버튼 없이 현재 폼 상태 그대로 GET 조회한다. (URL 에 조건이 그대로 남는다)
    function submitFilters() {
        if (searchTimer) {
            clearTimeout(searchTimer);
            searchTimer = null;
        }
        // 기본 전체 표시를 명시적인 전체 선택으로 바꾸지 않는다.
        if (visibilitySelect?.dataset.implicitVisibility === "true") visibilitySelect.disabled = true;
        form.submit();
        // 뒤로가기로 복원되는 폼에도 공개 상태 선택을 유지한다.
        if (visibilitySelect) visibilitySelect.disabled = false;
    }

    function cancelScheduledSearch() {
        if (searchTimer) clearTimeout(searchTimer);
        searchTimer = null;
    }

    function scheduleSearch() {
        cancelScheduledSearch();
        searchTimer = setTimeout(() => {
            searchTimer = null;
            // 한글은 한 음절이 끝나면 compositionend 직후 다음 음절의 compositionstart 가 온다. 조합 중에는 조회하지 않는다.
            if (composing) return;
            submitKeywordSearch();
        }, SEARCH_DEBOUNCE_MS);
    }

    // 검색어 조회는 페이지를 다시 그리므로, 돌아온 화면에서 검색창 포커스를 이어 준다.
    function submitKeywordSearch() {
        try {
            sessionStorage.setItem(KEYWORD_FOCUS_KEY, "1");
        } catch (e) {
            // 저장소를 못 쓰면 포커스 복원만 생략한다.
        }
        submitFilters();
    }

    function restoreKeywordFocus() {
        let restore = false;
        try {
            restore = sessionStorage.getItem(KEYWORD_FOCUS_KEY) === "1";
            sessionStorage.removeItem(KEYWORD_FOCUS_KEY);
        } catch (e) {
            return;
        }
        if (!restore || !keywordInput) return;
        keywordInput.focus();
        const end = keywordInput.value.length;
        try {
            keywordInput.setSelectionRange(end, end);
        } catch (e) {
            // type=search 에서 선택 범위를 지원하지 않는 브라우저는 포커스만 둔다.
        }
    }

    function resetSelect(select, placeholder, disabled = true) {
        if (!select) return;
        select.replaceChildren(new Option(placeholder, ""));
        select.disabled = disabled;
    }

    // 하위 지역 목록은 선택 즉시 재조회되는 서버 렌더링 결과로 채워진다.

    // 검색창만 debounce, 그 외 필터는 변경 즉시 적용한다.
    form.addEventListener("submit", event => {
        event.preventDefault();
        submitFilters();
    });
    restoreKeywordFocus();

    keywordInput?.addEventListener("compositionstart", () => {
        composing = true;
        // 앞 음절의 compositionend 가 걸어 둔 조회를 취소해야 다음 글자 입력 중에 페이지가 바뀌지 않는다.
        cancelScheduledSearch();
    });
    keywordInput?.addEventListener("compositionend", () => {
        composing = false;
        scheduleSearch();
    });
    keywordInput?.addEventListener("input", event => {
        if (composing || event.isComposing) return;
        scheduleSearch();
    });
    keywordInput?.addEventListener("keydown", event => {
        if (event.key !== "Enter") return;
        // 기본 submit(첫 필터 버튼 클릭)과 debounce 가 겹치지 않게 직접 조회한다.
        event.preventDefault();
        // 조합 중 Enter 는 글자를 확정만 한다. 이어지는 compositionend 가 조회를 예약한다.
        if (composing || event.isComposing) return;
        submitKeywordSearch();
    });

    // 부모 지역을 바꾸면 하위 조건은 비운 상태로 조회한다.
    continentSelect?.addEventListener("change", () => {
        resetSelect(countrySelect, "- 국가 선택 -");
        resetSelect(citySelect, "- 도시 선택 -");
        submitFilters();
    });

    countrySelect?.addEventListener("change", () => {
        resetSelect(citySelect, "- 도시 선택 -");
        submitFilters();
    });

    citySelect?.addEventListener("change", submitFilters);

    regionSelect?.addEventListener("change", () => {
        resetSelect(districtSelect, "- 시/군/구 선택 -");
        submitFilters();
    });

    districtSelect?.addEventListener("change", submitFilters);

    // 분류·데이터 상태·정렬도 바꾸는 즉시 조회한다. 쪽 번호는 폼에 없으므로 조건이 바뀌면 1쪽부터 본다.
    destinationTypeSelect?.addEventListener("change", submitFilters);
    dataStatusSelect?.addEventListener("change", submitFilters);
    visibilitySelect?.addEventListener("change", () => {
        visibilitySelect.dataset.implicitVisibility = "false";
        submitFilters();
    });
    sortSelect?.addEventListener("change", submitFilters);

    form.querySelectorAll(".admin-filter-tab[value]").forEach(button => {
        button.addEventListener("click", () => {
            const nextType = button.value;
            if (typeInput) typeInput.value = nextType;

            if (nextType === "domestic") {
                resetSelect(continentSelect, "- 대륙 선택 -");
                resetSelect(countrySelect, "- 국가 선택 -");
                resetSelect(citySelect, "- 도시 선택 -");
            } else if (nextType === "overseas") {
                resetSelect(regionSelect, "- 시/도 선택 -");
                resetSelect(districtSelect, "- 시/군/구 선택 -");
            } else {
                resetSelect(continentSelect, "- 대륙 선택 -");
                resetSelect(countrySelect, "- 국가 선택 -");
                resetSelect(citySelect, "- 도시 선택 -");
                resetSelect(regionSelect, "- 시/도 선택 -");
                resetSelect(districtSelect, "- 시/군/구 선택 -");
            }
        });
    });
});
