// 인기·지금 뜨는 여행지 전용. 다른 카드와 랜드마크의 이미지 처리는 건드리지 않는다.
(() => {
    const images = document.querySelectorAll('.popular-recommend .recommend-card-media img');
    const update = image => {
        const width = Number(image.dataset.sourceWidth) || image.naturalWidth;
        const height = Number(image.dataset.sourceHeight) || image.naturalHeight;
        const frame = image.parentElement;
        image.classList.toggle('is-small-popular', width > 0 && height > 0 &&
            (width < frame.clientWidth || height < frame.clientHeight));
        if (image.srcset && width > 0 && height > 0) {
            // cover 는 가로로 긴 사진을 높이 기준으로 확대하므로 보이는 칸 폭만으로 후보를 고르지 않는다.
            image.sizes = `${Math.ceil(Math.max(frame.clientWidth, frame.clientHeight * width / height))}px`;
        }
    };
    images.forEach(image => {
        const failed = () => {
            const original = image.dataset.originalSrc;
            delete image.dataset.originalSrc;
            if (original) {
                image.removeAttribute('srcset');
                image.removeAttribute('sizes');
                image.src = original;
            } else {
                image.classList.toggle('is-image-unavailable', true);
            }
        };
        image.addEventListener('error', failed);
        image.addEventListener('load', () => {
            image.classList.toggle('is-image-unavailable', false);
            update(image);
        });
        update(image);
        // defer 스크립트보다 먼저 완료된 성공·실패 요청도 처리한다.
        if (image.complete) {
            if (image.naturalWidth) update(image);
            else failed();
        }
    });
    window.addEventListener('resize', () => {
        images.forEach(image => { if (image.complete && image.naturalWidth) update(image); });
    });
})();
