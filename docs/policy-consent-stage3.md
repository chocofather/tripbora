# 사진별 동의 근거: 3단계 계약

현재 신규 업로드 정책은 [공개 리뷰 사진 업로드 이용조건 전환](review-photo-upload-terms.md)을 따른다. 아래는 과거 선택 동의 사진의 근거와 당시 구현 기록이며 삭제하지 않는다.

사진 활용 판정은 회원의 현재 동의 여부만으로 결정하지 않는다. 신규 업로드는 실제 AGREE 이벤트의 `id`, 원래 정책 버전 문자열, 원래 동의 시각을 사진에 복사한다. 경미 개정 이후 과거 동의를 재사용해도 표시 버전이나 현재 시각으로 바꾸지 않는다. 이미 저장된 NONE/REJECTED/REVOKED 사진을 새 동의로 되살리지 않는다.

## 공통 판정과 상태 경계

`DestinationCommentImageMapper.xml`의 `canonicalPhotoConsent`를 Java 런타임의 `photoRights`, 관리자 workflow eligibility와 제한 사유, 공개 갤러리가 공통으로 사용한다. 회원 ACTIVE, 최상위·미삭제·미차단 리뷰, 공개 여행지는 `canonicalPhotoPublicConditions`로 함께 확인한다. 심사 후보는 PENDING/APPROVED이며 실제 공개 갤러리는 추가로 APPROVED만 허용한다. 갤러리 우선순위와 기존 정렬은 허가 필터 뒤에 적용하며 최대 30장 계약을 유지한다. 일반 원본 리뷰 사진 조회에는 이 필터를 적용하지 않는다.

현재 정책은 활성·게시·시행 조건과 기존 effective_at/id 순서로 하나를 고른 뒤 동의 기준을 검증한다. 기준이 NULL/self인 버전은 독립 범위다. 경미 개정은 같은 종류의 앞선 root 기준을 직접 참조해야 하며, 기준보다 이른 시행/게시 또는 중간의 실질 개정·NULL/다른 기준을 건너뛰면 거부한다. 중간 개정이 비활성이어도 검사한다. 현재 유효 기준과 사진의 원래 이벤트 기준이 같아야 한다. 실질 개정은 과거 사진을 새 기준에 편입시키지 않는다.

실제 사진 런타임의 정책·동의 판정 시각은 DB `CURRENT_TIMESTAMP(6)` 벽시각으로 통일한다. Java는 mapper로 같은 DB 벽시각을 조회해 사용하며 JVM local now를 사진 판정 시각으로 혼용하지 않는다. 공개 갤러리 mapper의 기존 `currentTime` 파라미터는 API 호환을 위해 유지하지만 SQL의 정책 시계로 사용하지 않는다. 명시적인 `evaluate(..., now)` API는 별도 기준 시각의 판정용으로 유지한다.

AGREE 또는 과거 `decision_action=NULL, agreed=true` 이벤트만 원본 허가가 된다. 원본 뒤에 같은 정책 종류의 WITHDRAW 또는 과거 `decision_action=NULL, agreed=false`가 하나라도 있으면 id 순서의 중단 경계로 거부한다. 이후 새 AGREE로 옛 이벤트를 복구하지 않는다. DECLINE은 명시 철회와 구별하며 기존 사진 상태를 변경하지 않는다.

## ID가 있는 신규 스냅샷

`gallery_consent_id`가 있으면 해당 이벤트의 ID, 사진 소유자, REVIEW_GALLERY 종류, 원래 버전 문자열을 모두 확인한다. ID가 틀리면 legacy 시각 경로로 우회하지 않는다. 이벤트 TIMESTAMP와 사진 생성 TIMESTAMP의 순서를 확인해 업로드 뒤의 새 동의로 사진을 소급 편입시키지 않는다. DATETIME 스냅샷과 TIMESTAMP 이벤트의 시각 동등성으로 이벤트를 찾지 않는다.

`V20261102_004__bind_review_photo_consent_evidence.sql`은 동의 스냅샷을 DATETIME(6)로 확장한다. 후속 `V20261102_005__preserve_review_photo_precision_and_consent_retention.sql`은 사진 생성 시각을 TIMESTAMP(6)로 확장하고 FK를 ON DELETE SET NULL로 변경한다. 같은 초의 정상 동의→업로드를 소수점 유실 때문에 거부하지 않도록 하기 위함이다. 기존 값과 nullable 특성은 유지하며 예전 행을 추측해서 역기입하지 않는다. 생성 시각이 NULL인 과거 행은 chronology 근거가 없어 갤러리 활용을 거부한다.

nullable FK는 `ON DELETE SET NULL`이다. 탈퇴 회원의 3년 동의 이력 보관기간 만료 시 기존 회원별 전체 이력 삭제를 막지 않는다. 이력 삭제는 사진 상태를 승인으로 바꾸지 않으며, 두 판정 경로 모두 살아 있는 해당 소유자의 동의 이벤트를 요구한다. 보관기간 정리는 DEACTIVATED 회원의 이력을 모두 삭제하므로 원본 근거가 남지 않고, 공개 조건의 ACTIVE 검사도 통과하지 못한다.

## ID가 없는 과거 스냅샷과 시각 한계

과거 사진은 저장된 원래 버전 문자열과 소유자를 먼저 대조하고, 해당 버전·소유자의 이벤트 중 저장된 시각을 corroborate하는 후보가 정확히 한 개일 때만 허용한다. 후보 개수는 동의 여부로 먼저 줄이지 않는다. 같은 초의 다중 AGREE/DECLINE 등으로 후보가 겹치면 거부한다. 이벤트 시각의 정확한 값, 마이크로초 제거 값, 500000 마이크로초 기준 초 반올림 값만 비교한다. 임의 ±1초 tolerance, 근거 없는 시각 보정 또는 최신 동의 시각 대입은 사용하지 않는다. 과거 사진 생성 시각의 초 정밀도는 원본 이벤트의 초 절삭 값으로 보수적으로 순서를 검사한다. 중단 경계 검사는 동일하게 적용한다.

TIMESTAMP는 SQL 세션 시간대에 따른 표현이고 DATETIME은 시간대 없는 벽시각이다. 현재 SQL은 두 값을 변환 없이 대조한다. 과거 JVM local/JDBC 변환/DB session 설정이 같았다고 코드만으로 확정하지 않는다. `connectionTimeZone=LOCAL` 등의 현재 설정만으로 과거 저장 시각과 SQL 표현이 같았다고 간주해서도 안 된다. 과거 값이 다른 시간대에서 저장됐다면 시각 불일치로 제외될 수 있으며, 같은 소유자·버전에 우연히 같은 벽시각의 다른 이벤트가 존재할 가능성까지 SQL만으로 식별할 수는 없다.

따라서 DEV에서 과거 스냅샷의 JVM/JDBC/DB session 시간대 이력과 원본 이벤트를 대조해야 한다. 이 provenance가 확인되지 않은 데이터에 대해 legacy 노출이 완전히 검증됐다고 주장하지 않는다. 불일치 시 일괄 시간대 이동이나 임의 ID 역기입으로 우회하지 않고 해당 사진을 격리해 원본 근거를 확인한다. 신규 ID 스냅샷은 시각 동등성에 의존하지 않으며 이벤트의 SQL 벽시각을 기준으로 원본 정책의 역사적 유효성을 확인한다. 정책 DATETIME 시행 시각의 운영 기준과 DB session 설정이 일관적인지도 배포 환경 검증 대상이다.

## 검증 경계

계약 테스트는 실제 MyBatis XML을 파싱하고 공통 fragment 확장·파라미터·이벤트/기준/중단/공개 조건·조회 제한 및 migration 구조를 검사한다. DB 접속이나 SQL 실행은 하지 않는다. Flyway의 실제 적용, TIMESTAMP/DATETIME 변환과 기존 데이터 분포, 실행계획 및 동시성은 사용자 DEV 검증 대상이며 schema reference는 적용 완료 상태로 갱신하지 않는다.

## 구현 및 실행 결과

- `PolicyConsentService`, `GalleryConsentService`: 현재 회원 동의와 실제 업로드 허가 이벤트를 연결하고, 저장된 사진의 심사는 공통 SQL 계약에 위임한다. 기존 Boolean 철회 API의 `false`는 WITHDRAW 의미를 유지한다.
- `ReviewImageService`, `ReviewDuplicateService`, `ReviewGalleryPriorityService`: 기존 소유자 잠금과 권한 검증을 유지하고, 잠금 대기 전 읽은 스냅샷으로 판단하지 않도록 관련 트랜잭션의 READ_COMMITTED 경계를 맞춘다.
- `DestinationCommentImageMapper.xml`, `ReviewPhotoAnalysisMapper.xml`: 업로드 근거 저장, 기본 대기 목록·그룹·일괄 심사 자격 및 공개 갤러리에서 같은 사진별 계약을 사용한다. 갤러리 자격 필터는 LIMIT 앞에 적용한다.
- 기존 관리자 목록의 승인 버튼·제한 사유도 서버 판정과 일치시켰다. 반려 경로는 승인 자격과 분리하며, 기존 오류 응답 처리에 갤러리 동의 API를 포함했다. 새 페이지나 재동의 안내 UI는 추가하지 않았다.

`compileJava`, `bootJar`와 관련 Java 테스트 549개를 실행해 실패 0건, 건너뜀 1건을 확인했다. 건너뜀은 Windows의 심볼릭 링크 관련 기존 갤러리 읽기 테스트다. 마지막 관리자 목록 리소스 검증을 보강한 뒤 `ReviewImageApiSecurityTest`를 재실행했다. MVC 렌더링 결과에서 JS 내용 해시 URL 및 해당 리소스의 HTTP 상태·Content-Type을 확인했고, JAR 내 변경 migration·Mapper·템플릿·JS가 소스와 일치함을 확인했다.

Chrome fixture 브라우저 검증은 기존 분석·자격 제한·일반 목록 스크립트로 360/390/393/430/768/1440 폭에서 수행했다. 일반 목록 36개 사례에서 가로 overflow가 없었으며, 실제 클릭·선택·확인·요청 및 오류 안내 경로를 검사했다. API 응답은 fixture이며 실제 DB를 사용하는 업로드→심사→갤러리 통합 검증은 아니다.

DEV에서는 V003/V004/V005 적용, DB/JDBC/session 시간대와 과거 스냅샷 근거, 실제 두 관리자 및 동의 철회와 승인 경합, 대량 데이터 실행계획을 확인해야 한다. 4단계는 기존 `evaluateCurrent`와 명시적 `decideGallery` 계약을 통해 안내·동의·새 버전 거부를 연결할 수 있다. 나중에 결정하기는 이력을 만들지 않아야 한다.

## 2026-11-06 적용 파일 복원

`.004`는 Git 미추적 파일로 Git 이력이 없었다. 이전 Codex 작업 기록의 2026-11-04T17:43:58.298Z 최초 생성 patch에서 원본을 추출했고, Flyway 체크섬은 보고된 DB 적용값 `847444896`과 일치했다. 같은 기록의 17:47:08.740Z에는 created_at 정밀도 및 FK SET NULL 변경, 17:48:04.854Z에는 created_at nullable 유지 변경이 있었다. 후속 내용의 체크섬은 `407761043`이며 조사 시점 소스·빌드 리소스·JAR도 해당 후속 내용을 담고 있었다. 최초 기록은 `rollout-2026-11-05T02-39-32-01a1a7a5-05f6-70a2-afe2-db29c2719287.jsonl`이다.

원본 `.004`를 복원하고 필요한 후속 변경만 `.005`로 분리했다. `.005`는 gallery_consent_id 컬럼과 gallery_consent_at 변경을 반복하지 않으며, `.004`의 명명된 FK를 한 ALTER 안에서 교체한다. 실제 DB 적용 시각·현재 스키마·수동 변경 여부는 DB 미접속으로 미확인이다. 서버 기동 전 적용 당시 원본과 운영 산출물의 체크섬, 대상 환경의 적용 이력을 확인해야 하며, 다른 환경이 변경된 `.004`를 적용한 경우 동일 상태라고 가정하지 않는다. Flyway repair나 history 변경으로 우회하지 않는다.

## 로컬 DEV .005 실패 복구 (2026-11-06 후속 수정)

위 문단은 최초 분리 당시 기록이다. 이후 사용자가 아직 배포하지 않았으며 PROD·별도 배포 DB가 없음을 확인하고 실패한 `.005` 수정에 명시적으로 동의했다. 제공된 DEV 상태는 `.004 success=1 / checksum=847444896`, `.005 success=0 / checksum=974495723`, InnoDB, `created_at TIMESTAMP(0)`, nullable BIGINT 동의 ID, 동의 FK RESTRICT다. 직접 DB 조회로 확인한 상태는 아니다.

수정된 `.005`는 created_at 정밀도 변경 → 기존 FK 제거 → 동일 이름 FK SET NULL 재생성을 세 독립 ALTER로 수행한다. 새로운 Flyway 체크섬은 **-1439967468**이며 `.004`는 **847444896**을 유지한다. 한 ALTER의 FK DROP/ADD와 COPY가 필요한 타입 변경을 결합하지 않는다. NULL 허용·기본값·부모 BIGINT PK 참조는 유지한다. 목록/회원/사진 상태를 UPDATE하거나 파일을 삭제하지 않는다.

검증: 분리 계약 테스트의 기존 복합 ALTER 실패를 확인한 뒤 수정했고, 관련 테스트 12개·compileJava·bootJar·git diff --check가 통과했다. 실제 Flyway ChecksumCalculator로 두 체크섬을 확인하고 JAR의 migration 내용이 소스와 일치함을 확인했다. 아래 복구 예제는 같은 JAR의 의존성으로 javac 컴파일만 확인했다. DB 실행 결과나 실기 복구 성공을 의미하지 않는다.

### 사용자가 수행할 복구 순서

아래 명령은 **사용자 실행용**이다. Codex는 DB 접속, SQL 실행, repair, 서버 시작을 수행하지 않는다. MySQL 9.2 실기 적용 결과는 사용자 로그와 스키마로 확인한다.

1. 로컬 IntelliJ/bootRun 앱과 Docker app을 중단하고, 다른 클라이언트·배치의 관련 쓰기(사진 업로드, 동의 이력 삭제 포함)를 중지한다. 백업 중 DDL도 없어야 한다. 백업 후 이 상태를 계속 유지한다.
2. DEV DB를 백업하고 성공 종료·파일 크기를 확인한다. PowerShell에서 다음을 실행한다. 비밀번호는 컨테이너 환경에서 참조하며 명령행에 실제 값을 적지 않는다.

```powershell
docker compose --env-file .env.docker.local stop app
if ($LASTEXITCODE -ne 0) { throw 'app 중지 실패' }
$backupName = 'tripbora-dev-before-005-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.sql'
New-Item -ItemType Directory -Force local-data/backups | Out-Null
docker exec tripbora-dev-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces --result-file="$1" "$MYSQL_DATABASE"' sh "/tmp/$backupName"
if ($LASTEXITCODE -ne 0) { throw '백업 실패: 복구 진행 금지' }
docker cp "tripbora-dev-mysql:/tmp/$backupName" "local-data/backups/$backupName"
if ($LASTEXITCODE -ne 0) { throw '백업 복사 실패' }
if ((Get-Item "local-data/backups/$backupName").Length -eq 0) { throw '빈 백업' }
```

3. 아래 읽기 전용 SQL로 대상 DB·이력·실제 정의·고아 참조를 확인한다. `.004` 상태와 다르면 중단한다. 부모/자식 엔진과 BIGINT signed 여부, PK/인덱스도 SHOW CREATE에서 확인한다. DB 접속 도구는 사용자가 선택하며 history를 직접 수정하지 않는다.

```sql
SELECT DATABASE(), VERSION();
SELECT version, script, checksum, success FROM flyway_schema_history
WHERE version IN ('20261102.004','20261102.005') ORDER BY installed_rank;
SHOW CREATE TABLE destination_comment_images;
SHOW CREATE TABLE user_policy_consents;
SELECT i.id, i.gallery_consent_id FROM destination_comment_images i
LEFT JOIN user_policy_consents c ON c.id = i.gallery_consent_id
WHERE i.gallery_consent_id IS NOT NULL AND c.id IS NULL LIMIT 100;
```

4. 복구용 Flyway는 **현재 bootJar에 포함된 라이브러리의 Java API**를 사용한다. build.gradle에는 Flyway Gradle 플러그인이 없어 `gradlew flywayRepair`는 사용할 수 없다. 아래는 별도 CLI 설치나 Spring Boot 기동 없이 같은 의존성을 사용하는 일회성 방법이다. 프로젝트 루트의 PowerShell에서 수행한다. 이 절차는 로컬 호스트 포트 3308에 한정한다.

```powershell
# 수정본으로 빌드한 JAR 사용
.\gradlew.bat bootJar
if ($LASTEXITCODE -ne 0) { throw '빌드 실패' }
$work = Join-Path $env:TEMP ('tripbora-dev-flyway-' + [guid]::NewGuid())
$lib = Join-Path $work 'lib'
New-Item -ItemType Directory -Force $lib | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead((Resolve-Path 'build/libs/tripbora-0.0.1-SNAPSHOT.jar'))
try {
    foreach ($entry in $jar.Entries) {
        if ($entry.FullName -like 'BOOT-INF/lib/*.jar') {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $lib $entry.Name))
        }
    }
} finally { $jar.Dispose() }
$migrationDir = (Resolve-Path 'src/main/resources/db/migration').Path
# .env.docker.local의 DB_NAME / DB_USERNAME과 동일하게 직접 입력한다.
$env:DB_NAME = Read-Host 'DEV DB_NAME'
$env:DB_USERNAME = Read-Host 'DEV DB_USERNAME'
$secret = Read-Host 'DEV DB_PASSWORD' -AsSecureString
$env:DB_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3308/' + $env:DB_NAME + '?connectionTimeZone=LOCAL&characterEncoding=UTF-8'
$repairSource = Join-Path $work 'Dev005Repair.java'
@'
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
public class Dev005Repair {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[1].equals("CHECK") || args[1].equals("REPAIR_LOCAL_DEV_005")))
            throw new IllegalArgumentException("migration directory and explicit action required");
        String url = System.getenv("DB_URL");
        if (url == null || !url.matches("^jdbc:mysql://127\\.0\\.0\\.1:3308/[^?]+(?:\\?.*)?$"))
            throw new IllegalStateException("local DEV port 3308 only");
        String[] names = {"V20261102_004__bind_review_photo_consent_evidence.sql",
                "V20261102_005__preserve_review_photo_precision_and_consent_retention.sql"};
        int[] expected = {847444896, -1439967468};
        for (int n = 0; n < names.length; n++) {
            int checksum = ChecksumCalculator.calculate(new StringResource(Files.readString(Path.of(args[0], names[n]))));
            if (checksum != expected[n]) throw new IllegalStateException("unexpected source: " + names[n]);
        }
        var flyway = Flyway.configure().dataSource(url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))
                .locations("filesystem:" + Path.of(args[0]).toAbsolutePath())
                .baselineVersion("20261014.0").baselineOnMigrate(false).cleanDisabled(true).load();
        var result = flyway.validateWithResult();
        System.out.println(result.getAllErrorMessages());
        if (result.invalidMigrations.size() != 1
                || !"20261102.005".equals(result.invalidMigrations.get(0).version)
                || !"FAILED_VERSIONED_MIGRATION".equals(result.invalidMigrations.get(0).errorDetails.errorCode.toString()))
            throw new IllegalStateException("expected only failed .005; stop for review");
        if (args[1].equals("CHECK")) return;
        var repaired = flyway.repair();
        System.out.println("repair actions: " + repaired.repairActions);
        System.out.println("removed: " + repaired.migrationsRemoved);
        System.out.println("aligned: " + repaired.migrationsAligned);
        System.out.println("deleted: " + repaired.migrationsDeleted);
    }
}
'@ | Set-Content -Encoding ASCII $repairSource
# CHECK는 DB에 접속해 검증한다. 사용자만 실행한다.
java --class-path "$lib/*" $repairSource $migrationDir CHECK
if ($LASTEXITCODE -ne 0) { $env:DB_PASSWORD = $null; throw '사전 검증 실패: repair 금지' }
# .005 외 불일치 없음·백업·스키마 확인 후 사용자가 명시적으로 실행
java --class-path "$lib/*" $repairSource $migrationDir REPAIR_LOCAL_DEV_005
if ($LASTEXITCODE -ne 0) { $env:DB_PASSWORD = $null; throw 'repair 실패: 재기동 금지' }
$env:DB_PASSWORD = $null
```

repair의 예상 변경은 `.005` 실패 이력 제거뿐이다. `aligned`/`deleted`가 비어 있지 않거나 다른 실패 이력이 제거되면 중단하고 이력을 다시 조사한다. DB의 `.004` success/checksum은 그대로여야 한다. repair 후 `.005`는 pending이며, 이 시점에는 아직 스키마를 바꾸지 않았다. 위 Java 소스는 DB 접속 없는 javac 컴파일만 검증하며 실제 실행은 사용자가 한다.

5. 같은 수정본을 사용하는 DEV 앱 **한 개만** 기존 IntelliJ 실행 구성 또는 `.\gradlew.bat bootRun`으로 시작한다. 호스트 앱의 DB_URL은 3308이어야 하며 fallback 3306을 쓰지 않는다. 위에서 비운 DB_PASSWORD는 bootRun 실행 전에 다시 입력해야 한다. Gradle/Java가 `.env.docker.local`을 자동으로 읽는다고 가정하지 않는다. 기존 IntelliJ 구성의 비밀값은 출력하거나 변경하지 않는다. 수정본을 빌드한 Docker app을 쓰는 경우에도 다른 인스턴스는 시작하지 않는다. Spring Boot Flyway가 `.005`를 자동 적용한다. 서버 시작 성공 전까지 다른 쓰기는 계속 중지한다.
6. 로그에서 `.005` migration 시작/성공, 성공 적용 요약, Spring Boot Started를 확인한다. 실패 시 SQL State/Error Code/Message/Line/Statement를 보존한다. 아래 조회로 최종 상태를 확인한 후 쓰기를 재개한다.

```sql
SELECT version, checksum, success FROM flyway_schema_history
WHERE version IN ('20261102.004','20261102.005') ORDER BY installed_rank;
SELECT column_name, column_type, is_nullable, column_default, datetime_precision
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'destination_comment_images'
  AND column_name IN ('created_at','gallery_consent_id','gallery_consent_at');
SELECT constraint_name, referenced_table_name, delete_rule, update_rule
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE() AND table_name = 'destination_comment_images';
```

기대값: `.004 success=1 checksum=847444896`, `.005 success=1 checksum=-1439967468`, created_at 정밀도 6·NULL 허용·CURRENT_TIMESTAMP(6), 원래 이름 FK의 DELETE_RULE=SET NULL. 동의 컬럼과 기존 사진/회원/리뷰 상태는 보존한다.

### 중간 실패와 재실행

| 확인된 상태 | 조치 |
|---|---|
| 첫 ALTER 실패, TIMESTAMP(0)+RESTRICT | 최초 오류를 보존하고 원인 수정 전 재시작 금지 |
| 첫 ALTER만 성공, TIMESTAMP(6)+RESTRICT | 실제 실패 SQL·데이터 상태를 확인. 정밀도를 0으로 되돌리지 않음. 후속 재시도는 별도 검토 |
| FK 제거 후 추가 실패, TIMESTAMP(6)+FK 없음 | 쓰기 중단 유지. 고아 참조·부모 키·타입·권한 확인 후 FK 재생성만 수행하는 명시적 복구안 필요. 전체 .005를 무작정 재실행하면 DROP FK가 실패함 |
| 목표 스키마 완료이나 .005 실패 이력 | 상태와 이력 차이를 조사. repair만 반복하거나 이미 적용된 세 문장을 무조건 재실행하지 않음 |

세 문장은 각각 DDL로 커밋될 수 있으므로 migration 전체 rollback을 기대하지 않는다. 새 `.006`만 추가해 `.005` 실패 검증을 건너뛸 수는 없다. history 수동 DELETE/UPDATE, DB 초기화, 정밀도 축소, FK 검증 비활성화로 우회하지 않는다.
