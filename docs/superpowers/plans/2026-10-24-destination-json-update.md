# 여행지 JSON 선택 업데이트 구현 계획

**목표:** 기존 JSON 신규 등록을 유지하면서, PK·KTO 연결·이미지·공개 상태·사용자 참조를 보존하는 관리자 업데이트 모드를 제공한다.

**구조:** 기존 Parser/Validator/Master Resolver/중복 판별을 재사용한다. 업데이트 전용 미리보기와 선택 필드 patch 저장을 추가한다. 미리보기의 원문·선택·대상·DB 스냅샷은 세션의 만료되는 토큰에 묶고, 저장 트랜잭션에서 잠금 후 스냅샷을 비교한다.

**기술:** Java 17, Spring MVC/Security, MyBatis XML, MySQL, Thymeleaf.

요구사항은 이번 사용자 요청과 `docs/destination-registration.md`의 현행 계약을 따른다. 신규 등록 계약은 변경하지 않고 업데이트에서만 누락·null을 유지로 해석한다. `key`는 매칭에 사용하지 않는다.

## 작업 단위

- [x] 전용 저장소: 기본정보·번역·유형별 info·info 번역의 선택 필드만 UPDATE. 기존 번역 PK 유지. 관계는 차집합으로 변경. 삭제·재등록·이미지 저장 API 호출 금지.
- [x] 매칭·병합·미리보기: KTO contentId 우선. 이름/지역 후보는 관리자 확정 필수. 원문 ko의 name/shortDescription/description 및 외국어 각 필드를 독립 처리. 비선택/null/빈 목록은 유지. 관계 제거는 별도 명시 승인.
- [x] 충돌·적용: 세션 토큰과 원문/선택을 결합. 미리보기 후 변경 감지 시 저장하지 않고 재확인. 한 여행지 단위 트랜잭션. 동일 JSON 재실행은 no-op.
- [x] 보호 기록: 신규 Flyway migration으로 필드 경로별 JSON 수동 보강 기록. KTO 기존 데이터 skip 유지. 향후 KTO patch는 보호 필드를 제외하는 guard 제공. 실제 migration 적용은 사용자 DEV 기동에 맡긴다.
- [x] 기존 페이지 확장: 신규/업데이트 모드, 공통/개별 선택, 언어·필드별 비교, 긴 본문 접기, 최종 확인. 기존 신규 등록 스크립트 유지. 로컬 CSS/JS는 내용 해시 체인을 사용.
- [x] 회귀 검증: 필드 독립성·null/미체크 보존·5개 언어/info·관계/좌표·매칭 불확실/충돌·rollback·KTO 보호·권한/CSRF·신규 등록. compileJava, 관련 테스트, diff check, 템플릿 HTTP/해시 및 가능한 브라우저 확인.

## 주요 판단과 검토 경계

- 기존 전체 수정 API는 빈 번역 삭제 및 amenities 전체 교체를 하므로 사용하지 않는다.
- 한국어 원문은 `destination_translations.language_code='ko'`에 저장된다. 별도 한국어 복제 필드를 만들지 않으며 UI에서 ko 원문과 외국어 선택을 명확히 구분한다.
- 현재 여행지 KTO 자동 갱신 경로는 없고 기존 contentId는 재등록에서 건너뛴다. 불필요한 동기화 기능을 새로 만들지 않는다.
- 기존 `destination_translation_sources`는 Wikipedia 저작권 정보다. 수동 보강 기록으로 전용하지 않는다.
- 관계 목록은 기본 추가 병합이다. 교체 모드를 선택한 경우에만 제거 차이를 보여주고 최종 제거 승인을 요구한다.
- DB/SQL 직접 실행, DEV 서버 임의 기동, commit/push 금지. schema reference를 적용 완료 상태로 갱신하지 않는다.

## 검증 기록

- `compileJava`, 관련 JUnit 112개(17개 테스트 클래스), `bootJar` 성공.
- Node 단위 테스트 6개 및 Chrome 브라우저 테스트 1개 성공. 실제 Thymeleaf/리소스 HTTP 응답을 내보낸 정적 fixture와 모의 업데이트 API로 360/390/430/768/1440px의 선택 흐름·overflow·스크립트 오류를 확인했다.
- JAR의 템플릿/CSS/신규 JS/Mapper 2개/migration이 현재 소스와 일치한다. 실제 PROD 컨테이너 확인은 하지 않았다.
- 독립 코드 리뷰에서 중요 결함 없음. 이미 접수되어 토큰을 소비한 적용 요청은 이후 같은 세션에서 새 미리보기를 해도 계속 진행할 수 있다. 적용 시작 이후의 요청 취소 기능은 제공하지 않으며 승인된 계획의 데이터 충돌 검사는 그대로 수행한다.
- DB에 접속하지 않았고 migration을 적용하지 않았다. 실제 DB 제약·동시 편집 동작은 사용자의 DEV 검증이 남아 있다.
