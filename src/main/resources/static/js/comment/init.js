import {
    getDestinationId,
    fetchCommentsPage,
    fetchCommentLocation
} from './api.js';

import {
    setLoadedComments,
    getLoadedComments,
    concatComments
} from './commentsState.js';

import {
    setupCommentPagingEvents,
    initGuestRedirect,
    initCommentForm,
    bindCommentActions,
    bindReplySubmit,
    bindSingleImageModal,
    setupSortDropdownEvent
} from './events.js';

import {
    appendComments,
    renderComments,
    renderReviewStatistics,
    revealReviewTarget
} from './render.js';
import { detailMessage } from './messages.js';

export function init() {
    const destinationId = getDestinationId();
    const commentSectionEl = document.querySelector('.comment-section.content-comments');
    const commentListEl = document.getElementById('comment-list');
    const commentFormEl = document.getElementById('comment-form');
    const countEl = document.getElementById('comment-count');
    const moreButton = document.getElementById('load-more-comments');
    const sortButtons = commentSectionEl?.querySelectorAll('[data-comment-sort]') || [];
    const deepLink = window.TripBoraCommentDeepLink || window.TravelDiaryCommentDeepLink; // legacy fallback (P10a 제거)
    const targetCommentId = deepLink?.readTargetCommentId() ?? null;

    const pageSize = 5;
    let currentSort = 'latest';
    let dateSort = 'latest';
    let nextPage = 0;
    let isLoading = false;
    let isLastPage = false;
    let requestGeneration = 0;

    initGuestRedirect();
    const composer = document.getElementById('review-composer');
    const writeButton = document.querySelector('.review-write-toggle');
    writeButton?.addEventListener('click', () => {
        if (!composer) return;
        composer.hidden = !composer.hidden;
        writeButton.setAttribute('aria-expanded', String(!composer.hidden));
        if (!composer.hidden) (composer.querySelector('input[name="rating"]') || composer.querySelector('textarea'))?.focus();
    });

    function updateSortUi() {
        sortButtons.forEach(button => {
            if (button.hasAttribute('data-date-sort')) {
                button.dataset.commentSort = dateSort;
                button.textContent = dateSort === 'latest'
                    ? `${button.dataset.latestLabel} ↓` : `${button.dataset.oldestLabel} ↑`;
            }
            const active = button.dataset.commentSort === currentSort;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-pressed', String(active));
        });
    }

    function updateMoreButton() {
        if (!moreButton) return;
        moreButton.hidden = isLastPage || nextPage === 0;
        moreButton.disabled = isLoading;
        moreButton.textContent = isLoading
            ? detailMessage('commentLoading')
            : detailMessage('commentLoadMore');
    }

    async function loadCommentPage({reset = false, pageOverride = null} = {}) {
        if (isLoading && !reset) return;
        const generation = reset ? ++requestGeneration : requestGeneration;
        const page = Number.isInteger(pageOverride) && pageOverride >= 0
            ? pageOverride
            : (reset ? 0 : nextPage);

        if (reset) {
            nextPage = 0;
            isLastPage = false;
            setLoadedComments([]);
            // 응답을 받을 때 교체해 열린 답글과 기존 내용을 유지한다.
        }

        isLoading = true;
        commentListEl?.setAttribute('aria-busy', 'true');
        updateMoreButton();

        try {
            const data = await fetchCommentsPage(destinationId, page, pageSize, currentSort);
            if (generation !== requestGeneration) return;
            if (!Array.isArray(data.content)) throw new Error('댓글 응답 형식이 올바르지 않습니다.');

            if (reset) {
                setLoadedComments(data.content.slice());
                renderComments(getLoadedComments(), commentListEl);
            } else {
                concatComments(data.content);
                appendComments(data.content, commentListEl);
            }

            if (countEl) countEl.textContent = String(data.reviewCount ?? 0);
            renderReviewStatistics(data.reviewStatistics);
            nextPage = page + 1;
            isLastPage = Boolean(data.last);
        } catch (error) {
            if (generation === requestGeneration) {
                const summary = document.getElementById('review-summary');
                if (summary && summary.dataset.loaded !== 'true') {
                    summary.setAttribute('aria-busy', 'false');
                    document.getElementById('review-average').textContent = detailMessage('reviewLoadFailed');
                }
                console.error('댓글 목록 갱신 실패:', error);
            }
        } finally {
            if (generation !== requestGeneration) return;
            isLoading = false;
            commentListEl?.setAttribute('aria-busy', 'false');
            updateMoreButton();
        }
    }

    function reloadComments() {
        return loadCommentPage({reset: true});
    }

    async function loadInitialComments() {
        if (targetCommentId == null || !deepLink) {
            await reloadComments();
            return;
        }

        const locationGeneration = ++requestGeneration;
        try {
            const location = await fetchCommentLocation(destinationId, targetCommentId);
            if (locationGeneration !== requestGeneration) return;
            const targetPage = Number(location.page) - 1;
            if (!Number.isInteger(targetPage) || targetPage < 0) {
                throw new Error('댓글 위치 응답이 올바르지 않습니다.');
            }

            await loadCommentPage({reset: true, pageOverride: targetPage});
            revealReviewTarget(commentListEl, targetCommentId);
            if (!deepLink.focusTarget(commentListEl, 'data-id', targetCommentId)) {
                deepLink.scrollToSection(commentSectionEl);
            }
        } catch (error) {
            if (locationGeneration !== requestGeneration) return;
            await reloadComments();
            deepLink.scrollToSection(commentSectionEl);
        }
    }

    setupSortDropdownEvent(commentSectionEl, sort => {
        if (sort === 'latest' || sort === 'oldest') {
            dateSort = currentSort === sort ? (sort === 'latest' ? 'oldest' : 'latest') : dateSort;
            sort = dateSort;
        }
        if (!['latest', 'oldest', 'likes', 'rating_desc', 'rating_asc'].includes(sort) || sort === currentSort) return;
        currentSort = sort;
        updateSortUi();
        void reloadComments();
    });

    setupCommentPagingEvents(() => {
        if (!isLastPage) void loadCommentPage();
    });

    if (commentFormEl) {
        if (!commentSectionEl?.hasAttribute('data-reviews-only')) bindReplySubmit(commentListEl, destinationId, reloadComments);
        initCommentForm(destinationId, reloadComments);
    }
    bindCommentActions(commentListEl, reloadComments);

    bindSingleImageModal();

    updateSortUi();
    void loadInitialComments();
}

document.addEventListener('DOMContentLoaded', init);
