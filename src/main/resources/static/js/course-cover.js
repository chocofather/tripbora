document.addEventListener('DOMContentLoaded', () => {
    const area = document.querySelector('[data-course-cover]');
    if (!area) return;
    const file = area.querySelector('#course-cover-file');
    const preview = area.querySelector('[data-cover-preview]');
    const image = area.querySelector('[data-cover-image]');
    const select = area.querySelector('[data-cover-select]');
    const remove = area.querySelector('[data-cover-remove]');
    const removeValue = area.querySelector('[data-cover-remove-value]');
    const message = area.querySelector('[data-cover-message]');
    const existing = area.dataset.existingCover || '';
    let objectUrl = null;
    const release = () => {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        objectUrl = null;
    };
    const show = url => {
        preview.hidden = !url;
        remove.hidden = !url;
        select.textContent = url ? '이미지 변경' : '이미지 선택';
        if (url) image.src = url;
        else image.removeAttribute('src');
    };
    show(removeValue.value === 'true' ? '' : existing);
    select.addEventListener('click', () => file.click());
    file.addEventListener('change', () => {
        const chosen = file.files[0];
        if (!chosen) return;
        if (!['image/jpeg', 'image/png', 'image/webp'].includes(chosen.type) || chosen.size > 10 * 1024 * 1024) {
            file.value = '';
            release();
            show(removeValue.value === 'true' ? '' : existing);
            message.textContent = 'JPG·PNG·WEBP 이미지를 10MB 이하로 선택해 주세요.';
            return;
        }
        release();
        objectUrl = URL.createObjectURL(chosen);
        removeValue.value = 'false';
        message.textContent = '';
        show(objectUrl);
    });
    remove.addEventListener('click', () => {
        file.value = '';
        release();
        removeValue.value = 'true';
        message.textContent = '저장하면 첫 번째 여행지 이미지가 자동으로 사용됩니다.';
        show('');
    });
    image.addEventListener('error', () => {
        preview.hidden = true;
        message.textContent = '이미지를 표시할 수 없습니다. 이미지를 변경하거나 삭제해 주세요.';
    });
    window.addEventListener('pagehide', release);
});
