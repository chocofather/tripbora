// 기존 Kakao SDK 로더를 공유한다. 좌표 선택은 '위치 사용' 전까지 임시 상태다.
(() => {
    const editors = new WeakMap();
    let sdkPromise;
    let nextPanelId = 0;
    function validCoordinates(lat, lng) {
        const present = value => value !== null && value !== undefined && String(value).trim() !== '';
        return present(lat) && present(lng) && Number.isFinite(Number(lat)) && Number.isFinite(Number(lng))
            && Number(lat) >= -90 && Number(lat) <= 90 && Number(lng) >= -180 && Number(lng) <= 180;
    }
    function sdk() {
        if (sdkPromise) return sdkPromise;
        sdkPromise = new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('지도 연결 시간이 초과되었습니다.')), 12000);
            const ready = () => { clearTimeout(timer); resolve(window.kakao.maps); };
            if (window.kakao?.maps?.load) window.kakao.maps.load(ready);
            else if (typeof window.loadKakaoScript === 'function') {
                window.loadKakaoScript(ready);
                document.getElementById('kakao-map-script')?.addEventListener('error', () => {
                    clearTimeout(timer); reject(new Error('지도를 불러오지 못했습니다.'));
                }, {once: true});
            } else { clearTimeout(timer); reject(new Error('지도 연결을 확인해 주세요.')); }
        }).catch(error => { sdkPromise = null; throw error; });
        return sdkPromise;
    }
    function element(tag, className, text) {
        const node = document.createElement(tag);
        node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }
    function button(text, action) {
        const node = element('button', 'course-location-button', text);
        node.type = 'button'; node.addEventListener('click', action); return node;
    }
    function attach(container, item, onChange) {
        editors.get(container)?.dispose();
        container.replaceChildren();
        container.classList.add('course-location-editor');
        let active = true;
        let searchVersion = 0;
        let drawVersion = 0;
        let map;
        let marker;
        let stagedAddress = null;
        const panel = element('div', 'course-location-panel'); panel.hidden = true;
        panel.id = `course-location-panel-${++nextPanelId}`;
        const summary = element('p', 'course-location-summary');
        const toggle = button('위치 찾기', () => {
            panel.hidden = !panel.hidden;
            toggle.setAttribute('aria-expanded', String(!panel.hidden));
            if (!panel.hidden) {
                if (!query.value) query.value = item.name || item.address || '';
                if (item.countryCode === 'KR' && validCoordinates(item.latitude, item.longitude)) draw(item.latitude, item.longitude);
            } else { searchVersion++; drawVersion++; }
        });
        toggle.setAttribute('aria-expanded', 'false');
        toggle.setAttribute('aria-controls', panel.id);
        const controls = element('div', 'course-location-search');
        const query = element('input', 'course-location-query');
        query.type = 'search'; query.placeholder = '장소명 또는 주소로 위치 찾기';
        query.setAttribute('aria-label', '위치 검색어');
        const results = element('div', 'course-location-results');
        const status = element('p', 'course-location-status'); status.setAttribute('role', 'status');
        const canvas = element('div', 'course-location-canvas'); canvas.hidden = true;
        const lat = element('input', 'course-coordinate-input');
        const lng = element('input', 'course-coordinate-input');
        // 좌표는 검색/지도 선택의 임시 상태이며 사용자에게 직접 입력받지 않는다.
        [lat, lng].forEach(input => { input.type = 'hidden'; });
        const coordinates = element('div', 'course-location-coordinates'); coordinates.append(lat, lng);
        const note = element('p', 'course-location-note', '해외 장소는 장소명과 주소를 기준으로 Google Maps에서 연결됩니다.');
        const selected = element('p', 'course-location-selected');
        function stage(latitude, longitude, name = '') {
            if (!validCoordinates(latitude, longitude)) return;
            lat.value = Number(latitude).toFixed(8); lng.value = Number(longitude).toFixed(8);
            stagedAddress = null;
            selected.textContent = name ? `선택 위치: ${name}` : '지도에서 선택한 위치';
        }
        async function draw(latitude, longitude) {
            const version = ++drawVersion;
            try {
                const maps = await sdk();
                if (!active || version !== drawVersion || panel.hidden || !validCoordinates(latitude, longitude)) return;
                canvas.hidden = false;
                const point = new maps.LatLng(Number(latitude), Number(longitude));
                if (!map) {
                    map = new maps.Map(canvas, {center: point, level: 3});
                    marker = new maps.Marker({position: point, map});
                    maps.event.addListener(map, 'click', event => {
                        stage(event.latLng.getLat(), event.latLng.getLng());
                        marker.setPosition(event.latLng);
                    });
                } else { map.relayout(); map.setCenter(point); marker.setPosition(point); }
            } catch (error) { if (active && version === drawVersion) status.textContent = '지도를 불러오지 못했습니다. 위치 없이 저장할 수 있어요.'; }
        }
        async function search() {
            const text = query.value.trim();
            if (!text) { status.textContent = '장소명이나 주소를 입력해 주세요.'; return; }
            const version = ++searchVersion;
            status.textContent = '위치를 찾고 있습니다.';
            try {
                const maps = await sdk();
                if (!active || version !== searchVersion || panel.hidden) return;
                if (!maps.services?.Places) throw new Error('장소 검색 연결을 확인해 주세요.');
                new maps.services.Places().keywordSearch(text, (places, resultStatus) => {
                    if (!active || version !== searchVersion || panel.hidden) return;
                    results.replaceChildren();
                    const found = resultStatus === maps.services.Status.OK ? places : [];
                    found.forEach(place => {
                        const result = button(place.place_name, () => {
                            stage(place.y, place.x, place.place_name); draw(place.y, place.x);
                            stagedAddress = place.road_address_name || place.address_name || null;
                        });
                        result.className = 'course-location-result';
                        const address = element('small', '', place.road_address_name || place.address_name || '');
                        result.append(address); results.append(result);
                    });
                    status.textContent = found.length ? '위치를 선택한 뒤 위치 사용을 눌러 주세요.' : '검색 결과가 없습니다. 주소만 입력해도 저장할 수 있어요.';
                }, {size: 5});
            } catch (error) { if (active && version === searchVersion) status.textContent = '위치 검색을 사용할 수 없습니다. 주소만 입력해도 저장할 수 있어요.'; }
        }
        controls.append(query, button('검색', search));
        query.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); search(); } });
        [lat, lng].forEach(input => input.addEventListener('keydown', event => { if (event.key === 'Enter') event.preventDefault(); }));
        const refresh = () => {
            lat.value = item.latitude ?? ''; lng.value = item.longitude ?? '';
            summary.textContent = validCoordinates(item.latitude, item.longitude)
                ? '위치가 설정되어 있어요.' : '위치 선택은 선택사항입니다.';
        };
        const actions = element('div', 'course-location-actions');
        actions.append(button('위치 사용', () => {
            if (!validCoordinates(lat.value, lng.value)) {
                status.textContent = '검색 결과나 지도에서 위치를 선택해 주세요.'; return;
            }
            item.latitude = Number(lat.value).toFixed(8); item.longitude = Number(lng.value).toFixed(8);
            if (stagedAddress) { item.address = stagedAddress; item.locationAddress = stagedAddress; }
            if (map || item.countryCode === 'KR') draw(item.latitude, item.longitude);
            refresh(); status.textContent = '위치를 적용했습니다.'; onChange();
        }), button('위치 지우기', () => {
            searchVersion++;
            drawVersion++;
            item.latitude = null; item.longitude = null;
            stagedAddress = null;
            refresh(); canvas.hidden = true; selected.textContent = ''; status.textContent = '위치를 지웠습니다.'; onChange();
        }));
        panel.append(controls, results, canvas, selected, coordinates, actions, status);
        if (item.countryCode === 'KR') container.append(toggle, summary, panel);
        else {
            if (item.countryCode) container.append(note);
            // 해외의 기존 좌표도 보존하며 명시적인 지우기만 허용한다.
            if (validCoordinates(item.latitude, item.longitude)) container.append(summary, actions.children[1]);
        }
        refresh();
        const state = {refresh, dispose() { active = false; searchVersion++; drawVersion++; }};
        editors.set(container, state); return state;
    }
    window.CourseLocation = {attach, validCoordinates};
})();
