/**
 * 관리자 여행지 목록 - 선택 삭제.
 *
 * 행 체크박스는 form 속성으로 선택 삭제 폼에 묶여 있어, 체크한 여행지 ID만 destinationIds 로 한 번에 보낸다.
 * 선택 대상은 지금 쪽에 보이는 행뿐이다. 머리말 체크박스도 이 쪽의 행만 모두 선택/해제한다.
 * 여기서는 선택 수 표시, 머리말 체크 상태(전체/일부/없음), 삭제 확인, 중복 제출 방지만 맡는다.
 * 실제 삭제 규칙(연관 데이터·이미지 파일 정리)은 서버가 단건 삭제와 같게 처리한다.
 */
document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector("[data-destination-bulk-delete]");
    if (!form) return;

    const submitButton = form.querySelector("[data-destination-bulk-delete-submit]");
    const countLabel = form.querySelector("[data-destination-selection-count]");
    const selectAll = document.querySelector("[data-destination-select-all]");
    const choices = () => Array.from(document.querySelectorAll("[data-destination-select]"));
    const selected = () => choices().filter(choice => choice.checked);
    let submitting = false;

    function update() {
        const all = choices();
        const count = selected().length;
        submitButton.disabled = submitting || count === 0;
        submitButton.textContent = submitting ? "삭제 중..." : (count > 0 ? `선택 삭제 (${count})` : "선택 삭제");
        if (countLabel) {
            countLabel.textContent = count > 0 ? `이 페이지에서 ${count}건 선택` : "이 페이지에서 선택 없음";
        }
        if (selectAll) {
            selectAll.checked = all.length > 0 && count === all.length;
            selectAll.indeterminate = count > 0 && count < all.length;
            selectAll.disabled = submitting;
        }
        all.forEach(choice => choice.closest("tr")?.classList.toggle("is-selected", choice.checked));
    }

    document.addEventListener("change", event => {
        if (event.target.matches?.("[data-destination-select]")) update();
    });

    // 이 쪽에 보이는 행만 모두 선택하거나 모두 해제한다. 일부 선택 상태에서 누르면 모두 선택이 된다.
    selectAll?.addEventListener("change", () => {
        if (submitting) return;
        const checked = selectAll.checked;
        choices().forEach(choice => {
            choice.checked = checked;
        });
        update();
    });

    form.addEventListener("submit", event => {
        const count = selected().length;
        const message = `선택한 여행지 ${count}건을 삭제하시겠습니까?\n`
            + "관련 이미지와 여행지 데이터도 함께 삭제될 수 있습니다.\n"
            + "이 작업은 되돌릴 수 없습니다.";
        if (submitting || count === 0 || !window.confirm(message)) {
            event.preventDefault();
            return;
        }
        // 첫 제출 즉시 잠근다. 체크박스는 비활성화하면 전송 값에서 빠지므로 그대로 둔다.
        submitting = true;
        update();
    });

    // 오류 화면에서 뒤로 돌아오면(페이지 캐시) 다시 삭제할 수 있게 풀어 준다.
    window.addEventListener("pageshow", event => {
        if (!event.persisted) return;
        submitting = false;
        update();
    });

    update();
});
