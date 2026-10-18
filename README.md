# Java Inventory Reliability

재고 주문과 외부 시스템 전달에서 발생하는 동시성, 중복, 부분 실패를 처리하는 개인 프로젝트다. Java 21, Spring Boot, MyBatis와 PostgreSQL을 사용하며, 외부 시스템은 독립된 Python/SQLite 모의 서비스다.

**문제:** 재고가 부족한데 동시에 주문이 들어오거나, 외부 반영 직후 응답이 유실되면 초과 출고와 중복 반영이 발생할 수 있다.

**접근:** SKU·창고별 트랜잭션 잠금 및 수량 검사, 요청 키, transactional outbox, lease 기반 재시도와 대사로 실패 경계를 좁힌다. 동일한 가상 입력으로 개선 전후를 비교한다. 실험 내용과 측정 결과는 [RESULTS.md](RESULTS.md)를 참고한다.

## 실행

Docker Compose와 Python 3이 필요하다. 다음 명령은 이 디렉터리에서 실행한다. 로컬 포트 18184–18188과 별도의 명명된 실험 볼륨을 사용한다.

```sh
docker-compose -p inventory-reliability up -d --build
# app의 http://127.0.0.1:18184/actuator/health가 UP이 될 때까지 기다린 뒤:
python3 experiment.py --restart-tests
python3 migration_experiment.py
python3 legacy_contract_experiment.py
python3 stock_concurrency_experiment.py
python3 mapping_experiment.py
docker-compose -p inventory-reliability --profile scale up -d app2 proxy-one proxy-two
# app2의 http://127.0.0.1:18186/actuator/health가 UP이 될 때까지 기다린 뒤:
python3 scale_experiment.py
docker-compose -p inventory-reliability exec -T proxy-two nginx -s reload
python3 stock_concurrency_experiment.py --multi
python3 worker_experiment.py
docker-compose -p inventory-reliability start app2
# app2의 /actuator/health가 UP이 될 때까지 기다린 뒤:
python3 ops_snapshot.py
docker-compose -p inventory-reliability --profile scale stop
```

`--restart-tests`는 이 실험의 app 컨테이너를 강제 종료하고 다시 시작한다. 워커 비교는 종료 시 app2를 중지하므로 지표 스냅샷 전에 다시 시작한다. 스키마 실험은 격리된 실험 DB에 컬럼을 추가한다. 반복 실행 시 실험 데이터가 누적되며 위 stop 명령은 명명된 볼륨을 삭제하지 않는다. 각 실험은 원시 결과를 `evidence/`에 저장한다.

## 실험 경계

- 재고 실험은 `baseline_inventory_writer.sql`과 최소 가상 스키마에 Java 경로의 잠금 및 사전 재고 검사를 덧붙인다. **같은 잠금 규약에 참여하지 않는 다른 writer까지 보호하지 않는다.**
- 개선 전 외부 호출은 실패 양상을 재현하기 위한 실험용 baseline이다. 개선 경로는 비즈니스 행과 outbox를 한 트랜잭션에 기록한다.
- 중복 방지는 안정적인 클라이언트 요청 키와 모의 대상의 동일 키 멱등 처리에 의존한다. 보편적인 exactly-once 보장은 아니다.
- 매핑 검사는 opt-in 가상 catalogue snapshot 기준의 누락·비활성·미존재 코드 검사다. 존재하지만 의미가 잘못된 연결이나 실제 기준정보의 최신성은 검증하지 않는다.
- 대사는 실험 전표와 모의 외부 반영의 요청 키 및 행 수 비교다. 금액·수량·재고 원장·회계 대사는 범위에 없다.
- 실험 API와 장애 주입·재처리에는 인증이 없다. 실제 데이터와 연결하거나 배포해서는 안 된다.

모든 측정은 로컬 synthetic 실험이며 운영 성능, 실사용자의 개선, 실제 ERP 연동이나 SLA를 의미하지 않는다.
