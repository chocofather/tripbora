/*
  여행지 카드 썸네일(img.card-thumb)의 자르는 위치.

  여행지 목록은 CSS 로 중앙 cover 를 유지하며 이 위치 보정에서 제외한다.
  그 밖의 카드에서 세로 사진을 가운데 기준으로 자르면
  탑·고층 건물의 윗부분이 먼저 잘리므로, 실제 픽셀 비율(세로/가로)로 단계를 정해 data-thumb-shape 를 붙이고
  style.css 가 단계별로 조금 위쪽을 남긴다. 출처(업로드·외부 주소)와 관계없이 같은 기준이다.
  - 1.2 이상: portrait (일반 세로 사진)
  - 1.6 이상: tall (타워처럼 매우 긴 세로 사진)
  그보다 낮은 사진(가로·정사각형)은 지금처럼 가운데를 자른다.

  카드는 서버 렌더·fetch 렌더·복제(랜드마크 레일)로 생기고 lazy 로딩이라, 문서 전체에서 img load 를 받아 처리한다.
*/
(() => {
    const PORTRAIT_RATIO = 1.2;
    const TALL_RATIO = 1.6;

    const update = image => {
        if (image.closest('.destination-card-media')) return;
        if (image.closest('.home-converge-photo')) {
            // srcset 썸네일의 픽셀 크기를 원본 저해상도로 오인하지 않도록 원본 치수를 우선한다.
            const width = Number(image.dataset.sourceWidth) || image.naturalWidth;
            const height = Number(image.dataset.sourceHeight) || image.naturalHeight;
            const frame = image.parentElement;
            const small = width > 0 && height > 0 &&
                (width < frame.clientWidth || height < frame.clientHeight);
            image.classList.toggle('is-small-landmark', small);
            frame.classList.toggle('is-letterbox', small || image.classList.contains('is-no-derivatives'));
            return;
        }
        const ratio = image.naturalWidth ? image.naturalHeight / image.naturalWidth : 0;
        if (ratio >= TALL_RATIO) {
            image.dataset.thumbShape = 'tall';
        } else if (ratio >= PORTRAIT_RATIO) {
            image.dataset.thumbShape = 'portrait';
        } else {
            delete image.dataset.thumbShape;
        }
    };

    document.addEventListener('load', event => {
        if (event.target instanceof HTMLImageElement && event.target.classList.contains('card-thumb')) {
            update(event.target);
        }
    }, true);

    // 이 스크립트보다 먼저 받아진 사진
    document.querySelectorAll('img.card-thumb').forEach(image => {
        if (image.complete) update(image);
    });

    window.addEventListener('resize', () => {
        document.querySelectorAll('.home-converge-photo img.card-thumb').forEach(image => {
            if (image.complete) update(image);
        });
    });

})();
