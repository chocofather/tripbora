/**
 * 커뮤니티 글쓰기 종류 선택 모달.
 * 어느 게시판에서 눌러도 여행 질문 / 여행 팁을 고를 수 있고,
 * 선택하면 기존 작성 페이지 링크로 그대로 이동한다.
 */
document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('board-write-modal');
    const openButtons = document.querySelectorAll('[data-board-write-open]');
    if (!modal || openButtons.length === 0) return;

    function open() {
        modal.hidden = false;
        modal.querySelector('.board-write-option')?.focus();
    }

    function close() {
        modal.hidden = true;
    }

    openButtons.forEach(button => button.addEventListener('click', open));

    modal.addEventListener('click', event => {
        // 모달 내부 클릭은 backdrop 닫기로 이어지지 않는다. (항목 링크는 그대로 이동)
        if (event.target === modal || event.target.closest('[data-board-write-close]')) {
            close();
        }
    });

    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && !modal.hidden) close();
    });
});
