/**
 * 구조화 콘텐츠(STRUCTURED) 본문 사진 확대 모달. 공개 상세에서만 쓴다.
 *
 * <p>본문의 [data-structured-gallery-image] 사진(큰 이미지·이미지 + 글·슬라이더·이미지 배치)을 그려진 순서대로
 * 하나의 갤러리로 묶는다. 사진을 누르면(또는 포커스 후 Enter / Space) 그 사진부터 크게 보여 준다.
 * 슬라이더의 현재 장과는 따로 움직인다.
 *
 * <ul>
 *   <li>닫기: 모바일은 닫기 버튼만, 데스크톱은 사진·바탕 클릭과 Esc도 지원</li>
 *   <li>모바일 이동: 좌우 터치 또는 가로 스와이프. 세로 드래그·다중 터치는 제외한다.</li>
 *   <li>이동: 이전/다음 버튼, ← →. 처음과 끝은 이어진다. 사진이 한 장이면 버튼을 숨긴다.</li>
 *   <li>큰 사진 아래에 제목·설명·출처를 보여 준다. 출처는 본문에 그려진 출처 줄을 그대로 복사한다.
 *       (라벨 언어, 링크 규칙, target / rel 이 본문과 같다)</li>
 * </ul>
 *
 * <p>모달 DOM 은 페이지에 하나뿐이다. 열 때 showModal() 로 top layer 에 올리고 본문 스크롤을 잠근다.
 * 닫으면 스크롤을 되돌리고, 열기 전에 누른 사진으로 포커스를 돌려준다. 키 처리는 열려 있는 동안만 건다.
 */
(function () {
    'use strict';

    const IMAGE_SELECTOR = '.structured-content [data-structured-gallery-image]';

    function init() {
        const modal = document.querySelector('[data-structured-gallery]');
        const images = Array.from(document.querySelectorAll(IMAGE_SELECTOR));
        if (!modal || images.length === 0 || modal.dataset.galleryReady === 'true') return;
        modal.dataset.galleryReady = 'true';

        const modalImage = modal.querySelector('[data-gallery-current]');
        const meta = modal.querySelector('[data-gallery-meta]');
        // 모달의 글 자리. (본문 사진의 data-gallery-title / -description 값과 이름이 겹치지 않게 slot 으로 찾는다)
        const title = modal.querySelector('[data-gallery-slot="title"]');
        const description = modal.querySelector('[data-gallery-slot="description"]');
        const credit = modal.querySelector('[data-gallery-slot="credit"]');
        let currentIndex = 0;
        let returnFocus = null;
        let previousOverflow = '';
        const mobileViewport = window.matchMedia('(max-width: 720px)');
        const CONTROL_SELECTOR = 'button, a, input, select, textarea, [role="button"], [data-gallery-meta]';
        let gesture = null;
        let ignoredTouch = false;
        let suppressClickUntil = 0;

        function isMobile() {
            return mobileViewport.matches;
        }

        // 스크립트가 붙은 뒤에만 본문 사진을 누를 수 있는 버튼처럼 만든다. (alt 가 없으면 이름을 붙인다)
        images.forEach(image => {
            image.setAttribute('role', 'button');
            image.setAttribute('tabindex', '0');
            image.setAttribute('aria-haspopup', 'dialog');
            if (!image.getAttribute('alt')) image.setAttribute('aria-label', modal.dataset.openLabel || '');
        });

        function isOpen() {
            return modal.classList.contains('is-open');
        }

        /** 글 한 줄. 값이 없으면 숨긴다. */
        function setText(element, value) {
            element.textContent = value || '';
            element.hidden = !value;
        }

        /** 사진 바로 아래 출처 줄(큰 이미지·슬라이더·배치는 같은 figure, 이미지 + 글은 사진 칸 안). */
        function creditOf(image) {
            return image.closest('figure, .structured-image-text-media')?.querySelector('.structured-media-credit') || null;
        }

        function render() {
            const source = images[currentIndex];
            modalImage.src = source.currentSrc || source.src;
            modalImage.alt = source.getAttribute('alt') || '';
            setText(title, source.dataset.galleryTitle);
            setText(description, source.dataset.galleryDescription);
            const sourceCredit = creditOf(source);
            const copy = sourceCredit ? sourceCredit.cloneNode(true) : null;
            if (copy) copy.removeAttribute('title');
            credit.replaceChildren(...(copy ? [copy] : []));
            credit.hidden = !copy;
            meta.hidden = title.hidden && description.hidden && credit.hidden;
            modal.classList.toggle('is-single', images.length <= 1);
        }

        /** 순환 이동. 첫 장에서 이전 → 마지막, 마지막에서 다음 → 첫 장. */
        function move(step) {
            if (images.length <= 1) return;
            currentIndex = (currentIndex + step + images.length) % images.length;
            render();
        }

        function onKeydown(event) {
            if (event.key === 'Escape') {
                event.preventDefault();
                if (!isMobile()) close();
            } else if (event.key === 'ArrowLeft') {
                event.preventDefault();
                move(-1);
            } else if (event.key === 'ArrowRight') {
                event.preventDefault();
                move(1);
            }
        }

        function open(index) {
            if (index < 0) return;
            currentIndex = index;
            gesture = null;
            ignoredTouch = false;
            suppressClickUntil = 0;
            returnFocus = images[index];
            render();
            if (!isOpen()) {
                if (typeof modal.showModal === 'function') {
                    try {
                        if (!modal.open) modal.showModal();
                    } catch (error) {
                        modal.setAttribute('open', '');
                    }
                } else {
                    modal.setAttribute('open', '');
                }
                modal.classList.add('is-open');
                previousOverflow = document.body.style.overflow;
                document.body.style.overflow = 'hidden';
                document.addEventListener('keydown', onKeydown);
            }
            modal.querySelector('[data-gallery-close]').focus({preventScroll: true});
        }

        /** 여러 경로(버튼·Esc·dialog close 이벤트)로 불려도 한 번만 정리한다. */
        function close() {
            if (!isOpen()) return;
            modal.classList.remove('is-open');
            gesture = null;
            document.removeEventListener('keydown', onKeydown);
            if (typeof modal.close === 'function' && modal.open) modal.close();
            else modal.removeAttribute('open');
            document.body.style.overflow = previousOverflow;
            modalImage.removeAttribute('src');
            if (returnFocus) returnFocus.focus({preventScroll: true});
        }

        // 본문 사진: 누르기 / Enter / Space 로 연다. (이벤트 위임, 한 번만 건다)
        const content = images[0].closest('.structured-content') || document;
        content.addEventListener('click', event => {
            const image = event.target.closest('[data-structured-gallery-image]');
            if (image) open(images.indexOf(image));
        });
        content.addEventListener('keydown', event => {
            if (event.key !== 'Enter' && event.key !== ' ') return;
            const image = event.target.closest('[data-structured-gallery-image]');
            if (!image) return;
            event.preventDefault();
            open(images.indexOf(image));
        });

        // 드래그 뒤 합성 click을 캡처 단계에서 막아 버튼이나 좌우 터치와 중복 실행되지 않게 한다.
        modal.addEventListener('click', event => {
            if (isMobile() && Date.now() < suppressClickUntil) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }
        }, true);

        // 모달 안: 버튼은 이동·닫기만 하고 바탕 닫기로 번지지 않는다.
        const button = (selector, handler) => modal.querySelector(selector).addEventListener('click', event => {
            event.stopPropagation();
            handler();
        });
        button('[data-gallery-prev]', () => move(-1));
        button('[data-gallery-next]', () => move(1));
        button('[data-gallery-close]', close);
        // 모바일은 화면 좌우 절반을 터치 영역으로 쓴다. 컨트롤·설명·출처 링크는 제외한다.
        modal.addEventListener('click', event => {
            if (isMobile()) {
                if (!isOpen() || event.target.closest(CONTROL_SELECTOR) || event.detail === 0) return;
                const bounds = modal.getBoundingClientRect();
                move(event.clientX < bounds.left + bounds.width / 2 ? -1 : 1);
                return;
            }
            // 데스크톱의 사진·바탕 클릭 닫기는 유지한다.
            if (event.target === modalImage || event.target === modal) close();
        });

        modal.addEventListener('touchstart', event => {
            gesture = null;
            ignoredTouch = false;
            suppressClickUntil = 0;
            if (!isMobile() || !isOpen() || event.target.closest(CONTROL_SELECTOR)) return;
            if (event.touches.length !== 1) {
                ignoredTouch = true;
                suppressClickUntil = Date.now() + 800;
                return;
            }
            const touch = event.touches[0];
            gesture = {id: touch.identifier, x: touch.clientX, y: touch.clientY, maxDistance: 0, vertical: false};
        }, {passive: true});

        modal.addEventListener('touchmove', event => {
            if (!gesture) return;
            if (event.touches.length !== 1) {
                gesture = null;
                ignoredTouch = true;
                suppressClickUntil = Date.now() + 800;
                return;
            }
            const touch = event.touches[0];
            const dx = Math.abs(touch.clientX - gesture.x);
            const dy = Math.abs(touch.clientY - gesture.y);
            gesture.maxDistance = Math.max(gesture.maxDistance, dx, dy);
            if (dy > 10 && dy > dx) gesture.vertical = true;
        }, {passive: true});

        modal.addEventListener('touchend', event => {
            if (ignoredTouch) {
                suppressClickUntil = Date.now() + 800;
                if (event.touches.length === 0) ignoredTouch = false;
                return;
            }
            const current = gesture;
            gesture = null;
            if (!current || !isMobile() || !isOpen()) return;
            const touch = Array.from(event.changedTouches).find(item => item.identifier === current.id);
            if (!touch) return;
            const dx = touch.clientX - current.x;
            const dy = touch.clientY - current.y;
            const distance = Math.max(current.maxDistance, Math.abs(dx), Math.abs(dy));
            if (distance <= 10) return; // 작은 움직임은 일반 터치로 처리한다.
            suppressClickUntil = Date.now() + 800;
            if (!current.vertical && Math.abs(dx) >= 48 && Math.abs(dx) > Math.abs(dy) * 1.3) {
                move(dx < 0 ? 1 : -1);
            }
        }, {passive: true});

        modal.addEventListener('touchcancel', () => {
            gesture = null;
            suppressClickUntil = Date.now() + 800;
        }, {passive: true});
        // 브라우저 기본 Esc(cancel)·다른 경로로 닫혀도 같은 정리를 거친다.
        modal.addEventListener('cancel', event => {
            event.preventDefault();
            if (!isMobile()) close();
        });
        modal.addEventListener('close', close);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
