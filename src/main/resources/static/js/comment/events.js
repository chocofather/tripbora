import {
    postComment,
    postReply,
    toggleLikeApi,
    deleteCommentApi,
    fetchOwnCommentImages,
    updateCommentWithImages,
    fetchCommentTranslation
} from './api.js';

import { detailMessage } from './messages.js';
import { initPhotoUploadTerms, appendPhotoUploadPolicy, refreshPhotoUploadTerms } from './uploadTerms.js';

/** 댓글 하나에 첨부할 수 있는 사진 수. 서버 DestinationCommentService.MAX_COMMENT_IMAGES 와 같은 값. */
const MAX_COMMENT_IMAGES = 5;
const photoEditors = new WeakMap();
const submittingForms = new WeakSet();

export function ratingMarkup(value, required) {
    const selected = Number(value);
    const choices = [1, 2, 3, 4, 5].map(score => `<label class="review-rating-choice">
      <input type="radio" name="rating" value="${score}"${selected === score ? ' checked' : ''}${required ? ' required' : ''}>
      <span aria-hidden="true">★</span><span class="content-comment-sr-only">${score} / 5</span>
    </label>`).join('');
    return `<fieldset class="review-rating-input"><legend>${detailMessage('reviewRating')}</legend>
      <div class="review-rating-options">${choices}</div>
      ${required ? '' : `<label class="review-rating-unscored"><input type="radio" name="rating" value=""${!selected ? ' checked' : ''}>${detailMessage('reviewUnscored')}</label>`}
    </fieldset>`;
}

function showFormError(form, error) {
    let feedback = form.querySelector('.comment-edit-feedback');
    if (!feedback) {
        feedback = document.createElement('p');
        feedback.className = 'comment-edit-feedback';
        feedback.setAttribute('role', 'status');
        form.append(feedback);
    }
    feedback.textContent = error?.message || '댓글을 저장하지 못했습니다. 다시 시도해 주세요.';
}

/**
 * 선택한 사진이 제한을 넘으면 안내하고 전송을 막는다.
 * @param {HTMLFormElement} form 댓글/답글 작성 폼
 * @returns {boolean} 제한을 넘어 전송하면 안 되는 경우 true
 */
function exceedsImageLimit(form) {
    const input = form.querySelector('input[type="file"][name="images"]');
    if (!input || input.files.length <= MAX_COMMENT_IMAGES) return false;
    alert(detailMessage('commentImageLimit', MAX_COMMENT_IMAGES));
    return true;
}

// 비회원 클릭 시 로그인 페이지로 리다이렉트
export function initGuestRedirect() {
    const guestBox = document.getElementById('guest-comment-box');
    if (!guestBox) return;
    guestBox.addEventListener('click', () => {
        const redirect = encodeURIComponent(window.location.pathname);
        window.location.href = `/login?redirect=${redirect}`;
    });
}

// 정렬 상태와 조회는 init.js 한 곳에서 관리한다.
export function setupSortDropdownEvent(containerEl, onSortChange) {
    containerEl?.addEventListener('click', e => {
        const button = e.target.closest('[data-comment-sort]');
        if (!button || !containerEl.contains(button)) return;
        onSortChange(button.dataset.commentSort);
    });
}

/**
 * 댓글 작성 사진 선택/미리보기.
 * 파일 선택창을 다시 열면 브라우저가 input.files 를 통째로 교체하므로,
 * 선택 목록(selected)을 따로 들고 DataTransfer 로 input.files 와 동기화해
 * 여러 번 나눠 고른 사진이 누적되게 한다.
 * @param {HTMLFormElement} form 댓글 작성 폼
 */
function initCommentImagePreview(form, existing = []) {
    const input = form.querySelector('input[type="file"][name="images"]');
    const preview = form.querySelector('.comment-image-preview');
    if (!input || !preview) return;
    initPhotoUploadTerms(form);

    /** 전송 대상 사진 목록. input.files 는 항상 이 목록과 같게 맞춘다. */
    let selected = [];
    let retained = [...existing];
    const removed = new Set();

    const fileKey = file => `${file.name}|${file.size}|${file.lastModified}`;

    /** selected 를 실제 input.files 에 반영한다. */
    function syncInput() {
        try {
            const transfer = new DataTransfer();
            selected.forEach(file => transfer.items.add(file));
            input.files = transfer.files;
        } catch (error) {
            // DataTransfer 미지원 환경에서도 전송은 selected에서 직접 구성한다.
        }
        // value 가 비면 같은 파일을 다시 골라도 change 가 정상 발생한다.
        if (selected.length === 0) input.value = '';
    }

    /** 새로 고른 파일을 기존 선택에 누적한다. 중복은 제외하고 최대 5장까지만 남긴다. */
    function addFiles(files) {
        const keys = new Set(selected.map(fileKey));
        const added = Array.from(files || []).filter(file => {
            if (keys.has(fileKey(file))) return false;
            keys.add(fileKey(file));
            return true;
        });
        const merged = selected.concat(added.filter(file => {
            if (file.size <= 10 * 1024 * 1024) return true;
            alert('사진은 장당 10MB 이하로 선택해 주세요.');
            return false;
        }));
        const available = MAX_COMMENT_IMAGES - retained.length;
        if (merged.length > available) {
            alert(detailMessage('commentImageLimit', MAX_COMMENT_IMAGES));
        }
        selected = merged.slice(0, available);
        syncInput();
        render();
    }

    function removeFileAt(index) {
        selected.splice(index, 1);
        syncInput();
        render();
    }

    function clearFiles() {
        selected = [];
        syncInput();
        render();
    }

    function dispose() {
        preview.querySelectorAll('img[data-object-url]').forEach(img => URL.revokeObjectURL(img.src));
    }

    function render() {
        dispose();
        preview.innerHTML = '';

        // 선택된 사진이 없으면 미리보기 영역 자체를 감춘다.
        preview.hidden = selected.length + retained.length === 0;
        retained.forEach(photo => {
            const item = document.createElement('div');
            item.className = 'comment-image-preview-item';
            const image = document.createElement('img');
            image.src = photo.imageUrl;
            image.alt = '첨부된 사진';
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'comment-image-remove';
            remove.setAttribute('aria-label', '첨부 사진 삭제');
            remove.textContent = '×';
            remove.addEventListener('click', () => {
                removed.add(photo.id);
                retained = retained.filter(other => other.id !== photo.id);
                render();
            });
            item.append(image, remove);
            preview.append(item);
        });

        selected.forEach((file, index) => {
            const item = document.createElement('div');
            item.className = 'comment-image-preview-item';

            const image = document.createElement('img');
            image.src = URL.createObjectURL(file);
            image.dataset.objectUrl = 'true';
            image.alt = detailMessage('commentFilePreview', file.name);

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'comment-image-remove';
            remove.setAttribute('aria-label', detailMessage('commentFileRemove', file.name));
            remove.textContent = '×';
            remove.addEventListener('click', () => removeFileAt(index));

            item.append(image, remove);
            preview.append(item);
        });
    }

    input.addEventListener('change', () => addFiles(input.files));
    // 등록 성공 후 form.reset() 이 호출되면 선택/미리보기도 함께 비운다.
    form.addEventListener('reset', () => setTimeout(clearFiles, 0));
    const editor = {
        dispose,
        append(data) {
            data.delete('images');
            selected.forEach(file => data.append('images', file));
            removed.forEach(id => data.append('removeImageIds', id));
        }
    };
    photoEditors.set(form, editor);
    render();
    return editor;
}

/**
 * 0/2000 글자수 표시. (커뮤니티 게시글 댓글 작성폼과 같은 방식)
 * @param {HTMLFormElement} form 댓글 작성 폼
 */
function initCommentLengthCounter(form) {
    const textarea = form.querySelector('textarea[name="content"]');
    const output = form.querySelector('[data-comment-length]') || form.querySelector('#comment-length');
    if (!textarea || !output) return;

    const update = () => {
        output.textContent = String(textarea.value.length);
    };
    textarea.addEventListener('input', update);
    // 등록 성공 후 form.reset() 이 호출되면 글자수도 함께 되돌린다.
    form.addEventListener('reset', () => setTimeout(update, 0));
    update();
}

// 댓글 등록 폼
export function initCommentForm(destinationId, onCommentsReload) {
    const form = document.getElementById('comment-form');
    if (!form) return;
    initCommentImagePreview(form);
    initCommentLengthCounter(form);
    form.addEventListener('submit', async e => {
        e.preventDefault();
        if (exceedsImageLimit(form)) return;
        if (submittingForms.has(form)) return;
        submittingForms.add(form);
        const button = form.querySelector('button[type="submit"]');
        if (button) button.disabled = true;
        try {
            const data = new FormData(form);
            photoEditors.get(form)?.append(data);
            appendPhotoUploadPolicy(form, data);
            await postComment(destinationId, data);
            form.reset();
            const feedback = form.querySelector('.comment-edit-feedback');
            if (feedback) feedback.textContent = '';
            const composer = form.closest('#review-composer');
            if (composer) {
                composer.hidden = true;
                const toggle = document.querySelector('.review-write-toggle');
                toggle?.setAttribute('aria-expanded', 'false');
                toggle?.focus();
            }
            onCommentsReload();
        } catch (error) {
            if (error.status === 409) await refreshPhotoUploadTerms(form);
            showFormError(form, error);
        } finally {
            submittingForms.delete(form);
            if (button) button.disabled = false;
        }
    });
}

// 답글(대댓글) 등록
export function bindReplySubmit(containerEl, destinationId, onCommentsReload) {
    if (!containerEl) return;
    containerEl.addEventListener('submit', async e => {
        const form = e.target.closest('.nested-reply-form');
        if (!form) return;
        e.preventDefault();
        if (exceedsImageLimit(form)) return;
        if (submittingForms.has(form)) return;
        submittingForms.add(form);
        const button = form.querySelector('button[type="submit"]');
        if (button) button.disabled = true;
        try {
            const commentDiv = form.closest('.comment-item');
            let parentId = commentDiv.dataset.id;
            let mention = '';
            if (commentDiv.dataset.parentId) {
                parentId = commentDiv.dataset.parentId;
                mention = commentDiv.dataset.writerNickname ? `@${commentDiv.dataset.writerNickname} ` : '';
            }
            if (commentDiv.dataset.parentId && form.closest('.reply-list').parentElement.dataset.parentId) {
                parentId = form.closest('.reply-list').parentElement.dataset.parentId;
                mention = form.closest('.reply-list').parentElement.dataset.writerNickname
                    ? `@${form.closest('.reply-list').parentElement.dataset.writerNickname} `
                    : '';
            }
            const textarea = form.querySelector('textarea[name="content"]');
            if (mention && !textarea.value.startsWith(mention)) {
                textarea.value = mention + textarea.value;
            }
            const data = new FormData(form);
            photoEditors.get(form)?.append(data);
            appendPhotoUploadPolicy(form, data);
            data.set('parentCommentId', parentId);

            await postReply(destinationId, parentId, data);
            photoEditors.get(form)?.dispose();
            form.remove();
            onCommentsReload();
        } catch (error) {
            if (error.status === 409) await refreshPhotoUploadTerms(form);
            showFormError(form, error);
        } finally {
            submittingForms.delete(form);
            if (button) button.disabled = false;
        }
    });
}

// 댓글 액션 바인딩(수정, 삭제 등은 필요시 배열 업데이트 & 부분 렌더로)
export function bindCommentActions(containerEl, onCommentsReload) {
    if (!containerEl) return;
    containerEl.addEventListener('click', e => {
        const commentDiv = e.target.closest('.comment-item');
        if (!commentDiv) return;
        const id = commentDiv.dataset.id;
        if (handleTranslation(e, commentDiv, id)) return;
        if (handleLike(e, id, onCommentsReload)) return;
        if (handleDelete(e, id, onCommentsReload)) return;
        if (showEditForm(e, commentDiv)) return;
        if (handleSaveEdit(e, commentDiv, id, onCommentsReload)) return;
        if (handleCancelEdit(e, commentDiv, id)) return;
        if (!containerEl.closest('[data-reviews-only]')) {
            if (toggleReplyForm(e, commentDiv)) return;
            if (cancelReply(e)) return;
        }
    });
}

function handleTranslation(e, commentDiv, id) {
    const button = e.target.closest('.translate-btn');
    if (!button) return false;
    const content = commentDiv.querySelector('.comment-content');
    const status = commentDiv.querySelector('.content-comment-translation-status');
    if (!content || !status) return true;

    if (button.dataset.translationState === 'translated') {
        content.innerHTML = safeOriginalMarkup(content.dataset.originalContent || '');
        button.dataset.translationState = 'original';
        button.textContent = detailMessage('commentTranslate');
        status.hidden = true;
        status.textContent = '';
        return true;
    }

    button.disabled = true;
    button.textContent = detailMessage('commentTranslating');
    status.hidden = true;
    requestTranslation(id, button, content, status, 0);
    return true;
}

async function requestTranslation(id, button, content, status, attempt) {
    try {
        const response = await fetchCommentTranslation(id);
        if (response.status === 'PROCESSING') {
            const waitSeconds = Math.max(1, Number(response.retryAfterSeconds || 1));
            if (attempt >= 3) {
                button.disabled = false;
                button.textContent = detailMessage('commentTranslate');
                status.textContent = detailMessage('commentTranslationRetry');
                status.hidden = false;
                return;
            }
            window.setTimeout(
                () => requestTranslation(id, button, content, status, attempt + 1),
                Math.min(waitSeconds, 30) * 1000);
            return;
        }
        if (response.status !== 'READY' || typeof response.translatedText !== 'string' || !response.translatedText.trim()) {
            throw new Error('Invalid translation result');
        }
        // 외부 번역 결과는 HTML로 해석하지 않는다.
        content.textContent = response.translatedText;
        button.dataset.translationState = 'translated';
        button.textContent = detailMessage('commentOriginal');
        button.disabled = false;
        status.hidden = true;
        status.textContent = '';
    } catch (error) {
        button.disabled = false;
        button.textContent = detailMessage('commentTranslate');
        status.textContent = error.status === 429
            ? detailMessage('commentTranslationRateLimited')
            : detailMessage('commentTranslationFailed');
        status.hidden = false;
    }
}

function safeOriginalMarkup(original) {
    const holder = document.createElement('div');
    holder.textContent = original;
    return holder.innerHTML.replace(/@([^\s@]+)/g, (match, nickname) =>
        `<span class="mention content-comment-mention">@${nickname}</span>`);
}

function handleLike(e, id, onCommentsReload) {
    const btn = e.target.closest('.like-btn');
    if (!btn) return false;
    if (!document.getElementById('comment-form')) {
        console.warn('로그인이 필요합니다.');
        return true;
    }
    toggleLikeApi(id)
        .then(() => onCommentsReload())
        .catch(err => console.error('좋아요 실패:', err));
    return true;
}

function handleDelete(e, id, onCommentsReload) {
    if (!e.target.matches('.delete-btn')) return false;
    if (!confirm(detailMessage('commentDeleteConfirm'))) return true;
    deleteCommentApi(id)
        .then(res => {
            if (res.ok) {
                onCommentsReload();
            } else {
                alert(detailMessage('commentDeleteForbidden'));
            }
        })
        .catch(err => console.error('삭제 실패:', err));
    return true;
}





function showEditForm(e, commentDiv) {
    if (!e.target.matches('.edit-btn')) return false;
    if (commentDiv.classList.contains('editing')) return true;
    const reply = commentDiv.querySelector('.reply-form');
    photoEditors.get(reply?.querySelector('form'))?.dispose();
    reply?.remove();
    const contentEl = commentDiv.querySelector('.comment-content');
    const original = contentEl.dataset.originalContent ?? contentEl.textContent;
    const form = document.createElement('form');
    form.className = 'edit-comment-form comment-write-form';
    const uniqueId = `edit-image-${commentDiv.dataset.id}`;
    form.innerHTML = `
    ${commentDiv.dataset.parentId ? '' : ratingMarkup(commentDiv.dataset.rating, Boolean(commentDiv.dataset.rating))}
    <div class="textarea-wrapper">
      <textarea name="content" class="edit-text" maxlength="2000" required></textarea>
    </div>
    <div class="comment-image-preview" hidden></div>
    <div class="comment-controls">
      <button type="button" class="cancel-edit-btn">${detailMessage('commentCancel')}</button>
      <span class="comment-length"><span data-comment-length>0</span>/2000</span>
      <input type="file" id="${uniqueId}" name="images" accept="image/jpeg,image/png,image/webp" multiple hidden disabled>
      <label for="${uniqueId}" class="image-upload-label"><span aria-hidden="true">＋</span>${detailMessage('commentPhoto')}</label>
      <button type="button" class="save-edit-btn submit-btn" disabled>${detailMessage('commentSave')}</button>
    </div>
    <p class="comment-edit-feedback" role="status"></p>
  `;
    form.querySelector('textarea').value = original;
    initCommentLengthCounter(form);
    form.addEventListener('submit', event => {
        event.preventDefault();
        form.querySelector('.save-edit-btn').click();
    });
    contentEl.hidden = true;
    const photos = contentEl.parentElement.querySelector(':scope > .comment-images');
    if (photos) photos.hidden = true;
    contentEl.after(form);
    commentDiv.classList.add('editing');
    fetchOwnCommentImages(commentDiv.dataset.id).then(images => {
        if (!form.isConnected) return;
        initCommentImagePreview(form, images);
        form.querySelector('input[type="file"]').disabled = false;
        form.querySelector('.save-edit-btn').disabled = false;
    }).catch(error => {
        if (form.isConnected) form.querySelector('.comment-edit-feedback').textContent = error.message;
    });
    return true;
}

function handleSaveEdit(e, commentDiv, id, onCommentsReload) {
    if (!e.target.matches('.save-edit-btn')) return false;
    const form = e.target.closest('.edit-comment-form');
    if (!form?.reportValidity() || e.target.disabled) return true;
    const data = new FormData(form);
    try {
        photoEditors.get(form)?.append(data);
        appendPhotoUploadPolicy(form, data);
    } catch (error) {
        showFormError(form, error);
        return true;
    }
    e.target.disabled = true;
    const cancel = form.querySelector('.cancel-edit-btn');
    if (cancel) cancel.disabled = true;
    form.querySelector('.comment-edit-feedback').textContent = '';
    Promise.resolve()
        .then(() => updateCommentWithImages(id, data))
        .then(() => {
            photoEditors.get(form)?.dispose();
            onCommentsReload();
        })
        .catch(async error => {
            if (error.status === 409) await refreshPhotoUploadTerms(form);
            form.querySelector('.comment-edit-feedback').textContent = error.message;
            e.target.disabled = false;
            if (cancel) cancel.disabled = false;
        });
    return true;
}

function handleCancelEdit(e, commentDiv, id) {
    if (!e.target.matches('.cancel-edit-btn')) return false;
    if (e.target.disabled) return true;
    const form = commentDiv.querySelector('.edit-comment-form');
    if (!form) return false;
    photoEditors.get(form)?.dispose();
    form.remove();
    commentDiv.classList.remove('editing');
    const body = commentDiv.querySelector('.content-comment-body');
    body.querySelector(':scope > .comment-content').hidden = false;
    const photos = body.querySelector(':scope > .comment-images');
    if (photos) photos.hidden = false;
    return true;
}

function toggleReplyForm(e, commentDiv) {
    if (!e.target.matches('.reply-btn')) return false;
    if (commentDiv.classList.contains('editing')) return true;
    const disclosure = commentDiv.querySelector(':scope > .review-replies');
    if (disclosure) disclosure.open = true;

    const existing = commentDiv.querySelector('.reply-form');
    if (existing) {
        photoEditors.get(existing.querySelector('form'))?.dispose();
        existing.remove();
        return true;
    }

    const uniqueId = `nested-image-${commentDiv.dataset.id}`;
    const container = document.createElement('div');
    container.className = 'reply-form';
    container.innerHTML = `
    <form class="nested-reply-form comment-write-form" enctype="multipart/form-data">
      <div class="textarea-wrapper">
        <textarea name="content" maxlength="2000" placeholder="${detailMessage('commentReplyPlaceholder')}" required></textarea>
      </div>
      <div class="comment-image-preview" hidden></div>
      <div class="comment-controls">
        <button type="button" class="cancel-reply">${detailMessage('commentCancel')}</button>
        <span class="comment-length"><span data-comment-length>0</span>/2000</span>
        <input type="file" id="${uniqueId}" name="images" accept="image/jpeg,image/png,image/webp" multiple hidden>
        <label for="${uniqueId}" class="image-upload-label">
          <img src="/uploads/icons/camera.png" alt="${detailMessage('commentPhoto')}">
          ${detailMessage('commentPhoto')}
        </label>
        <button type="submit" class="submit-btn">${detailMessage('commentSubmit')}</button>
      </div>
    </form>
  `;
    commentDiv.appendChild(container);
    initCommentImagePreview(container.querySelector('form'));
    initCommentLengthCounter(container.querySelector('form'));
    return true;
}

function cancelReply(e) {
    if (!e.target.matches('.cancel-reply')) return false;
    const reply = e.target.closest('.reply-form');
    photoEditors.get(reply?.querySelector('form'))?.dispose();
    reply?.remove();
    return true;
}

// 더보기 상태와 조회는 init.js 한 곳에서 관리한다.
export function setupCommentPagingEvents(onLoadMore) {
    const moreBtn = document.getElementById('load-more-comments');
    if (!moreBtn) return;
    moreBtn.addEventListener('click', () => {
        onLoadMore();
    });
}

// 댓글 이미지 클릭 시 확대 모달 오픈. 이동은 그 댓글에 첨부된 사진 안에서만 순환한다.
export function bindSingleImageModal() {
    // 여행지 상세는 키보드·터치를 지원하는 기존 메인 모달을 재사용한다. 구형 독립 화면만 fallback.
    if (document.getElementById('destination-image-modal')) return;
    const modal = document.getElementById('image-modal');
    const modalImg = document.getElementById('modal-img');
    if (!modal || !modalImg) return;

    /** 현재 보고 있는 댓글의 사진 목록과 위치 */
    let images = [];
    let index = 0;

    const isOpen = () => modal.style.display !== 'none';

    /** 목록 범위를 벗어나면 반대쪽 끝으로 순환한다. */
    function show(nextIndex) {
        if (images.length === 0) return;
        index = (nextIndex + images.length) % images.length;
        modalImg.src = images[index];
    }

    function open(target) {
        const group = target.closest('.comment-images');
        images = group
            ? Array.from(group.querySelectorAll('.comment-image')).map(image => image.src)
            : [target.src];
        modal.classList.toggle('is-single', images.length <= 1);
        show(Math.max(images.indexOf(target.src), 0));
        modal.style.display = 'flex';
    }

    function close() {
        modal.style.display = 'none';
        images = [];
        index = 0;
    }

    document.addEventListener('click', e => {
        const nav = e.target.closest?.('.comment-image-nav');
        if (nav && modal.contains(nav)) {
            // 확대 이미지 클릭(닫기)으로 이어지지 않도록 여기서 끊는다.
            e.preventDefault();
            e.stopPropagation();
            show(index + (nav.classList.contains('prev') ? -1 : 1));
            return;
        }

        if (e.target.matches('.comment-image')) {
            open(e.target);
            return;
        }

        if (e.target === modalImg) {
            close();
            return;
        }

        if (e.target.matches('.close-btn')) {
            close();
        }
    });

    document.addEventListener('keydown', e => {
        // 모달이 닫혀 있으면 방향키/ESC 에 간섭하지 않는다.
        if (!isOpen()) return;

        if (e.key === 'Escape') {
            close();
            return;
        }
        if (e.key === 'ArrowLeft') {
            e.preventDefault();
            show(index - 1);
            return;
        }
        if (e.key === 'ArrowRight') {
            e.preventDefault();
            show(index + 1);
        }
    });
}
