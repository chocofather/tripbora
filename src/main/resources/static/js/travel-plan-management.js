/* 목록 관리와 확정본 열람 전용. 일정 편집·실시간 연결에는 관여하지 않는다. */
document.addEventListener('DOMContentLoaded', () => {
    const dialog = document.querySelector('[data-plan-manage-dialog]');
    if (!dialog) return;
    const messages = {
        delete: ['여행을 삭제할까요?', '모든 참여자의 일정·채팅·투표를 포함한 여행 전체가 삭제됩니다. 삭제 후에는 되돌릴 수 없습니다.', '여행 삭제'],
        leave: ['여행에서 나갈까요?', '내 참여 관계만 해제됩니다. 다른 친구들의 일정은 유지되며, 다시 참여하려면 초대 링크가 필요합니다.', '여행에서 나가기'],
        hide: ['내 목록에서 삭제할까요?', '이 여행을 내 목록에서 삭제하면 다시 열 수 없습니다. 다른 참여자의 기록은 유지됩니다. 마지막 보관자가 삭제하면 원본과 확정 기록도 영구 삭제됩니다. 되돌릴 수 없습니다.', '내 목록에서 삭제']
    };
    let selectedForm = null, returnFocus = null, previousOverflow = '';
    const confirm = dialog.querySelector('[data-plan-manage-confirm]');
    const close = () => { if (!confirm.disabled) dialog.close(); };
    function positionFriends() {
        document.querySelectorAll('.tp-final-friends[open] .tp-final-friends-panel').forEach(panel => {
            panel.style.transform = '';
            const rect = panel.getBoundingClientRect();
            const offset = rect.left < 16 ? 16 - rect.left
                : Math.min(0, document.documentElement.clientWidth - 16 - rect.right);
            panel.style.transform = `translateX(${offset}px)`;
        });
    }
    document.addEventListener('toggle', event => {
        if (event.target.matches('.tp-final-friends')) positionFriends();
    }, true);
    window.addEventListener('resize', positionFriends);
    document.addEventListener('click', event => {
        const button = event.target.closest('[data-plan-manage-open]');
        if (button) {
            event.preventDefault();
            const form = button.closest('form');
            const message = messages[form?.dataset.manageKind];
            if (!message || dialog.open) return;
            selectedForm = form;
            const menu = button.closest('[data-plan-popover]');
            returnFocus = menu ? menu.querySelector('summary') : button;
            if (menu) menu.open = false;
            dialog.querySelector('[data-plan-manage-title]').textContent = message[0];
            dialog.querySelector('[data-plan-manage-trip]').textContent = form.dataset.manageTrip || '';
            dialog.querySelector('[data-plan-manage-description]').textContent = message[1];
            confirm.textContent = message[2]; confirm.disabled = false;
            previousOverflow = document.body.style.overflow;
            dialog.showModal(); document.body.style.overflow = 'hidden';
            dialog.querySelector('[data-plan-manage-cancel]').focus();
            return;
        }
        document.querySelectorAll('[data-plan-popover][open]').forEach(popover => {
            if (!popover.contains(event.target)) popover.open = false;
        });
        if (event.target === dialog) {
            const rect = dialog.getBoundingClientRect();
            if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close();
        }
    });
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || dialog.open) return;
        document.querySelectorAll('[data-plan-popover][open]').forEach(popover => {
            popover.open = false; popover.querySelector('summary').focus();
        });
    });
    dialog.querySelector('[data-plan-manage-cancel]').addEventListener('click', close);
    dialog.addEventListener('cancel', event => { if (confirm.disabled) event.preventDefault(); });
    dialog.addEventListener('close', () => {
        selectedForm = null; document.body.style.overflow = previousOverflow; returnFocus?.focus();
    });
    confirm.addEventListener('click', () => {
        if (!selectedForm || confirm.disabled) return;
        // 서버가 렌더링한 기존 액션과 CSRF 필드를 그대로 제출한다.
        confirm.disabled = true;
        selectedForm.requestSubmit();
    });
    window.addEventListener('pageshow', () => { confirm.disabled = false; });
});
