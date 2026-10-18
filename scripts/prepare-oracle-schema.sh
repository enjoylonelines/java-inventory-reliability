#!/usr/bin/env bash
# Initialize only a new schema in the dedicated compose project. Never delete data.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ORACLE_PWD:?Set a development-only Oracle password}"
if [[ ! "$ORACLE_PWD" =~ ^[A-Za-z0-9]{16,64}$ ]]; then
  printf 'Use a new development-only password of 16–64 ASCII letters or digits.\n' >&2
  exit 1
fi
case "${1:-}" in
  inventory_repro) task_tablespace=IR_REPRO_DATA; task_file=ir_repro_data01.dbf ;;
  inventory_outbox_test) task_tablespace=IR_OUTBOX_DATA; task_file=ir_outbox_data01.dbf ;;
  inventory_crash_test) task_tablespace=IR_CRASH_DATA; task_file=ir_crash_data01.dbf ;;
  inventory_report_test) task_tablespace=IR_REPORT_DATA; task_file=ir_report_data01.dbf ;;
  *) printf 'Choose inventory_repro, inventory_outbox_test, inventory_crash_test, or inventory_report_test.\n' >&2; exit 1 ;;
esac
task_user=$1
if docker compose version >/dev/null 2>&1; then task_compose=(docker compose); else task_compose=(docker-compose); fi
task_container=$("${task_compose[@]}" -p inventory-oracle-repro -f compose.oracle.yaml ps -q oracle)
if [[ -z "$task_container" ]]; then printf 'The dedicated compose Oracle is not running.\n' >&2; exit 1; fi
task_project=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$task_container")
if [[ "$task_project" != inventory-oracle-repro ]]; then printf 'Unexpected container project.\n' >&2; exit 1; fi

# SQL*Plus can echo a failed CREATE USER including the password. Capture its output;
# do not print it on failure. USER/TABLESPACE identifiers are fixed by the allowlist.
if task_output=$({
  printf '%s\n' 'WHENEVER SQLERROR EXIT FAILURE' 'SET ECHO OFF' 'ALTER SESSION SET CONTAINER=FREEPDB1;'
  printf "DECLARE n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM dba_users WHERE username=UPPER('%s'); IF n<>0 THEN raise_application_error(-20001,'user exists'); END IF; SELECT COUNT(*) INTO n FROM dba_tablespaces WHERE tablespace_name='%s'; IF n<>0 THEN raise_application_error(-20002,'tablespace exists'); END IF; END;\n/\n" "$task_user" "$task_tablespace"
  printf "CREATE TABLESPACE %s DATAFILE '/opt/oracle/oradata/FREE/FREEPDB1/%s' SIZE 128M AUTOEXTEND ON NEXT 64M MAXSIZE 4G;\n" "$task_tablespace" "$task_file"
  printf 'CREATE USER %s IDENTIFIED BY "%s" DEFAULT TABLESPACE %s QUOTA UNLIMITED ON %s;\n' "$task_user" "$ORACLE_PWD" "$task_tablespace" "$task_tablespace"
  printf 'GRANT CREATE SESSION, CREATE TABLE TO %s;\n' "$task_user"
  if [[ "$task_user" == inventory_report_test ]]; then
    printf 'GRANT SELECT ON SYS.V_$SQL TO %s;\nGRANT SELECT ON SYS.V_$SESSION TO %s;\nGRANT SELECT ON SYS.V_$SQL_PLAN TO %s;\nGRANT SELECT ON SYS.V_$SQL_PLAN_STATISTICS_ALL TO %s;\n' "$task_user" "$task_user" "$task_user" "$task_user"
  fi
  printf 'ALTER SESSION SET CURRENT_SCHEMA=%s;\n' "$task_user"
  cat src/main/resources/oracle/schema.sql
  printf '\nEXIT;\n'
} | docker exec -i "$task_container" sqlplus -s / as sysdba 2>&1); then
  printf 'Initialized new schema %s in the dedicated local Oracle.\n' "$task_user"
else
  printf 'Initialization failed. Existing users and tablespaces are never replaced; SQL output is hidden because it may contain credentials. Check the dedicated container and schema manually.\n' >&2
  exit 1
fi
