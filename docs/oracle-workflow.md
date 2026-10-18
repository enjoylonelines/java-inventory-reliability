# Oracle 업무 흐름과 검증 범위

이 경로는 Java 21 / Spring Boot / MyBatis와 Oracle Free를 사용하는 격리 개발용 수직 기능이다. 기존 PostgreSQL 실험과 데이터·측정은 분리돼 있다. 외부 시스템은 모의 서비스이며 실제 ERP 연동이나 운영 시스템이 아니다.

## 구조와 업무 경계

`출고 또는 반품 요청 → 도메인 검증 → Oracle 트랜잭션 → 전표·수불·현재고·단순 분개·outbox·감사 기록`으로 저장한다. 별도 outbox 작업자가 커밋된 이벤트를 모의 대상에 전송한다. 보고는 Oracle 기록을 조회한다. 외부 전송 성공은 업무 저장 성공과 별개다.

- 출고는 창고·SKU 순으로 재고 행을 잠그고 부족분을 거절한다. 부분 반품은 원출고를 잠가 누적 수량을 제한하고 원금액 기준으로 역분개한다. 같은 요청 키와 같은 입력은 결과를 재사용하며 다른 입력은 충돌이다. 요청 키는 이 구현의 전체 범위에서 유일하며 무기한 보관한다.
- 상태 객체는 허용 가능한 전이와 수량·금액 규칙을 담당하고, 서비스는 트랜잭션과 저장을 조정한다. DB 잠금·제약이 요청 간 경쟁을 다루므로 객체 검사만으로 동시성을 보장하지 않는다.
- 재고 보고는 현재고와 수불을, 단순 재무 보고는 확정 전표와 매출·채권 분개를 비교한다. 재무 대사는 전표 합계 기준이며 모든 행 단위 손상이나 여러 HTTP 요청 간 일관된 스냅샷까지 검증하지 않는다.
- 동일 DB에서 이 규약을 따르지 않는 직접 SQL 또는 다른 writer는 보호하지 않는다. 기존 PostgreSQL 경로는 `oracle` 프로파일에서 활성화되지 않는다. 신규 전표 API는 주문 생성부터 게시까지 한 요청에서 처리하므로 사용자가 임시저장·승인 단계를 별도 호출하는 업무 기능은 없다.
- 단일 통화와 세금·수금 없는 단순 매출·채권에 한정한다. 완전한 회계, tenant 격리, 초기재고 입력 UI, 전표 검색 UI는 없다. 초기 재고는 격리 테스트에서 수불과 현재고를 함께 준비한다.

## Oracle 준비와 실행

### 재현용 컨테이너

Docker Compose, JDK 21, Maven이 필요하다. 다음은 기존 DB가 아닌 `inventory-oracle-repro` 프로젝트와 별도 볼륨을 사용한다. 로컬 포트 11522를 기본으로 사용하며 필요하면 `ORACLE_PORT`로 빈 포트를 지정한다. Oracle 이미지 사용 조건과 로컬 자원을 먼저 확인한다. 비밀번호는 16~64자의 영문·숫자 개발 전용 값으로, 실제 자격 증명을 사용하지 않는다. 컨테이너 환경 변수는 해당 호스트의 Docker 관리자에게 보일 수 있다.

```sh
# 개발 전용 비밀번호를 입력하고 현재 셸에만 설정한다.
read -rs ORACLE_PWD; export ORACLE_PWD
docker compose -p inventory-oracle-repro -f compose.oracle.yaml up -d
# oracle 컨테이너가 healthy가 될 때까지 기다린다.
./scripts/prepare-oracle-schema.sh inventory_repro
export ORACLE_TEST_URL="jdbc:oracle:thin:@//127.0.0.1:${ORACLE_PORT:-11522}/FREEPDB1"
export ORACLE_TEST_USER=inventory_repro
export ORACLE_TEST_PASSWORD="$ORACLE_PWD"
ORACLE_INTEGRATION_TEST=true mvn -Dtest=OraclePostingIntegrationTest,OracleReportIntegrationTest,OracleSpringContextIntegrationTest,OracleSpringApiIntegrationTest test
```

`docker compose` 플러그인 대신 `docker-compose` 명령이 설치된 환경은 시작 명령을 그 이름으로 바꾼다. 초기화 스크립트는 지정된 Compose 프로젝트만 대상으로 하며 기존 계정이나 테이블스페이스가 있으면 교체하지 않고 실패한다. Oracle DDL은 암묵적 커밋이므로 중간 실패 시 일부 객체가 남을 수 있다. 원인을 확인하고 보존 여부를 결정해야 하며, 스크립트는 자동 삭제하지 않는다. 볼륨을 유지하면 다음 시작에도 DB가 남고 새 `ORACLE_PWD` 값만으로 기존 비밀번호가 바뀌지 않는다.

전용 빈 계정이 필요한 실험은 다음처럼 각 계정을 **한 번만** 준비한다. 각 테스트는 실제 가상 행을 남기며, outbox·충돌·조회 비교 실험은 빈 전용 스키마에서만 실행된다. 이미 사용한 스키마를 자동으로 비우거나 다른 프로젝트의 데이터를 지우지 않는다.

```sh
./scripts/prepare-oracle-schema.sh inventory_outbox_test
ORACLE_INTEGRATION_TEST=true ORACLE_OUTBOX_TEST_USER=inventory_outbox_test mvn -Dtest=OracleOutboxIntegrationTest test

./scripts/prepare-oracle-schema.sh inventory_crash_test
ORACLE_CRASH_EXPERIMENT=true ORACLE_CRASH_USER=inventory_crash_test mvn -Dtest=OracleWorkerCrashExperimentTest test

./scripts/prepare-oracle-schema.sh inventory_report_test
ORACLE_REPORT_EXPERIMENT=true ORACLE_REPORT_USER=inventory_report_test mvn -Dtest=OracleStockReportComparisonTest test
```

각 명령은 위의 `ORACLE_TEST_URL`과 `ORACLE_TEST_PASSWORD`를 사용한다. 충돌 실험은 자신이 시작한 자식 JVM을 강제 종료하며, 조회 실험은 전용 스키마의 데이터를 추가하고 인덱스를 생성한다. 원시 JSON은 `evidence/`에 남는다. 위 스키마는 이미 데이터가 있으면 다시 사용할 수 없으므로 반복 실험 시 별도의 새 개발 환경을 준비해야 한다.

### 기존 Oracle 개발 DB를 직접 준비할 때

1. 다른 애플리케이션과 분리된 Oracle 개발 DB 및 **새 빈 스키마**를 준비한다. `src/main/resources/oracle/schema.sql`을 그 계정에서 SQL*Plus 등으로 한 번 실행한다. Oracle DDL은 암묵적으로 커밋되므로 기존 스키마에서 실행하지 않는다. 이 초기 스키마에는 outbox 이력과 감사 테이블이 이미 포함돼 있다. `002_outbox_history.sql`, `003_action_audit.sql`은 해당 테이블이 없던 이전 개발 스키마를 위한 별도 변경이므로 새 스키마에 중복 적용하지 않는다. 기존 데이터 이전은 검증하지 않았다. `004_optional_stock_cover_index.sql`은 조회 실험에서 사용한 선택 후보이며 기본 스키마에 포함되지 않는다.
2. `ORACLE_JDBC_URL`, `ORACLE_JDBC_USER`, `ORACLE_JDBC_PASSWORD`를 안전하게 설정하고 `oracle` Spring 프로파일을 사용한다. 자격 증명은 저장소나 URL에 넣지 않는다. 스키마 자동 초기화는 꺼져 있다.
3. API는 기본적으로 꺼져 있다. 격리 환경에서만 `inventory.api.enabled=true`와 서로 다른 충분히 긴 `inventory.api.viewer-token`, `inventory.api.operator-token`을 제공한다. `/api/oracle/**`의 GET은 두 역할에, 쓰기는 담당자에게 허용한다. 성공한 업무 변경과 수동 재처리는 역할명으로 기록하지만, 거절된 요청과 실패한 업무 변경의 감사 이력은 구현하지 않았다. 이 정적 공유 토큰과 역할명 감사 기록은 개인 식별, 토큰 회전, TLS, 운영 권한 관리가 아니며 운영 보안으로 사용할 수 없다.
4. outbox 작업자 역시 기본적으로 꺼져 있다. `inventory.oracle.outbox.enabled=true`와 HTTP loopback의 `/apply`를 가리키는 `inventory.oracle.outbox.mock-url`을 명시해야 한다. 네트워크 네임스페이스가 다른 컨테이너의 loopback은 서로 다르므로 모의 서비스를 같은 네임스페이스에서 실행해야 한다. 전달은 최소 한 번 시도되며 중복 방지는 모의 대상의 이벤트 ID 멱등 처리에 의존한다.

API는 `POST /api/oracle/shipments`, `POST /api/oracle/returns`에서 `Idempotency-Key`를 요구한다. 조회는 `/api/oracle/reports/stock`, `/financial`, `/shipments/{shipmentId}/returns`를 사용한다. 모의 대상의 이벤트 단위 관찰은 별도로 `inventory.oracle.reconciliation.enabled=true`와 `inventory.oracle.reconciliation.mock-state-url`을 설정할 때 `/api/oracle/reconciliation/outbox/{eventId}`에서 조회한다. 상태 주소는 명시적인 loopback IP의 HTTP `/state`만 허용한다. 모의 서비스의 전체 상태를 받아 해당 ID만 비교하므로 응답이 2MiB를 넘으면 실패한다. Oracle과 모의 서비스는 서로 다른 시점에 읽으며 결과는 확정 대사가 아니다. 구성과 계약은 컨트롤러와 테스트를 참고한다.

## 테스트와 측정 재현

기본 `mvn test`는 단위 테스트를 실행하며, Oracle 환경 변수로 명시하지 않은 통합 테스트는 건너뛴다. 실제 DB 검증은 위에서 준비한 **전용 테스트 스키마**에 대해 `ORACLE_INTEGRATION_TEST=true`, `ORACLE_TEST_URL`, `ORACLE_TEST_USER`, `ORACLE_TEST_PASSWORD`를 설정한 뒤 다음을 실행한다.

```sh
mvn -Dtest=OraclePostingIntegrationTest,OracleReportIntegrationTest,OracleSpringContextIntegrationTest,OracleSpringApiIntegrationTest test
```

테스트는 고유한 가상 데이터 행을 생성하고 삭제하지 않는다. `OracleOutboxIntegrationTest`는 별도의 빈 전용 계정과 `ORACLE_OUTBOX_TEST_USER`가 필요하며 기존 대기열을 소모할 수 있으므로 공유 스키마에서 실행하지 않는다. 이 테스트는 시작 시 스키마·빈 대기열·허용 계정명을 검사하고 실행 후 행을 남긴다. 반복 실행을 위해 데이터를 임의로 지우지 말고 새 격리 스키마를 준비하고 테스트의 계정 허용 범위를 확인한다.

실제 Oracle에서 출고부터 loopback HTTP 모의 수신 및 이벤트별 대사까지 연결하는 테스트는 같은 전용 DB 변수와 `ORACLE_E2E_TEST=true`, `ORACLE_E2E_DEDICATED_SCHEMA=true`를 설정한 뒤 `mvn -Dtest=OraclePostingHttpEndToEndTest test`로 실행한다. 자체 생성 이벤트만 선택하는 테스트 전용 매퍼와 테스트 내 HTTP 수신자를 사용하므로 전체 대기열 선택·스케줄러나 Python 모의 서비스 자체를 검증하는 테스트는 아니다.

조회 비교는 `ORACLE_QUERY_EXPERIMENT=true`와 동일한 DB 변수로 `mvn -Dtest=OracleQueryComparisonTest test`를 실행한다. 매 실행마다 가상 수불 20,000행을 추가하고 지우지 않으며 `evidence/`에 원시 표본을 저장한다. Oracle의 실제 실행계획·운영 부하·캐시가 통제된 벤치마크가 아니므로 결과의 한계는 [측정 결과](../RESULTS.md)를 확인한다.

## 아직 확인하지 않은 것

운영 배포와 부하, 전체 writer의 잠금 준수, 완전한 장애 지점별 복구 시간 분포, 실제 외부 시스템, 실사용 데이터, 다중 tenant 보안은 검증하지 않았다. 별도 트랜잭션이나 외부 대상의 조회를 하나의 원자적 스냅샷으로 취급할 수 없다. 상세 작업 계획은 [개발 계획](plans/inventory-reliability-roadmap.md)에 남겨 둔다.
