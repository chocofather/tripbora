# 기존 회원 사진 활용 동의 UI: 4단계

사진의 별도 선택 동의 UI는 이후 [업로드 이용조건 전환](review-photo-upload-terms.md)으로 제거됐다. 아래는 당시 구현·검증 기록이다. 과거 이력은 보존한다.

## 구현 계획과 경계

사용자가 V004/V005 DEV 적용을 확인했다. 해당 migration과 사진별 동의 SQL은 수정하지 않는다. DB 접속이나 SQL 실행, 서버 자동 기동, commit/push는 수행하지 않는다.

기존 GalleryConsentController의 GET/PUT, GalleryConsentService와 PolicyConsentService를 확장한다. 전용 동의 테이블·중복 API·별도 마이페이지를 만들지 않는다. 공통 인증 레이아웃의 선택 동의 모달과 기존 내 리뷰 사진 관리의 설정을 같은 상태 응답과 명시적 결정으로 연결한다. 일반 로그인과 기존 소셜 회원은 동일한 회원 principal과 레이아웃을 사용한다.

1. 공통 판정과 실제 동의 이력으로 최초 동의/실질 개정 안내 여부를 구분한다. 단순 표시 버전 변경과 경미 개정은 안내를 반복하지 않는다.
2. GET 상태에는 실제 정책 제목·전문·버전·현재 결정 상태를 제공한다. PUT은 AGREE/DECLINE/WITHDRAW/LATER를 구분하고 기존 Boolean false의 WITHDRAW 의미를 유지한다.
3. 서버 세션에 사용자·동의 기준별 LATER 억제를 보관한다. LATER는 이력이나 사진 상태를 변경하지 않는다. 정책 변경 409 이후 사용자가 새 내용을 읽고 직접 다시 선택한다.
4. 회원 잠금과 현재 버전 검증으로 중복 제출을 정리한다. 신규 AGREE/DECLINE은 사진 UPDATE를 수행하지 않고 명시적 WITHDRAW만 기존 철회 경로를 사용한다.
5. 흰색·보라색 기존 디자인을 사용하고 모바일/키보드/확대 환경을 검증한다. 마이페이지 변경은 현재 문서와 다른 탭의 안내에도 서버 재조회로 반영한다.

## 검증 계획

관련 단위·보안·MVC 테스트: 무이력/기존 거부/기존 철회/경미 개정/실질 개정/활성 정책 없음/전문 누락/버전 충돌/중복 요청/세션 defer/CSRF/본인 식별/사진 상태 보존. 기존 가입·동의·사진별 권한 회귀와 compileJava, bootJar, git diff --check를 실행한다.

브라우저는 MVC 렌더링 결과와 실제 CSS/JS, 응답 fixture를 사용해 360/390/393/430/768/1440 폭에서 주요 버튼·오류·409·포커스·가로 overflow를 확인한다. 실제 DEV DB 저장 및 외부 소셜 인증은 별도 수동 확인 대상이다.

## 구현 결과

- 기존 `/mypage/gallery-consent` GET/PUT을 확장했다. 기존 Boolean false는 WITHDRAW를 유지하고, 새 요청은 action·현재 policyVersionId·version을 검증한다. 신규 endpoint나 DB migration은 없다.
- `PolicyConsentEvaluation`은 변경하지 않고 기존 공통 판정과 최신 종류별 이력 조회를 함께 사용한다. 다른 기준의 과거 DECLINE을 최초 무이력으로 잘못 안내하지 않는다. 명시 이력이 없는 기존 false는 임의로 DECLINE/WITHDRAW로 재해석하지 않는다.
- 상태는 실제 정책 제목·전문·현재 표시 버전·기준 ID와 최근 결정 버전·종류·시각을 제공한다. 번역 fallback 시 실제 표시한 언어를 저장한다. prompt의 결정 출처는 RECONSENT, 설정은 MYPAGE다.
- 사용자 행 잠금과 READ_COMMITTED 경계 안에서 동일 기준·동일 최신 결정을 중복 기록하지 않는다. WITHDRAW 이후 AGREE는 새로운 결정으로 남기며 철회된 사진을 복구하지 않는다.
- LATER는 사용자·기준별 HttpSession 억제만 기록한다. 서버 세션의 수명 동안 같은 기준은 반복 안내하지 않으며 로그아웃 시 세션 무효화로 해제된다. 다른 기준의 실질 개정은 다시 판단한다.
- 공통 USER/ADMIN 레이아웃의 native dialog를 일반·소셜 로그인 완료 회원이 함께 사용한다. 기존 내 리뷰 사진 설정도 같은 JS 상태를 사용한다. 체크박스 사전 선택은 없다. 실제 전문은 textContent로 표시하며 텍스트와 상태의 불필요한 HTML 실행을 허용하지 않는다.
- DECLINE 이후에도 잔존하는 과거 사진 권한을 철회할 수 있게 WITHDRAW를 유지한다. 현재 정책 ID·버전·기준이 유효하다면 본문 조회 장애가 있어도 철회는 가능하다. AGREE/DECLINE/LATER는 전문이 없으면 거절한다.
- 다른 탭 변경은 BroadcastChannel/storage/focus로 서버를 재조회한다. localStorage에 동의 상태를 저장하지 않는다. 409는 최신 정책을 보여준 뒤 새 클릭을 요구한다. 인증 만료·HTML 로그인 redirect·네트워크 오류를 성공으로 표시하지 않는다.

## 검증 결과 및 제한

관련 Java 테스트 623개는 실패 0건, 건너뜀 1건(기존 Windows 심볼릭 링크 관련 갤러리 읽기 테스트)이다. 일반/소셜 가입 및 로그인, 사진 업로드·그룹·일괄 심사·철회·갤러리 회귀를 포함한다. JS 단위 테스트 15개와 Chrome 48개 사례가 통과했다. 여섯 화면 폭은 iframe의 실제 innerWidth로 검증하고 가로 overflow가 없음을 확인했다. Chrome iframe 검증은 병렬 배경 문서의 자동 포커스 차이를 피하기 위해 제목 포커스를 명시적으로 설정한다.

compileJava·bootJar·git diff --check 통과, MVC 실제 렌더링에서 전용 CSS/JS 내용 해시와 HTTP 200·Content-Type 및 비회원 미포함을 확인했다. 최종 JAR에 동일 파일이 포함되는지도 검사한다. 사진별 권한 SQL과 V004/V005는 변경하지 않는다. 새 migration은 없다.

실제 DEV에서는 일반 로그인/외부 소셜 로그인부터 최초 안내, 로그아웃 후 재로그인, 정책 개정 도중 제출, 여러 브라우저·탭의 동시 결정, 결정 이력의 출처·언어·시각과 사진 상태 불변을 확인해야 한다. DB 접속이나 SQL 실행, DEV 서버 기동은 자동으로 수행하지 않았다. 브라우저 API는 fixture이므로 실제 동의 저장이나 외부 소셜 인증 성공을 검증했다고 주장하지 않는다.
