document.addEventListener('DOMContentLoaded', () => {
    const picker = document.querySelector('[data-course-theme-picker]');
    if (!picker) return;
    const chips = Array.from(picker.querySelectorAll('[data-course-theme]'));
    const inputs = picker.querySelector('[data-course-theme-inputs]');
    const status = picker.querySelector('[data-course-theme-status]');
    const available = new Set(chips.map(chip => chip.dataset.themeId));
    // edit/retry의 hidden input 순서가 mapping display_order다.
    let selected = [...new Set(Array.from(inputs.querySelectorAll('input')).map(input => input.value))]
        .filter(id => available.has(id)).slice(0, 3);
    function render() {
        chips.forEach(chip => chip.setAttribute('aria-pressed', String(selected.includes(chip.dataset.themeId))));
        inputs.replaceChildren(...selected.map(id => {
            const input = document.createElement('input'); input.type = 'hidden';
            input.name = 'themeIds'; input.value = id; return input;
        }));
    }
    chips.forEach(chip => chip.addEventListener('click', () => {
        const id = chip.dataset.themeId;
        if (selected.includes(id)) selected = selected.filter(value => value !== id);
        else if (selected.length === 3) {
            status.textContent = '테마는 최대 3개까지 선택할 수 있어요.'; return;
        } else selected.push(id);
        status.textContent = ''; render();
    }));
    render();
});
