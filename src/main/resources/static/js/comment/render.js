import { detailMessage } from './messages.js';

const defaultProfileImage = '/images/default.png';
const unlikedIcon = '/uploads/icons/like.png';
const likedIcon = '/uploads/icons/like2.png';

function parseDate(value) {
    if (!value) return null;
    const normalized = typeof value === 'string'
        ? value.trim().replace(/^(\d{4}-\d{2}-\d{2})\s/, '$1T')
        : value;
    const date = new Date(normalized);
    return Number.isFinite(date.getTime()) ? date : null;
}

function formatDate(value) {
    const date = parseDate(value);
    if (!date) return '';
    const twoDigits = number => String(number).padStart(2, '0');
    return `${twoDigits(date.getFullYear() % 100)}.${twoDigits(date.getMonth() + 1)}.${twoDigits(date.getDate())} `
        + `${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}`;
}

function isEdited(comment) {
    const createdAt = parseDate(comment.createdAt)?.getTime();
    const updatedAt = parseDate(comment.updatedAt)?.getTime();
    return Number.isFinite(createdAt) && Number.isFinite(updatedAt) && updatedAt > createdAt;
}

function profileImageUrl(value) {
    if (typeof value !== 'string' || !value.trim()) return defaultProfileImage;
    const trimmed = value.trim();
    return /^(?:https?:|data:|blob:|\/)/i.test(trimmed) ? trimmed : `/${trimmed}`;
}

function bindProfileFallback(image) {
    image?.addEventListener('error', () => {
        if (image.dataset.fallbackApplied === 'true') return;
        image.dataset.fallbackApplied = 'true';
        image.src = defaultProfileImage;
    });
}

function makePublicProfileLink(userId, child, className, withdrawn = false) {
    // 최종 탈퇴 회원은 공개 프로필이 없으므로 링크를 걸지 않는다.
    if (userId == null || withdrawn === true) return child;
    const link = document.createElement('a');
    link.className = className;
    link.href = `/users/${encodeURIComponent(String(userId))}`;
    link.append(child);
    return link;
}

/**
 * @닉네임 하이라이트(파란색, 클릭) 변환 함수
 */
function highlightMentions(content) {
    if (!content) return '';
    const holder = document.createElement('div');
    holder.textContent = content;
    return holder.innerHTML.replace(/@([^\s@]+)/g, (match, nickname) =>
        `<span class="mention content-comment-mention">@${nickname}</span>`
    );
}

/**
 * 댓글 첨부 사진(최대 5장) 마크업.
 * 클릭 시 모달은 .comment-image 를 기준으로 잡히므로 클래스는 그대로 둔다.
 */
function renderCommentImages(comment) {
    const imageUrls = Array.isArray(comment.imageUrls) ? comment.imageUrls : [];
    if (imageUrls.length === 0) return '';
    const items = imageUrls
        .map((url, index) =>
            `<img src="${url}" class="comment-image content-comment-image"
                  data-review-id="${comment.id}" data-review-image-id="${comment.photos?.[index]?.id ?? ''}"
                  alt="${detailMessage('commentImageAlt', index + 1)}">`)
        .join('');
    return `<div class="comment-images content-comment-images">${items}</div>`;
}

/**
 * 부모 ID별로 댓글을 그룹화
 */
export function groupByParent(comments) {
    return comments.reduce((acc, c) => {
        const key = c.parentCommentId ?? null;
        acc[key] = acc[key] || [];
        acc[key].push(c);
        return acc;
    }, {});
}

/**
 * 단일 댓글 LI 생성
 */
export function createCommentItem(comment, depth = 0, parentNickname = '') {
    const reviewsOnly = Boolean(document.querySelector('[data-reviews-only]'));
    const li = document.createElement('li');
    li.className = 'comment-item content-comment-item' + (depth > 0 ? ' reply' : '');
    li.dataset.id = comment.id;
    if (comment.parentCommentId == null && Number.isInteger(comment.rating)) li.dataset.rating = String(comment.rating);
    if (comment.writer?.id != null) li.dataset.userId = String(comment.writer.id);
    if (depth === 1) {
        li.dataset.parentId = comment.parentCommentId;
        li.dataset.writerNickname = parentNickname;
    }
    // 관리자 조치된 댓글: 트리(대댓글)는 유지하고 본문만 안내 문구로 대체한다.
    // 커뮤니티 댓글과 동일하게 카드 그리드(40px 프로필 열) 없이 안내 문구만 붙여
    // 대댓글에서도 본문 폭을 그대로 쓰게 한다.
    if (comment.moderated === true || comment.deleted === true) {
        li.classList.add('content-comment-deleted');
        const moderatedText = document.createElement('p');
        moderatedText.className = 'comment-content content-comment-text content-comment-deleted-text';
        moderatedText.textContent = detailMessage('commentModerated');
        li.append(moderatedText);
        return li;
    }

    const profileUrl = profileImageUrl(comment.writer?.profileImage);
    const nickname   = comment.writer?.nickname     || detailMessage('commentUnknownUser');
    const isWriter   = comment.writer?.isWriter === true;
    const isLoggedIn = comment.isLoggedIn === true;
    const edited = isEdited(comment);
    const likeControl = isLoggedIn
        ? `<button type="button" class="like-btn content-comment-action content-comment-like${comment.likedByMe ? ' is-liked' : ''}"
                   data-id="${comment.id}" aria-pressed="${Boolean(comment.likedByMe)}">
                <img src="${comment.likedByMe ? likedIcon : unlikedIcon}" alt="" aria-hidden="true"
                     class="likeicon content-comment-like-icon">
                <span>${detailMessage('commentLike')}</span>
                <span class="content-comment-like-count">${comment.likes ?? 0}</span>
            </button>`
        : `<span class="content-comment-like-readonly content-comment-like">
                <img src="${comment.likedByMe ? likedIcon : unlikedIcon}" alt="" aria-hidden="true"
                     class="likeicon content-comment-like-icon">
                <span>${detailMessage('commentLike')}</span>
                <span class="content-comment-like-count">${comment.likes ?? 0}</span>
            </span>`;
    const translationControl = comment.translationAvailable === true
        ? `<button type="button" class="translate-btn content-comment-action"
                   data-translation-state="original">${detailMessage('commentTranslate')}</button>`
        : '';

    const profileImage = document.createElement('img');
    profileImage.src = profileUrl;
    profileImage.className = 'comment-profile content-comment-avatar';
    profileImage.alt = detailMessage('commentProfileAlt', nickname);
    bindProfileFallback(profileImage);

    const nicknameElement = document.createElement('span');
    nicknameElement.className = 'comment-nickname content-comment-nickname';
    nicknameElement.textContent = nickname;

    const body = document.createElement('div');
    body.className = 'content-comment-body';
    body.innerHTML = `
        <div class="comment-header content-comment-header">
            ${isWriter ? `<span class="comment-author-tag content-comment-author-tag">${detailMessage('commentAuthor')}</span>` : ''}
            <span class="content-comment-meta">
                <time datetime="${comment.createdAt || ''}">${formatDate(comment.createdAt)}</time>
                ${edited ? `<span class="edited-tag content-comment-edited">${detailMessage('commentEdited')}</span>` : ''}
            </span>
        </div>
        ${comment.parentCommentId == null && Number.isInteger(comment.rating) && comment.rating >= 1 && comment.rating <= 5
            ? `<p class="review-rating-display" aria-label="${comment.rating} / 5"><span aria-hidden="true">${'★'.repeat(comment.rating)}${'☆'.repeat(5 - comment.rating)}</span><span>${comment.rating} / 5</span></p>`
            : comment.parentCommentId == null ? `<p class="review-legacy-badge">${detailMessage('reviewLegacy')}</p>` : ''}
        <p class="comment-content content-comment-text">${highlightMentions(comment.content)}</p>
        <p class="content-comment-translation-status" hidden></p>
        ${renderCommentImages(comment)}
        <div class="comment-actions content-comment-actions">
            ${likeControl}
            ${translationControl}
            ${isLoggedIn && !reviewsOnly ? `<button type="button" class="reply-btn content-comment-action">${detailMessage('commentReply')}</button>` : ''}
            ${comment.myComment ? `
                <button type="button" class="edit-btn content-comment-action">${detailMessage('commentEdit')}</button>
            ` : ''}
            ${comment.myComment || comment.admin ? `
                <button type="button" class="delete-btn content-comment-action">${detailMessage('commentDelete')}</button>
            ` : ''}
        </div>
    `;
    body.querySelector('.comment-content').dataset.originalContent = comment.content || '';
    if (window.adminModeration?.isAdminUser()) {
        body.querySelector('.content-comment-actions').append(
            window.adminModeration.makeButton(
                'DESTINATION_COMMENT', comment.id, 'moderate-btn content-comment-action'));
    }
    body.querySelector('.content-comment-header').prepend(
        makePublicProfileLink(comment.writer?.id, nicknameElement,
            'content-comment-writer-link', comment.writer?.withdrawn)
    );

    const card = document.createElement('div');
    card.className = 'content-comment-card';
    card.append(
        makePublicProfileLink(comment.writer?.id, profileImage,
            'content-comment-profile-link', comment.writer?.withdrawn),
        body
    );
    li.append(card);
    return li;
}

/**
 * 댓글/답글 append (초기화X)
 * comments: 추가로 붙일 것만 (보통 새댓글/새대댓글 한 개 or 더보기 불러온 배열)
 */
export function appendComments(comments, container) {
    if (!Array.isArray(comments)) return;
    if (container.closest('[data-reviews-only]')) {
        comments.filter(comment => comment.parentCommentId == null)
            .forEach(comment => container.appendChild(createCommentItem(comment)));
        return;
    }

    // 각 댓글/대댓글 별로 올바른 위치에 append
    comments.forEach(comment => {
        // 부모 댓글이 있으면, 부모의 .reply-list에 append
        if (comment.parentCommentId) {
            const parentLi = container.querySelector(`.comment-item[data-id="${comment.parentCommentId}"]`);
            if (!parentLi) return;
            let replyUl = parentLi.querySelector('.reply-list');
            if (!replyUl) {
                replyUl = document.createElement('ul');
                replyUl.className = 'reply-list content-comment-replies';
                ensureReplyDisclosure(parentLi).appendChild(replyUl);
            }
            replyUl.appendChild(createCommentItem(comment, 1, comment.writer?.nickname || ''));
        } else {
            // 원댓글(부모 없음)은 컨테이너에 append
            container.appendChild(createCommentItem(comment, 0, ''));
        }
    });
    container.querySelectorAll('.review-replies').forEach(details => {
        details.querySelector('summary').textContent = detailMessage('reviewReplies', details.querySelectorAll('.reply-list > .comment-item').length);
    });
}

function ensureReplyDisclosure(parentLi) {
    let details = parentLi.querySelector(':scope > .review-replies');
    if (!details) {
        details = document.createElement('details');
        details.className = 'review-replies';
        details.appendChild(document.createElement('summary'));
        parentLi.appendChild(details);
    }
    return details;
}

export function revealReviewTarget(container, commentId) {
    const target = container.querySelector(`.comment-item[data-id="${commentId}"]`);
    let ancestor = target?.parentElement;
    while (ancestor && ancestor !== container) {
        if (ancestor.matches('details.review-replies')) ancestor.open = true;
        ancestor = ancestor.parentElement;
    }
}

/**
 * 전체 초기화 후 모든 댓글/답글 트리 렌더
 */
export function renderComments(comments, container) {
    if (container.closest('[data-reviews-only]')) {
        container.replaceChildren();
        appendComments(comments, container);
        return;
    }
    const openRoots = new Set(Array.from(container.querySelectorAll('.review-replies[open]'))
        .map(details => details.parentElement.dataset.id));
    // (1) container가 반드시 <ul>인지 체크
    if (!(container instanceof HTMLUListElement)) {
        console.error('container must be a <ul>!');
        // 여기서 <ul>이 아니라면 아래처럼 새로 만들어서 교체해줘도 됨
        const newUl = document.createElement('ul');
        newUl.id = container.id;
        container.replaceWith(newUl);
        container = newUl;
    }
    container.innerHTML = '';
    if (!Array.isArray(comments)) return;
    const ids = new Set(comments.map(c => c.id));
    const filtered = comments.filter(c =>
        c.parentCommentId == null || ids.has(c.parentCommentId)
    );
    const grouped = groupByParent(filtered);
    (grouped.null || []).forEach(root => renderFlatTree(grouped, root, 0, container));
    container.querySelectorAll('.review-replies').forEach(details => {
        if (openRoots.has(details.parentElement.dataset.id)) details.open = true;
    });
}

function renderFlatTree(grouped, comment, depth, parentUl, parentNickname = '') {
    // (2) LI 생성/append만 사용 (이미 잘 되어있음)
    const li = createCommentItem(comment, depth > 1 ? 1 : depth, parentNickname);
    parentUl.appendChild(li);
    const children = grouped[comment.id] || [];
    if (children.length) {
        const childUl = document.createElement('ul');
        childUl.className = 'reply-list content-comment-replies';
        const disclosure = depth === 0 ? ensureReplyDisclosure(li) : li;
        if (depth === 0) disclosure.querySelector('summary').textContent = detailMessage('reviewReplies', children.length);
        disclosure.appendChild(childUl);
        children.forEach(child =>
            renderFlatTree(grouped, child, (depth > 0 ? 1 : depth + 1), childUl, comment.writer?.nickname || '')
        );
    }
}

export function renderReviewStatistics(statistics) {
    const summary = document.getElementById('review-summary');
    if (!summary) return;
    const count = Number(statistics?.ratingCount) || 0;
    const average = statistics?.averageRating == null ? null : Number(statistics.averageRating);
    const hasRating = count > 0 && Number.isFinite(average);
    const averageEl = document.getElementById('review-average');
    averageEl.textContent = hasRating
        ? new Intl.NumberFormat(document.documentElement.lang || 'ko', {minimumFractionDigits: 1, maximumFractionDigits: 1}).format(average)
        : detailMessage('reviewNoRating');
    averageEl.classList.toggle('is-unrated', !hasRating);
    document.getElementById('review-average-stars').style.width = `${hasRating ? Math.min(5, Math.max(0, average)) * 20 : 0}%`;
    document.getElementById('review-rating-count').textContent = detailMessage('reviewRatingCount', count);
    summary.querySelectorAll('[data-rating-score]').forEach(row => {
        const score = Number(row.dataset.ratingScore);
        const value = Number(statistics?.distribution?.[score - 1]) || 0;
        const meter = row.querySelector('meter');
        meter.max = Math.max(count, 1);
        meter.value = value;
        row.querySelector('.review-distribution-count').textContent = String(value);
    });
    summary.setAttribute('aria-busy', 'false');
    summary.dataset.loaded = 'true';
}


/**
 * 기타(이미지, 단순리스트 등은 동일)
 */

export function renderCommentList(comments) {
    const container = document.getElementById('comment-list');
    container.innerHTML = comments.map(comment => `
        <div class="comment-item">${highlightMentions(comment.content)}</div>
    `).join('');
}
