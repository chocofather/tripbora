# TripBora 운영 구조

TripBora 운영 환경의 **현재 확정된 구조**를 기록한다.
배포, Docker, Cloudflare, 운영 DB, DBeaver 관련 작업을 하는 AI(Claude·Codex·ChatGPT)와 사람은 이 문서를 먼저 확인한다.
운영환경을 추정하지 않는다.

> 비밀번호·API Key·토큰·Cloudflare secret 같은 실제 값은 이 문서에 적지 않는다. 파일 위치와 변수 이름만 기록한다.

---

## 1. 운영 호스트

- 별도의 AWS·VPS·클라우드 VM에서 실행하는 구조가 **아니다.**
- 운영 애플리케이션과 운영 MySQL은 사용자의 **MacBook Air에서 Docker Desktop으로** 실행된다.
  - 운영 호스트: `gimminjun-ui-MacBookAir.local`
- 즉 MacBook Air가 실질적인 운영 서버다.
  - Docker Desktop을 완전히 종료하면 운영 app과 운영 MySQL 컨테이너도 멈춘다.

---

## 2. 운영 Docker

Compose project: `tripbora-prod` (저장소의 `compose.prod.yaml`)

| | 운영 App | 운영 MySQL |
|---|---|---|
| container_name | `tripbora-prod-app` | `tripbora-prod-mysql` |
| image | `tripbora-prod-app:local` | `mysql:9.2.0` |
| host binding | `127.0.0.1:8080` → container `8080` | `127.0.0.1:3307` → container `3306` |
| 기타 | Spring profile `prod` | database `tripbora_prod`, 앱 DB 사용자 `tripbora_app`, volume `tripbora_prod_mysql92_data` |

- 운영 MySQL은 외부 인터넷에 직접 공개하지 않는다. `127.0.0.1`에만 바인딩되어 있다.

---

## 3. Cloudflare

| 항목 | 값 |
|---|---|
| Tunnel name | `tripbora-prod` |
| Tunnel connector | `gimminjun-ui-MacBookAir.local` |
| Published application hostname | `tripbora.com` |
| Service | `http://127.0.0.1:8080` |

요청 흐름:

```
사용자
→ tripbora.com
→ Cloudflare
→ Cloudflare Tunnel (tripbora-prod)
→ MacBook Air
→ 127.0.0.1:8080
→ tripbora-prod-app
```

Cloudflare는 애플리케이션이나 MySQL을 호스팅하지 않는다. 외부 HTTPS·도메인 요청을 MacBook의 로컬 운영 앱으로 전달하는 통로다.

---

## 4. 운영 DB 연결 (DBeaver)

DBeaver는 운영 호스트와 같은 MacBook에서 실행하므로 **SSH Tunnel이 필요 없다.**

| 항목 | 값 |
|---|---|
| Host | `127.0.0.1` |
| Port | `3307` |
| Database | `tripbora_prod` |
| Username | `tripbora_app` |
| Password | `/Users/minjun/tripbora-config/docker-prod.env`의 `DB_PASSWORD` |

- Docker Desktop이 꺼져 있으면 3307 포트가 없으므로 `Connection refused`가 나는 것이 정상이다.

### DBeaver 권장 연결 이름

운영과 개발을 이름과 포트로 바로 구분할 수 있게 연결을 둘로 나눈다.

| 연결 이름 | Host | Port | Database |
|---|---|---|---|
| `TripBora - DEV` | `127.0.0.1` | `3308` | 개발 env 파일의 `DB_NAME` (`.env.docker.local`, 현재 `mydb`) |
| `TripBora - PRODUCTION` | `127.0.0.1` | `3307` | `tripbora_prod` |

- PRODUCTION 연결은 DBeaver의 연결 유형을 Production으로 지정해 색으로도 구분하는 것을 권장한다.

---

## 5. 운영 환경파일

- 운영 app env file: `/Users/minjun/tripbora-config/docker-prod.env`
- 이 파일은 저장소 밖에 있고, 실제 값은 이 문서나 Git에 기록하지 않는다.

Compose에서 쓰는 주요 변수(이름만):

- `DB_USERNAME`
- `DB_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `UPLOAD_PATH`
- `DIARY_PRIVATE_PATH`
- `COVER_LIBRARY_PRIVATE_PATH`

`UPLOAD_PATH`, `DIARY_PRIVATE_PATH`, `COVER_LIBRARY_PRIVATE_PATH`는 호스트 경로다. 컨테이너 안의 `/data/uploads`, `/data/diary-private`, `/data/cover-library-private`에 bind mount된다(`compose.prod.yaml`).

선택 변수:

- `FLYWAY_BASELINE_ON_MIGRATE`: 기존 운영 DB를 Flyway에 처음 편입하는 배포에서만 `true`로 둔다. 아래 5-1 참고. 없으면 `false`다.

---

## 5-1. DB migration (Flyway)

- DB 변경은 `src/main/resources/db/migration/V<버전>__<설명>.sql`로 Git에서 관리한다.
- 별도 Flyway 컨테이너나 CLI는 없다. 기존 배포 명령(`docker compose -f compose.prod.yaml --env-file /Users/minjun/tripbora-config/docker-prod.env up -d --build`)으로 app 컨테이너가 시작될 때 Spring Boot가 `tripbora_prod`에 붙어 아직 적용되지 않은 migration만 적용한다.
- migration 적용이나 검증이 실패하면 app이 뜨지 않는다(`docker logs tripbora-prod-app`으로 확인). 실패를 무시하고 뜨는 경로는 없다.
- 적용 이력은 DB의 `flyway_schema_history` 테이블에 남는다. 업무 테이블이 아니라 migration 관리용 메타데이터다.

### baseline (Flyway 도입 이전 schema)

- 도입 이전의 기존 schema 전체를 버전 `20261014.0`(`TripBora pre-Flyway baseline`)으로 등록한다.
- 이후 migration 버전은 이보다 커야 한다. 예: `V20261014_001__add_course_structure.sql` = 버전 `20261014.001`.
  - 이보다 작은 버전 파일은 적용되지 않으므로 날짜를 거꾸로 쓰지 않는다.

### 최초 편입 절차 (DEV·PROD 각각 한 번)

1. 편입 전 DB 백업을 먼저 받는다.
2. 해당 환경에서만 `FLYWAY_BASELINE_ON_MIGRATE=true`를 준다.
   - PROD: `/Users/minjun/tripbora-config/docker-prod.env`에 한 줄 추가
   - DEV(IntelliJ): 실행 설정 환경변수에 추가
3. app을 한 번 띄운다. 로그에 baseline 메시지(`Successfully baselined schema with version: 20261014.0`)가 나오고 `flyway_schema_history`가 생긴다.
4. **바로 `FLYWAY_BASELINE_ON_MIGRATE=false`로 되돌리거나 줄을 지운다.** 이후 배포는 이 값 없이 한다.

- `false` 상태에서 이력 테이블이 없는 기존 DB에 붙으면 Flyway는 baseline하지 않고 기동을 멈춘다(`Found non-empty schema(s) ... but no schema history table`). 의도한 안전장치다. 백업 복원 DB나 잘못 붙은 DB를 조용히 baseline 처리하지 않게 한다.
- 처음 편입하는 배포에서 이 값을 빠뜨리면 위 오류로 운영 app이 뜨지 않는다. 그때는 2~4를 하고 다시 배포한다.

---

## 6. 운영 / 개발 구분

운영 환경의 식별값:

| 항목 | 값 |
|---|---|
| 컨테이너 | `tripbora-prod-app`, `tripbora-prod-mysql` |
| DB | `tripbora_prod` |
| 포트 | 8080(app), 3307(mysql) |
| 도메인 | `tripbora.com`이 이 환경을 바라본다 |

저장소의 개발용 `compose.yaml`(project `tripbora-dev`)도 있다.

| 항목 | 값 |
|---|---|
| 컨테이너 | `tripbora-dev-app`, `tripbora-dev-mysql` |
| app 포트 | 8081 |
| DB 이름 | env 파일(`.env.docker.local`)의 `DB_NAME` |
| volume | `tripbora_dev_mysql92_data` |

MySQL 호스트 포트는 운영과 개발이 다르다. 둘 다 `127.0.0.1`에만 바인딩한다.

| 환경 | 호스트 → 컨테이너 | DB |
|---|---|---|
| DEV (`tripbora-dev-mysql`) | `127.0.0.1:3308` → MySQL `3306` | `DB_NAME` (현재 `mydb`) |
| PROD (`tripbora-prod-mysql`) | `127.0.0.1:3307` → MySQL `3306` | `tripbora_prod` |

- 호스트(DBeaver, IntelliJ에서 띄운 앱 등)에서 접속할 때만 3308/3307을 쓴다.
- Docker 네트워크 안의 앱 컨테이너는 두 환경 모두 `mysql:3306`으로 접속한다.
- 포트가 달라 두 MySQL을 동시에 띄울 수 있다. 그래도 DB 작업 전에는 접속한 DB가 의도한 쪽(`tripbora_prod`인지 아닌지)인지 확인한다.

규칙:

- 운영·개발을 설명하거나 작업할 때는 항상 **컨테이너명, DB명, 포트를 확인한 뒤** 답한다.
- `prod`라는 이름만 보고 별도 클라우드 서버가 있다고 추정하지 않는다.
- `.claude/skills/deploy`는 과거 launchd 방식이라 현재 구조와 맞지 않는다(CLAUDE.md 참고). 배포 절차의 근거로 쓰지 않는다.

---

## 7. 운영 상태 확인 명령

```bash
docker info                                   # Docker Desktop 동작 여부
docker ps --filter name=tripbora-prod         # 운영 컨테이너
docker ps --filter name=tripbora-prod-mysql   # 운영 MySQL
docker port tripbora-prod-mysql               # 예상: 3306/tcp -> 127.0.0.1:3307
docker port tripbora-prod-app                 # 예상: 8080/tcp -> 127.0.0.1:8080
docker port tripbora-dev-mysql                # (개발) 예상: 3306/tcp -> 127.0.0.1:3308
```

---

## 8. 운영 원칙

- Docker Desktop을 종료하면 TripBora 운영 서비스도 멈춘다.
- MacBook이 꺼지거나 절전 등으로 서비스가 멈추면 `tripbora.com`도 정상 서비스되지 않는다.
- Cloudflare Tunnel이 살아 있어도 원본 서비스 `127.0.0.1:8080`이 죽어 있으면 사이트는 동작하지 않는다.
- 운영 DB를 다루기 전 반드시 `tripbora_prod`인지 확인한다. 운영 DB와 개발 DB를 혼동하지 않는다.
- 운영 DB에 SQL을 임의로 실행하지 않는다. 스키마 변경은 사용자가 검토한 Flyway migration 파일로만, 배포 때 app 기동 과정에서 적용된다(5-1).

---

## 9. 향후 서버 이전

- 지금은 MacBook Air가 운영 호스트다.
- AWS·VPS·클라우드 VM 등으로 이전하면 **이 문서를 먼저 갱신한다.**
- 갱신할 때는 아래 항목을 새 구조로 **교체**한다. 기존 Mac 운영 구조를 사실처럼 남겨 두지 않는다.
  - 실제 호스트
  - Docker 위치
  - Cloudflare Tunnel connector
  - DB 접근 방식
  - DBeaver 접근 방식

---

## 10. 배포 CSS/JS와 CDN 캐시

- `WebConfig`의 Spring `VersionResourceResolver`가 파일 내용으로 해시 경로를 만든다.
  예: `/css/travel-plan-list-<내용 해시>.css`. 날짜 query를 수동으로 갱신하지 않는다.
- `StaticResourceDialect`가 Thymeleaf의 일반 `link href` / `script src` 중 `/css/**`, `/js/**`도
  표준 링크 빌더와 `ResourceUrlEncodingFilter`를 거치게 한다. 공통 layout, 개별 페이지,
  독립 로그인 화면, fragment 모두 적용되며 기존 `th:href` / `th:src`도 유지한다.
- CSS 내부 URL은 Spring 리소스 체인의 `CssLinkResourceTransformer`가 처리한다.
  외부 CDN과 버전이 명시된 WebJar, 사용자 업로드의 정책은 변경하지 않는다.
- HTML의 기존 Security `no-store` 정책과 정적 파일의 캐시를 유지한다.
  Cloudflare에서 HTML을 강제로 캐시하는 규칙을 추가하면 이 계약이 깨질 수 있다.
  정상 새 HTML을 받아야 새 해시 URL을 요청한다.

### 개발 시 필수 규칙

이 절은 Git 추적 대상이며, Git에서 제외되는 `AGENTS.md`의 정적 리소스 개발 규칙을
다른 개발 환경에도 전달하는 공통 기준이다. 규칙 변경 시 두 문서를 함께 갱신한다.

- 신규·수정 로컬 CSS/JS는 기존 `WebConfig`의 내용 해시 리소스 체인,
  `ResourceUrlEncodingFilter`, `StaticResourceDialect`를 사용한다. 공통 layout,
  페이지 전용 파일, 독립 페이지와 Thymeleaf fragment 모두 같은 기준을 적용한다.
- 신규 참조는 `th:href="@{/css/...}"`, `th:src="@{/js/...}"`를 우선한다.
  렌더링 결과에 해시 없는 고정 URL을 새로 도입하지 않으며, `?v=날짜` 등의 수동 버전,
  해시 하드코딩, 기능별 별도 버전 관리 구현을 추가하지 않는다.
- 파일 내용이 바뀌면 브라우저 요청 경로의 해시도 바뀌어야 한다.
  JavaScript에서 동적으로 불러오는 로컬 CSS/JS도 리소스 체인을 우회하지 않는다.
- CSS 내부 `@import`, `url(...)` 및 연결된 CSS·이미지·폰트를 확인한다.
  의존 파일만 변경한 경우에도 부모 CSS의 캐시 때문에 이전 파일을 계속 요청하지 않는지
  실제 브라우저 요청으로 검증한다. 부모 CSS 자체의 해시만 확인하고 끝내지 않는다.
- 새 페이지나 리소스 참조 추가 시 실제 Thymeleaf 렌더링 HTML과 브라우저 Network에서
  공통·전용·fragment CSS/JS의 해시 경로를 확인한다. 응답 HTTP 상태와 Content-Type이
  정상이고 404, MIME 오류, Console 오류가 없는지 확인한다. 템플릿 소스만 보고 판단하지 않는다.
  기존 `StaticResourceRenderingTest`, `StaticResourceDeploymentTest`와 관련 브라우저 테스트를
  변경 범위에 맞게 활용한다.
- 배포 관련 검증에서는 Dockerfile의 기존 `./gradlew clean bootJar --no-daemon` 빌드 흐름을
  기준으로 최신 CSS/JS와 템플릿이 JAR에 포함됐는지, 소스와 내용이 일치하는지 확인한다.
  Windows에서 정상이어도 Linux JAR에서는 파일명 대소문자가 일치해야 한다.
  로컬 `bootJar` 확인을 실제 운영 컨테이너 검증으로 보고하지 않는다.
- Cloudflare가 query string을 무시해도 새 내용을 구별하는 해시 경로 전략을 유지한다.
  전체 `Cache-Control`을 `no-cache`로 바꾸거나 HTML을 강제로 CDN 캐시하는 방식으로 우회하지 않는다.
  외부 CDN·버전 명시 WebJar·사용자 업로드의 기존 정책도 유지한다.
- 검증을 이유로 운영 배포나 DB 접근을 임의로 수행하지 않는다.
  문서만 수정하는 작업은 Gradle 빌드·테스트를 실행하지 않는다.

### 확인된 장애 증거

2026-10-20 조사에서 같은 `/css/travel-plan-list.css`가 서로 다른 Cloudflare edge에서
서로 다른 내용으로 반환됐다. 둘 다 HTTP 200 / `text/css` / `CF-Cache-Status: HIT`였다.

| edge | 크기 | SHA-256 | 주요 차이 |
|---|---:|---|---|
| NRT | 10,900 bytes | `02DDC04511DE22C8E41ACF960FFF7D3F27E1900616E9D4CA41ACB70DF5371A7D` | 로컬과 동일 |
| HKG | 9,217 bytes | `29EA2C8B21C392103E4898FDB7DBD82B736C23DE62F67AE78D36A81B5FCBAAB4` | `.tp-trip-icon`, `.tp-status-tabs` 규칙 없음 |

고정 URL의 응답은 `Cache-Control: public, max-age=14400`이었다. 운영 `/courses` HTML에서도
공통 CSS만 해시 경로이고 전용 CSS/JS는 고정 경로인 점을 확인했다.
Mac 원본 앱 응답과 운영 컨테이너 내부 JAR는 Windows에서 직접 확인하지 못했다.
`/travel-plans`는 인증이 필요해 공개 요청은 로그인으로 302 응답했다.
특정 코스에서 가져오기 HTML이 누락됐다는 현상의 운영 원인은 별도 인증/상세 URL 확인이 필요하다.

### 재배포 후 읽기 전용 확인

1. 브라우저에서 새 페이지를 열고 CSS/JS 요청 경로에 내용 해시가 있는지 확인한다.
   `/travel-plans`, `/travel-plans/{id}`, `/courses/{id}`의 전용 파일도 확인한다.
2. Mac에서 해당 해시 경로의 원본 응답을 `curl -I http://127.0.0.1:8080/<해시 경로>`로 확인한다.
   공개 응답 `https://tripbora.com/<해시 경로>`와 HTTP 상태, Content-Type, 파일 SHA-256을 비교한다.
3. 가져오기 버튼 HTML이 없다면 캐시만 원인으로 단정하지 않는다. 배포된 템플릿과
   `adminPreview` 조건을 확인한다. 공개 상세의 버튼/모달은 로그인 여부와 별도로 렌더링되며,
   실제 생성과 코스 템플릿 API는 기존 인증/CSRF 검사를 따른다.
4. 새 HTML의 해시 경로는 정상 파일을 반환해야 한다. 이전 배포의 파일이 바뀐 경우
   이전 해시를 원본에 직접 요청하면 Spring은 새 파일을 잘못 반환하지 않고 404로 거절한다.
