# 여행지 JSON 생성 작업 지침 (AI용)

이 문서는 Claude·Codex 같은 AI가 **여행지 이름 목록을 받아 TripBora JSON 일괄등록용 JSON을 만들 때** 따르는 작업 규칙이다.
사람용 사용설명서가 아니다. 관리자 화면 사용법은 다루지 않는다.

기준은 두 가지다.

- 현재 구현된 JSON 일괄등록 기능 (`/admin/destinations/json-import`, `service/destinationimport/`)
- 관리자 화면에서 내보낸 최신 `destination-import-master.json`

둘이 이 문서와 다르면 **master 파일과 구현이 우선**이다. 차이를 발견하면 작업 결과에 짧게 알린다.

기존 여행지 보강도 같은 JSON 계약을 사용한다. 업데이트 모드의 선택·null 병합·매칭·보호 정책은
[여행지 JSON 선택 업데이트와 필드 보호](destination-json-update-protection.md)를 참고한다.
이 문서의 여행지 JSON 자동 생성은 계속 신규 등록용 전체 계약과 품질 기준을 따른다.

---

## 1. 작업 목표

사용자가 여행지 이름만 준다.

```
경복궁
창덕궁
후쿠오카 타워
오사카성
```

AI는 다음을 모두 수행해 **바로 붙여넣거나 파일로 올릴 수 있는 JSON 하나**를 만든다.

1. 대상 여행지 조사 (어떤 실제 장소인지 식별)
2. 국내/해외 판단, type 판단
3. 실제 소재 지역 확인
4. 사실정보 적극 조사 (좌표·외부 ID, 그 type의 `info` 필드 전체, `amenities` 목록 전체)
5. 5개 언어 데이터 작성 (충분한 분량의 description, `info.translations` 4개 언어 포함)
6. `destination-import-master.json`과 대조
7. TripBora JSON 계약에 맞는 JSON 생성
8. 품질 점검 (문서 끝 체크리스트)

**이미지는 생성 대상이 아니다.** 이미지는 등록 후 운영자가 관리자 화면에서 직접 추가한다.

등록 시점에 시스템이 다시 검사하는 항목은 다음과 같다. AI의 JSON은 이 검사를 처음부터 통과하는 것을 목표로 한다.

- 형식 검사: 모르는 필드, 길이, 지역·카테고리·편의시설 이름, 외부 ID 형식
- 중복 판별
- 국내 TourAPI contentId 확인

### 핵심 원칙: 조사 우선, null은 조사 후에만

목표는 형식 검사를 통과하는 JSON이 아니라 **바로 등록해도 되는 품질의 데이터**다.
여행지 이름만 받아도 각 장소를 실제로 웹 조사해서 가능한 정보를 최대한 채운다.

1. **먼저 충분히 조사한다.** 공식 홈페이지의 이용안내·관람안내·시설안내·접근성 안내·FAQ·visitor information 페이지를 찾아 연다.
   공식 페이지가 부족하면 정부·관광청·공공기관 → (국내) TourAPI → 신뢰할 수 있는 공식·준공식 자료 순으로 넓힌다.
2. **조사로 확인한 값은 반드시 채운다.** description은 충분한 분량으로, `info`는 그 type의 모든 필드를, `amenities`는 master 목록 전체를 기준으로 조사한다.
3. **실제로 조사했는데도 신뢰할 근거가 없는 값만 `null` / `[]`로 둔다.**

`null`과 `[]`은 "조사했지만 확인 불가"의 결과다. 조사를 건너뛰기 위한 기본값으로 쓰는 것은 잘못된 생성이다.
"확인 못 하면 `null`" 규칙(8·11절)은 그대로 유효하며, 추측으로 값을 채우는 것은 여전히 금지다.

---

## 2. 작업 전에 읽을 파일

반드시:

1. `docs/destination-registration.md` (이 문서)
2. 최신 `destination-import-master.json`

필요할 때:

3. `docs/db/tripbora_schema_reference.md` (컬럼 길이·문자셋을 직접 확인해야 할 때)

master 파일 다루기:

- 사용자가 첨부했거나 경로를 알려준 파일을 쓴다. **없으면 작업을 시작하지 말고 사용자에게 요청한다.**
  - 관리자 → 여행지 관리 → JSON 일괄 등록 → 「마스터 데이터 내보내기」에서 받는다.
- `generatedAt`을 확인한다. 오래된 파일이거나 지역·카테고리가 바뀌었다고 들었다면 새로 내보내 달라고 요청한다.
- 지역명, 카테고리명, amenity code는 **기억이나 추측으로 쓰지 않는다.** master에 실제로 있는 문자열만 그대로 복사한다.
- **DB PK(숫자 ID)는 JSON 어디에도 쓰지 않는다.** master에도 ID는 없다.

### master 파일 구조

| 경로 | 내용 |
|---|---|
| `contract.types` / `contract.seasons` | 허용 type·season |
| `contract.languages` | `ko`, `en`, `ja`, `zh-CN`, `zh-TW` |
| `contract.infoTranslationLanguages` | `en`, `ja`, `zh-CN`, `zh-TW` (info 번역 언어. ko 없음) |
| `contract.limits`, `contract.rules` | 길이 제한, 외부 ID 형식, 국내·해외 규칙 |
| `contract.infoFields.<TYPE>[]` | `name`, `kind`, `maxLength`, `maxBytes`, `translatable`, `fact` |
| `regions.domestic[]` | 대한민국 → 시·도 → 시·군·구 (`name`, `nameEn`, `children`) |
| `regions.overseas[]` | 대륙 → 국가 → 도시·지역 |
| `categoriesByType.<TYPE>[]` | 그 type에서 쓸 수 있는 카테고리 이름 |
| `amenitiesByType.<TYPE>[]` | 그 type에서 쓸 수 있는 편의시설 `{code, name}` |

---

## 3. 작업 단위

- 권장: 한 번에 **10~20개**
- 최대: **30개**. 가져오기 기능의 상한이고, 파일 크기도 1MB 이하여야 한다.
- 요청이 30개를 넘으면 여러 JSON 파일로 나눈다. 예: `destinations-20261008-01.json`, `-02.json`

---

## 4. 판단 규칙

### type

허용값: `ATTRACTION`, `ACCOMMODATION`, `RESTAURANTS`, `CAFE`, `ACTIVITY`, `SHOP`

- 조사한 뒤 가장 적절한 type **하나**를 고른다.
- 두 type 사이에서 애매하면 임의로 확정하지 않는다. JSON에서 빼고 "type 확인 필요"(후보 type과 이유)로 보고한다.
- `CAFE`는 내부적으로 `restaurant_info` / `restaurant_amenities`를 `RESTAURANTS`와 함께 쓴다. 그래도 JSON에는 `"type": "CAFE"`로 정확히 쓴다.
  - 카테고리·편의시설·info 필드는 master의 `CAFE` 항목을 그대로 따른다.

### season

허용값: `SPRING`, `SUMMER`, `FALL`, `WINTER`, `ALL_SEASONS`

- 특정 계절에만 맞는다는 명확한 근거가 없으면 `ALL_SEASONS`를 먼저 검토한다.
- 계절성이 분명한 곳은 그 계절을 고른다. 예: 겨울 한정 스키장, 특정 계절에만 여는 시설.

---

## 5. 지역 (`region`)

`region`은 `{ "country", "city", "district" }`이다. 값은 master `regions` 트리의 `name`을 **글자 그대로** 쓴다.

- **국내**: `country`는 `대한민국`, `city`는 시·도, `district`는 시·군·구다.
  - 예: `{"country": "대한민국", "city": "서울", "district": "종로구"}`
- **해외**: 대륙은 넣지 않는다. 국가부터 쓴다.
  - 예: `{"country": "일본", "city": "후쿠오카", "district": null}`
- master에서 하위 지역(`children`)이 있는 단계는 **반드시 그 아래까지** 쓴다.
- `children`이 비어 있으면 그 단계에서 멈추고 나머지는 `null`이다. 하위가 없는데 값을 넣으면 오류다.
- 가져오기는 최대 3단계(country → city → district)까지만 받는다. master에서 district 아래에 지역이 더 있으면 JSON으로 등록할 수 없으니 보고한다.
- 비슷한 이름으로 맞춰 주지 않는다. "서울특별시"처럼 master에 없는 표기를 쓰지 말고, master에 있는 이름(`서울`)을 쓴다.

**master에 없는 지역은 만들지 않는다.** 실제 장소는 맞지만 그 지역이 master에 없으면 가까운 지역에 억지로 넣지 않는다. JSON에서 빼고 **"지역 마스터 추가 필요"**(국가·도시명)로 보고한다.

---

## 6. 카테고리와 편의시설

### categories / mainCategory

- `categories`는 **1개 이상 필수**다. master `categoriesByType.<TYPE>`에 있는 문자열만 쓴다.
- 없는 카테고리를 만들지 않는다. 같은 값을 두 번 넣지 않는다.
- 여행지 성격과 직접 관련된 것만 고른다. 태그처럼 많이 붙이지 않는다(보통 1~3개).
- `mainCategory`
  - 가장 핵심적인 카테고리가 분명하면 지정한다. 애매하면 `null`이고, 시스템이 기존 규칙으로 대표를 정한다.
  - 지정하면 반드시 `categories` 안에 있어야 한다.

### amenities

- master `amenitiesByType.<TYPE>[].code` 값만 쓴다. code 목록은 기억으로 쓰지 말고 매번 최신 master에서 읽는다.
- **조사 절차**
  1. 해당 type의 `amenitiesByType.<TYPE>` 목록 전체(`code`, `name`)를 먼저 확인한다. 이 목록이 조사 체크리스트다.
  2. 여행지마다 공식 홈페이지의 시설 안내, 이용 안내, 접근성·장애인 편의 안내, FAQ, visitor information, 관람 동선·지도 안내 등을 찾아 연다.
     공식 자료가 부족하면 정부·관광청·공공기관(국내는 TourAPI의 편의시설 정보 포함) 자료를 확인한다.
  3. 목록의 각 항목이 출처에서 확인되면 **반드시** `amenities`에 넣는다. 예: 공식 시설안내에 화장실이 있으면 해당 code를 넣는다.
  4. 확인에 쓴 출처는 `evidence`의 `fields`에 `amenities`로 남긴다.
- **추측은 금지한다.** "유명 관광지니까 화장실이 있겠지", "호텔이니까 와이파이가 있겠지"처럼 출처 없이 넣지 않는다.
- **`[]`을 기본값으로 쓰지 않는다.** 위 절차대로 실제로 조사했는데 master 목록 중 신뢰할 수 있게 확인된 항목이 하나도 없을 때만 `[]`이다.

---

## 7. 텍스트 (`translations`)

`translations`의 키는 `ko`, `en`, `ja`, `zh-CN`, `zh-TW`이고, 각 값은 `{ "name", "shortDescription", "description" }`이다.

- 다섯 언어를 모두 채우는 것이 기본이다.
- `ko.name`은 필수다.
- 다른 언어에 `shortDescription`이나 `description`을 넣었다면 그 언어의 `name`도 반드시 넣는다.
- 고유명사는 그 언어에서 실제로 널리 쓰이는 공식·통용 표기를 우선한다. 공식 사이트의 다국어 페이지, 관광청 표기를 참고한다.
- 한국어를 기준으로 쓰고, 다른 언어는 직역투가 아니라 여행 서비스에서 자연스럽게 읽히도록 옮긴다. 원문에 없는 사실은 덧붙이지 않는다.
- 이모지는 쓰지 않는다.

### shortDescription

- 목록·상단에서 장소의 특징을 바로 알 수 있는 **1문장**, **255자 이내**.
- "꼭 가봐야 할", "최고의", "인생 여행지" 같은 과장 표현을 쓰지 않는다. 장소의 핵심 특징을 구체적으로 쓴다.

### description

- 상세페이지의 실제 본문이다. **2~4문장 요약 수준은 금지한다.** 한도는 65,535바이트다.
- 분량 기준
  - 기본 **3~4문단**, 한국어 기준 **약 700~1,300자**를 목표로 한다.
  - 정보가 충분한 주요 관광지는 필요하면 더 길어도 된다.
  - 조사해도 정보가 적은 장소는 억지로 분량을 채우지 않는다. 이 경우에도 확인된 내용으로 가능한 한 문단을 구성한다.
- 문단 구성 (자연스럽게 녹여 쓰고, 소제목·목록 형식으로 쓰지 않는다)
  1. 어떤 장소인지, 건립·형성 배경, 대표적인 특징
  2. 실제 주요 볼거리, 건축·자연·전시·시설의 특징
  3. 역사·문화·자연적 의미, 그 장소만의 특징
  4. 방문자가 실제로 경험할 수 있는 것: 전망, 산책, 관람 동선, 주변 관광 연계 등
- 다른 언어(`en`, `ja`, `zh-CN`, `zh-TW`)도 한국어 본문과 같은 내용·문단 구성으로 쓴다. 번역에서 문단을 생략하거나 요약하지 않는다.
- 금지:
  - 분량을 늘리려고 사실을 만들어내기
  - 확인하지 않은 수치·연도·시설·운영시간·요금·휴무일을 사실처럼 쓰기
  - 홍보 문구, 과장 표현
  - 다른 여행지 설명을 복사해 이름만 바꾸기

---

## 8. 사실정보와 AI 작성정보

| 구분 | 항목 | 규칙 |
|---|---|---|
| AI가 작성 가능 | `shortDescription`, `description`, 번역, `guide`, `etc`, type·season 후보 | 자연어로 작성. 단 그 안에도 확인 안 된 사실(시간·요금·수치)은 넣지 않는다 |
| 반드시 외부 확인 | 좌표, 운영시간, 휴무일, 입장료, 전화번호, 홈페이지, 주차·반려동물·예약 등 boolean, 좌석 수·객실 수 등 숫자, 체크인·체크아웃, 주요 메뉴, 가격대, `tourApiContentId`, `wikidataQid`, `googlePlaceId`, 지역 | **먼저 적극 조사**해서 확인한 값은 채운다. 조사했는데도 **확인 못 하면 `null`** |

master `contract.infoFields`의 `fact: true` 필드는 모두 "반드시 외부 확인" 대상이다.
"외부 확인 대상"은 "비워 두는 대상"이 아니라 "출처를 찾아 채우는 대상"이다.

- boolean은 `false`도 사실값이다. "모르면 false"가 아니라 **모르면 `null`**이다.
- 좌표의 추가 조사·대표 지점 선택·`null` 허용 조건은 10절을 따른다. 일반적인 사실정보의 `null` 원칙만으로 좌표 조사를 끝내지 않는다.

---

## 9. 외부 ID (`external`)

`external`은 `{ "tourApiContentId", "wikidataQid", "googlePlaceId" }`이다. 모르면 각각 `null`이다.

| | `tourApiContentId` | `wikidataQid` | `googlePlaceId` |
|---|---|---|---|
| 국내 | 허용 | **금지 (오류)** | **금지 (오류)** |
| 해외 | **금지 (오류)** | 허용 | 허용 |

- **`tourApiContentId`**
  - 숫자 문자열이다. 예: `"126508"`
  - TourAPI에서 그 여행지임을 실제로 확인한 경우에만 넣는다.
  - TourAPI를 직접 조회할 수 없으면 `null`로 둔다. API 키를 문서나 JSON에 쓰지 않는다.
  - 미리보기가 TourAPI로 존재 여부를 확인한다. 없는 contentId는 오류, 이름이 다르면 경고다.
- **`wikidataQid`**
  - 형식은 `Q` 뒤에 숫자다. 예: `"Q123456"`
  - 실제 Wikidata 엔티티 페이지를 열어 같은 장소인지 확인한 경우에만 넣는다.
  - 등록할 때 시스템이 Wikidata를 다시 확인한다.
- **`googlePlaceId`**
  - 실제 조회로 확인한 경우에만 넣는다.
  - 시스템은 형식만 확인하므로 미리보기에 "외부 검증되지 않은 Place ID" 경고가 항상 뜬다. 정상이다.
- **ID를 형식에 맞춰 지어내는 것은 절대 금지다.**

---

## 10. 좌표

- `latitude` / `longitude`는 TripBora 상세페이지 지도 표시용 핵심 데이터이며, **원칙적으로 필수 품질 항목**이다. 둘 다 없으면 상세 지도가 표시되지 않는다.
- `latitude`, `longitude`는 따옴표 없는 숫자다. 예: `37.579617`
- 실제 출처에서 확인한 값만 쓴다. **대표 지점 선택과 좌표 추측은 다르다.** 방문에 적합한 지점을 선택하더라도 그 지점의 좌표 자체는 반드시 출처로 확인한다.
- 둘 중 하나만 넣지 않는다(오류). 아래 추가 조사 후에도 확정하지 못했을 때만 둘 다 `null`로 둔다.
- 도시 중심 좌표나 추정 좌표를 대신 넣지 않는다.
- 국내인데 대한민국 범위를 벗어나면 경고가 뜬다. 위도·경도가 뒤바뀌지 않았는지 확인한다.

### 적극 조사와 출처 대조

1. 여행지 생성 시 좌표를 반드시 적극적으로 조사한다. **한두 출처에서 찾지 못했다는 이유만으로 바로 `null` 처리하지 않는다.**
2. 공식 홈페이지 → 정부·공공기관 → 관광청·TourAPI → Wikidata → 신뢰 가능한 지도·POI 자료 순으로 추가 조사한다. 주소·지도·오시는 길 안내에서 실제 장소와 대표 방문 지점을 대조한다.
3. 출처별 좌표가 조금 다르면 시설 중심, 출입구, 방문센터 등 **각 좌표가 어떤 지점을 나타내는지** 확인하고 아래 대표 좌표 기준에 따라 선택한다. 작은 위치 차이만으로 `null` 처리하거나 좌표를 평균·보간해 만들지 않는다.
4. 실제 장소 자체가 다른 것으로 보일 정도의 충돌이면 임의로 선택하지 않는다. 장소·주소·지점의 동일성을 추가 조사하고, 끝까지 해소하지 못한 경우에만 아래 `null` 조건을 적용한다.

일반적인 실제 관광지는 좌표가 존재하는 것이 정상이다. 공식·공공·지도 자료에 실제 장소에 대응하는 신뢰 가능한 대표 좌표가 있는데 `null`로 두는 것은 잘못된 생성이다.

### 장소 유형별 대표 좌표

사용자가 지도를 보고 실제로 방문할 때 유용한 지점을 우선한다.

| 장소 유형 | 대표 좌표 기준 |
|---|---|
| 일반 건물·시설·관광지 | 해당 시설의 정확한 위치 |
| 사찰·박물관·미술관·전망대·랜드마크 | 실제 시설 위치 |
| 국립공원·도립공원·군립공원 | 넓은 산 전체의 중앙점보다 대표 방문 입구·탐방지원센터·대표 관광지 진입점 우선 |
| 산·산악 관광지 | 정상 좌표를 무조건 쓰지 않는다. 일반 방문자가 실제 진입하는 대표 탐방지점이 더 적절하면 그 지점 사용 |
| 해변·공원·넓은 자연관광지 | 대표 출입구·방문센터·공식 대표 지점 등 지도에서 찾아가기 적합한 지점 |
| 여러 진입점이 있는 장소 | JSON의 `region`과 `description`에서 대표 방문 지역으로 잡은 지점과 일치하는 좌표 |

대표 입구·탐방센터 좌표를 썼다면 가능하면 `info.guide` 또는 `meta.note`에 지점 이름과 "대표 진입 지점 기준"이라는 의미를 남긴다. 실제 시설이나 진입점의 좌표를 확인하는 것이며, 인근 도시·다른 관광지의 좌표로 대체하는 것이 아니다.

### `null` 허용과 보고

- **충분한 추가 조사 후에도 실제 장소에 대응하는 신뢰 가능한 대표 좌표를 확정하지 못한 경우에만 둘 다 `null`**로 둔다. 어떤 출처와 지점을 조사했고 왜 확정하지 못했는지 설명할 수 있어야 한다.
- 기존 import 계약의 `null` 허용은 유지한다. 좌표 미확인만으로 여행지를 무조건 제외하지 않으며 JSON에 포함할 수 있다. 등록 가능 여부와 지도 표시 품질은 구분한다.
- 좌표가 `null`이면 그냥 정상 생성으로 끝내지 않는다. **완료 보고에 `좌표 확인 필요: 여행지명`과 조사 후에도 확정하지 못한 이유를 반드시 적는다.** `meta.note`에도 같은 표기와 이유를 남겨 후속 확인이 가능하게 한다.
- 좌표를 채웠다면 `evidence.fields`에 `latitude`, `longitude`를 반드시 포함하고 실제 확인한 출처 URL을 남긴다.

---

## 11. 유형별 운영정보 (`info`)

`info`는 master `contract.infoFields.<TYPE>`에 있는 필드만 쓴다. 다른 type의 필드를 넣으면 오류다.

### 조사 절차

1. 최신 master의 `contract.infoFields.<TYPE>`를 먼저 읽고, **그 type에 정의된 필드 전체**를 조사 대상으로 삼는다.
   필드 이름을 이 문서나 기억에서 가져오지 말고 매번 master의 실제 필드 목록을 기준으로 한다.
2. 각 필드를 다음 순서로 적극 조사한다.
   1. 공식 홈페이지 (이용안내·관람안내·요금안내·오시는 길·FAQ·visitor information)
   2. 정부·관광청·공공기관
   3. 국내라면 TourAPI(한국관광공사)
   4. 신뢰할 수 있는 공식·준공식 자료
3. 운영시간·휴무일·요금처럼 바뀌기 쉬운 값은 최신 공식 출처를 우선한다. 계절·요일·대상별로 다르면 그 구분을 살려 쓴다.
4. 실제로 조사했는데도 근거가 없거나 최신성을 신뢰하기 어려운 필드만 `null`로 둔다.

잘못된 생성으로 보는 경우:

- 공식 홈페이지에 운영시간·휴무일·요금이 나와 있는데 조사하지 않고 `null`로 둔 경우
- 공식 홈페이지가 존재하는데 `homepageUrl`을 `null`로 둔 경우
- 공식 사이트·관광청에 대표 전화가 공개돼 있는데 `contactNumber`를 `null`로 둔 경우
- `fact: false` 필드(`guide`, `etc` 등)를 조사 결과를 바탕으로 쓸 수 있는데 비워 둔 경우

기타:

- 한국어 값은 `info` 바로 아래 필드에 쓴다.
- 확인하지 못한 필드는 `null`로 둔다(생략도 허용되지만 조사 여부를 드러내기 위해 `null`을 권장한다).

필드 규칙은 master의 각 필드 정의를 그대로 따른다.

| 속성 | 의미 |
|---|---|
| `kind` | `TEXT` 문자열 · `LONG_TEXT` 긴 문자열 · `URL` http(s) 주소 · `BOOLEAN` true/false · `INTEGER` 0 이상 정수 · `RATING` 0.0~5.0, 소수 첫째 자리까지 |
| `maxLength` | **글자 수** 상한 (한글·영문 모두 1자) |
| `maxBytes` | **UTF-8 바이트** 상한 (한글 1자 = 3바이트) |
| `translatable` | `true`면 `info.translations`에 번역을 넣을 수 있다 |
| `fact` | `true`면 실제 출처 확인이 필요하다. `false`(`guide`, `etc`)는 AI가 자연어로 쓸 수 있다 |

주의할 점:

- **RESTAURANTS·CAFE는 한도가 짧다.** 예: `mainMenu` 64자, `openingHours` 64자, `priceRange`·`breakTime`·`closedDays` 32자.
  - 길면 요약하되 의미를 바꾸지 않는다. 요약할 수 없으면 핵심만 남기고 나머지는 `etc`(255자)나 `null`로 처리한다.
- `info`의 한국어 원문 필드에는 이모지 등 4바이트 문자를 넣을 수 없다(원문 테이블이 utf8mb3, 오류).
- `homepageUrl`은 `http://` 또는 `https://`로 시작하고 255자 이하다.

### info.translations

- 언어 키는 `en`, `ja`, `zh-CN`, `zh-TW`만 쓴다. `ko`를 넣으면 오류다.
- **번역은 필수다.** 한국어 `info`에 값을 채운 `translatable: true` 필드는 `en`, `ja`, `zh-CN`, `zh-TW` **4개 언어 모두**에 번역을 넣는다.
  - 번역 가능 필드를 한국어에는 채웠는데 `info.translations`가 비어 있거나 일부 언어만 있는 상태로 JSON을 만들지 않는다.
  - 한국어 값이 `null`인 필드는 번역도 넣지 않는다.
- `translatable: false`인 필드는 번역하지 않는다. 예: `contactNumber`, `homepageUrl`, `BOOLEAN`, `INTEGER`, 기타 master에서 `translatable: false`인 필드.
- 한국어 원문의 정보를 축약·누락·변경하지 않는다. 계절·요일·대상별 구분, 예외 조건까지 모두 옮긴다.

```json
{
  "info": {
    "openingHours": "<공식 출처에서 확인한 운영시간 (한국어)>",
    "closedDays": "<공식 출처에서 확인한 휴무일 (한국어)>",
    "admissionFee": "<공식 출처에서 확인한 요금 (한국어)>",
    "homepageUrl": "<공식 홈페이지 URL>",
    "translations": {
      "en":    { "openingHours": "<영어 번역>", "closedDays": "<영어 번역>", "admissionFee": "<영어 번역>" },
      "ja":    { "openingHours": "<일본어 번역>", "closedDays": "<일본어 번역>", "admissionFee": "<일본어 번역>" },
      "zh-CN": { "openingHours": "<간체 번역>", "closedDays": "<간체 번역>", "admissionFee": "<간체 번역>" },
      "zh-TW": { "openingHours": "<번체 번역>", "closedDays": "<번체 번역>", "admissionFee": "<번체 번역>" }
    }
  }
}
```

번역 세부 규칙:

- 번역은 표현만 옮긴다. 시간·금액·요일 같은 값의 의미를 바꾸지 않는다. 예: `09:00~18:00` → `9:00 AM–6:00 PM`은 되지만 시간 자체를 바꾸면 안 된다.
- 번역 길이도 같은 `maxLength`/`maxBytes`를 지킨다.

---

## 12. 근거 (`evidence`)

`info`·`amenities`·좌표·외부 ID를 조사할 때 사용한 출처를 남긴다.

- 여행지마다 가능하면 **1~3개의 신뢰도 높은 출처**를 남긴다. 공식 방문 안내 페이지 하나로 여러 필드를 확인했다면 한 항목에 모은다.
- 사실값을 채워 놓고 evidence가 없는 상태를 최소화한다.
- 좌표를 채운 경우에는 예외 없이 해당 출처의 `fields`에 `latitude`, `longitude`를 모두 넣고, 실제로 확인한 URL을 남긴다. 대표 입구·탐방센터를 선택했다면 가능하면 `info.guide` 또는 `meta.note`에도 지점 이름과 대표 진입 지점 기준임을 적는다.
- `amenities`를 확인한 출처는 `fields`에 `amenities`로 적는다.

```json
{
  "evidence": [
    { "url": "<공식 방문 안내 페이지 URL>", "fields": ["openingHours", "closedDays", "admissionFee", "homepageUrl", "amenities"] },
    { "url": "<공공데이터·Wikidata 등 페이지 URL>", "fields": ["latitude", "longitude", "wikidataQid"] }
  ]
}
```

- `url`은 필수이고 http(s) 주소, 2,000자 이하다.
- `fields`에는 그 출처로 확인한 JSON 필드 이름을 쓴다. 예: `latitude`, `longitude`, `tourApiContentId`, `wikidataQid`, `googlePlaceId`, `info` 필드 이름.
- evidence는 **DB에 저장되지 않는다.** 미리보기에서 관리자가 검수할 때만 쓴다.
- 사실값이 있는데 evidence가 비어 있으면 경고가 뜬다.
- **실제로 연 페이지의 주소만 쓴다.** 존재하지 않는 출처를 만들지 않는다.

출처 우선순위:

1. 공식 홈페이지
2. 정부·관광청·공공기관
3. TourAPI(한국관광공사)
4. Wikidata / Wikipedia / Wikimedia Commons
5. 신뢰할 수 있는 기타 자료

블로그·리뷰 같은 사용자 작성 콘텐츠만으로 중요한 사실값을 확정하지 않는다.

### 출처끼리 값이 다를 때

- 임의로 하나를 고르지 않는다.
- 가장 공식적이고 최신인 출처를 우선한다.
- 그래도 판단하기 어려우면 그 필드는 `null`로 두고 "확인 필요"로 보고한다.
- 좌표의 작은 차이는 즉시 `null`로 처리하지 않고 10절의 대표 지점 선택 기준으로 판단한다. 실제 장소가 다를 정도의 충돌은 추가 조사 후에도 해소되지 않을 때만 `null`로 두고 "좌표 확인 필요"로 보고한다.

---

## 13. 이미지

JSON에 이미지 관련 값을 넣지 않는다. 계약에 필드가 없으므로 넣으면 오류다.

- 넣지 않는 것: 이미지 URL, KTO 이미지, Commons 이미지, 대표·슬라이드 이미지, 이미지 라이선스 정보
- 이미지는 등록 후 관리자가 직접 추가한다.

---

## 14. JSON 생성 규칙

항상 **하나의 JSON 객체**를 만든다. 아래는 최상위 구조만 보여 주려고 `destinations`를 빈 배열로 적었다.
실제 파일의 `destinations`에는 아래 「한 건 예시」 형태의 여행지 객체를 1~30개 넣는다(빈 배열은 오류).

```json
{
  "version": 1,
  "meta": { "generator": "Claude", "generatedAt": "2026-10-08T15:00:00+09:00", "note": "궁궐 2곳, 일본 2곳" },
  "destinations": []
}
```

- 최상위 필드는 `version`(항상 `1`), `meta`(선택), `destinations`(1~30개)뿐이다.
- `meta` 필드는 `generator`, `generatedAt`, `note`(모두 문자열)뿐이다.
- 여행지 한 건에 쓸 수 있는 필드:
  - `key`, `type`, `season`, `region`, `latitude`, `longitude`, `external`
  - `categories`, `mainCategory`, `amenities`, `translations`, `info`, `evidence`
- **계약에 없는 필드는 오류다.** 메모·주석·이미지 필드를 임의로 추가하지 않는다. 같은 필드를 두 번 쓰지 않는다.
- 모르는 값은 빈 문자열 `""` 대신 `null`을 쓴다(빈 문자열도 `null`로 처리된다).
- JSON 문법이 정확해야 한다. 주석·끝 쉼표·작은따옴표를 쓰지 않는다.
- 사용자가 "JSON 파일 만들어줘"라고 하면 실제 `.json` 파일로 만든다.
  - UTF-8로 저장한다.
  - 사용자가 위치를 정하지 않았으면 `src/` 밖에 만들고 경로를 알려준다.
  - git에 추가하지 않는다.
- JSON 내용을 바로 응답할 때는 JSON 블록 하나만 주고, JSON 안이나 바로 뒤에 설명문을 붙이지 않는다.

### key

`key`는 DB에 저장되지 않는 작업 행 식별자다. 미리보기·등록 결과에 표시된다.

- 영문·숫자·`.`·`_`·`-`만 쓰고 64자 이하로 한다. 소문자 slug 형태를 권장한다.
  - 예: `gyeongbokgung`, `fukuoka-tower`, `osaka-castle`
- **파일 안에서 중복되지 않게 한다.** 같은 key가 다시 나오면 뒤 행이 오류(INVALID)가 된다. key가 없는 행끼리는 비교하지 않는다.
- 시스템에서는 선택값이지만 이 지침에서는 **항상 쓴다.**

### 한 건 예시 (국내 ATTRACTION, 조사를 마친 형태)

이 예시는 **조사를 끝낸 여행지의 모양**을 보여 준다. 가능한 정보는 조사해서 채우고, `info.translations`와 `amenities`도 함께 채운다.

- `<...>` 부분은 자리 표시다. 실제 조사한 값으로 바꾸고, 그대로 쓰면 안 된다.
- 운영시간·요금 같은 실제 값은 이 문서에 고정하지 않는다. 매번 최신 공식 출처에서 확인한다.
- `info` 필드 목록과 amenity code는 이 예시가 아니라 최신 master의 `contract.infoFields.<TYPE>`, `amenitiesByType.<TYPE>`을 기준으로 한다.
- **예제의 `null`은 구조 설명용이며, 실제 자동 생성에서는 반드시 먼저 조사하고 확인되지 않은 경우에만 `null`을 사용한다.**
  - `latitude`, `longitude`, `tourApiContentId`, `parkingAvailable`은 숫자·ID·boolean 실제값을 문서에 고정하지 않으려고 `null`로 적었다.
    실제 생성에서는 조사해서 확인한 숫자(따옴표 없음)·ID 문자열·`true`/`false`를 넣고, 그 출처를 `evidence`에 남긴다(아래 evidence 두 번째 항목이 그 형태다).
  - `wikidataQid`, `googlePlaceId`의 `null`은 국내 여행지라서 넣으면 안 되는 값이다(9절).

```json
{
  "key": "gyeongbokgung",
  "type": "ATTRACTION",
  "season": "ALL_SEASONS",
  "region": { "country": "대한민국", "city": "서울", "district": "종로구" },
  "latitude": null,
  "longitude": null,
  "external": { "tourApiContentId": null, "wikidataQid": null, "googlePlaceId": null },
  "categories": ["<master ATTRACTION 카테고리명>"],
  "mainCategory": "<categories 중 하나>",
  "amenities": ["<공식 시설안내에서 확인한 master code>", "<확인한 master code>", "<확인한 master code>"],
  "translations": {
    "ko": {
      "name": "경복궁",
      "shortDescription": "<장소의 핵심 특징 1문장, 255자 이내>",
      "description": "<1문단: 어떤 장소인지·건립 배경·대표 특징>\n\n<2문단: 주요 볼거리·건축 특징>\n\n<3문단: 역사·문화적 의미>\n\n<4문단: 관람 동선·산책·주변 연계 등 방문 경험> (한국어 약 700~1,300자)"
    },
    "en":    { "name": "Gyeongbokgung Palace", "shortDescription": "<영어 1문장>", "description": "<한국어 본문과 같은 내용·문단 구성의 영어 본문>" },
    "ja":    { "name": "景福宮", "shortDescription": "<일본어 1문장>", "description": "<같은 구성의 일본어 본문>" },
    "zh-CN": { "name": "景福宫", "shortDescription": "<간체 1문장>", "description": "<같은 구성의 간체 본문>" },
    "zh-TW": { "name": "景福宮", "shortDescription": "<번체 1문장>", "description": "<같은 구성의 번체 본문>" }
  },
  "info": {
    "openingHours": "<공식 출처에서 확인한 운영시간 (계절·요일 구분 포함)>",
    "closedDays": "<공식 출처에서 확인한 휴무일>",
    "admissionFee": "<공식 출처에서 확인한 요금 (대상별 구분·무료 조건 포함)>",
    "parkingAvailable": null,
    "contactNumber": "<공식 출처에서 확인한 대표 전화>",
    "homepageUrl": "<공식 홈페이지 URL>",
    "guide": "<조사 결과를 바탕으로 쓴 관람 팁·이용 안내>",
    "translations": {
      "en":    { "openingHours": "<영어>", "closedDays": "<영어>", "admissionFee": "<영어>", "guide": "<영어>" },
      "ja":    { "openingHours": "<일본어>", "closedDays": "<일본어>", "admissionFee": "<일본어>", "guide": "<일본어>" },
      "zh-CN": { "openingHours": "<간체>", "closedDays": "<간체>", "admissionFee": "<간체>", "guide": "<간체>" },
      "zh-TW": { "openingHours": "<번체>", "closedDays": "<번체>", "admissionFee": "<번체>", "guide": "<번체>" }
    }
  },
  "evidence": [
    { "url": "<공식 관람안내 페이지 URL>", "fields": ["openingHours", "closedDays", "admissionFee", "contactNumber", "homepageUrl", "amenities"] },
    { "url": "<TourAPI·공공데이터 페이지 URL>", "fields": ["latitude", "longitude", "tourApiContentId"] }
  ]
}
```

해외는 `region`이 `{ "country": "일본", "city": "후쿠오카", "district": null }`처럼 바뀌고, `external`에는 확인한 `wikidataQid`나 `googlePlaceId`만 넣는다(`tourApiContentId`는 `null`).

---

## 15. 중복

- "이미 등록돼 있을 것 같다"는 판단으로 여행지를 빼지 않는다. **요청받은 여행지는 모두 JSON에 넣는다.** 중복은 시스템이 판별한다.
  - `DestinationDuplicateService`가 기존 DB와 파일 안 중복을 모두 본다.
- 같은 요청 목록에 같은 장소가 명백히 두 번 있으면 한 건만 만들고 사용자에게 알린다. 예: "경복궁"과 "Gyeongbokgung Palace".

---

## 16. 정보가 부족할 때

- 정보가 부족하다는 이유로 여행지 전체를 빼지 않는다. 1절의 순서대로 충분히 조사해서 확인한 범위까지 쓰고, 그래도 확인 못 한 사실값만 `null`로 둔다.
- 다만 등록에 꼭 필요한 값을 정할 수 없으면 억지로 JSON을 만들지 않는다. 그 여행지는 빼고 이유와 함께 보고한다.
  - 어떤 실제 장소인지 식별할 수 없다. 이름이 같은 장소가 여러 곳이면 후보를 제시한다.
  - 국가·지역을 판단할 수 없거나, 그 지역이 master에 없다("지역 마스터 추가 필요").
  - type을 판단할 수 없다(후보 type 제시).
  - master에서 쓸 카테고리를 하나도 정할 수 없다.
- 요청 수 = JSON에 넣은 수 + 제외하고 보고한 수가 되어야 한다.

---

## 17. 최종 응답

정상 생성이면 짧게 답한다.

> 총 12개 여행지 JSON을 생성했습니다.

그리고 JSON 파일 경로나 JSON 내용을 준다. 확인이 필요한 항목이 있으면 JSON과 따로 짧게 목록으로 알린다.

- 제외한 여행지와 이유: 지역 마스터 추가 필요, type 확인 필요, 장소 식별 불가 등
- 출처끼리 값이 달라 `null`로 둔 필드
- 좌표가 `null`인 여행지: `좌표 확인 필요: 여행지명`과 충분한 추가 조사 후에도 확정하지 못한 이유 (`meta.note`에도 기록)
- 요청 목록 안의 중복

---

## 18. 금지사항 요약

- DB ID 사용
- master에 없는 지역·카테고리·amenity code 생성
- contentId·QID·Place ID 추측 또는 형식 맞춰 생성
- 좌표 추측(도시 중심 좌표 대입 포함)
- 운영시간·휴무일·입장료·연락처 추측
- boolean·숫자 운영정보 추측(모르면 `false`가 아니라 `null`)
- 웹 조사 없이 `info` 필드를 `null`, `amenities`를 `[]`로 두기 (빠른 기본값으로 쓰기)
- 공식 홈페이지가 있는데 `homepageUrl`을 `null`로 두기
- 한국어 `info`의 번역 가능 필드를 채우고 `info.translations` 4개 언어를 비워 두기
- 2~4문장 요약 수준의 `description`
- 분량을 늘리려고 사실 만들어내기
- 이미지 관련 값 넣기
- 존재하지 않는 출처 만들기
- 등록을 통과시키려고 데이터를 임의로 바꾸기
- JSON 계약에 없는 필드 추가

---

## 작업 체크리스트

JSON을 내기 전에 확인한다. 스키마 검사 통과만으로 완료로 보지 않는다. **등록 가능한 품질인지**도 여행지마다 점검한다.

### 품질 점검 (여행지마다)

- [ ] `latitude` / `longitude`를 적극 조사했다. 한두 출처에서 찾지 못했다고 조사를 끝내지 않았다
- [ ] 일반 관광지를 별다른 이유 없이 `null`로 두지 않았다. 공식·공공·지도 자료에서 확인 가능한 대표 좌표를 반영했다
- [ ] 넓은 공원·산은 실제 대표 방문 지점을 기준으로 좌표를 선택했고, 여러 진입점이 있으면 `region`·`description`의 대표 방문 지역과 일치한다
- [ ] 좌표를 채운 경우 `evidence`에 `latitude` / `longitude` 출처 URL이 있다
- [ ] 좌표가 `null`이면 충분한 추가 조사 내용과 확정하지 못한 이유를 설명할 수 있고, `meta.note`와 완료 보고에 `좌표 확인 필요: 여행지명` 및 이유가 있다
- [ ] `description`이 지나치게 짧지 않다 (기본 3~4문단, 한국어 약 700~1,300자. 정보가 적은 장소만 예외). 다른 언어도 같은 구성이다
- [ ] master `contract.infoFields.<TYPE>`의 모든 필드를 실제로 조사했다
- [ ] 공식 홈페이지가 있는데 `homepageUrl`을 `null`로 두지 않았다. 공식 출처에 있는 운영시간·휴무일·요금·연락처를 빠뜨리지 않았다
- [ ] 한국어 `info`에 값이 있는 `translatable: true` 필드는 `en`·`ja`·`zh-CN`·`zh-TW` 4개 언어 번역이 모두 있다
- [ ] master `amenitiesByType.<TYPE>` 목록 전체를 확인하고 실제 시설을 조사했다. 조사 없이 `[]`로 두지 않았다
- [ ] 남아 있는 `null` / `[]`은 모두 "조사했지만 신뢰할 근거를 찾지 못한" 값이다
- [ ] 채운 사실값에는 근거가 있고, `evidence`에 1~3개 출처(실제로 연 URL)를 남겼다

### 형식 점검

- [ ] 최신 `destination-import-master.json`을 읽었다 (`generatedAt` 확인)
- [ ] 요청 수 = JSON 건수 + 제외 보고 건수, 파일당 30건 이하
- [ ] `type`·`season`이 허용값이다
- [ ] `region`이 master 트리에 있고, 하위 지역이 있는 단계까지 썼다 (해외는 대륙 없이 국가부터)
- [ ] `categories` 1개 이상, 모두 그 type의 master 목록에 있다
- [ ] `mainCategory`는 `null`이거나 `categories` 안에 있다
- [ ] `amenities`는 그 type의 master code이고, 실제로 확인된 것만 넣었다
- [ ] 국내는 `tourApiContentId`만, 해외는 `wikidataQid`·`googlePlaceId`만 넣었고 모두 실제 확인한 값이다
- [ ] 좌표는 둘 다 있거나 둘 다 `null`이다
- [ ] `info`에는 그 type 필드만 있고, `maxLength`·`maxBytes`를 지켰다 (RESTAURANTS·CAFE 특히)
- [ ] `fact: true` 값은 모두 출처에서 확인했고 `evidence`에 남겼다. 조사 후에도 모르면 `null`
- [ ] `info.translations`에는 `translatable: true` 필드만, `ko` 없이 넣었다
- [ ] 5개 언어 `name`이 있고 `ko.name`이 비어 있지 않다
- [ ] 이미지 필드와 계약에 없는 필드가 없다. `key`는 파일 안에서 중복되지 않는다
- [ ] JSON 문법이 올바르고, JSON 뒤에 설명문이 없다
