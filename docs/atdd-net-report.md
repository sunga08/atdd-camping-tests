# 공용 인프라 DB 전환 작업 보고서

## 개요

각 애플리케이션(admin, kiosk, reservation)이 공용 네트워크(`atdd-net`)와 공용 DB(`atdd-db`)를 사용하도록 Docker Compose 설정을 수정했습니다.

## 변경 사항

### 1. docker-compose-infra.yml

**변경 내용**: `init.sql` 볼륨 마운트 추가

```yaml
volumes:
  - ./db/init.sql:/docker-entrypoint-initdb.d/init.sql
```

**효과**: MySQL 컨테이너 최초 시작 시 `/docker-entrypoint-initdb.d/` 디렉토리의 SQL 파일이 자동 실행되어 테이블 생성 및 초기 데이터가 삽입됩니다.

---

### 2. docker-compose.yml

#### 2-1. 각 서비스에 네트워크 연결

모든 서비스(kiosk, admin, reservation)에 다음 설정 추가:

```yaml
networks:
  - atdd-net
```

> **참고**: top-level `networks` 섹션은 제거했습니다. 두 compose 파일을 함께 실행(`-f file1 -f file2`)하면 `docker-compose-infra.yml`의 네트워크 정의가 공유됩니다.

#### 2-2. DB 준비 대기 (depends_on + condition)

DB가 healthcheck를 통과한 후 앱이 시작되도록 설정:

```yaml
# admin, reservation 서비스에 추가
depends_on:
  db:
    condition: service_healthy
```

#### 2-3. DB 환경변수 오버라이드 (admin, reservation)

```yaml
environment:
  - SPRING_DATASOURCE_URL=jdbc:mysql://atdd-db:3306/atdd?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
  - SPRING_DATASOURCE_USERNAME=root
  - SPRING_DATASOURCE_PASSWORD=secret
  - SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
  - SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.MySQLDialect
  - SPRING_JPA_HIBERNATE_DDL_AUTO=none
```

| 환경변수 | 값 | 설명 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://atdd-db:3306/atdd` | 공용 DB 컨테이너 연결 |
| `SPRING_DATASOURCE_USERNAME` | `root` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | `secret` | DB 비밀번호 |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `com.mysql.cj.jdbc.Driver` | MySQL 드라이버 |
| `SPRING_JPA_DATABASE_PLATFORM` | `org.hibernate.dialect.MySQLDialect` | Hibernate MySQL 방언 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `none` | init.sql로 초기화하므로 DDL 자동 생성 비활성화 |

---

## 실행 순서

두 compose 파일을 함께 실행하면 `depends_on.condition: service_healthy`가 동작하여 DB 준비 후 앱이 자동으로 시작됩니다.

```bash
# Gradle 태스크로 실행 (권장)
./gradlew test

# 또는 직접 실행
docker compose -f infra/docker-compose-infra.yml -f infra/docker-compose.yml up -d --build

# 종료
docker compose -f infra/docker-compose-infra.yml -f infra/docker-compose.yml down
```

## 데이터 초기화 시점

**요구사항**: "데이터 초기화 시점은 infra를 띄우고 app을 실행하기 전에 수행해야 한다"

### 실행 흐름

```
1. docker compose up 실행
   │
   ├─ db 서비스 시작 (MySQL)
   │    │
   │    ├─ MySQL 프로세스 시작
   │    │
   │    ├─ /docker-entrypoint-initdb.d/init.sql 자동 실행  ← 데이터 초기화
   │    │
   │    └─ healthcheck 통과 (mysqladmin ping 성공)
   │
   └─ admin, reservation 서비스 시작 (depends_on.condition: service_healthy)
        │
        └─ 앱 실행
```

### 초기화 순서 보장

| 단계 | 시점 | 설명 |
|---|---|---|
| 1 | infra(DB) 띄움 | MySQL 컨테이너 시작 |
| 2 | 데이터 초기화 | MySQL 시작 중 init.sql 자동 실행 |
| 3 | healthcheck 통과 | init.sql 실행 완료 후 |
| 4 | app 실행 | healthcheck 통과 후 (depends_on.condition) |

MySQL의 `docker-entrypoint-initdb.d` 디렉토리에 마운트된 SQL 파일은 **MySQL 프로세스가 완전히 시작되기 전에 실행**됩니다. `healthcheck`는 MySQL이 응답 가능한 상태가 되어야 통과하므로, init.sql 실행이 완료된 후에 앱이 시작됩니다.

> **결론**: infra 띄우고 → 데이터 초기화 → app 실행을 충족

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                      atdd-net (공용 네트워크)                  │
│                                                             │
│  ┌─────────┐    ┌─────────┐    ┌─────────────┐             │
│  │  kiosk  │───▶│  admin  │───▶│   atdd-db   │             │
│  │ :18080  │    │ :18081  │    │    :13306   │             │
│  └─────────┘    └─────────┘    └─────────────┘             │
│                       │                 ▲                   │
│                       │                 │                   │
│               ┌───────────────┐         │                   │
│               │  reservation  │─────────┘                   │
│               │    :18082     │                             │
│               └───────────────┘                             │
└─────────────────────────────────────────────────────────────┘
```

## DB 선택: H2 vs MySQL

### 배경

`docker-compose-infra.yml`에서는 MySQL 8.0을 사용하도록 정의되어 있으나, `atdd-camping-admin`과 `atdd-camping-reservation`에는 MySQL JDBC 드라이버가 없었습니다.

### 선택지 비교

| 항목 | H2로 변경 | MySQL로 통합 |
|---|---|---|
| 앱 코드 수정 | 불필요 | `build.gradle`에 의존성 추가 |
| 실제 운영 환경 유사성 | 낮음 | 높음 |
| 공용 DB 개념 | 불가능 (H2는 인메모리/임베디드) | 가능 |
| mission.txt 의도 부합 | X | O |
| Docker 컨테이너화 | 부자연스러움 | 자연스러움 |

### 결정: MySQL로 통합

**선택 이유:**

1. **공용 DB 목적 부합**: H2는 인메모리/임베디드 DB로, Docker 컨테이너로 공용 DB를 띄우는 것이 부자연스럽습니다.
2. **mission.txt 의도**: "공용 인프라 DB로 전환"이라는 요구사항에 MySQL이 적합합니다.
3. **운영 환경 유사성**: 실제 운영 환경에서는 MySQL 같은 RDBMS를 사용하므로 테스트 신뢰성이 높아집니다.
4. **변경 범위 최소화**: `build.gradle`에 한 줄 추가하는 수준으로 간단합니다.

### 적용 내용

다음 파일에 MySQL 드라이버 의존성을 추가했습니다:

- `repos/atdd-camping-admin/build.gradle`
- `repos/atdd-camping-reservation/build.gradle`

```gradle
// Database
runtimeOnly 'com.h2database:h2'
runtimeOnly 'com.mysql:mysql-connector-j'  // 추가
```

H2는 로컬 개발/테스트 용도로 유지하고, Docker 환경에서는 환경변수로 MySQL을 사용하도록 오버라이드합니다.

---

### 3. 추가 환경변수 설정

테스트 중 발견된 이슈를 해결하기 위해 다음 환경변수를 추가했습니다:

```yaml
# admin, reservation 서비스에 추가
- SPRING_SQL_INIT_MODE=never  # 앱 내부 data.sql 실행 비활성화 (init.sql과 충돌 방지)
```

### 4. build.gradle.kts composeUp/composeDown 태스크 수정

두 compose 파일을 함께 실행하도록 수정:

```kotlin
tasks.register<Exec>("composeUp") {
    description = "Start test environment"
    commandLine(
        "sh", "-c",
        "docker compose -f infra/docker-compose-infra.yml -f infra/docker-compose.yml up -d --build"
    )
}

tasks.register<Exec>("composeDown") {
    description = "Stop test environment"
    commandLine(
        "sh", "-c",
        "docker compose -f infra/docker-compose-infra.yml -f infra/docker-compose.yml down"
    )
}
```

> **변경 이유**: `depends_on.condition: service_healthy`는 같은 compose 프로젝트 내 서비스 간에만 동작합니다. 두 compose 파일을 `-f file1 -f file2` 형식으로 함께 실행하면 하나의 프로젝트로 병합되어 `depends_on`이 정상 동작합니다.

---

## 테스트 결과

```
BUILD SUCCESSFUL
Tests: 100% passed
```

---

## 주의사항

1. **두 파일 함께 실행**: `-f docker-compose-infra.yml -f docker-compose.yml` 형식으로 두 파일을 함께 실행해야 `depends_on.condition`이 동작합니다.
2. **DB 초기화**: `init.sql`은 컨테이너 **최초 생성 시에만** 실행됩니다. 재초기화가 필요하면 볼륨을 삭제해야 합니다. (`docker compose -f infra/docker-compose-infra.yml -f infra/docker-compose.yml down -v`)
3. **MySQL 드라이버**: 각 앱의 `build.gradle`에 MySQL 드라이버 의존성이 필요합니다. (이미 추가 완료)
4. **앱 내부 SQL 초기화 비활성화**: `SPRING_SQL_INIT_MODE=never`로 앱 내부 data.sql 실행을 비활성화하여 init.sql과의 충돌을 방지합니다.

---

## 체크리스트 통과 여부

| 항목 | 통과 | 근거 |
|---|:---:|---|
| 앱 compose의 포트/네트워크 충돌이 없다 | ✅ | kiosk:18080, admin:18081, reservation:18082, db:13306 모두 고유 포트 사용 |
| 공용 네트워크 atdd-net 사용, DB 호스트 atdd-db 설정 | ✅ | 모든 서비스에 `networks: - atdd-net` 설정, `SPRING_DATASOURCE_URL=jdbc:mysql://atdd-db:3306/atdd` |
| 준비 대기로 플래키 없음 | ✅ | `depends_on.condition: service_healthy`로 DB healthcheck 통과 후 앱 시작 |
| 테스트 베이스URL과 DB 크리덴셜 외부화 | ✅ | `build.gradle.kts`에서 환경변수로 URL 설정, `docker-compose.yml`에서 DB 크리덴셜 환경변수 오버라이드 |