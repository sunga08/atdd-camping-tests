# ATDD Camping Tests

## 개요

레거시 기반 다중 서비스 환경에서 **스모크 테스트**를 중심으로 동작하는 인수 테스트 허브.
각 서비스의 소스코드를 직접 다루지 않고, **블랙박스 방식**으로 API 엔드포인트를 검증한다.

---

## 테스트 허브 역할

```
┌─────────────────────────────────────────────────────────────┐
│                   ATDD Test Hub (이 프로젝트)                  │
│                                                             │
│   - 서비스 소스코드를 소유하지 않음                                 │
│   - HTTP API를 통한 블랙박스 테스트                               │
│   - 배포 후 스모크 테스트 실행                                    │
│   - 다중 서비스 통합 검증                                        │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ HTTP 요청
┌─────────────────────────────────────────────────────────────┐
│                    테스트 대상 서비스들                          │
│   ┌─────────┐  ┌─────────┐  ┌───────────┐ ┌─────────┐        │
│   │ Kiosk   │  │ Admin   │  │Reservation│ │  ...    │       │
│   │ Service │  │ Service │  │ Service   │ │         │        │
│   └─────────┘  └─────────┘  └───────────┘ └─────────┘        │
└─────────────────────────────────────────────────────────────┘
```

---

## 프로젝트 구조

```
atdd-camping-tests/
├── src/test/
│   ├── java/com/camping/tests/
│   │   ├── RunCucumberTest.java      # 테스트 실행 진입점
│   │   └── steps/                    # Step Definitions
│   └── resources/features/           # Gherkin 시나리오
├── infra/
│   ├── docker-compose-infra.yml      # 공유 인프라 (DB)
│   ├── docker-compose.yml            # 서비스 컨테이너
│   ├── db/init.sql                   # 테스트 데이터 시딩
│   └── wiremock/mappings/            # 외부 API 모킹
└── repos/                            # 서비스 소스 (참조용)
```

---

## 기술 스택

| 구분 | 기술 | 용도 |
|------|------|------|
| BDD | Cucumber 7.14 | 시나리오 기반 테스트 |
| HTTP | RestAssured 5.3.2 | API 호출 및 검증 |
| Runner | JUnit 5 | 테스트 실행 |
| Mocking | WireMock | 외부 서비스 격리 |
| Infra | Docker Compose | 테스트 환경 구성 |

---

## 인프라 구성

### 테스트 실행 환경

```
┌──────────────────────────────────────────────┐
│              Docker Network                  │
│                                              │
│  ┌────────────┐        ┌────────────┐        │
│  │  MySQL 8   │◄───────│  Services  │        │
│  │   :13306   │        │  :8080~    │        │
│  └────────────┘        └─────┬──────┘        │
│                              │               │
│  ┌────────────┐              │               │
│  │  WireMock  │◄─────────────┘               │
│  │ (Payment)  │                              │
│  └────────────┘                              │
└──────────────────────────────────────────────┘
        ▲
        │ HTTP
┌───────┴───────┐
│  Test Runner  │
│  (Cucumber)   │
└───────────────┘
```

### infra 디렉토리 구조

```
infra/
├── docker-compose-infra.yml    # 공유 인프라 (DB, WireMock)
├── docker-compose.yml          # 테스트 대상 서비스
├── db/
│   └── init.sql                # DB 스키마 및 테스트 데이터
├── dockerfiles/
│   ├── Dockerfile-kiosk        # Kiosk 서비스 빌드
│   ├── Dockerfile-admin        # Admin 서비스 빌드
│   └── Dockerfile-reservation  # Reservation 서비스 빌드
└── wiremock/
    └── mappings/
        └── payment-approve.json  # 결제 API 모킹
```

### Docker Compose 구성

**docker-compose-infra.yml** - 공유 인프라

| 서비스 | 이미지 | 포트 | 용도 |
|--------|--------|------|------|
| db | mysql:8.0 | 13306:3306 | 테스트 데이터베이스 |

**docker-compose.yml** - 테스트 대상 서비스

| 서비스 | 포트 | 설명 |
|--------|------|------|
| kiosk | 18080:8080 | 매점 키오스크 서비스 |

### 동작 순서

```
1. composeUp 실행
   │
   ├── docker-compose-infra.yml 실행
   │   └── MySQL 컨테이너 시작
   │       └── db/init.sql 실행 (스키마 생성 + 테스트 데이터 시딩)
   │
   └── docker-compose.yml 실행
       └── Kiosk 서비스 빌드 및 시작
           └── Dockerfile-kiosk로 빌드 (Multi-stage build)

2. test 실행
   └── Cucumber 테스트가 서비스에 HTTP 요청

3. composeDown 실행
   └── 모든 컨테이너 종료
```

### 테스트 데이터 (db/init.sql)

| 테이블 | 데이터 |
|--------|--------|
| products | 12개 상품 (렌탈/판매) |
| campsites | 35개 캠핑 사이트 |
| reservations | 샘플 예약 데이터 |
| sales_records | 판매 기록 |
| rental_records | 대여 기록 |

### WireMock 모킹

외부 결제 API를 모킹하여 테스트 격리:
```json
POST /v1/payments → { "paymentKey": "pay_mock", "status": "APPROVED" }
```

---

## 실행 방법

### 전체 테스트 실행 (자동화)

```bash
./gradlew test
```

자동으로 다음 순서로 실행됩니다:
1. `composeUp` - Docker 환경 시작 (인프라 + 서비스)
2. `waitForServices` - 서비스 준비 대기 (헬스체크)
3. `test` - Cucumber 테스트 실행
4. `composeDown` - Docker 환경 종료

### 개별 시나리오 실행

```bash
# 1. 환경 시작 및 준비 대기
./gradlew composeUp waitForServices

# 2. IDE에서 시나리오 개별 실행 또는 특정 태그로 실행
./gradlew test -Dcucumber.filter.tags="@smoke"

# 3. 작업 완료 후 환경 종료
./gradlew composeDown
```

### 환경 관리

```bash
# 환경 시작
./gradlew composeUp

# 환경 종료
./gradlew composeDown
```

### Gradle 태스크 설명

| 태스크 | 설명 |
|--------|------|
| `composeUp` | Docker 환경 시작 (인프라 + 서비스) |
| `waitForServices` | 서비스가 준비될 때까지 헬스체크 (최대 30회, 2초 간격) |
| `test` | Cucumber 테스트 실행 |
| `composeDown` | Docker 환경 종료 |

`waitForServices`는 `http://localhost:8080`에 HTTP 요청을 보내 200 응답을 받을 때까지 대기합니다. 서비스가 60초 내에 준비되지 않으면 빌드가 실패합니다.
