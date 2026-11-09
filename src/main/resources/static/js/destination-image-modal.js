/**
 * 여행지 상세 이미지 확대 모달.
 * 캐러셀 이미지와 대표 이미지(no-image 케이스) 모두 클릭하면 원본 비율로 크게 보여준다.
 *
 * - 리스너는 로드 즉시 document 에 캡처 단계로 걸고, 모달 요소는 클릭 시점에 찾는다.
 * - 열 때는 항상 showModal() 로 top layer 에 올린다.
 * - 상세 페이지의 이미지 목록을 그대로 써서 이전/다음 이동을 지원한다.
 * - 모달 아래에 지금 보이는 사진의 출처를 캐러셀 슬라이드의 data-* 값으로 보여준다.
 * - 주소는 있지만 받지 못한 사진은 깨진 아이콘·대체 문구 대신 빈 자리로 두거나 영역을 숨긴다.
 */
(function () {
    const MODAL_ID = 'destination-image-modal';
    const CAPTION_ID = 'destination-image-modal-caption';
    const IMAGE_SELECTOR = '.carousel .slide img, .carousel.no-image img';

    // 현재 모달이 보여주는 이미지 목록과 위치
    let images = [];
    let currentIndex = 0;
    const mobile = window.matchMedia('(max-width: 600px)');
    let returnFocus = null;
    let previousOverflow = '';
    let gesture = null;
    let ignoreImageClick = false;
    let galleryScope = true;
    let pagedGallery = null;
    let navigation = 0;
    let moving = false;
    let retryStep = 1;

    function getModal() {
        return document.getElementById(MODAL_ID);
    }

    function isOpen(modal) {
        return !!modal && modal.classList.contains('is-open');
    }

    function collectImages() {
        return Array.from(document.querySelectorAll(IMAGE_SELECTOR));
    }

    function render(modal) {
        const modalImage = modal.querySelector('.image-modal-img');
        const source = pagedGallery ? pagedGallery.current() : images[currentIndex];
        if (!modalImage) return;
        if (!source) {
            modalImage.removeAttribute('src');
            const caption = document.getElementById(CAPTION_ID);
            if (caption) caption.hidden = true;
            if (pagedGallery) {
                modal.querySelector('.image-modal-nav.prev').disabled = !pagedGallery.canMove(-1);
                modal.querySelector('.image-modal-nav.next').disabled = !pagedGallery.canMove(1);
            }
            return;
        }

        modalImage.classList.remove('is-error');
        modalImage.src = source.currentSrc || source.src;
        renderCaption(source);
        const messages = document.getElementById('destination-detail-i18n')?.dataset;
        modalImage.alt = source.alt || messages?.galleryFallbackAlt || '';
        if (mobile.matches) modalImage.setAttribute('draggable', 'false');
        else modalImage.removeAttribute('draggable');
        // 이미지가 한 장뿐이면 좌/우 버튼을 숨긴다.
        modal.classList.toggle('is-single', !pagedGallery && images.length <= 1);
        if (pagedGallery) {
            modal.querySelector('.image-modal-nav.prev').disabled = !pagedGallery.canMove(-1);
            modal.querySelector('.image-modal-nav.next').disabled = !pagedGallery.canMove(1);
        } else {
            modal.querySelectorAll('.image-modal-nav').forEach(button => { button.disabled = false; });
        }
        if (galleryScope && !pagedGallery) {
            document.dispatchEvent(new CustomEvent('destination-gallery-change', {
                detail: {index: currentIndex}
            }));
        }
    }

    function captionNode(tag, text) {
        const node = document.createElement(tag);
        node.textContent = text;
        return node;
    }

    function captionLink(text, href, rel) {
        const link = captionNode('a', text);
        link.href = href;
        link.target = '_blank';
        link.rel = rel;
        return link;
    }

    /**
     * 지금 보이는 사진의 출처. 캐러셀 아래 출처와 같은 값(제공처 · 라이선스 · 촬영 · 크레딧 · 출처 링크)을
     * 있는 것만 이어 붙인다. 출처 정보가 없는 사진은 캡션을 숨긴다.
     */
    function renderCaption(source, caption = document.getElementById(CAPTION_ID)) {
        if (!caption) return;
        const data = source.closest('.slide')?.dataset || {};
        const labels = document.getElementById(CAPTION_ID)?.dataset || caption.dataset;

        const reviewId = data.reviewId || source.dataset.reviewId;
        if (reviewId && /^\d+$/.test(reviewId)) {
            const link = captionNode('a', labels.reviewLinkLabel || '');
            // 두 식별자 모두 서버/현재 상세 URL에서 얻는다. 외부 주소나 HTML은 사용하지 않는다.
            const destinationId = window.location.pathname.split('/').pop();
            const reviewUrl = data.reviewUrl || (/^\d+$/.test(destinationId)
                ? `/destinations/${destinationId}?commentId=${reviewId}` : null);
            const nodes = [captionNode('span', labels.travelerPhotoLabel || '')];
            if (reviewUrl) {
                link.href = reviewUrl;
                nodes.push(link);
            }
            caption.replaceChildren(...nodes);
            caption.hidden = false;
            return;
        }

        if (!data.sourceName && !data.licenseLabel && !data.photographer && !data.sourceUrl) {
            caption.replaceChildren();
            caption.hidden = true;
            return;
        }
        const parts = [];
        if (data.sourceName) parts.push(captionNode('span', data.sourceName));
        if (data.licenseLabel) {
            parts.push(data.licenseUrl
                ? captionLink(data.licenseLabel, data.licenseUrl, 'noopener noreferrer license')
                : captionNode('span', data.licenseLabel));
        }
        if (data.photographer) parts.push(captionNode('span', `${labels.photographerLabel} ${data.photographer}`));
        if (data.credit) parts.push(captionNode('span', `${labels.creditLabel} ${data.credit}`));

        const nodes = [captionNode('span', labels.sourceLabel)];
        parts.forEach((part, index) => {
            if (index > 0) {
                const separator = captionNode('span', '·');
                separator.setAttribute('aria-hidden', 'true');
                nodes.push(separator);
            }
            nodes.push(part);
        });
        if (data.sourceUrl) {
            const link = captionLink('↗', data.sourceUrl, 'noopener noreferrer');
            link.setAttribute('aria-label', labels.sourceLinkLabel || '');
            nodes.push(link);
        }
        caption.replaceChildren(...nodes);
        caption.hidden = false;
    }

    /** 순환 이동. 첫 장에서 이전 -> 마지막, 마지막에서 다음 -> 첫 장. */
    async function move(step) {
        const modal = getModal();
        if (pagedGallery) {
            if (moving || !modal) return;
            moving = true; retryStep = step;
            const version = navigation;
            const status = modal.querySelector('[data-gallery-viewer-status]');
            const retry = modal.querySelector('[data-gallery-viewer-retry]');
            const labels = document.getElementById('destination-detail-i18n')?.dataset || {};
            if (status) status.textContent = labels.galleryLoading || '';
            if (retry) retry.hidden = true;
            modal.setAttribute('aria-busy', 'true');
            const moved = await pagedGallery.move(step);
            if (version !== navigation) return;
            moving = false; modal.setAttribute('aria-busy', 'false');
            render(modal);
            if (status) status.textContent = pagedGallery.error() ? labels.galleryLoadFailed : !moved ? labels.galleryContinue : '';
            if (retry) retry.hidden = !pagedGallery.error();
            return;
        }
        if (!modal || images.length <= 1) return;
        currentIndex = (currentIndex + step + images.length) % images.length;
        render(modal);
    }

    /** <dialog> 를 못 쓰는 환경에서만 쓰는 대체 경로. */
    function openWithoutDialogSupport(modal) {
        modal.classList.add('is-fallback');
        modal.setAttribute('open', '');
    }

    function openModal(image) {
        const modal = getModal();
        if (!modal) return;

        const group = image.closest('.comment-images');
        galleryScope = !group;
        pagedGallery = !group ? window.TripBoraGallery?.viewerFor?.(image) : null;
        images = pagedGallery ? [] : group ? Array.from(group.querySelectorAll('.comment-image')) : collectImages();
        currentIndex = Math.max(images.indexOf(image), 0);
        navigation++; moving = false;
        modal.querySelector('[data-gallery-viewer-status]')?.replaceChildren();
        const retry = modal.querySelector('[data-gallery-viewer-retry]');
        if (retry) retry.hidden = true;
        returnFocus = image;
        previousOverflow = document.body.style.overflow;
        gesture = null;
        ignoreImageClick = false;
        render(modal);
        // showModal()이 닫기 버튼에 포커스를 주기 전에 숨김 접근성 상태를 해제한다.
        modal.removeAttribute('aria-hidden');

        // showModal() 은 open 상태에서 호출하면 예외가 나고,
        // open 속성만 붙은 dialog 는 top layer 밖(비모달)으로 렌더링돼 화면에 보이지 않는다.
        // 그래서 남아 있는 open 상태를 먼저 정리한 뒤 항상 showModal() 로 연다.
        if (typeof modal.showModal === 'function') {
            try {
                if (modal.open) modal.close();
                modal.classList.remove('is-fallback');
                modal.showModal();
            } catch (error) {
                openWithoutDialogSupport(modal);
            }
        } else {
            openWithoutDialogSupport(modal);
        }
        modal.classList.add('is-open');
        modal.removeAttribute('aria-hidden');
        document.body.style.overflow = 'hidden';
    }

    function closeModal() {
        const modal = getModal();
        if (!modal) return;

        if (typeof modal.close === 'function' && modal.open) {
            modal.close();
        } else {
            modal.removeAttribute('open');
        }
        finishClose(modal);
    }

    function finishClose(modal) {
        if (!modal.classList.contains('is-open')) return;
        modal.classList.remove('is-open', 'is-fallback');
        document.body.style.overflow = previousOverflow;
        gesture = null;
        ignoreImageClick = false;
        // -1은 탭 순서를 추가하지 않고 프로그램 포커스 복귀만 허용한다.
        // 모달 이동은 기존 캐러셀의 활성 사진도 바꾸므로 현재 보이는 사진으로 복귀한다.
        const focusTarget = pagedGallery ? pagedGallery.focus() : returnFocus && (images[currentIndex] || returnFocus);
        if (focusTarget?.isConnected) {
            const tabindex = focusTarget.getAttribute('tabindex');
            if (tabindex === null) focusTarget.setAttribute('tabindex', '-1');
            focusTarget.focus({preventScroll: true});
        }
        returnFocus = null;
        pagedGallery?.cancel();
        navigation++; moving = false; pagedGallery = null; images = [];
        modal.removeAttribute('aria-busy');
        modal.setAttribute('aria-hidden', 'true');

        const modalImage = modal.querySelector('.image-modal-img');
        if (modalImage) {
            modalImage.removeAttribute('src');
        }
    }

    // 캡처 단계로 걸어 다른 스크립트가 클릭을 가로채도 확대가 동작하게 한다.
    document.addEventListener('click', (event) => {
        const target = event.target;
        if (!target || typeof target.closest !== 'function') return;

        if (target.closest('.image-modal-close')) {
            event.preventDefault();
            closeModal();
            return;
        }
        if (target.closest('[data-gallery-viewer-retry]')) {
            event.preventDefault(); void move(retryStep); return;
        }

        // 좌/우 이동. 닫기(이미지·배경 클릭)로 이어지지 않도록 여기서 끊는다.
        const nav = target.closest('.image-modal-nav');
        if (nav) {
            event.preventDefault();
            event.stopPropagation();
            move(nav.classList.contains('prev') ? -1 : 1);
            return;
        }

        // 스마트폰에서는 이미지 안의 탭을 배경 닫기로 전파하지 않는다.
        if (target.closest('.image-modal-img')) {
            event.preventDefault();
            event.stopPropagation();
            if (!mobile.matches) {
                closeModal();
                return;
            }
            if (ignoreImageClick) {
                ignoreImageClick = false;
                return;
            }
            const rect = target.closest('.image-modal-img').getBoundingClientRect();
            const position = (event.clientX - rect.left) / rect.width;
            if (position < .38) move(-1);
            else if (position > .62) move(1);
            return;
        }

        // 모달 바깥(백드롭) 클릭
        const modal = getModal();
        if (modal && target === modal) {
            closeModal();
            return;
        }

        // contain 이미지 주변 여백과 전환 중 down/up 대상의 공통 조상(track)도 같은 사진을 연다.
        const surface = target.closest('.carousel .slide, .carousel');
        const image = target.closest(IMAGE_SELECTOR) || (surface && !target.closest('.nav')
            ? (surface.querySelector('.slide.active img') || surface.querySelector('img')) : null);
        const reviewImage = target.closest('.comment-image');
        const gridImage = target.closest('[data-gallery-tile]')?.querySelector('img');
        if (!image && !reviewImage && !gridImage) return;
        if (window.TripBoraGallery?.suppressClick && image) { event.preventDefault(); event.stopPropagation(); return; }
        event.preventDefault();
        event.stopPropagation();
        openModal(image || reviewImage || gridImage);
    }, true);

    // pan-y는 세로 제스처를 브라우저에 맡기고 가로 이동만 이 모달이 처리한다.
    document.addEventListener('pointerdown', (event) => {
        const image = event.target?.closest?.('.image-modal-img');
        if (!mobile.matches || !isOpen(getModal()) || !image || event.isPrimary === false || event.button !== 0) return;
        ignoreImageClick = false;
        gesture = {id: event.pointerId, x: event.clientX, y: event.clientY};
        image.setPointerCapture?.(event.pointerId);
    }, true);

    document.addEventListener('pointerup', (event) => {
        if (!gesture || gesture.id !== event.pointerId) return;
        const dx = event.clientX - gesture.x;
        const dy = event.clientY - gesture.y;
        gesture = null;
        ignoreImageClick = Math.max(Math.abs(dx), Math.abs(dy)) > 12;
        if (mobile.matches && Math.abs(dx) >= 40 && Math.abs(dx) > Math.abs(dy) * 1.4) {
            event.preventDefault();
            move(dx < 0 ? 1 : -1);
        }
    }, true);

    document.addEventListener('pointercancel', () => {
        gesture = null;
        ignoreImageClick = true;
    }, true);

    // 모달이 열려 있을 때만 키보드에 반응한다.
    document.addEventListener('keydown', (event) => {
        const modal = getModal();
        if (!isOpen(modal)) return;

        if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            closeModal();
            return;
        }
        if (event.key === 'ArrowLeft') {
            event.preventDefault();
            move(-1);
            return;
        }
        if (event.key === 'ArrowRight') {
            event.preventDefault();
            move(1);
        }
    });

    // ESC 등으로 dialog 가 스스로 닫힐 때 상태를 맞춘다.
    document.addEventListener('close', (event) => {
        if (event.target && event.target.id === MODAL_ID) {
            if (!event.target.open) finishClose(event.target);
        }
    }, true);

    /**
     * 주소는 있지만 받지 못한 사진. 대표 이미지 한 장이면 영역을 숨기고, 슬라이드는 빈 자리로 두되
     * 모든 슬라이드가 실패하면 캐러셀째 숨긴다(인덱스가 어긋나지 않게 슬라이드는 지우지 않는다).
     */
    function hideBrokenImage(image) {
        if (window.TripBoraGallery?.discard?.(image)) return;
        const cover = image.closest('.carousel.no-image');
        if (cover) {
            cover.hidden = true;
            return;
        }
        const slide = image.closest('.slide');
        if (!slide) return;
        slide.classList.add('is-image-error');
        const wrapper = slide.closest('.carousel-wrapper');
        if (wrapper && !wrapper.querySelector('.slide:not(.is-image-error)')) wrapper.hidden = true;
    }

    // error 는 버블링되지 않으므로 캡처 단계에서 받는다.
    document.addEventListener('error', (event) => {
        const target = event.target;
        if (!target || typeof target.matches !== 'function') return;
        if (target.matches('.image-modal-img')) {
            if (pagedGallery && target.getAttribute('src')) {
                pagedGallery.discard(); render(getModal()); return;
            }
            if (target.getAttribute('src')) target.classList.add('is-error');
            return;
        }
        if (target.matches(IMAGE_SELECTOR)) hideBrokenImage(target);
        else if (target.matches('#destination-gallery-grid img, .destination-gallery-thumbnails img')) window.TripBoraGallery?.discard?.(target);
    }, true);

    // 스크립트보다 먼저 실패한 사진. 지연 로딩 사진은 아직 받기 전일 수 있어 위 리스너에 맡긴다.
    collectImages().forEach((image) => {
        if (image.complete && image.naturalWidth === 0 && image.getAttribute('loading') !== 'lazy') {
            hideBrokenImage(image);
        }
    });
    window.TripBoraImageViewer = {caption: renderCaption};
})();
