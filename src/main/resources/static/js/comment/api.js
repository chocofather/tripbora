// comment/api.js

/**
 * 공통 응답 처리 헬퍼
 * @param {Response} res - Fetch API 응답 객체
 * @param {string} label - 작업 라벨 (에러 메시지에 사용)
 * @returns {Promise<any>} JSON 파싱 결과
 */
async function handleJson(res, label) {
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        const error = new Error(body.message || body.detail || `${label} 실패: HTTP ${res.status}`);
        error.status = res.status;
        throw error;
    }
    const ct = res.headers.get('Content-Type') || '';
    if (!ct.includes('application/json')) {
        throw new Error(`${label} 응답이 JSON이 아닙니다: ${ct}`);
    }
    return res.json();
}

/**
 * 상태를 바꾸는 요청에 실을 CSRF 헤더.
 * 토큰과 헤더 이름은 layout 의 meta 값을 그대로 쓴다. (폼 전송이 쓰는 값과 같다)
 * @param {Object} [extra] 함께 보낼 다른 헤더
 * @returns {Object} fetch headers
 */
function csrfHeaders(extra) {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    const headers = {...(extra || {})};
    if (token && header) headers[header] = token;
    return headers;
}

/**
 * URL에서 destinationId 추출
 * @returns {string}
 */
export function getDestinationId() {
    return window.location.pathname.split('/').pop();
}

/**
 * 댓글 등록
 * @param {string|number} destinationId
 * @param {FormData} formData
 * @returns {Promise<Object>} 생성된 댓글 데이터
 */
export function postComment(destinationId, formData) {
    return fetch(`/comments?destinationId=${destinationId}`, {
        method: 'POST',
        headers: csrfHeaders(),
        body: formData
    }).then(res => handleJson(res, '댓글 등록'));
}

/**
 * 댓글 목록 조회 (페이지 기반 + 정렬 포함)
 * @param {string|number} destinationId
 * @param {number} page - 0부터 시작하는 페이지 번호
 * @param {number} size - 한 페이지에 불러올 댓글 수
 * @param {string} [sort='latest'] - 정렬 방식 ('latest', 'oldest', 'likes')
 * @returns {Promise<Object>} Page<CommentDto> 형태 응답
 */
export function fetchCommentsPage(destinationId, page = 0, size = 5, sort = 'latest') {
    const rootsOnly = Boolean(document.querySelector('[data-reviews-only]'));
    const url = `/comments/list/page?destinationId=${destinationId}&page=${page}&size=${size}&sort=${sort}&rootsOnly=${rootsOnly}&_=${Date.now()}`;
    return fetch(url, {
        headers: { 'Accept': 'application/json' },
        cache: 'no-store'
    }).then(res => handleJson(res, '댓글 페이지 로드'));
}

/**
 * 특정 활성 댓글이 포함된 루트 댓글 페이지를 조회한다.
 * 반환되는 page는 1부터 시작한다.
 */
export function fetchCommentLocation(destinationId, commentId) {
    const url = `/comments/${encodeURIComponent(commentId)}/location`
        + `?destinationId=${encodeURIComponent(destinationId)}`;
    return fetch(url, {
        headers: {'Accept': 'application/json'},
        cache: 'no-store'
    }).then(res => handleJson(res, '댓글 위치 조회'));
}

/** 현재 사이트 언어로 댓글을 번역한다. target/source text는 서버가 결정한다. */
export async function fetchCommentTranslation(commentId) {
    const response = await fetch(`/comments/${encodeURIComponent(commentId)}/translation`, {
        headers: { 'Accept': 'application/json' },
        credentials: 'same-origin',
        cache: 'no-store'
    });
    const contentType = response.headers.get('Content-Type') || '';
    const body = contentType.includes('application/json') ? await response.json() : {};
    if (!response.ok) {
        const error = new Error(body.message || `번역 실패: HTTP ${response.status}`);
        error.status = response.status;
        error.retryAfterSeconds = Number(response.headers.get('Retry-After') || 0);
        throw error;
    }
    return body;
}

/**
 * 대댓글(답글) 등록
 * @param {string|number} destinationId
 * @param parentCommentId
 * @param {FormData} formData
 * @returns {Promise<Object>} 생성된 대댓글 데이터
 */
export function postReply(destinationId, parentCommentId, formData) {
    formData.append('parentCommentId', parentCommentId);
    return fetch(
        `/comments?destinationId=${destinationId}`,
        { method: 'POST', headers: csrfHeaders(), body: formData }
    )
    .then(res => handleJson(res, '대댓글 등록'));
}

/**
 * 좋아요 토글
 * @param {string|number} commentId
 * @returns {Promise<string>} 'liked' 또는 'unliked'
 */
export function toggleLikeApi(commentId) {
    return fetch(`/comments/${commentId}/like-toggle`,
        { method: 'POST', headers: csrfHeaders() })
        .then(res => {
            if (!res.ok) throw new Error(`좋아요 토글 실패: HTTP ${res.status}`);
            return res.text();
        });
}

/**
 * 댓글 삭제
 * @param {string|number} commentId
 * @returns {Promise<Response>}
 */
export function deleteCommentApi(commentId) {
    return fetch(`/comments/${commentId}`, {
        method: 'DELETE',
        credentials: 'include', // 반드시 추가!
        headers: csrfHeaders({
            'Content-Type': 'application/json' // 있으면 좋음 (필수는 아님)
        })
    });
}

/**
 * 댓글 수정
 * @param {string|number} commentId
 * @param {Object} body - 수정 데이터 (예: { content: '...' })
 * @returns {Promise<Response>}
 */
export function updateCommentApi(commentId, body) {
    return fetch(`/comments/${commentId}`, {
        method: 'PUT',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body)
    });
}

export function fetchOwnCommentImages(commentId) {
    return fetch(`/comments/${encodeURIComponent(commentId)}/images`, {cache: 'no-store'})
        .then(res => handleJson(res, '댓글 사진 조회'));
}

export async function updateCommentWithImages(commentId, body) {
    const response = await fetch(`/comments/${encodeURIComponent(commentId)}/with-images`, {
        method: 'PUT', headers: csrfHeaders(), body
    });
    if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        const error = new Error(data.message || data.detail || '댓글을 수정하지 못했습니다. 다시 시도해 주세요.');
        error.status = response.status;
        throw error;
    }
}
