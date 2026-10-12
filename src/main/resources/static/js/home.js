const homeI18n = document.getElementById('home-i18n').dataset;

/*
  각 탭은 테마 카테고리를 기본(한국어) 이름으로 고른다. (categories.name 은 UNIQUE, DB 번호는 쓰지 않는다)
  여행지 season 이 아니라 이 카테고리 연결로 고르므로 ALL_SEASONS 여행지도 탭에 나온다.
  관리자 화면에서 카테고리 이름을 바꾸면 여기도 같이 바꿔야 한다.
  카테고리 자리에 SEASON_POPULAR_TAB 을 두면 카테고리 대신 여행지 season 의 최근 7일 인기 순으로 고른다.
*/
const SEASON_POPULAR_TAB = null;
// 모든 탭은 데스크톱 한 줄 네 장만 받는다. (슬라이드·더보기 없음)
const SEASON_CARD_LIMIT = 4;

function localizedTags(labels, categoryNames) {
    return labels.split('|').map((label, index) => ({label, categoryName: categoryNames[index]}));
}

// 1. 시즌별 메타데이터 (각 태그: label, categoryName)
const seasonMeta = {
    SPRING: {
        badge: homeI18n.springBadge,
        title: homeI18n.springTitle,
        desc: homeI18n.springDescription,
        bgClass: 'spring',
        monthRange: [3,     4, 5],
        tags: localizedTags(homeI18n.springTags,
            ['국내 봄 여행지', '국내 벚꽃 명소', '피크닉 명소', '해외 봄 여행지', '일본 벚꽃 명소'])
    },
    SUMMER: {
        badge: homeI18n.summerBadge,
        title: homeI18n.summerTitle,
        desc: homeI18n.summerDescription,
        bgClass: 'summer',
        monthRange: [6, 7, 8],
        tags: localizedTags(homeI18n.summerTags, ['바다', '계곡', '해외 휴양지', '섬'])
    },
    FALL: {
        badge: homeI18n.fallBadge,
        title: homeI18n.fallTitle,
        desc: homeI18n.fallDescription,
        bgClass: 'fall',
        monthRange: [9, 10, 11],
        tags: localizedTags(homeI18n.fallTags,
            [SEASON_POPULAR_TAB, '단풍 명소', '가을 꽃 명소', '가을 풍경 여행'])
    },
    WINTER: {
        badge: homeI18n.winterBadge,
        title: homeI18n.winterTitle,
        desc: homeI18n.winterDescription,
        bgClass: 'winter',
        monthRange: [12, 1, 2],
        tags: localizedTags(homeI18n.winterTags, ['눈꽃 여행', '온천', '겨울 스포츠', '따듯한 여행지'])
    }
};

// 2. 현재 시즌 계산
const month = new Date().getMonth() + 1;
let currentSeason = 'SPRING';
for (const [key, value] of Object.entries(seasonMeta)) {
    if (value.monthRange.includes(month)) {
        currentSeason = key;
        break;
    }
}
// 개발환경(localhost)에서만 ?season= 으로 계절을 미리 본다. 그 밖에는 null 이라 위 월별 판별을 그대로 쓴다.
const previewSeason = (window.TripBoraSeasonPreview || window.TravelDiarySeasonPreview) // legacy fallback (P10a 제거)
    ?.previewSeasonKey(location.hostname, location.search);
if (previewSeason) currentSeason = previewSeason;
const meta = seasonMeta[currentSeason];

// 3. DOM 메타데이터 적용
const wrapper = document.querySelector('.seasonal-bg-wrapper');
if (wrapper) wrapper.className = `seasonal-bg-wrapper ${meta.bgClass}`;
const badgeElem = document.querySelector('.season-badge');
if (badgeElem) badgeElem.textContent = meta.badge;
const titleElem = document.querySelector('.season-title');
if (titleElem) titleElem.textContent = meta.title;
const descElem = document.querySelector('.season-sub');
if (descElem) descElem.textContent = meta.desc;

// 4. 태그 버튼 생성 및 이벤트 바인딩
const tagListDiv = document.querySelector('.season-tag-list');
function renderTags() {
    if (tagListDiv && meta.tags) {
        tagListDiv.innerHTML = "";
        meta.tags.forEach((tag, idx) => {
            const btn = document.createElement('button');
            btn.className = 'tag';
            btn.textContent = tag.label;
            btn.onclick = () => {
                tagListDiv.querySelectorAll('.tag').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                // 태그 클릭 시 추천 여행지 뿌리기 (카테고리 이름 기반)
                renderSeasonDestinations(currentSeason, tag.categoryName);
            };
            if (idx === 0) btn.classList.add('active');
            tagListDiv.appendChild(btn);
        });
    }
}
renderTags();

/*
  계절 추천 카드 폭 (home.css 의 .trip-card-list: 데스크톱 최대 네 장 폭, 좁은 화면 가로 스크롤).
  브라우저는 이 폭 × 화면 배율로 srcset 의 480w / 960w 중 알맞은 썸네일을 고른다.
*/
const SEASON_CARD_IMAGE_SIZES = '(max-width: 420px) calc((100vw - 44px) / 1.3), '
    + '(max-width: 680px) calc((100vw - 44px) / 1.6), (max-width: 960px) calc((100vw - 72px) / 2.6), 265px';

// 상위 지역이 오면 '충남 공주시' 처럼 앞에 붙인다. 상위 지역이 없는 응답은 지역명만 쓴다.
function regionText(dest) {
    return [dest.parentRegionName, dest.regionName]
        .filter(value => value && value.trim())
        .join(' ');
}

function createDestinationCard(dest, imageSizes = SEASON_CARD_IMAGE_SIZES) {
    const card = document.createElement('div');
    card.className = 'trip-card';
    // 여행지 업로드 사진은 서버가 카드 크기로 줄인 썸네일을 쓴다. 그 밖의 주소는 원래대로 둔다.
    // 세로 사진은 card-thumbnail.js 가 자르는 위치를 조금 위로 옮긴다. (card-thumb)
    const thumbnailAttributes = dest.cardImageSrcset
        ? `srcset="${dest.cardImageSrcset}" sizes="${imageSizes}"`
        : '';
    card.innerHTML = `
        <img class="card-thumb" src="${dest.cardImageUrl || dest.imageUrl}" ${thumbnailAttributes} alt="${dest.name}" />
        <div class="trip-info">
          <h4>${dest.name}</h4>
          <p>${regionText(dest)}</p>
        </div>
    `;
    const image = card.querySelector('img');
    // 공공누리 제3유형(변경금지)은 잘라 보이지 않게 원본 비율 전체를 담는다.
    if (dest.imageNoDerivatives) image.classList.add('is-no-derivatives');
    if (dest.cardImageUrl) {
        // 썸네일을 받지 못하면 원본으로 한 번만 되돌린다.
        image.addEventListener('error', () => {
            image.removeAttribute('srcset');
            image.src = dest.imageUrl;
        }, {once: true});
    }
    card.onclick = () => location.href = `/destinations/${dest.id}`;
    return card;
}

// 5. 추천 여행지 뿌리기 (API 연동)
async function renderSeasonDestinations(season, categoryName = SEASON_POPULAR_TAB) {
    // 카테고리 탭은 그 카테고리 후보 중 무작위, 인기 탭은 같은 season 여행지의 최근 7일 조회 합계 순이다.
    const url = categoryName
        ? `/api/season-destinations?season=${season}&categoryName=${encodeURIComponent(categoryName)}&limit=${SEASON_CARD_LIMIT}`
        : `/api/season-destinations/popular?season=${season}&limit=${SEASON_CARD_LIMIT}`;
    // API에서 [{id, name, imageUrl, regionName, season, ...}] 형태 반환
    const res = await fetch(url);
    // 응답이 실패하면 카드 없이 둔다. (카테고리가 아직 없으면 서버는 빈 배열을 준다)
    const data = res.ok ? await res.json() : [];
    const listDiv = document.querySelector('.trip-card-list');
    if (!listDiv) return;
    listDiv.innerHTML = '';
    data.forEach(dest => {
        listDiv.appendChild(createDestinationCard(dest, SEASON_CARD_IMAGE_SIZES));
    });
}

// 6. 페이지 진입 시 기본 여행지(시즌+첫번째 태그)
if (meta.tags.length > 0) {
    renderSeasonDestinations(currentSeason, meta.tags[0].categoryName);
}

// 인기 여행지는 home.html 이 서버에서 그린다. (필터 없는 편집형 영역이라 이 파일에서 다루지 않는다)
