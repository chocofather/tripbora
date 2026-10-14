/**
 * 관리자 구조화 콘텐츠(STRUCTURED) 블록 에디터. 도메인별 초기화 설정으로 같은 엔진을 사용한다.
 *
 * <p>정해진 블록 6종을 위에서 아래로 쌓는다. 자유 배치·드래그는 없고 위/아래 버튼으로 순서를 바꾼다.
 * 화면 상태는 하나의 model({version: 1, blocks: [...]})이 갖고, 모든 추가·삭제·복제·이동·입력·이미지 업로드가
 * 이 model 을 바꾼다. 저장 직전에 model 을 JSON 으로 써서 hidden 칸(structuredContent)에 넣는다.
 * 서버가 같은 JSON 을 다시 엄격하게 읽고 검사하므로, 여기의 검사는 미리 알려 주는 용도다.
 *
 * <p>처음 model 은 같은 hidden 칸의 값이다. 수정 화면이면 저장된 글, 저장이 거부되어 다시 그려진 화면이면
 * 방금 보낸 값이라 작성하던 블록·글·이미지·순서가 그대로 돌아온다.
 *
 * <p>사용자 글은 모두 textContent / value 로만 넣는다. (innerHTML 로 그리지 않는다)
 * 일반 글의 서식 본문도 허용한 태그·class 만 createElement 로 다시 만들어 넣는다. (cleanRichNodes)
 */
(function () {
    'use strict';

    const UPLOAD_URL = '/admin/api/travel-info/content-images';
    const IMAGE_URL_PREFIX = '/uploads/travel-info/content/';
    const IMAGE_ACCEPT = '.jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp';

    /** 서버 StructuredContentValidator 와 같은 한도. (StructuredEditorLimitsContractTest 가 맞는지 본다) */
    const LIMITS = Object.freeze({
        blocks: 60,
        sliderItems: 40,
        title: 120,
        richText: 5000,
        richTextHtml: 20000,
        imageText: 3000,
        caption: 300,
        alt: 200,
        itemTitle: 100,
        callout: 500,
        creditAuthor: 100,
        creditSource: 100,
        creditSourceUrl: 500,
        creditLicense: 100,
        creditLicenseUrl: 500
    });

    /** 이미지 출처표시(credit) 칸. 서버 StructuredImageCredit 과 같은 이름·순서다. */
    const CREDIT_FIELDS = Object.freeze(['author', 'source', 'sourceUrl', 'license', 'licenseUrl']);
    const CREDIT_URL_FIELDS = Object.freeze(['sourceUrl', 'licenseUrl']);

    /**
     * 라이선스 입력 제안값. 저장은 언제나 입력한 글 그대로다. (코드로 바꾸지 않는다)
     * 제안값과 똑같이 입력하면 라이선스 URL 을 채워 준다. 단, 직접 입력한 URL 은 덮어쓰지 않는다.
     */
    const CREDIT_LICENSES = Object.freeze([
        {name: '공공누리 제1유형', url: 'https://www.kogl.or.kr/info/licenseType1.do'},
        {name: '공공누리 제2유형', url: 'https://www.kogl.or.kr/info/licenseType2.do'},
        {name: '공공누리 제3유형', url: 'https://www.kogl.or.kr/info/licenseType3.do'},
        {name: '공공누리 제4유형', url: 'https://www.kogl.or.kr/info/licenseType4.do'},
        {name: 'CC BY 4.0', url: 'https://creativecommons.org/licenses/by/4.0/'},
        {name: 'CC BY-SA 4.0', url: 'https://creativecommons.org/licenses/by-sa/4.0/'},
        {name: 'CC0', url: 'https://creativecommons.org/publicdomain/zero/1.0/'}
    ]);

    const TYPE_LABELS = Object.freeze({
        SECTION_TITLE: '섹션 제목',
        RICH_TEXT: '일반 글',
        FULL_IMAGE: '큰 이미지',
        IMAGE_TEXT: '이미지 + 글',
        IMAGE_SLIDER: '이미지 슬라이더',
        IMAGE_GRID: '이미지 배치',
        CALLOUT: '강조 문구'
    });

    /** 이미지 배치 블록의 칸 수. (서버 StructuredContentValidator.GRID_COLUMNS 와 같다) */
    const GRID_COLUMNS = Object.freeze([2, 3]);

    /**
     * 일반 글 서식의 허용 목록. 서버 StructuredRichTextHtml 과 같은 태그·class 프리셋이다.
     * 글자 크기·글꼴·강조는 정해진 이름만 있고, 숫자·글꼴 이름·색을 직접 넣는 칸은 없다. 최종 정리는 서버가 한다.
     */
    const RICH_BLOCK_TAGS = Object.freeze(['P', 'H3', 'H4', 'UL', 'OL', 'BLOCKQUOTE']);
    const RICH_TEXT_BLOCK_SELECTOR = 'p, h3, h4, li, blockquote';
    const RICH_INLINE_TAGS = Object.freeze({B: 'strong', STRONG: 'strong', I: 'em', EM: 'em', U: 'u', MARK: 'mark',
        A: 'a', SPAN: 'span'});
    /**
     * 글꼴·글자 크기는 고른 글자에만 씌우는 span class 다. (문단 단위가 아니다)
     * 글자 크기는 예전에 p 전체에 붙이던 값이 저장돼 있을 수 있어 p 에서도 지우지 않고 그대로 보여 준다.
     */
    const RICH_FONT_CLASSES = Object.freeze(['rt-font-sans', 'rt-font-serif']);
    const RICH_SIZE_CLASSES = Object.freeze(['rt-size-small', 'rt-size-large']);
    const RICH_CLASSES = Object.freeze({
        p: RICH_SIZE_CLASSES,
        span: [...RICH_FONT_CLASSES, ...RICH_SIZE_CLASSES],
        mark: ['rt-point']
    });
    /** 문단 스타일. 본문은 p, 소제목·중제목은 h4 / h3 이다. 글자 크기는 따로 고른 글자에만 붙인다. */
    const RICH_PARAGRAPH_STYLES = Object.freeze([['body', '본문'], ['h4', '소제목'], ['h3', '중제목']]);
    const RICH_FONTS = Object.freeze([['', '기본 글꼴'], ['rt-font-sans', '고딕'], ['rt-font-serif', '명조']]);
    const RICH_SIZES = Object.freeze([['rt-size-small', '작게'], ['', '기본 크기'], ['rt-size-large', '크게']]);

    const ADD_OPTIONS = Object.freeze([
        {label: '섹션 제목', type: 'SECTION_TITLE'},
        {label: '일반 글', type: 'RICH_TEXT'},
        {label: '큰 이미지', type: 'FULL_IMAGE'},
        {label: '이미지 왼쪽 + 글 오른쪽', type: 'IMAGE_TEXT', imagePosition: 'LEFT'},
        {label: '글 왼쪽 + 이미지 오른쪽', type: 'IMAGE_TEXT', imagePosition: 'RIGHT'},
        {label: '이미지 슬라이더', type: 'IMAGE_SLIDER'},
        {label: '이미지 2장 배치', type: 'IMAGE_GRID', columns: 2},
        {label: '이미지 3장 배치', type: 'IMAGE_GRID', columns: 3},
        {label: '강조 문구', type: 'CALLOUT'}
    ]);

    // ---- 작은 도우미 ----------------------------------------------------------------------

    function el(tag, attributes = {}, children = []) {
        const element = document.createElement(tag);
        Object.entries(attributes).forEach(([name, value]) => {
            if (value === null || value === undefined || value === false) return;
            if (name === 'text') element.textContent = value;
            else if (name === 'className') element.className = value;
            else if (name.startsWith('on')) element.addEventListener(name.slice(2), value);
            else element.setAttribute(name, value === true ? '' : value);
        });
        (Array.isArray(children) ? children : [children])
            .filter(child => child !== null && child !== undefined && child !== false)
            .forEach(child => element.append(child));
        return element;
    }

    function text(value) {
        return typeof value === 'string' ? value : '';
    }

    function blank(value) {
        return text(value).trim() === '';
    }

    /** 짧고 겹치지 않는 식별자. 서버 규칙([A-Za-z0-9_-], 40자 이하)에 맞는 'b-' / 'i-' + 영숫자 10자. */
    function randomPart() {
        const bytes = new Uint8Array(10);
        window.crypto.getRandomValues(bytes);
        return Array.from(bytes, byte => (byte % 36).toString(36)).join('');
    }

    function newId(prefix, used) {
        let id;
        do {
            id = `${prefix}-${randomPart()}`;
        } while (used.has(id));
        used.add(id);
        return id;
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? {[header]: token} : {};
    }

    /**
     * 서버가 돌려준 값만 이미지로 받는다. 크기는 서버가 잰 값이다.
     * 저장된 글의 출처표시(credit)도 함께 읽어, 손대지 않고 다시 저장해도 그대로 남게 한다.
     */
    function readImage(value, imageUrlPrefix = IMAGE_URL_PREFIX) {
        if (!value || typeof value.url !== 'string' || !value.url.startsWith(imageUrlPrefix)) return null;
        // 서버 policy와 같이 해당 namespace의 UUID 파일만 받는다.
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp)$/.test(
            value.url.slice(imageUrlPrefix.length))) return null;
        const width = Number(value.width);
        const height = Number(value.height);
        if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) return null;
        return {url: value.url, width, height, credit: readCredit(value.credit)};
    }

    /** 출처표시. 객체가 아니면 출처표시가 없는 이미지(null)다. 화면 model 에서는 칸마다 글(빈 글 포함)을 둔다. */
    function readCredit(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
        return Object.fromEntries(CREDIT_FIELDS.map(name => [name, text(value[name])]));
    }

    function emptyCredit() {
        return readCredit({});
    }

    /** URL 칸은 앞뒤 공백을 떼고 쓴다. 글 칸은 다른 글 칸처럼 입력한 그대로다. */
    function creditValue(credit, name) {
        const value = text(credit[name]);
        return CREDIT_URL_FIELDS.includes(name) ? value.trim() : value;
    }

    /**
     * 값이 있는 칸만, 서버 정규 JSON 과 같은 순서(author, source, sourceUrl, license, licenseUrl)로 쓴다.
     * 출처표시를 켜 두었어도 모두 비었으면 null 이다. (빈 credit({}) 은 서버가 거부하므로 아예 쓰지 않는다)
     */
    function writeCredit(credit) {
        if (!credit) return null;
        const written = {};
        CREDIT_FIELDS.forEach(name => {
            const value = creditValue(credit, name);
            if (!blank(value)) written[name] = value;
        });
        return Object.keys(written).length ? written : null;
    }

    /** 이미지 한 장. 서버 정규 JSON 과 같은 순서(url, width, height, credit). */
    function writeImage(image) {
        const written = {url: image.url, width: image.width, height: image.height};
        const credit = writeCredit(image.credit);
        if (credit) written.credit = credit;
        return written;
    }

    /** 공통 출처 설정을 적용해도 되는지. 이미지 한 장의 저장 검사(validate)와 같은 규칙·문구다. 문제가 없으면 "". */
    function commonCreditProblem(credit) {
        const written = writeCredit(credit);
        if (!written) return '적용할 출처 정보를 입력해 주세요.';
        if (!written.author && !written.source) return '출처표시에는 저작자 또는 출처명을 입력해 주세요.';
        if (written.sourceUrl && !isWebUrl(written.sourceUrl)) return '출처 URL은 http 또는 https 주소로 입력해 주세요.';
        if (written.licenseUrl && !isWebUrl(written.licenseUrl)) return '라이선스 URL은 http 또는 https 주소로 입력해 주세요.';
        return '';
    }

    /** [전체 이미지에 적용]이 덮어쓸 사진 수. 이미 다른 개별 출처가 있는 사진만 센다. */
    function commonCreditOverwrites(items, credit) {
        const common = JSON.stringify(writeCredit(credit));
        return items.filter(item => {
            const current = item.image ? writeCredit(item.image.credit) : null;
            return current && JSON.stringify(current) !== common;
        }).length;
    }

    /**
     * 슬라이더 공통 출처를 넘겨받은 사진들(그 슬라이더의 items)의 출처 칸에 복사한다. 채운 사진 수를 돌려준다.
     * onlyEmpty 면 출처가 비어 있는 사진만 채운다. 이미지가 아직 없는 자리(업로드 중·실패)는 출처를 붙일 곳이 없어 건너뛴다.
     * 사진마다 따로 복사하므로 적용한 뒤 한 장을 고쳐도 다른 사진은 그대로이고, 저장은 지금처럼 사진마다의 credit 이다.
     */
    function applyCommonCredit(items, credit, onlyEmpty) {
        const written = writeCredit(credit);
        if (!written) return 0;
        let applied = 0;
        items.forEach(item => {
            if (!item.image || (onlyEmpty && writeCredit(item.image.credit))) return;
            item.image.credit = readCredit(written);
            applied++;
        });
        return applied;
    }

    /**
     * 서버 StructuredContentValidator 와 같은 기준: host 가 있는 http / https 절대 주소.
     * 브라우저 URL 은 "https:example.com" 처럼 // 없는 주소도 받으므로 모양을 먼저 본다. 최종 기준은 서버다.
     */
    function isWebUrl(value) {
        if (!/^https?:\/\/[^\s/?#]+/i.test(value) || /[\s<>"{}|\\^`]/.test(value)) return false;
        try {
            const url = new URL(value);
            return (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname !== '';
        } catch {
            return false;
        }
    }

    // ---- 일반 글 서식 (제한형) -----------------------------------------------------------------

    /** 안의 글을 그대로 둔 채 태그만 벗긴다. */
    function unwrapNode(node) {
        const parent = node.parentNode;
        if (!parent) return;
        while (node.firstChild) parent.insertBefore(node.firstChild, node);
        parent.removeChild(node);
    }

    /** 허용한 class 프리셋만 옮긴다. */
    function copyRichClasses(from, to) {
        const allowed = RICH_CLASSES[to.tagName.toLowerCase()] || [];
        const kept = Array.from(from.classList || []).filter(name => allowed.includes(name)).sort();
        if (kept.length) to.className = kept.join(' ');
    }

    /** 내용을 통째로 버리는 요소. (글로 남기면 안 되는 스크립트·미디어 등) */
    const RICH_DROPPED_TAGS = new Set(['SCRIPT', 'STYLE', 'TEMPLATE', 'NOSCRIPT', 'IFRAME', 'OBJECT', 'EMBED',
        'SVG', 'MATH', 'IMG', 'VIDEO', 'AUDIO', 'CANVAS', 'TEXTAREA', 'SELECT', 'BUTTON', 'INPUT']);

    /** 문단 안의 글·글자 서식. 허용하지 않은 태그는 벗기고, 안에 들어간 문단은 줄바꿈으로 잇는다. */
    function cleanRichInline(node, parent) {
        if (node.nodeType === Node.TEXT_NODE) {
            parent.append(document.createTextNode(node.textContent));
            return;
        }
        if (node.nodeType !== Node.ELEMENT_NODE || RICH_DROPPED_TAGS.has(node.tagName)) return;
        if (node.tagName === 'BR') {
            parent.append(document.createElement('br'));
            return;
        }
        const inlineTag = RICH_INLINE_TAGS[node.tagName];
        // 주소가 http(s) 가 아닌 링크, 글꼴·글자 크기 프리셋이 없는 span(브라우저가 붙인 style span 등)은 글만 남긴다.
        const unwrap = (inlineTag === 'a' && !isWebUrl(text(node.getAttribute('href')).trim()))
            || (inlineTag === 'span' && !RICH_CLASSES.span.some(name => node.classList.contains(name)));
        if (unwrap) {
            node.childNodes.forEach(child => cleanRichInline(child, parent));
            return;
        }
        if (inlineTag) {
            const element = document.createElement(inlineTag);
            if (inlineTag === 'a') element.setAttribute('href', node.getAttribute('href').trim());
            if (inlineTag === 'mark' || inlineTag === 'span') copyRichClasses(node, element);
            node.childNodes.forEach(child => cleanRichInline(child, element));
            if (element.textContent !== '') parent.append(element);
            return;
        }
        const nestedBlock = /^(P|DIV|H[1-6]|LI|BLOCKQUOTE|UL|OL)$/.test(node.tagName);
        if (nestedBlock && parent.textContent.trim() !== '' && parent.lastChild?.nodeName !== 'BR') {
            parent.append(document.createElement('br'));
        }
        node.childNodes.forEach(child => cleanRichInline(child, parent));
    }

    function cleanRichBlock(node, tag) {
        if (tag === 'UL' || tag === 'OL') {
            const list = document.createElement(tag.toLowerCase());
            node.childNodes.forEach(child => {
                const item = document.createElement('li');
                if (child.nodeType === Node.ELEMENT_NODE && child.tagName === 'LI') {
                    copyRichClasses(child, item);
                    child.childNodes.forEach(grandChild => cleanRichInline(grandChild, item));
                } else {
                    cleanRichInline(child, item);
                }
                if (item.textContent.trim() !== '') list.append(item);
            });
            return list.children.length ? list : null;
        }
        const element = document.createElement(tag.toLowerCase());
        copyRichClasses(node, element);
        node.childNodes.forEach(child => cleanRichInline(child, element));
        while (element.lastChild?.nodeName === 'BR') element.lastChild.remove();
        return element.textContent.trim() !== '' ? element : null;
    }

    /**
     * 허용 목록만 남긴 새 노드. HTML 문자열은 스크립트가 돌지 않는 별도 문서(DOMParser)에서만 읽고,
     * 화면에 넣을 노드는 허용한 태그·class·http(s) 링크만 createElement 로 다시 만든다.
     * b / i 는 strong / em, div 는 p 로 바꾸고, 문단 밖의 글은 p 로 묶는다. (서버 정리와 같은 모양)
     */
    function cleanRichNodes(source) {
        const root = typeof source === 'string'
            ? new DOMParser().parseFromString(`<!doctype html><body>${source}`, 'text/html').body
            : source;
        const fragment = document.createDocumentFragment();
        let loose = null;
        const flushLoose = () => {
            while (loose?.lastChild?.nodeName === 'BR') loose.lastChild.remove();
            if (loose && loose.textContent.trim() !== '') fragment.append(loose);
            loose = null;
        };
        root.childNodes.forEach(node => {
            const element = node.nodeType === Node.ELEMENT_NODE ? node : null;
            const tag = element ? ({DIV: 'P', LI: 'P'}[element.tagName] || element.tagName) : null;
            if (tag && RICH_BLOCK_TAGS.includes(tag)) {
                flushLoose();
                const block = cleanRichBlock(element, tag);
                if (block) fragment.append(block);
            } else {
                if (!loose) loose = document.createElement('p');
                cleanRichInline(node, loose);
            }
        });
        flushLoose();
        return fragment;
    }

    function escapeRich(value) {
        return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /** cleanRichNodes 가 만든 노드(허용 태그·class·href 뿐)를 HTML 문자열로 쓴다. */
    function richHtml(node) {
        return Array.from(node.childNodes).map(child => {
            if (child.nodeType === Node.TEXT_NODE) return escapeRich(child.textContent.replace(/ /g, ' '));
            if (child.nodeType !== Node.ELEMENT_NODE) return '';
            const tag = child.tagName.toLowerCase();
            if (tag === 'br') return '<br>';
            const className = child.getAttribute('class');
            const href = tag === 'a' ? child.getAttribute('href') : null;
            return `<${tag}${className ? ` class="${escapeRich(className)}"` : ''}${href ? ` href="${escapeRich(href)}"` : ''}>`
                + `${richHtml(child)}</${tag}>`;
        }).join('');
    }

    function cleanRichHtml(value) {
        return richHtml(cleanRichNodes(text(value)));
    }

    /** 서식 없는 예전 글을 에디터 문단으로. 빈 줄은 문단, 문단 안의 줄바꿈은 br 이다. */
    function plainToRichHtml(value) {
        const fragment = document.createDocumentFragment();
        text(value).replace(/\r\n?/g, '\n').split(/\n\s*\n/)
            .map(paragraph => paragraph.trim())
            .filter(Boolean)
            .forEach(paragraph => {
                const element = document.createElement('p');
                paragraph.split('\n').forEach((line, index) => {
                    if (index > 0) element.append(document.createElement('br'));
                    element.append(document.createTextNode(line.trim()));
                });
                fragment.append(element);
            });
        return richHtml(fragment);
    }

    function richLineText(element) {
        let value = '';
        const walk = node => node.childNodes.forEach(child => {
            if (child.nodeType === Node.TEXT_NODE) value += child.textContent;
            else if (child.nodeName === 'BR') value += '\n';
            else if (child.nodeType === Node.ELEMENT_NODE) walk(child);
        });
        walk(element);
        return value.replace(/ /g, ' ').split('\n').map(line => line.trim()).join('\n').trim();
    }

    /** 정리된 서식 본문의 글. 문단은 빈 줄, br 은 줄바꿈, 목록 항목은 한 줄씩이다. (서버 plainText 와 같은 모양) */
    function richHtmlToText(html) {
        const body = new DOMParser().parseFromString(`<!doctype html><body>${text(html)}`, 'text/html').body;
        return Array.from(body.children)
            .map(block => block.tagName === 'UL' || block.tagName === 'OL'
                ? Array.from(block.children).map(richLineText).filter(Boolean).join('\n')
                : richLineText(block))
            .filter(Boolean)
            .join('\n\n');
    }

    /** class 없는 p 와 줄바꿈뿐이면 서식이 없는 글이다. 그때는 예전처럼 text 만 저장한다. */
    function isPlainRichHtml(html) {
        const body = new DOMParser().parseFromString(`<!doctype html><body>${text(html)}`, 'text/html').body;
        return Array.from(body.children).every(block => block.tagName === 'P' && block.attributes.length === 0
            && Array.from(block.querySelectorAll('*')).every(child => child.tagName === 'BR'));
    }

    /**
     * 일반 글 링크 입력값을 저장할 주소로. 앞뒤 공백을 지우고, scheme 없이 쓴 주소(tripbora.com, www.naver.com,
     * //host)에는 https:// 를 붙인다. http / https 가 아닌 scheme(javascript:, data:, file:, vbscript:, mailto: …)은
     * 붙이지 않고 거절한다. 붙인 뒤에도 isWebUrl 로 다시 검사한다. 쓸 수 없으면 null. (최종 기준은 서버 정리다)
     */
    function normalizeLinkUrl(value) {
        const raw = text(value).trim();
        if (!raw) return null;
        let url = raw;
        if (!/^https?:\/\//i.test(raw)) {
            // "host:8080/path" 처럼 포트가 붙은 주소는 scheme 이 아니다.
            if (/^[a-z][a-z0-9+.-]*:/i.test(raw) && !/^[^\s/:]+:\d+(?:[/?#]|$)/.test(raw)) return null;
            url = `https://${raw.replace(/^\/\//, '')}`;
            let host = '';
            try {
                host = new URL(url).hostname;
            } catch {
                return null;
            }
            if (!host.includes('.') && host !== 'localhost') return null;
        }
        return isWebUrl(url) ? url : null;
    }

    /** 관리자 미리보기 한 줄. 빈 칸은 빼고, 저작자와 출처명이 같으면 한 번만 쓴다. 값이 없으면 "". */
    function creditPreview(credit) {
        if (!credit) return '';
        const author = text(credit.author).trim();
        const source = text(credit.source).trim();
        const parts = [author, source === author ? '' : source, text(credit.license).trim()].filter(Boolean);
        return parts.length ? `사진: ${parts.join(' · ')}` : '';
    }

    async function uploadImage(file, imageUploadUrl, imageUrlPrefix) {
        const body = new FormData();
        body.append('image', file);
        let response;
        try {
            response = await fetch(imageUploadUrl, {
                method: 'POST', credentials: 'same-origin', headers: csrfHeaders(), body
            });
        } catch {
            throw new Error('이미지를 올리지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.');
        }
        let data = {};
        try {
            data = await response.json();
        } catch {
            data = {};
        }
        const image = response.ok ? readImage(data, imageUrlPrefix) : null;
        if (!image) {
            throw new Error(typeof data.message === 'string' && data.message
                ? data.message
                : '이미지를 올리지 못했습니다. 잠시 후 다시 시도해 주세요.');
        }
        // 새 파일은 출처표시 없이 시작한다. 교체 전 이미지의 저작권 정보가 새 이미지에 남지 않게 한다.
        return {...image, credit: null};
    }

    /** 블록 카드·확인 창에 쓰는 이름. 이미지 배치는 장수까지 보여 준다. */
    function blockLabel(block) {
        return block.type === 'IMAGE_GRID' ? `이미지 ${block.columns}장 배치` : TYPE_LABELS[block.type];
    }

    // ---- model --------------------------------------------------------------------------

    function emptyImageItem(itemIds) {
        return {id: newId('i', itemIds), image: null, alt: '', title: '', caption: ''};
    }

    function readImageItem(item, imageUrlPrefix) {
        return {id: item.id, image: readImage(item.image, imageUrlPrefix), alt: text(item.alt),
            title: text(item.title), caption: text(item.caption)};
    }

    /** 슬라이더·이미지 배치의 사진 한 장. 서버 정규 JSON 과 같은 순서(id, image, alt, title, caption). */
    function writeImageItem(item) {
        const written = {id: item.id};
        if (item.image) written.image = writeImage(item.image);
        if (!blank(item.alt)) written.alt = item.alt;
        if (!blank(item.title)) written.title = item.title;
        if (!blank(item.caption)) written.caption = item.caption;
        return written;
    }

    function emptyBlock(option, usedIds) {
        const id = newId('b', usedIds);
        switch (option.type) {
            case 'SECTION_TITLE': return {id, type: 'SECTION_TITLE', title: ''};
            case 'RICH_TEXT': return {id, type: 'RICH_TEXT', layout: 'DEFAULT', text: '', html: ''};
            case 'FULL_IMAGE': return {id, type: 'FULL_IMAGE', image: null, alt: '', caption: ''};
            case 'IMAGE_TEXT':
                return {id, type: 'IMAGE_TEXT', imagePosition: option.imagePosition === 'RIGHT' ? 'RIGHT' : 'LEFT',
                    image: null, alt: '', title: '', text: ''};
            case 'IMAGE_SLIDER': return {id, type: 'IMAGE_SLIDER', title: '', items: []};
            case 'IMAGE_GRID': {
                // 장수가 정해진 블록이라 빈 칸을 미리 만들어 둔다.
                const columns = option.columns === 3 ? 3 : 2;
                const itemIds = new Set();
                return {id, type: 'IMAGE_GRID', columns,
                    items: Array.from({length: columns}, () => emptyImageItem(itemIds))};
            }
            default: return {id, type: 'CALLOUT', text: ''};
        }
    }

    /**
     * 저장된 JSON 한 블록을 화면 model 로. 모르는 블록이면 null.
     * 예전 섹션 제목의 짧은 소개(lead)는 읽지 않는다. (다시 저장하면 빠진다. 서버도 같은 방식으로 버린다)
     * 본문 폭(layout)이 없던 예전 일반 글은 기본(DEFAULT)이다.
     * 일반 글은 화면에서 서식 본문(html)으로 고친다. 서식 없는 예전 글(text)은 빈 줄 = 문단, 줄바꿈 = br 로 바꿔 연다.
     */
    function readBlock(raw, imageUrlPrefix = IMAGE_URL_PREFIX) {
        if (!raw || typeof raw !== 'object' || typeof raw.id !== 'string') return null;
        const id = raw.id;
        switch (raw.type) {
            case 'SECTION_TITLE': return {id, type: raw.type, title: text(raw.title)};
            case 'RICH_TEXT': {
                const html = blank(raw.html) ? plainToRichHtml(raw.text) : cleanRichHtml(raw.html);
                return {id, type: raw.type, layout: raw.layout === 'FOCUSED' ? 'FOCUSED' : 'DEFAULT',
                    text: richHtmlToText(html), html};
            }
            case 'FULL_IMAGE':
                return {id, type: raw.type, image: readImage(raw.image, imageUrlPrefix), alt: text(raw.alt), caption: text(raw.caption)};
            case 'IMAGE_TEXT':
                return {id, type: raw.type, imagePosition: raw.imagePosition === 'RIGHT' ? 'RIGHT' : 'LEFT',
                    image: readImage(raw.image, imageUrlPrefix), alt: text(raw.alt), title: text(raw.title), text: text(raw.text)};
            case 'IMAGE_SLIDER':
                return {id, type: raw.type, title: text(raw.title),
                    items: (Array.isArray(raw.items) ? raw.items : [])
                        .filter(item => item && typeof item.id === 'string')
                        .map(item => readImageItem(item, imageUrlPrefix))};
            case 'IMAGE_GRID': {
                // 칸 수와 장수가 맞지 않는 값은 화면에서 고칠 수 없으므로 읽지 못한 블록으로 둔다.
                const items = Array.isArray(raw.items) ? raw.items.filter(item => item && typeof item.id === 'string') : [];
                if (!GRID_COLUMNS.includes(raw.columns) || items.length !== raw.columns) return null;
                return {id, type: raw.type, columns: raw.columns, items: items.map(item => readImageItem(item, imageUrlPrefix))};
            }
            case 'CALLOUT': return {id, type: raw.type, text: text(raw.text)};
            default: return null;
        }
    }

    /** 값이 있는 칸만, 서버 정규 JSON 과 같은 순서(type, id, …)로 쓴다. */
    function writeBlock(block) {
        const out = {type: block.type, id: block.id};
        const put = (key, value) => {
            if (!blank(value)) out[key] = value;
        };
        const putImage = image => {
            if (image) out.image = writeImage(image);
        };
        switch (block.type) {
            case 'SECTION_TITLE': put('title', block.title); break;
            case 'RICH_TEXT': {
                // 서식이 없으면 예전과 같은 일반 글(text)만, 서식이 있으면 서식 본문(html)도 쓴다.
                out.layout = block.layout;
                const html = cleanRichHtml(block.html);
                put('text', richHtmlToText(html));
                if (!isPlainRichHtml(html)) put('html', html);
                break;
            }
            case 'FULL_IMAGE': putImage(block.image); put('alt', block.alt); put('caption', block.caption); break;
            case 'IMAGE_TEXT':
                out.imagePosition = block.imagePosition;
                putImage(block.image); put('alt', block.alt); put('title', block.title); put('text', block.text);
                break;
            case 'IMAGE_SLIDER':
                put('title', block.title);
                out.items = block.items.map(writeImageItem);
                break;
            case 'IMAGE_GRID':
                out.columns = block.columns;
                out.items = block.items.map(writeImageItem);
                break;
            default: put('text', block.text);
        }
        return out;
    }

    function blockHasContent(block) {
        switch (block.type) {
            case 'SECTION_TITLE': return !blank(block.title);
            case 'IMAGE_SLIDER': return !blank(block.title) || block.items.length > 0;
            case 'IMAGE_GRID':
                return block.items.some(item => item.image || !blank(item.title) || !blank(item.caption) || !blank(item.alt));
            case 'FULL_IMAGE': return !!block.image || !blank(block.alt) || !blank(block.caption);
            case 'IMAGE_TEXT':
                return !!block.image || !blank(block.alt) || !blank(block.title) || !blank(block.text);
            default: return !blank(block.text);
        }
    }

    function blockSummary(block) {
        if (block.type === 'IMAGE_SLIDER') {
            const title = text(block.title).trim();
            return `${title ? title + ' · ' : ''}이미지 ${block.items.length}장`;
        }
        if (block.type === 'IMAGE_GRID') {
            return `이미지 ${block.items.filter(item => item.image).length} / ${block.columns}장`;
        }
        const value = text(block.title || block.text || block.alt).trim().split('\n')[0];
        return value.length > 60 ? value.slice(0, 60) + '…' : value;
    }

    // ---- 검사 (서버 규칙과 같은 필수값. 최종 기준은 서버다) -----------------------------------

    function validate(blocks, pendingUploads) {
        const global = [];
        const fields = new Map(); // focusKey → message
        const byBlock = new Map(); // blockId → [message]
        const add = (block, key, message) => {
            fields.set(key, message);
            if (!byBlock.has(block.id)) byBlock.set(block.id, []);
            byBlock.get(block.id).push(message);
        };
        // 출처표시: 저장할 값이 있으면 저작자·출처명 중 하나가 있어야 하고, URL 은 http / https 주소만 받는다.
        // (켜 두고 모두 비웠으면 credit 을 쓰지 않으므로 검사할 것이 없다)
        const checkCredit = (block, image, imageKey, prefix) => {
            const credit = writeCredit(image?.credit);
            if (!credit) return;
            if (!credit.author && !credit.source) {
                add(block, `${imageKey}:credit-author`, `${prefix}출처표시에는 저작자 또는 출처명을 입력해 주세요.`);
            }
            if (credit.sourceUrl && !isWebUrl(credit.sourceUrl)) {
                add(block, `${imageKey}:credit-sourceUrl`, `${prefix}출처 URL은 http 또는 https 주소로 입력해 주세요.`);
            }
            if (credit.licenseUrl && !isWebUrl(credit.licenseUrl)) {
                add(block, `${imageKey}:credit-licenseUrl`, `${prefix}라이선스 URL은 http 또는 https 주소로 입력해 주세요.`);
            }
        };
        if (pendingUploads > 0) global.push('이미지 업로드가 끝난 뒤 다시 저장해 주세요.');
        if (blocks.length === 0) global.push('블록을 하나 이상 추가해 주세요.');
        if (blocks.length > LIMITS.blocks) global.push(`블록은 ${LIMITS.blocks}개까지 추가할 수 있습니다.`);

        blocks.forEach(block => {
            const key = name => `${block.id}:${name}`;
            switch (block.type) {
                case 'SECTION_TITLE':
                    if (blank(block.title)) add(block, key('title'), '제목을 입력해 주세요.');
                    break;
                case 'RICH_TEXT': {
                    const html = cleanRichHtml(block.html);
                    const plain = richHtmlToText(html);
                    if (blank(plain)) add(block, key('text'), '본문을 입력해 주세요.');
                    else if (plain.length > LIMITS.richText) {
                        add(block, key('text'), `본문은 ${LIMITS.richText}자 이하로 입력해 주세요.`);
                    } else if (!isPlainRichHtml(html) && html.length > LIMITS.richTextHtml) {
                        add(block, key('text'), '본문 서식이 너무 많습니다. 서식을 줄여 주세요.');
                    }
                    break;
                }
                // 이미지 설명(alt)은 선택이다. 비우면 공개 화면이 캡션·제목 등으로 대신 채운다.
                case 'FULL_IMAGE':
                    if (!block.image) add(block, key('image'), '이미지를 올려 주세요.');
                    checkCredit(block, block.image, key('image'), '');
                    break;
                case 'IMAGE_TEXT':
                    if (!block.image) add(block, key('image'), '이미지를 올려 주세요.');
                    checkCredit(block, block.image, key('image'), '');
                    if (blank(block.text)) add(block, key('text'), '본문을 입력해 주세요.');
                    break;
                case 'IMAGE_SLIDER':
                    if (block.items.length === 0) add(block, key('add'), '이미지를 한 장 이상 추가해 주세요.');
                    if (block.items.length > LIMITS.sliderItems) {
                        add(block, key('add'), `이미지는 ${LIMITS.sliderItems}장까지 추가할 수 있습니다.`);
                    }
                    block.items.forEach((item, index) => {
                        const itemKey = name => `${block.id}:${item.id}:${name}`;
                        if (!item.image) add(block, itemKey('image'), `${index + 1}번째 이미지: 이미지를 올려 주세요.`);
                        checkCredit(block, item.image, itemKey('image'), `${index + 1}번째 이미지: `);
                    });
                    break;
                case 'IMAGE_GRID':
                    // 장수는 블록을 만들 때 정해지고 화면에서 바뀌지 않는다. 칸마다 이미지만 필수다.
                    block.items.forEach((item, index) => {
                        if (!item.image) {
                            add(block, `${block.id}:${item.id}:image`, `${index + 1}번째 칸: 이미지를 올려 주세요.`);
                        }
                        checkCredit(block, item.image, `${block.id}:${item.id}:image`, `${index + 1}번째 칸: `);
                    });
                    break;
                default:
                    if (blank(block.text)) add(block, key('text'), '강조 문구를 입력해 주세요.');
            }
        });
        return {global, fields, byBlock, valid: global.length === 0 && fields.size === 0};
    }

    // ---- 에디터 -----------------------------------------------------------------------------

    function createEditor(root, input, form, {imageUploadUrl, imageUrlPrefix}) {
        const domPrefix = root.id;
        const state = {
            blocks: [],
            collapsed: new Set(),
            uploads: new Set(), // 업로드 중인 이미지 자리 key
            imageErrors: new Map(), // 이미지 자리 key → 서버 메시지
            openCredits: new Set(), // 출처 정보 입력 칸을 펼친 이미지 자리 key
            // 슬라이더 공통 출처 설정. 블록 id 별 입력값·결과 문구이고 저장하지 않는다. (적용하면 사진마다의 credit 에 복사된다)
            commonCredits: new Map(), // 블록 id → credit 입력값
            commonCreditStatus: new Map(), // 블록 id → {text, error}
            openCommonCredits: new Set(), // 공통 출처 설정을 펼친 블록 id
            // 일반 글 편집 칸의 실행취소 기록. 블록 id → {undo, redo, typing}. 다시 그려도 이어지고 저장하지 않는다.
            richHistory: new Map(),
            errors: null, // 마지막 저장 시도 검사 결과
            menuOpen: false,
            loadError: false,
            dirty: false,
            active: !root.hidden
        };

        // 처음 model: 수정 화면의 저장 값, 또는 저장이 거부되어 다시 그려진 화면의 방금 보낸 값.
        const initial = input.value.trim();
        if (initial) {
            try {
                const parsed = JSON.parse(initial);
                if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.blocks)) throw new Error('version');
                state.blocks = parsed.blocks.map(raw => readBlock(raw, imageUrlPrefix)).filter(Boolean);
                if (state.blocks.length !== parsed.blocks.length) state.loadError = true;
            } catch {
                state.loadError = true;
            }
        }

        function allBlockIds() {
            return new Set(state.blocks.map(block => block.id));
        }

        function markDirty() {
            state.dirty = true;
        }

        function serialize() {
            return JSON.stringify({version: 1, blocks: state.blocks.map(writeBlock)});
        }

        // ---- 그리기 ----

        /** 다시 그린 뒤에도 보던 칸에 머문다. (업로드가 끝나 다시 그려도 입력 중인 칸이 풀리지 않게) */
        function render(focusKey) {
            const active = document.activeElement;
            const activeKey = active && root.contains(active) ? active.dataset.focusKey : null;
            const selection = active && typeof active.selectionStart === 'number'
                ? [active.selectionStart, active.selectionEnd] : null;

            // replaceChildren 은 null 을 "null" 글자로 넣으므로 빈 자리는 미리 뺀다.
            root.replaceChildren(...[
                el('div', {className: 'admin-structured-editor-header'}, [
                    el('h3', {text: '구조화 콘텐츠'}),
                    el('p', {className: 'admin-help-text',
                        text: '블록을 위에서 아래로 쌓아 본문을 만듭니다. 순서가 공개 화면 순서이고, 모양은 화면이 정합니다.'})
                ]),
                renderSummary(),
                state.loadError ? el('p', {className: 'admin-structured-load-error', role: 'alert',
                    text: '저장된 블록 중 읽지 못한 것이 있습니다. 화면에 보이는 블록만 다시 저장됩니다.'}) : null,
                state.blocks.length === 0
                    ? el('p', {className: 'admin-structured-empty',
                        text: '아직 블록이 없습니다. 아래 [+ 블록 추가]로 첫 블록을 넣어 주세요.'})
                    : el('ol', {className: 'admin-structured-blocks'},
                        state.blocks.map((block, index) => renderBlock(block, index))),
                renderAddBar()
            ].filter(Boolean));

            const target = focusKey || activeKey;
            if (target) {
                const element = root.querySelector(`[data-focus-key="${CSS.escape(target)}"]`);
                if (element && !element.disabled) {
                    element.focus({preventScroll: !focusKey});
                    if (!focusKey && selection && typeof element.setSelectionRange === 'function') {
                        try {
                            element.setSelectionRange(selection[0], selection[1]);
                        } catch {
                            // 선택을 되돌릴 수 없는 입력 종류는 그냥 둔다.
                        }
                    }
                }
            }
        }

        function renderSummary() {
            const errors = state.errors;
            if (!errors || errors.valid) return null;
            const count = errors.global.length + errors.fields.size;
            return el('div', {className: 'admin-alert admin-structured-error-summary', role: 'alert',
                tabindex: '-1', 'data-focus-key': 'error-summary'}, [
                el('p', {text: `구조화 콘텐츠에서 고칠 곳이 ${count}개 있습니다.`}),
                errors.global.length ? el('ul', {}, errors.global.map(message => el('li', {text: message}))) : null
            ]);
        }

        function renderAddBar() {
            const full = state.blocks.length >= LIMITS.blocks;
            const menuId = `${root.id || 'structured'}-add-menu`;
            return el('div', {className: 'admin-structured-add'}, [
                el('button', {
                    type: 'button', className: 'admin-btn is-small admin-structured-add-toggle',
                    'aria-expanded': String(state.menuOpen), 'aria-controls': menuId,
                    disabled: full, 'data-focus-key': 'add-toggle', text: '+ 블록 추가',
                    onclick: () => {
                        state.menuOpen = !state.menuOpen;
                        render(state.menuOpen ? 'add-option-0' : 'add-toggle');
                    }
                }),
                full ? el('span', {className: 'admin-help-text', text: `블록은 ${LIMITS.blocks}개까지 추가할 수 있습니다.`}) : null,
                el('div', {
                    id: menuId, className: 'admin-structured-add-menu', role: 'group',
                    'aria-label': '추가할 블록 종류', hidden: !state.menuOpen,
                    onkeydown: event => {
                        if (event.key !== 'Escape') return;
                        state.menuOpen = false;
                        render('add-toggle');
                    }
                }, ADD_OPTIONS.map((option, index) => el('button', {
                    type: 'button', className: 'admin-structured-add-option', text: option.label,
                    'data-focus-key': `add-option-${index}`,
                    onclick: () => addBlock(option)
                })))
            ]);
        }

        function renderBlock(block, index) {
            const label = blockLabel(block);
            const position = `${index + 1}번째 블록(${label})`;
            const collapsed = state.collapsed.has(block.id);
            const bodyId = `${domPrefix}-sb-${block.id}-body`;
            const errorId = `${domPrefix}-sb-${block.id}-errors`;
            const messages = state.errors?.byBlock.get(block.id) || [];
            const action = (name, symbol, ariaLabel, handler, options = {}) => el('button', {
                type: 'button', className: `admin-btn is-small admin-structured-action${options.danger ? ' is-danger' : ''}`,
                'aria-label': ariaLabel, title: ariaLabel, 'data-focus-key': `${block.id}:${name}`,
                disabled: options.disabled, 'aria-expanded': options.expanded, 'aria-controls': options.controls,
                text: symbol, onclick: handler
            });

            return el('li', {className: `admin-structured-block${messages.length ? ' is-invalid' : ''}`,
                'data-block-id': block.id, 'aria-label': position}, [
                el('div', {className: 'admin-structured-block-header'}, [
                    el('span', {className: 'admin-structured-block-type', text: `${index + 1}. ${label}`}),
                    el('span', {className: 'admin-structured-block-summary', 'data-block-summary': '',
                        text: blockSummary(block)}),
                    el('div', {className: 'admin-structured-block-actions', role: 'group', 'aria-label': `${position} 관리`}, [
                        action('up', '↑', `${position} 위로 이동`, () => moveBlock(block, -1), {disabled: index === 0}),
                        action('down', '↓', `${position} 아래로 이동`, () => moveBlock(block, 1),
                            {disabled: index === state.blocks.length - 1}),
                        action('copy', '복제', `${position} 복제`, () => duplicateBlock(block),
                            {disabled: state.blocks.length >= LIMITS.blocks}),
                        action('toggle', collapsed ? '펼치기' : '접기', `${position} ${collapsed ? '펼치기' : '접기'}`,
                            () => toggleBlock(block), {expanded: String(!collapsed), controls: bodyId}),
                        action('delete', '삭제', `${position} 삭제`, () => deleteBlock(block), {danger: true})
                    ])
                ]),
                messages.length ? el('ul', {id: errorId, className: 'admin-structured-block-errors'},
                    messages.map(message => el('li', {text: message}))) : null,
                el('div', {id: bodyId, className: 'admin-structured-block-body', hidden: collapsed},
                    renderBlockFields(block, errorId))
            ]);
        }

        function invalidAttributes(key, errorId) {
            const invalid = state.errors?.fields.has(key);
            return invalid ? {'aria-invalid': 'true', 'aria-describedby': errorId, className: 'is-invalid'} : {};
        }

        /**
         * 글 칸 하나. 값이 바뀌면 model 만 바꾸고 다시 그리지 않는다. (입력 중 커서를 지킨다)
         * attributes 는 입력 칸에 더 붙일 속성(list, inputmode 등), optionalHint: false 는 "(선택)" 표시를 뺀다.
         * secondary 는 보조 입력(이미지 설명 alt)이라 제목·설명보다 한 단계 약하게 보인다.
         * draft 는 저장하지 않는 입력 도우미(슬라이더 공통 출처)라 글을 고친 것으로 치지 않는다.
         */
        function field(block, options, errorId) {
            const key = options.key;
            const id = `${domPrefix}-sb-${key.replace(/:/g, '-')}`;
            const invalid = invalidAttributes(key, errorId);
            const control = el(options.multiline ? 'textarea' : 'input', {
                ...options.attributes,
                id, type: options.multiline ? null : 'text', maxlength: options.max,
                rows: options.multiline ? options.rows || 4 : null,
                'aria-required': options.required ? 'true' : null,
                'aria-invalid': invalid['aria-invalid'], 'aria-describedby': invalid['aria-describedby'],
                className: invalid.className, 'data-focus-key': key,
                oninput: event => {
                    options.set(event.target.value);
                    if (!options.draft) markDirty();
                    if (options.summary) {
                        const summary = root.querySelector(`[data-block-id="${CSS.escape(block.id)}"] [data-block-summary]`);
                        if (summary) summary.textContent = blockSummary(block);
                    }
                }
            });
            control.value = options.value;
            return el('div', {className: `admin-form-field${options.secondary ? ' is-secondary' : ''}`}, [
                el('label', {for: id}, [options.label, options.required
                    ? el('span', {className: 'admin-structured-required', 'aria-hidden': 'true', text: ' *'})
                    : options.optionalHint === false
                        ? null
                        : el('span', {className: 'admin-structured-optional', text: ' (선택)'})]),
                control,
                options.help ? el('span', {className: 'admin-help-text', text: options.help}) : null
            ]);
        }

        /**
         * 일반 글 본문. 정해진 서식 프리셋만 쓰는 편집 칸이다. (워드프로세서처럼 아무 모양이나 넣지 않는다)
         * 입력하면 model(html·text)만 바꾸고 다시 그리지 않는다. 툴바 버튼은 편집 칸의 선택을 잃지 않게 mousedown 을 막고,
         * 고르기 칸·링크 입력처럼 초점이 옮겨 가는 도구는 마지막 선택을 기억해 두었다가 되돌린 뒤 적용한다.
         * 붙여 넣기는 서식 없는 글로만 받고, 끌어 놓기는 받지 않는다.
         *
         * <p>실행취소: 문단 스타일·목록·인용·강조·글꼴은 스크립트가 DOM 을 직접 바꾸므로 브라우저의 실행취소 기록에
         * 남지 않고, 섞어 쓰면 그 기록이 어긋난다. 그래서 편집 칸마다 바뀌기 직전 모양(정리한 HTML + 커서 위치)을
         * 작은 기록으로 쌓고, Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z 와 브라우저 메뉴의 실행취소도 이 기록으로 돌린다.
         * 이어서 입력한 글은 잠깐 멈출 때까지 한 번으로 묶는다.
         */
        function richTextField(block, errorId) {
            const key = `${block.id}:text`;
            const id = `${domPrefix}-sb-${key.replace(/:/g, '-')}`;
            const invalid = invalidAttributes(key, errorId);
            let savedRange = null;
            if (!state.richHistory.has(block.id)) state.richHistory.set(block.id, {undo: [], redo: [], typing: null});
            const history = state.richHistory.get(block.id);

            const surface = el('div', {
                id, className: `admin-structured-richtext-surface${invalid.className ? ' is-invalid' : ''}`,
                contenteditable: 'true', role: 'textbox', 'aria-multiline': 'true', 'aria-labelledby': `${id}-label`,
                'aria-required': 'true', 'aria-invalid': invalid['aria-invalid'],
                'aria-describedby': invalid['aria-describedby'], 'data-focus-key': key,
                oninput: () => sync(),
                onbeforeinput: event => {
                    if (event.inputType === 'historyUndo' || event.inputType === 'historyRedo') {
                        event.preventDefault();
                        if (event.inputType === 'historyUndo') undo();
                        else redo();
                    } else if (event.inputType.startsWith('format')) {
                        endTyping();
                        pushHistory();
                    } else {
                        beginTyping();
                    }
                },
                onkeydown: event => {
                    if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
                    const pressed = event.key.toLowerCase();
                    if (pressed === 'z' && !event.shiftKey) {
                        event.preventDefault();
                        undo();
                    } else if (pressed === 'y' || (pressed === 'z' && event.shiftKey)) {
                        event.preventDefault();
                        redo();
                    }
                },
                onpaste: event => {
                    event.preventDefault();
                    endTyping();
                    pushHistory();
                    document.execCommand('insertText', false, event.clipboardData?.getData('text/plain') || '');
                },
                ondrop: event => event.preventDefault(),
                onkeyup: () => remember(),
                onmouseup: () => {
                    endTyping();
                    remember();
                },
                onfocus: () => remember()
            });
            surface.append(cleanRichNodes(block.html));
            // 빈 칸에서 바로 입력해도 문단(p) 안에 글이 들어가게 한다.
            if (!surface.firstChild) surface.append(el('p', {}, [el('br')]));

            const status = el('p', {className: 'admin-structured-richtext-status', role: 'status'});
            const say = message => { status.textContent = message || ''; };

            function sync() {
                block.html = richHtml(cleanRichNodes(surface));
                block.text = richHtmlToText(block.html);
                markDirty();
                const summary = root.querySelector(`[data-block-id="${CSS.escape(block.id)}"] [data-block-summary]`);
                if (summary) summary.textContent = blockSummary(block);
            }

            function currentRange() {
                const selection = window.getSelection();
                if (!selection.rangeCount) return null;
                const range = selection.getRangeAt(0);
                return surface.contains(range.commonAncestorContainer) ? range : null;
            }

            function remember() {
                const range = currentRange();
                if (range) savedRange = range.cloneRange();
                updateState();
            }

            /**
             * 기억해 둔 선택을 되돌린다. keepFocus 면 편집 칸으로 focus 를 옮기지 않는다. (고르기 칸에서 화살표로
             * 값을 바꾸는 중에 focus 를 빼앗으면 이어서 누른 키가 고른 글자를 덮어쓴다)
             */
            function restore(keepFocus) {
                // focus 하면 onfocus 가 그 순간의 커서를 다시 기억하므로, 되돌릴 선택은 먼저 잡아 둔다.
                const saved = savedRange;
                if (!keepFocus) surface.focus({preventScroll: true});
                const selection = window.getSelection();
                if (saved && surface.contains(saved.commonAncestorContainer)) {
                    selection.removeAllRanges();
                    selection.addRange(saved);
                } else if (!currentRange()) {
                    const end = document.createRange();
                    end.selectNodeContents(surface);
                    end.collapse(false);
                    selection.removeAllRanges();
                    selection.addRange(end);
                }
            }

            // ---- 실행취소 기록 ----

            /*
              편집 칸 안의 위치를 글자 수로 센다. 문단 경계와 br 은 한 칸이다. 그래서 문단 스타일·목록·인용으로
              문단 모양을 바꾸거나(br 로 나뉜 줄 ↔ 목록 항목), 실행취소로 다시 그려도 같은 글자 자리를 가리킨다.
            */
            function richWalker() {
                return document.createTreeWalker(surface, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
            }

            function positionOf(container, offset) {
                const point = document.createRange();
                point.setStart(container, offset);
                const walker = richWalker();
                let count = 0;
                let first = true;
                let node;
                while ((node = walker.nextNode())) {
                    const isText = node.nodeType === Node.TEXT_NODE;
                    if (isText && node === container) return count + offset;
                    const isBreak = node.nodeName === 'BR';
                    const isBlock = !isText && !isBreak && node.matches(RICH_TEXT_BLOCK_SELECTOR);
                    if (!isText && !isBreak && !isBlock) continue;
                    if (point.comparePoint(node, 0) > 0) return count;
                    if (isText) count += node.length;
                    else if (isBreak || !first) count += 1;
                    if (isBlock) first = false;
                }
                return count;
            }

            /**
             * 글자 수 위치의 DOM 지점. preferNext 면 같은 문단 안 두 글자 조각 사이에서 뒤 조각의 처음을 고른다.
             * (선택 시작점이 앞 조각 끝에 놓이면 글꼴 span 밖으로 읽혀 툴바가 다른 글꼴을 보여 준다)
             */
            function pointAt(position, preferNext) {
                const walker = richWalker();
                let count = 0;
                let first = true;
                let last = [surface, 0];
                let node;
                while ((node = walker.nextNode())) {
                    if (node.nodeType === Node.TEXT_NODE) {
                        const end = count + node.length;
                        if (position < end || (position === end && !(preferNext && node.length))) {
                            return [node, position - count];
                        }
                        count += node.length;
                        last = [node, node.length];
                    } else if (node.nodeName === 'BR') {
                        if (position <= count) return last;
                        count += 1;
                        last = [node.parentNode, Array.prototype.indexOf.call(node.parentNode.childNodes, node) + 1];
                    } else if (node.matches(RICH_TEXT_BLOCK_SELECTOR)) {
                        if (!first) {
                            if (position <= count) return last;
                            count += 1;
                        }
                        first = false;
                        last = [node, 0];
                    }
                }
                return last;
            }

            function selectionPositions() {
                const range = currentRange()
                    || (savedRange && surface.contains(savedRange.commonAncestorContainer) ? savedRange : null);
                return range ? {start: positionOf(range.startContainer, range.startOffset),
                    end: positionOf(range.endContainer, range.endOffset)} : null;
            }

            function selectPositions(positions) {
                if (!positions) return;
                const range = document.createRange();
                range.setStart(...pointAt(positions.start, positions.start !== positions.end));
                range.setEnd(...pointAt(positions.end));
                window.getSelection().removeAllRanges();
                window.getSelection().addRange(range);
            }

            function snapshot() {
                return {html: richHtml(cleanRichNodes(surface)), positions: selectionPositions()};
            }

            /** 바꾸기 직전 모양을 쌓는다. 같은 모양이 맨 위에 있으면 다시 쌓지 않는다. */
            function pushHistory() {
                const current = snapshot();
                const top = history.undo[history.undo.length - 1];
                if (!top || top.html !== current.html) history.undo.push(current);
                if (history.undo.length > 100) history.undo.shift();
                history.redo.length = 0;
                updateHistoryButtons();
            }

            /** 이어서 입력하는 글은 1초 멈출 때까지 한 번의 기록으로 묶는다. */
            function beginTyping() {
                if (!history.typing) pushHistory();
                clearTimeout(history.typing);
                history.typing = setTimeout(() => { history.typing = null; }, 1000);
            }

            function endTyping() {
                clearTimeout(history.typing);
                history.typing = null;
            }

            function applySnapshot(saved) {
                surface.replaceChildren(cleanRichNodes(saved.html));
                if (!surface.firstChild) surface.append(el('p', {}, [el('br')]));
                savedRange = null;
                surface.focus({preventScroll: true});
                selectPositions(saved.positions);
                sync();
                remember();
            }

            function undo() {
                endTyping();
                if (!history.undo.length) return;
                history.redo.push(snapshot());
                applySnapshot(history.undo.pop());
                updateHistoryButtons();
            }

            function redo() {
                endTyping();
                if (!history.redo.length) return;
                history.undo.push(snapshot());
                applySnapshot(history.redo.pop());
                updateHistoryButtons();
            }

            function closestInSurface(selector) {
                const range = currentRange();
                const node = range?.startContainer;
                const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
                const found = element?.closest(selector);
                return found && surface.contains(found) ? found : null;
            }

            /** 선택이 걸친 글 문단(p, 제목, 목록 항목, 인용). 없으면 커서가 있는 문단 하나. */
            function selectedBlocks() {
                const range = currentRange();
                if (!range) return [];
                const blocks = Array.from(surface.querySelectorAll(RICH_TEXT_BLOCK_SELECTOR))
                    .filter(element => range.intersectsNode(element) && !element.querySelector(RICH_TEXT_BLOCK_SELECTOR));
                if (blocks.length) return blocks;
                const closest = closestInSurface(RICH_TEXT_BLOCK_SELECTOR);
                return closest ? [closest] : [];
            }

            function ensureBlocks() {
                if (selectedBlocks().length === 0) document.execCommand('formatBlock', false, 'p');
                return selectedBlocks();
            }

            function setClass(element, names, value) {
                element.classList.remove(...names);
                if (value) element.classList.add(value);
                if (!element.classList.length) element.removeAttribute('class');
            }

            /*
              문단 모양(문단 스타일·목록·인용)은 브라우저 formatBlock / insertList 를 쓰지 않고 고른 문단을 직접 바꾼다.
              브라우저 명령은 br 로 나뉜 줄을 따로 문단으로 보고 p 안에 p, 인용 안에 목록처럼 겹친 모양을 만든다.
              글꼴은 글자에 씌운 span 이라 문단을 바꿔도 그대로 따라간다.
            */
            function replaceTag(element, tag) {
                const next = document.createElement(tag);
                next.append(...element.childNodes);
                element.replaceWith(next);
                return next;
            }

            /** br 로 나뉜 줄마다의 노드 묶음. 글이 없는 줄은 뺀다. */
            function blockLines(element) {
                const lines = [[]];
                Array.from(element.childNodes).forEach(node => {
                    if (node.nodeName === 'BR') lines.push([]);
                    else lines[lines.length - 1].push(node);
                });
                return lines.filter(nodes => nodes.some(node => node.textContent.trim() !== ''));
            }

            /** 목록 항목 하나를 문단으로 꺼낸다. 가운데 항목이면 목록을 둘로 나눈다. */
            function listItemToParagraph(item) {
                const list = item.parentElement;
                const siblings = Array.from(list.children);
                const tail = siblings.slice(siblings.indexOf(item) + 1);
                const paragraph = document.createElement('p');
                paragraph.append(...item.childNodes);
                list.after(paragraph);
                if (tail.length) {
                    const rest = document.createElement(list.tagName);
                    rest.append(...tail);
                    paragraph.after(rest);
                }
                item.remove();
                if (!list.children.length) list.remove();
                return paragraph;
            }

            function applyParagraphStyle(value) {
                const tag = value === 'h3' || value === 'h4' ? value.toUpperCase() : 'P';
                const blocks = ensureBlocks();
                if (blocks.some(element => element.tagName === 'LI')) {
                    say('목록 안에서는 문단 스타일을 바꿀 수 없습니다. 목록을 먼저 풀어 주세요.');
                }
                blocks.filter(element => element.tagName !== 'LI').forEach(element => {
                    const target = element.tagName === tag ? element : replaceTag(element, tag);
                    // 예전에 문단 전체에 붙이던 글자 크기는 문단 스타일을 다시 고르면 지운다.
                    setClass(target, RICH_SIZE_CLASSES, null);
                });
            }

            /** 글머리(UL) / 번호(OL) 목록. 이미 같은 목록이면 풀고, 다른 목록이면 종류만 바꾼다. */
            function toggleList(type) {
                const blocks = ensureBlocks();
                if (!blocks.length) return;
                const items = blocks.filter(element => element.tagName === 'LI');
                if (items.length === blocks.length) {
                    const lists = Array.from(new Set(items.map(item => item.parentElement)));
                    if (lists.every(list => list.tagName === type)) {
                        items.forEach(listItemToParagraph);
                    } else {
                        lists.filter(list => list.tagName !== type).forEach(list => replaceTag(list, type));
                    }
                    return;
                }
                // 문단(줄바꿈으로 나뉜 줄은 한 줄씩)을 하나의 목록으로 묶는다.
                const sources = blocks.filter(element => element.tagName !== 'LI');
                const list = document.createElement(type);
                sources[0].before(list);
                sources.forEach(element => {
                    blockLines(element).forEach(nodes => {
                        const item = document.createElement('li');
                        item.append(...nodes);
                        list.append(item);
                    });
                    element.remove();
                });
                if (!list.children.length) list.remove();
            }

            /** 고른 범위의 글자 조각(text node). 범위 끝에 걸친 조각은 잘라서 고른 부분만 남긴다. */
            function splitSelectedText(range) {
                let {startContainer, startOffset, endContainer, endOffset} = range;
                if (endContainer.nodeType === Node.TEXT_NODE && endOffset > 0 && endOffset < endContainer.length) {
                    endContainer.splitText(endOffset);
                }
                if (startContainer.nodeType === Node.TEXT_NODE && startOffset > 0 && startOffset < startContainer.length) {
                    const rest = startContainer.splitText(startOffset);
                    if (endContainer === startContainer) {
                        endContainer = rest;
                        endOffset -= startOffset;
                    }
                    startContainer = rest;
                    startOffset = 0;
                }
                const bounded = document.createRange();
                bounded.setStart(startContainer, startOffset);
                bounded.setEnd(endContainer, endOffset);
                const nodes = [];
                const walker = document.createTreeWalker(surface, NodeFilter.SHOW_TEXT);
                let node;
                while ((node = walker.nextNode())) {
                    if (!node.length || !bounded.intersectsNode(node)) continue;
                    if (node === startContainer && startOffset >= node.length) continue;
                    if (node === endContainer && endOffset === 0) continue;
                    nodes.push(node);
                }
                return nodes;
            }

            function presetOf(node, names) {
                const span = node.parentElement?.closest(names.map(name => `span.${name}`).join(', '));
                return span && surface.contains(span) ? names.find(name => span.classList.contains(name)) : '';
            }

            function fontOf(node) {
                return presetOf(node, RICH_FONT_CLASSES);
            }

            /** 글자 크기. 예전처럼 문단(p) 전체에 붙은 크기는 그 문단 글자들의 크기로 본다. */
            function sizeOf(node) {
                const size = presetOf(node, RICH_SIZE_CLASSES);
                if (size) return size;
                const paragraph = node.parentElement?.closest('p');
                return paragraph && surface.contains(paragraph)
                    ? RICH_SIZE_CLASSES.find(name => paragraph.classList.contains(name)) || '' : '';
            }

            /**
             * 고른 글자에만 글꼴(kind 'font') 또는 글자 크기(kind 'size') 프리셋을 씌운다.
             * ('기본 글꼴' / '기본 크기' 는 그 프리셋을 지운다)
             * 고른 글자가 걸친 문단마다 글자 조각의 글꼴·크기를 다시 정해 조각마다 span 하나(class 두 개까지)로 새로 감싼다.
             * 그래서 기존 span 의 일부만 바꿔도 앞뒤 글자는 그대로 남고, span 이 겹겹이 쌓이지 않으며,
             * 굵게·강조·링크처럼 글자를 감싼 다른 서식도 그대로 둔다. 예전 문단 단위 크기는 글자 span 으로 옮긴다.
             */
            function applyTextPreset(kind, value) {
                const range = currentRange();
                if (!range || range.collapsed) {
                    say(kind === 'font' ? '글꼴을 바꿀 글자를 먼저 선택해 주세요.' : '글자 크기를 바꿀 글자를 먼저 선택해 주세요.');
                    return;
                }
                const selected = splitSelectedText(range);
                if (!selected.length) return;
                const chosen = new Set(selected);
                const containers = new Set(selected.map(node =>
                    node.parentElement.closest(RICH_TEXT_BLOCK_SELECTOR) || surface));
                containers.forEach(container => {
                    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
                    const pieces = [];
                    let node;
                    while ((node = walker.nextNode())) {
                        if (!node.length) continue;
                        const font = kind === 'font' && chosen.has(node) ? value : fontOf(node);
                        const size = kind === 'size' && chosen.has(node) ? value : sizeOf(node);
                        pieces.push([node, [font, size].filter(Boolean).join(' ')]);
                    }
                    container.querySelectorAll('span').forEach(unwrapNode);
                    if (container.tagName === 'P') setClass(container, RICH_SIZE_CLASSES, null);
                    pieces.forEach(([piece, classes]) => {
                        if (!classes) return;
                        const span = document.createElement('span');
                        span.className = classes;
                        piece.before(span);
                        span.append(piece);
                    });
                    // 이웃한 같은 글꼴·크기 조각은 하나로 합친다.
                    container.querySelectorAll('span').forEach(span => {
                        let next = span.nextSibling;
                        while (span.isConnected && next?.nodeName === 'SPAN' && next.className === span.className) {
                            span.append(...next.childNodes);
                            next.remove();
                            next = span.nextSibling;
                        }
                    });
                });
                const first = selected[0];
                const last = selected[selected.length - 1];
                const changed = document.createRange();
                changed.setStart(first, 0);
                changed.setEnd(last, last.length);
                window.getSelection().removeAllRanges();
                window.getSelection().addRange(changed);
            }

            /** 형광 강조(point=false) / 포인트 강조(point=true). 이미 같은 강조 안이면 푼다. 문단마다 따로 감싼다. */
            function toggleMark(point) {
                const range = currentRange();
                if (!range || range.collapsed) {
                    say('강조할 글자를 먼저 선택해 주세요.');
                    return;
                }
                const current = closestInSurface('mark');
                if (current && current.classList.contains('rt-point') === point) {
                    Array.from(surface.querySelectorAll('mark'))
                        .filter(mark => range.intersectsNode(mark) && mark.classList.contains('rt-point') === point)
                        .forEach(unwrapNode);
                    return;
                }
                const blocks = selectedBlocks();
                const parts = (blocks.length ? blocks : [null]).map(element => {
                    if (!element) return range.cloneRange();
                    const part = document.createRange();
                    part.selectNodeContents(element);
                    if (range.compareBoundaryPoints(Range.START_TO_START, part) > 0) {
                        part.setStart(range.startContainer, range.startOffset);
                    }
                    if (range.compareBoundaryPoints(Range.END_TO_END, part) < 0) {
                        part.setEnd(range.endContainer, range.endOffset);
                    }
                    return part;
                }).filter(part => !part.collapsed && part.toString().trim() !== '');
                const marks = parts.reverse().map(part => {
                    const content = part.extractContents();
                    content.querySelectorAll('mark').forEach(unwrapNode);
                    const mark = document.createElement('mark');
                    if (point) mark.className = 'rt-point';
                    mark.append(content);
                    part.insertNode(mark);
                    return mark;
                });
                if (marks.length) {
                    const selected = document.createRange();
                    selected.setStartBefore(marks[marks.length - 1]);
                    selected.setEndAfter(marks[0]);
                    window.getSelection().removeAllRanges();
                    window.getSelection().addRange(selected);
                }
            }

            /** 고른 문단을 인용문으로(이미 모두 인용문이면 본문으로). 목록 항목은 바꾸지 않는다. */
            function toggleQuote() {
                const blocks = ensureBlocks().filter(element => element.tagName !== 'LI');
                if (!blocks.length) {
                    say('목록 안에서는 인용문을 쓸 수 없습니다. 목록을 먼저 풀어 주세요.');
                    return;
                }
                const unquote = blocks.every(element => element.tagName === 'BLOCKQUOTE');
                blocks.filter(element => unquote || element.tagName !== 'BLOCKQUOTE')
                    .forEach(element => replaceTag(element, unquote ? 'P' : 'BLOCKQUOTE'));
            }

            // 링크: 선택한 글자에 http / https 주소만 건다. 주소는 툴바 아래 작은 입력 줄에서 받는다.
            // scheme 없이 쓴 주소에는 https:// 를 붙인다. (normalizeLinkUrl)
            const linkHintId = `${id}-link-hint`;
            const linkInput = el('input', {
                type: 'text', className: 'admin-structured-richtext-link-input',
                placeholder: 'example.com 또는 https://example.com', inputmode: 'url', autocomplete: 'off',
                'aria-label': '링크 주소', 'aria-describedby': linkHintId, maxlength: 500,
                onkeydown: event => {
                    if (event.key === 'Enter') {
                        event.preventDefault();
                        applyLink();
                    } else if (event.key === 'Escape') {
                        event.preventDefault();
                        closeLink(true);
                    }
                }
            });
            const linkRow = el('div', {className: 'admin-structured-richtext-link', hidden: true}, [
                linkInput,
                el('button', {type: 'button', className: 'admin-btn is-small', text: '적용', onclick: () => applyLink()}),
                el('button', {type: 'button', className: 'admin-btn is-small', text: '취소', onclick: () => closeLink(true)}),
                el('span', {id: linkHintId, className: 'admin-structured-richtext-link-hint',
                    text: '주소만 입력해도 https://가 자동으로 추가됩니다.'})
            ]);

            function openLink() {
                const link = closestInSurface('a');
                const range = currentRange();
                if (!link && (!range || range.collapsed)) {
                    say('링크를 걸 글자를 먼저 선택해 주세요.');
                    return;
                }
                savedRange = range ? range.cloneRange() : savedRange;
                linkRow.hidden = false;
                linkInput.value = link ? text(link.getAttribute('href')) : '';
                linkInput.focus();
            }

            function closeLink(focusBack) {
                linkRow.hidden = true;
                linkInput.value = '';
                if (focusBack) restore();
            }

            function applyLink() {
                if (blank(linkInput.value)) {
                    say('링크 주소를 입력해 주세요.');
                    linkInput.focus();
                    return;
                }
                const url = normalizeLinkUrl(linkInput.value);
                if (!url) {
                    say('사용할 수 없는 주소입니다. 웹 주소(예: example.com)만 넣을 수 있습니다.');
                    linkInput.focus();
                    return;
                }
                restore();
                endTyping();
                pushHistory();
                const link = closestInSurface('a');
                const range = currentRange();
                if (link && (!range || range.collapsed)) link.setAttribute('href', url);
                else document.execCommand('createLink', false, url);
                closeLink(false);
                say('');
                sync();
                remember();
            }

            function removeLink() {
                const link = closestInSurface('a');
                const range = currentRange();
                if (link) {
                    const whole = document.createRange();
                    whole.selectNodeContents(link);
                    window.getSelection().removeAllRanges();
                    window.getSelection().addRange(whole);
                } else if (!range || range.collapsed || !Array.from(surface.querySelectorAll('a'))
                    .some(anchor => range.intersectsNode(anchor))) {
                    say('선택한 곳에 링크가 없습니다.');
                    return;
                }
                document.execCommand('unlink');
            }

            /**
             * 서식 명령 하나. 고르던 선택을 되돌리고, 실행취소 기록을 쌓고, 바꾼 뒤 같은 글자 자리를 다시 고른다.
             * (문단을 통째로 고른 채 두면 이어서 누른 키가 그 문단 글을 덮어쓴다)
             * 고르기 칸(문단 스타일·글꼴)은 keepFocus 로 focus 를 고르기 칸에 둔 채 적용한다.
             */
            function format(run, keepFocus) {
                restore(keepFocus);
                say('');
                endTyping();
                pushHistory();
                const positions = selectionPositions();
                run();
                selectPositions(positions);
                sync();
                remember();
            }

            const command = (name, label, ariaLabel, run, options = {}) => el('button', {
                type: 'button', className: `admin-structured-richtext-button${options.className ? ' ' + options.className : ''}`,
                text: label, title: ariaLabel, 'aria-label': ariaLabel, 'data-rich-command': name,
                'aria-pressed': options.toggle ? 'false' : null,
                onmousedown: event => event.preventDefault(),
                onclick: () => format(run)
            });
            // 고른 값은 restore() 전에 읽는다. restore() 가 편집 칸에 focus 를 돌려주면 onfocus → updateState() 가
            // 고르기 칸을 지금 커서 위치의 값으로 되돌리기 때문이다. (그러면 언제나 본문 / 기본 글꼴이 적용된다)
            const picker = (label, choices, apply) => el('select', {
                className: 'admin-structured-richtext-select', 'aria-label': label, title: label,
                onchange: event => {
                    const value = event.target.value;
                    format(() => apply(value), true);
                    // 선택을 다시 그리면 브라우저가 편집 칸으로 focus 를 옮긴다. 고르기 칸으로 돌려 키보드로 이어서 고를 수 있게 한다.
                    // (고른 글자 자리는 format 이 기억해 두었다)
                    event.target.focus({preventScroll: true});
                }
            }, choices.map(([value, name]) => el('option', {value, text: name})));
            const historyButton = (name, label, ariaLabel, run) => el('button', {
                type: 'button', className: 'admin-structured-richtext-button is-history', text: label,
                title: ariaLabel, 'aria-label': ariaLabel, 'data-rich-command': name, disabled: true,
                onmousedown: event => event.preventDefault(),
                onclick: () => {
                    say('');
                    run();
                }
            });
            const undoButton = historyButton('undo', '↶', '실행취소 (Ctrl+Z)', undo);
            const redoButton = historyButton('redo', '↷', '다시실행 (Ctrl+Y)', redo);

            function updateHistoryButtons() {
                undoButton.disabled = history.undo.length === 0;
                redoButton.disabled = history.redo.length === 0;
            }
            const paragraphPicker = picker('문단 스타일', RICH_PARAGRAPH_STYLES, applyParagraphStyle);
            paragraphPicker.value = 'body';
            const fontPicker = picker('글꼴', RICH_FONTS, value => applyTextPreset('font', value));
            const sizePicker = picker('글자 크기', RICH_SIZES, value => applyTextPreset('size', value));
            sizePicker.value = '';
            const divider = () => el('span', {className: 'admin-structured-richtext-divider', 'aria-hidden': 'true'});

            const toolbar = el('div', {className: 'admin-structured-richtext-toolbar', role: 'toolbar',
                'aria-label': '본문 서식', 'aria-controls': id}, [
                undoButton,
                redoButton,
                divider(),
                paragraphPicker,
                divider(),
                command('bold', 'B', '굵게', () => document.execCommand('bold'), {toggle: true, className: 'is-bold'}),
                command('italic', 'I', '기울임', () => document.execCommand('italic'), {toggle: true, className: 'is-italic'}),
                command('underline', 'U', '밑줄', () => document.execCommand('underline'),
                    {toggle: true, className: 'is-underline'}),
                command('mark', '형광', '형광 강조', () => toggleMark(false), {className: 'is-mark'}),
                command('point', '포인트', '포인트 강조', () => toggleMark(true), {className: 'is-point'}),
                divider(),
                el('button', {type: 'button', className: 'admin-structured-richtext-button', text: '링크',
                    title: '링크 추가', 'aria-label': '링크 추가', 'data-rich-command': 'link',
                    onmousedown: event => event.preventDefault(), onclick: () => { say(''); openLink(); }}),
                command('unlink', '링크 해제', '링크 해제', removeLink),
                divider(),
                command('ul', '• 목록', '글머리 목록', () => toggleList('UL'), {toggle: true}),
                command('ol', '1. 목록', '번호 목록', () => toggleList('OL'), {toggle: true}),
                command('quote', '인용', '인용문', toggleQuote, {toggle: true}),
                divider(),
                fontPicker,
                sizePicker
            ]);

            /** 커서가 있는 곳의 서식을 툴바에 보여 준다. */
            function updateState() {
                if (!currentRange()) return;
                const pressed = {
                    bold: document.queryCommandState('bold'),
                    italic: document.queryCommandState('italic'),
                    underline: document.queryCommandState('underline'),
                    ul: !!closestInSurface('ul'),
                    ol: !!closestInSurface('ol'),
                    quote: !!closestInSurface('blockquote')
                };
                toolbar.querySelectorAll('[aria-pressed]').forEach(button => {
                    button.setAttribute('aria-pressed', String(!!pressed[button.dataset.richCommand]));
                });
                const current = selectedBlocks()[0];
                paragraphPicker.value = !current ? 'body'
                    : current.tagName === 'H3' ? 'h3'
                        : current.tagName === 'H4' ? 'h4' : 'body';
                const start = currentRange().startContainer;
                const textNode = start.nodeType === Node.TEXT_NODE ? start
                    : (start.firstChild?.nodeType === Node.TEXT_NODE ? start.firstChild : null);
                fontPicker.value = textNode ? fontOf(textNode) : '';
                sizePicker.value = textNode ? sizeOf(textNode) : '';
            }
            updateHistoryButtons();

            return el('div', {className: 'admin-form-field admin-structured-richtext'}, [
                el('label', {id: `${id}-label`, onclick: () => surface.focus()}, ['본문',
                    el('span', {className: 'admin-structured-required', 'aria-hidden': 'true', text: ' *'})]),
                el('div', {className: 'admin-structured-richtext-box'}, [toolbar, linkRow, surface]),
                status,
                el('span', {className: 'admin-help-text',
                    text: 'Enter 로 문단을 나눕니다. 글자 크기·글꼴·강조는 정해진 프리셋만 쓸 수 있고, 붙여 넣은 글은 서식 없이 들어갑니다.'})
            ]);
        }

        /**
         * 이미지 자리. 고르면 바로 올리고, 성공하면 서버가 준 url / width / height 로 바꾼다.
         * 바꾸기·지우기는 model 만 고치고, 예전 파일 정리는 저장 때 서버가 한다.
         */
        function imageControl(options, errorId) {
            const {key, image, label} = options;
            const uploading = state.uploads.has(key);
            const error = state.imageErrors.get(key);
            const inputId = `${domPrefix}-sb-${key.replace(/:/g, '-')}-file`;
            const statusId = `${inputId}-status`;
            const invalid = invalidAttributes(key, errorId);
            const fileInput = el('input', {
                id: inputId, type: 'file', accept: IMAGE_ACCEPT, className: 'admin-visually-hidden',
                disabled: uploading, 'data-focus-key': `${key}:file`,
                'aria-describedby': [statusId, invalid['aria-describedby']].filter(Boolean).join(' '),
                'aria-invalid': invalid['aria-invalid'],
                onchange: event => {
                    const file = event.target.files?.[0];
                    if (file) uploadInto(key, file, options.assign);
                }
            });
            return el('div', {className: `admin-structured-image${invalid.className ? ' is-invalid' : ''}`}, [
                el('div', {className: 'admin-structured-image-preview'}, image
                    ? el('img', {src: image.url, alt: '', loading: 'lazy'})
                    : el('span', {text: '이미지 없음'})),
                el('div', {className: 'admin-structured-image-side'}, [
                    el('div', {className: 'admin-structured-image-actions'}, [
                        el('label', {for: inputId, className: `admin-btn is-small${uploading ? ' is-disabled' : ''}`},
                            [image ? `${label} 교체` : `${label} 올리기`, fileInput]),
                        image && options.remove ? el('button', {
                            type: 'button', className: 'admin-btn is-small', disabled: uploading,
                            'data-focus-key': `${key}:remove`, text: '이미지 빼기',
                            onclick: () => {
                                options.remove();
                                markDirty();
                                render(`${key}:file`);
                            }
                        }) : null
                    ]),
                    el('p', {id: statusId, className: `admin-structured-image-status${error ? ' is-error' : ''}`,
                        'aria-live': 'polite',
                        text: uploading ? '업로드 중…'
                            : error || (image ? `${image.width} × ${image.height}px` : 'JPG·PNG·WebP, 10MB 이하. 긴 변 2000px를 넘으면 줄여 저장합니다.')})
                ])
            ]);
        }

        /**
         * 이미지 한 장의 편집 카드. 큰 이미지·이미지 + 글·슬라이더 사진·이미지 배치 칸이 모두 이 카드를 쓴다.
         *   [이미지 미리보기·교체·해상도]
         *   [기본 정보] 블록이 가진 글 칸(제목·설명) + 한 단계 낮은 접근성 설명(alt)
         *   [출처 정보] 접힌 토글
         * 넓은 자리(큰 이미지, 슬라이더 사진)는 [이미지 | 기본 정보] 두 칸에 출처를 아래 한 줄로,
         * 좁은 자리(stacked: 이미지 배치 칸, 이미지 + 글의 사진 칸)는 모두 한 칸으로 쌓는다.
         */
        function imageCard(block, options, errorId) {
            const fields = options.fields || [];
            return el('div', {className: `admin-structured-image-card${options.stacked ? ' is-stacked' : ''}`}, [
                imageControl({key: options.imageKey, image: options.image, label: '이미지',
                    assign: options.assign, remove: options.remove}, errorId),
                el('div', {className: 'admin-structured-image-basics', role: 'group',
                    'aria-label': `${options.name} 기본 정보`}, [
                    fields.length ? el('p', {className: 'admin-structured-section-label', 'aria-hidden': 'true',
                        text: '기본 정보'}) : null,
                    ...fields,
                    field(block, {key: options.alt.key, label: '접근성 설명(alt)', max: LIMITS.alt, secondary: true,
                        summary: options.alt.summary, help: '이미지를 설명하는 대체 텍스트입니다.',
                        value: options.alt.value, set: options.alt.set}, errorId)
                ]),
                creditControl(block, options.image, options.imageKey, errorId)
            ]);
        }

        /**
         * 이미지 한 장의 출처 정보. 모든 이미지 카드에 언제나 있다. (이미지를 올리기 전에는 추가 버튼이 비활성이다)
         * credit 이 없으면 [+ 출처 정보 추가] 버튼 하나뿐이다. 누르면 빈 credit 을 만들고 입력 칸을 펼친다.
         * credit 이 있으면 [출처 정보 있음 ▾] 토글과 요약 한 줄로 접어 두고, 펼치면 입력 칸·미리보기·[출처 정보 제거]가 보인다.
         * 제거는 credit 을 지운다. (값이 있으면 먼저 묻는다) 모두 비운 채 저장하면 credit 을 쓰지 않는다.
         * 출처 글은 번역하지 않으므로 원문 에디터에만 있다.
         */
        function creditControl(block, image, imageKey, errorId) {
            const credit = image ? image.credit : null;
            const addKey = `${imageKey}:credit`;
            if (!image) {
                // credit 은 이미지에 붙는 정보라 이미지가 없으면 넣을 자리가 없다. 자리만 보여 주고 막아 둔다.
                return el('div', {className: 'admin-structured-credit', role: 'group', 'aria-label': '출처 정보'}, [
                    el('button', {type: 'button', className: 'admin-structured-credit-add', disabled: true,
                        'aria-describedby': `${domPrefix}-sb-${addKey.replace(/:/g, '-')}-help`, text: '+ 출처 정보 추가'}),
                    el('span', {id: `${domPrefix}-sb-${addKey.replace(/:/g, '-')}-help`, className: 'admin-help-text',
                        text: '이미지를 올린 뒤 추가할 수 있습니다.'})
                ]);
            }
            if (!credit) {
                return el('div', {className: 'admin-structured-credit', role: 'group', 'aria-label': '출처 정보'}, [
                    el('button', {
                        type: 'button', className: 'admin-structured-credit-add', 'data-focus-key': addKey,
                        title: '외부 이미지의 저작자·라이선스를 기록합니다.', text: '+ 출처 정보 추가',
                        onclick: () => {
                            image.credit = emptyCredit();
                            state.openCredits.add(imageKey);
                            markDirty();
                            render(`${imageKey}:credit-author`);
                        }
                    })
                ]);
            }

            const open = state.openCredits.has(imageKey);
            const toggleKey = `${imageKey}:credit-toggle`;
            const panelId = `${domPrefix}-sb-${addKey.replace(/:/g, '-')}`;
            const invalid = CREDIT_FIELDS.some(name => state.errors?.fields.has(`${imageKey}:credit-${name}`));
            // 접었을 때는 토글 옆 요약, 펼쳤을 때는 입력 칸 아래 미리보기. 둘 다 입력하는 대로 바뀐다.
            const summary = el('span', {className: 'admin-structured-credit-summary'});
            const preview = el('p', {className: 'admin-structured-credit-preview', 'aria-live': 'polite'});
            const refreshPreview = () => {
                const line = creditPreview(credit);
                summary.textContent = line || '아직 입력하지 않았습니다.';
                summary.classList.toggle('is-empty', !line);
                preview.textContent = line;
                preview.hidden = line === '';
            };
            refreshPreview();
            const header = el('div', {className: 'admin-structured-credit-header'}, [
                el('button', {
                    type: 'button', className: `admin-structured-credit-toggle${invalid ? ' is-invalid' : ''}`,
                    'aria-expanded': String(open), 'aria-controls': open ? panelId : null,
                    'data-focus-key': toggleKey,
                    onclick: () => {
                        if (open) state.openCredits.delete(imageKey);
                        else state.openCredits.add(imageKey);
                        render(toggleKey);
                    }
                }, [
                    el('span', {text: '출처 정보 있음'}),
                    el('span', {className: 'admin-structured-credit-chevron', 'aria-hidden': 'true', text: open ? '▴' : '▾'})
                ]),
                open ? null : summary
            ]);
            if (!open) {
                return el('div', {className: 'admin-structured-credit', role: 'group', 'aria-label': '출처 정보'}, [header]);
            }

            return el('div', {className: 'admin-structured-credit is-open', role: 'group', 'aria-label': '출처 정보'}, [
                header,
                el('div', {id: panelId, className: 'admin-structured-credit-fields'}, [
                    ...creditInputs(block, credit, imageKey, `${panelId}-licenses`, errorId, {onChange: refreshPreview}),
                    el('div', {className: 'admin-structured-credit-footer'}, [
                        preview,
                        el('button', {
                            type: 'button', className: 'admin-btn is-small admin-structured-credit-remove',
                            'data-focus-key': `${imageKey}:credit-remove`, text: '출처 정보 제거',
                            onclick: () => {
                                if (writeCredit(image.credit)
                                    && !window.confirm('이 이미지의 출처 정보를 지울까요? 입력한 내용이 사라집니다.')) {
                                    return;
                                }
                                image.credit = null;
                                state.openCredits.delete(imageKey);
                                markDirty();
                                render(addKey);
                            }
                        })
                    ])
                ])
            ]);
        }

        /**
         * 출처 입력 칸 다섯 개(저작자·출처명 / 출처 URL / 라이선스 / 라이선스 URL).
         * 이미지 한 장의 출처 정보와 슬라이더 공통 출처 설정이 같은 칸을 쓴다. 칸 key 는 `${keyPrefix}:credit-<이름>` 이다.
         * options.onChange 는 값이 바뀔 때마다, options.draft 는 저장하지 않는 입력값(공통 출처)일 때 쓴다.
         */
        function creditInputs(block, credit, keyPrefix, licenseListId, errorId, options) {
            const creditField = (name, label, max, extra = {}) => field(block, {
                key: `${keyPrefix}:credit-${name}`, label, max, optionalHint: false, draft: options.draft,
                attributes: extra.attributes, help: extra.help, value: credit[name],
                set: value => {
                    credit[name] = value;
                    if (extra.after) extra.after(value);
                    options.onChange();
                }
            }, errorId);
            const urlAttributes = {inputmode: 'url', autocomplete: 'off', spellcheck: 'false'};
            const licenseUrlField = creditField('licenseUrl', '라이선스 URL', LIMITS.creditLicenseUrl,
                {attributes: urlAttributes});
            const licenseUrlInput = licenseUrlField.querySelector('input');
            // 제안 라이선스를 그대로 입력하면 URL 을 채운다. 비었거나 다른 제안값의 URL 일 때만 바꾼다. (직접 쓴 URL 은 둔다)
            const fillLicenseUrl = value => {
                const known = CREDIT_LICENSES.find(item => item.name === value.trim());
                const current = text(credit.licenseUrl).trim();
                if (!known || (current && !CREDIT_LICENSES.some(item => item.url === current))) return;
                credit.licenseUrl = known.url;
                licenseUrlInput.value = known.url;
            };
            return [
                el('div', {className: 'admin-structured-credit-names'}, [
                    creditField('author', '저작자', LIMITS.creditAuthor),
                    creditField('source', '출처명', LIMITS.creditSource)
                ]),
                creditField('sourceUrl', '출처 URL', LIMITS.creditSourceUrl, {attributes: urlAttributes}),
                creditField('license', '라이선스', LIMITS.creditLicense, {
                    attributes: {list: licenseListId, autocomplete: 'off'}, after: fillLicenseUrl,
                    help: '목록에 없어도 직접 입력할 수 있습니다.'
                }),
                el('datalist', {id: licenseListId}, CREDIT_LICENSES.map(item => el('option', {value: item.name}))),
                licenseUrlField
            ];
        }

        /**
         * 슬라이더 공통 출처 설정. 같은 출처의 사진이 많을 때 한 번 입력해 이 슬라이더 사진들의 출처 칸에 채운다.
         * 입력값은 저장하지 않는 도우미이고, 적용하면 사진마다의 credit 에 따로 복사된다. (applyCommonCredit)
         * 평소에는 접힌 토글 한 줄이다. 다른 슬라이더 블록의 사진에는 닿지 않는다.
         */
        function commonCreditControl(block, errorId) {
            const prefix = `${block.id}:common`;
            const open = state.openCommonCredits.has(block.id);
            const toggleKey = `${prefix}:toggle`;
            const panelId = `${domPrefix}-sb-${block.id}-common-credit`;
            const header = el('div', {className: 'admin-structured-credit-header'}, [
                el('button', {
                    type: 'button', className: 'admin-structured-credit-toggle',
                    'aria-expanded': String(open), 'aria-controls': open ? panelId : null,
                    'data-focus-key': toggleKey,
                    onclick: () => {
                        if (open) state.openCommonCredits.delete(block.id);
                        else state.openCommonCredits.add(block.id);
                        render(toggleKey);
                    }
                }, [
                    el('span', {text: '공통 출처 설정'}),
                    el('span', {className: 'admin-structured-credit-chevron', 'aria-hidden': 'true', text: open ? '▴' : '▾'})
                ]),
                open ? null : el('span', {className: 'admin-structured-credit-summary is-empty',
                    text: '같은 출처를 이 슬라이더의 여러 이미지에 한 번에 채웁니다.'})
            ]);
            const className = 'admin-structured-credit admin-structured-common-credit';
            if (!open) {
                return el('div', {className, role: 'group', 'aria-label': '공통 출처 설정'}, [header]);
            }

            if (!state.commonCredits.has(block.id)) state.commonCredits.set(block.id, emptyCredit());
            const credit = state.commonCredits.get(block.id);
            const status = state.commonCreditStatus.get(block.id);
            const defaultStatus = '올린 이미지의 출처 정보에 채웁니다. 적용한 뒤에도 이미지마다 고칠 수 있습니다.';
            const statusLine = el('p', {className: `admin-help-text admin-structured-common-credit-status${status?.error ? ' is-error' : ''}`,
                'aria-live': 'polite', text: status ? status.text : defaultStatus});
            const hasImages = block.items.some(item => item.image);
            const applyButton = (name, label, onlyEmpty) => el('button', {
                type: 'button', className: 'admin-btn is-small', disabled: !hasImages,
                'data-focus-key': `${prefix}:${name}`, text: label,
                onclick: () => applyCommonCreditTo(block, onlyEmpty, `${prefix}:${name}`)
            });
            return el('div', {className: `${className} is-open`, role: 'group', 'aria-label': '공통 출처 설정'}, [
                header,
                el('div', {id: panelId, className: 'admin-structured-credit-fields'}, [
                    ...creditInputs(block, credit, prefix, `${panelId}-licenses`, errorId, {
                        draft: true,
                        onChange: () => {
                            // 값을 고치면 지난 적용 결과 문구는 맞지 않으므로 안내 문구로 되돌린다.
                            state.commonCreditStatus.delete(block.id);
                            statusLine.textContent = defaultStatus;
                            statusLine.classList.remove('is-error');
                        }
                    }),
                    el('div', {className: 'admin-structured-credit-footer'}, [
                        statusLine,
                        el('div', {className: 'admin-structured-image-actions'}, [
                            applyButton('apply-all', '전체 이미지에 적용', false),
                            applyButton('apply-empty', '출처 없는 이미지에만 적용', true)
                        ])
                    ])
                ])
            ]);
        }

        function renderBlockFields(block, errorId) {
            const key = name => `${block.id}:${name}`;
            switch (block.type) {
                case 'SECTION_TITLE':
                    return [field(block, {key: key('title'), label: '제목', max: LIMITS.title, required: true, summary: true,
                        value: block.title, set: value => { block.title = value; }}, errorId)];
                case 'RICH_TEXT':
                    return [
                        renderChoices(block, {name: 'layout', legend: '본문 폭', property: 'layout',
                            choices: [['DEFAULT', '기본'], ['FOCUSED', '집중형']],
                            help: '집중형은 짧은 도입부·에세이를 좁은 폭 가운데에 둡니다. 휴대폰에서는 같습니다.'}),
                        richTextField(block, errorId)
                    ];
                case 'FULL_IMAGE':
                    // 큰 이미지에는 제목이 없다. 기본 정보는 설명(캡션)과 접근성 설명(alt)이다.
                    return [
                        imageCard(block, {
                            name: '큰 이미지', imageKey: key('image'), image: block.image,
                            assign: image => { block.image = image; }, remove: () => { block.image = null; },
                            fields: [
                                field(block, {key: key('caption'), label: '설명', max: LIMITS.caption,
                                    value: block.caption, set: value => { block.caption = value; }}, errorId)
                            ],
                            alt: {key: key('alt'), summary: true, value: block.alt, set: value => { block.alt = value; }}
                        }, errorId)
                    ];
                case 'IMAGE_TEXT':
                    return [
                        renderChoices(block, {name: 'position', legend: '배치', property: 'imagePosition',
                            choices: [['LEFT', '이미지 왼쪽'], ['RIGHT', '이미지 오른쪽']],
                            help: '휴대폰에서는 언제나 이미지가 위에 옵니다.'}),
                        el('div', {className: 'admin-structured-split'}, [
                            el('div', {}, [
                                // 제목·본문은 글 칸에 있으므로 사진 카드의 기본 정보는 접근성 설명(alt)뿐이다.
                                imageCard(block, {
                                    name: '이미지', imageKey: key('image'), image: block.image, stacked: true,
                                    assign: image => { block.image = image; }, remove: () => { block.image = null; },
                                    alt: {key: key('alt'), value: block.alt, set: value => { block.alt = value; }}
                                }, errorId)
                            ]),
                            el('div', {}, [
                                field(block, {key: key('title'), label: '제목', max: LIMITS.title, summary: true,
                                    value: block.title, set: value => { block.title = value; }}, errorId),
                                field(block, {key: key('text'), label: '본문', max: LIMITS.imageText, multiline: true,
                                    rows: 6, required: true, summary: true, help: '문단을 나누려면 빈 줄을 사용하세요.',
                                    value: block.text, set: value => { block.text = value; }}, errorId)
                            ])
                        ])
                    ];
                case 'IMAGE_SLIDER':
                    return renderSlider(block, errorId);
                case 'IMAGE_GRID':
                    // 장수가 정해져 있어 추가·삭제·이동 없이 칸마다 이미지를 올리거나 바꾼다.
                    return [
                        el('ol', {className: `admin-structured-items admin-structured-grid is-${block.columns}`,
                            'aria-label': `${blockLabel(block)} 이미지`},
                        block.items.map((item, index) => renderImageItem(block, item, index, errorId, {fixed: true}))),
                        el('span', {className: 'admin-help-text',
                            text: '공개 화면에서는 사진을 같은 비율로 맞춰 자르고, 휴대폰에서는 한 줄에 한 장씩 보여 줍니다.'})
                    ];
                default:
                    return [field(block, {key: key('text'), label: '강조 문구', max: LIMITS.callout, multiline: true,
                        rows: 2, required: true, summary: true,
                        value: block.text, set: value => { block.text = value; }}, errorId)];
            }
        }

        /** 정해진 프리셋 중 하나를 고르는 라디오 묶음. (이미지 위치, 본문 폭) 숫자를 직접 넣는 칸은 두지 않는다. */
        function renderChoices(block, options) {
            const name = `${domPrefix}-sb-${block.id}-${options.name}`;
            const choice = ([value, label]) => el('label', {className: 'admin-content-format-option'}, [
                (() => {
                    const radio = el('input', {type: 'radio', name, value,
                        'data-focus-key': `${block.id}:${options.name}-${value}`,
                        onchange: () => {
                            block[options.property] = value;
                            markDirty();
                        }});
                    radio.checked = block[options.property] === value;
                    return radio;
                })(),
                el('span', {text: label})
            ]);
            return el('fieldset', {className: 'admin-structured-choices'}, [
                el('legend', {text: options.legend}),
                ...options.choices.map(choice),
                el('span', {className: 'admin-help-text', text: options.help})
            ]);
        }

        function renderSlider(block, errorId) {
            const addKey = `${block.id}:add`;
            const room = LIMITS.sliderItems - block.items.length;
            const addInputId = `${domPrefix}-sb-${block.id}-add-files`;
            const addInvalid = invalidAttributes(addKey, errorId);
            const addError = state.imageErrors.get(addKey);
            return [
                field(block, {key: `${block.id}:title`, label: '슬라이더 제목', max: LIMITS.title, summary: true,
                    value: block.title, set: value => { block.title = value; }}, errorId),
                commonCreditControl(block, errorId),
                block.items.length
                    ? el('ol', {className: 'admin-structured-items', 'aria-label': '슬라이더 이미지'},
                        block.items.map((item, index) => renderImageItem(block, item, index, errorId)))
                    : el('p', {className: 'admin-structured-empty', text: '아직 이미지가 없습니다.'}),
                el('div', {className: 'admin-structured-image-actions'}, [
                    el('label', {for: addInputId, className: `admin-btn is-small${room <= 0 ? ' is-disabled' : ''}`}, [
                        '+ 이미지 추가',
                        el('input', {id: addInputId, type: 'file', accept: IMAGE_ACCEPT, multiple: true,
                            className: 'admin-visually-hidden', disabled: room <= 0, 'data-focus-key': addKey,
                            'aria-invalid': addInvalid['aria-invalid'], 'aria-describedby': `${addInputId}-help ${addInvalid['aria-describedby'] || ''}`.trim(),
                            onchange: event => addSliderImages(block, Array.from(event.target.files || []))})
                    ]),
                    el('span', {id: `${addInputId}-help`, className: `admin-help-text${addError ? ' is-error' : ''}`,
                        'aria-live': 'polite',
                        text: addError || `여러 장을 한 번에 고를 수 있습니다. ${block.items.length} / ${LIMITS.sliderItems}장`})
                ])
            ];
        }

        /**
         * 슬라이더·이미지 배치의 사진 한 장. fixed(이미지 배치)면 장수가 정해져 있어 이동·삭제 버튼을 두지 않는다.
         */
        function renderImageItem(block, item, index, errorId, options = {}) {
            const key = name => `${block.id}:${item.id}:${name}`;
            const position = options.fixed ? `${index + 1}번째 칸` : `${index + 1}번째 이미지`;
            const itemAction = (name, symbol, ariaLabel, handler, options = {}) => el('button', {
                type: 'button', className: `admin-btn is-small admin-structured-action${options.danger ? ' is-danger' : ''}`,
                'aria-label': ariaLabel, title: ariaLabel, 'data-focus-key': key(name), disabled: options.disabled,
                text: symbol, onclick: handler
            });
            return el('li', {className: 'admin-structured-item', 'aria-label': position}, [
                el('div', {className: 'admin-structured-item-header'}, [
                    el('span', {className: 'admin-structured-block-type', text: position}),
                    options.fixed ? null : el('div', {className: 'admin-structured-block-actions', role: 'group', 'aria-label': `${position} 관리`}, [
                        itemAction('up', '↑', `${position} 위로 이동`, () => moveItem(block, item, -1), {disabled: index === 0}),
                        itemAction('down', '↓', `${position} 아래로 이동`, () => moveItem(block, item, 1),
                            {disabled: index === block.items.length - 1}),
                        itemAction('delete', '삭제', `${position} 삭제`, () => deleteItem(block, item), {danger: true})
                    ])
                ]),
                // 이미지 배치 칸은 좁으므로 카드 안을 한 칸으로 쌓는다.
                imageCard(block, {
                    name: position, imageKey: key('image'), image: item.image, stacked: options.fixed,
                    assign: image => { item.image = image; },
                    fields: [
                        field(block, {key: key('title'), label: '제목', max: LIMITS.itemTitle,
                            value: item.title, set: value => { item.title = value; }}, errorId),
                        field(block, {key: key('caption'), label: '설명', max: LIMITS.caption,
                            value: item.caption, set: value => { item.caption = value; }}, errorId)
                    ],
                    alt: {key: key('alt'), value: item.alt, set: value => { item.alt = value; }}
                }, errorId)
            ]);
        }

        // ---- 동작 ----

        function addBlock(option) {
            if (state.blocks.length >= LIMITS.blocks) return;
            const block = emptyBlock(option, allBlockIds());
            state.blocks.push(block);
            state.menuOpen = false;
            markDirty();
            const firstField = {
                SECTION_TITLE: 'title', RICH_TEXT: 'text', CALLOUT: 'text', IMAGE_SLIDER: 'title',
                IMAGE_GRID: block.items?.[0] ? `${block.items[0].id}:image:file` : null
            }[block.type];
            render(firstField ? `${block.id}:${firstField}` : `${block.id}:image:file`);
        }

        function moveBlock(block, offset) {
            const from = state.blocks.indexOf(block);
            const to = from + offset;
            if (from < 0 || to < 0 || to >= state.blocks.length) return;
            state.blocks.splice(from, 1);
            state.blocks.splice(to, 0, block);
            markDirty();
            // 끝에 닿아 같은 버튼이 막히면 반대쪽 버튼에 머문다.
            const edge = (offset < 0 && to === 0) || (offset > 0 && to === state.blocks.length - 1);
            render(`${block.id}:${edge ? (offset < 0 ? 'down' : 'up') : (offset < 0 ? 'up' : 'down')}`);
        }

        /** 복제는 새 블록 id, 슬라이더·이미지 배치라면 이미지마다 새 id. 이미지 파일은 같은 것을 함께 쓴다. */
        function duplicateBlock(block) {
            if (state.blocks.length >= LIMITS.blocks) return;
            const copy = JSON.parse(JSON.stringify(block));
            copy.id = newId('b', allBlockIds());
            if (copy.type === 'IMAGE_SLIDER' || copy.type === 'IMAGE_GRID') {
                const itemIds = new Set();
                copy.items.forEach(item => { item.id = newId('i', itemIds); });
            }
            state.blocks.splice(state.blocks.indexOf(block) + 1, 0, copy);
            markDirty();
            render(`${copy.id}:copy`);
        }

        function toggleBlock(block) {
            if (state.collapsed.has(block.id)) state.collapsed.delete(block.id);
            else state.collapsed.add(block.id);
            render(`${block.id}:toggle`);
        }

        function deleteBlock(block) {
            if (blockHasContent(block)
                && !window.confirm(`${blockLabel(block)} 블록을 삭제할까요? 입력한 내용이 사라집니다.`)) {
                return;
            }
            const index = state.blocks.indexOf(block);
            state.blocks.splice(index, 1);
            state.collapsed.delete(block.id);
            markDirty();
            const next = state.blocks[Math.min(index, state.blocks.length - 1)];
            render(next ? `${next.id}:delete` : 'add-toggle');
        }

        /**
         * 공통 출처를 이 슬라이더(block)의 사진에만 채운다. 전체 적용이 다른 개별 출처를 덮어쓰게 되면 먼저 묻는다.
         * 결과는 버튼 옆 문구로 알리고, 채운 사진의 출처 정보는 각 사진 카드에서 다시 고칠 수 있다.
         */
        function applyCommonCreditTo(block, onlyEmpty, focusKey) {
            const credit = state.commonCredits.get(block.id);
            const problem = commonCreditProblem(credit);
            if (problem) {
                state.commonCreditStatus.set(block.id, {text: problem, error: true});
                render(focusKey);
                return;
            }
            const overwrites = onlyEmpty ? 0 : commonCreditOverwrites(block.items, credit);
            if (overwrites > 0 && !window.confirm(
                `기존에 입력된 개별 출처 정보(이미지 ${overwrites}장)도 덮어씁니다. 적용할까요?`)) {
                return;
            }
            const applied = applyCommonCredit(block.items, credit, onlyEmpty);
            if (applied > 0) markDirty();
            state.commonCreditStatus.set(block.id, {
                text: applied > 0 ? `이미지 ${applied}장에 공통 출처를 적용했습니다.`
                    : onlyEmpty ? '출처가 비어 있는 이미지가 없습니다.' : '출처를 채울 이미지가 없습니다.',
                error: false
            });
            render(focusKey);
        }

        function moveItem(block, item, offset) {
            const from = block.items.indexOf(item);
            const to = from + offset;
            if (from < 0 || to < 0 || to >= block.items.length) return;
            block.items.splice(from, 1);
            block.items.splice(to, 0, item);
            markDirty();
            const edge = (offset < 0 && to === 0) || (offset > 0 && to === block.items.length - 1);
            render(`${block.id}:${item.id}:${edge ? (offset < 0 ? 'down' : 'up') : (offset < 0 ? 'up' : 'down')}`);
        }

        function deleteItem(block, item) {
            const hasText = !blank(item.title) || !blank(item.caption) || !blank(item.alt);
            if ((item.image || hasText) && !window.confirm('이 이미지를 슬라이더에서 뺄까요? 입력한 설명도 사라집니다.')) {
                return;
            }
            const index = block.items.indexOf(item);
            block.items.splice(index, 1);
            markDirty();
            const next = block.items[Math.min(index, block.items.length - 1)];
            render(next ? `${block.id}:${next.id}:delete` : `${block.id}:add`);
        }

        async function uploadInto(key, file, assign) {
            state.uploads.add(key);
            state.imageErrors.delete(key);
            render();
            try {
                assign(await uploadImage(file, imageUploadUrl, imageUrlPrefix));
                markDirty();
            } catch (error) {
                state.imageErrors.set(key, error.message);
            } finally {
                state.uploads.delete(key);
                render();
            }
        }

        /** 고른 여러 장을 차례로 올린다. 남은 자리보다 많으면 앞에서부터 자리만큼만 넣는다. */
        async function addSliderImages(block, files) {
            const addKey = `${block.id}:add`;
            const room = LIMITS.sliderItems - block.items.length;
            state.imageErrors.delete(addKey);
            if (files.length === 0 || room <= 0) return;
            const accepted = files.slice(0, room);
            if (files.length > room) {
                state.imageErrors.set(addKey,
                    `이미지는 ${LIMITS.sliderItems}장까지 넣을 수 있어 ${files.length - room}장은 추가하지 않았습니다.`);
            }
            const itemIds = new Set(block.items.map(item => item.id));
            const items = accepted.map(() => ({id: newId('i', itemIds), image: null, alt: '', title: '', caption: ''}));
            block.items.push(...items);
            markDirty();
            items.forEach(item => state.uploads.add(`${block.id}:${item.id}:image`));
            render();
            for (let index = 0; index < items.length; index++) {
                const key = `${block.id}:${items[index].id}:image`;
                try {
                    items[index].image = await uploadImage(accepted[index], imageUploadUrl, imageUrlPrefix);
                } catch (error) {
                    state.imageErrors.set(key, error.message);
                } finally {
                    state.uploads.delete(key);
                    render();
                }
            }
        }

        // ---- 저장 ----

        form.addEventListener('submit', event => {
            // 읽지 못한 저장 값은 손대기 전까지 그대로 다시 보낸다. (서버가 같은 기준으로 판단한다)
            const keepRaw = state.loadError && !state.dirty;
            if (!state.active) {
                // 일반 에디터로 저장해도, 저장이 거부되어 돌아오면 이 초안을 되살릴 수 있게 값은 남긴다.
                if (!keepRaw) input.value = serialize();
                return;
            }
            const result = validate(state.blocks, state.uploads.size);
            if (!result.valid) {
                event.preventDefault();
                state.errors = result;
                // 고칠 곳이 접힌 블록·접힌 출처 정보 안에 있으면 펼친다.
                result.byBlock.forEach((messages, blockId) => state.collapsed.delete(blockId));
                result.fields.forEach((message, key) => {
                    const at = key.indexOf(':credit-');
                    if (at >= 0) state.openCredits.add(key.slice(0, at));
                });
                render('error-summary');
                return;
            }
            state.errors = null;
            if (!keepRaw) input.value = serialize();
        });

        render();

        return {
            hasContent: () => state.blocks.length > 0,
            setActive(active) {
                state.active = active;
                root.hidden = !active;
            },
            isActive: () => state.active,
            serialize,
            blocks: () => state.blocks
        };
    }

    /** DOM 요소 또는 selector를 받는다. 각 root의 model·편집 이력·업로드 설정은 독립적이다. */
    function init(options = {}) {
        const root = typeof options.root === 'string' ? document.querySelector(options.root) : options.root;
        if (!root) return null;
        if (root.structuredEditor) return root.structuredEditor;
        const form = root.closest('form');
        const inputOption = options.input || root.dataset.structuredInput;
        const input = typeof inputOption === 'string' ? form?.querySelector(inputOption) : inputOption;
        if (!input || !form || (input.form && input.form !== form)) return null;
        const imageUploadUrl = options.imageUploadUrl || root.dataset.imageUploadUrl || UPLOAD_URL;
        const imageUrlPrefix = options.imageUrlPrefix || root.dataset.imageUrlPrefix || IMAGE_URL_PREFIX;
        if (!/^\/uploads\/(?:[a-z0-9][a-z0-9-]*\/){2,}$/.test(imageUrlPrefix)) {
            throw new Error('구조화 이미지 전용 namespace가 필요합니다.');
        }
        // endpoint는 같은 origin의 절대 경로로만 설정한다. 도메인별 endpoint는 그대로 유지한다.
        if (!/^\/(?!\/)[^?#\\\s]+$/.test(imageUploadUrl)) {
            throw new Error('이미지 업로드 endpoint가 올바르지 않습니다.');
        }
        if (!root.id) root.id = newId('structured-editor', new Set());
        // 일반 글 편집 칸에서 Enter 가 div 가 아니라 문단(p)을 만들게 한다.
        document.execCommand('defaultParagraphSeparator', false, 'p');
        root.structuredEditor = createEditor(root, input, form, {imageUploadUrl, imageUrlPrefix});
        return root.structuredEditor;
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-structured-editor]').forEach(root => init({root}));
    });

    // 테스트에서 상태 없는 도우미를 직접 확인할 수 있게 둔다.
    window.StructuredEditor = Object.freeze({init, LIMITS, readBlock, writeBlock, validate, newId,
        applyCommonCredit, commonCreditOverwrites, commonCreditProblem, normalizeLinkUrl});
    // 기존 여행정보 호출부와 테스트의 이름을 유지한다.
    window.TravelInfoStructuredEditor = window.StructuredEditor;
})();
