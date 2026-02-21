# WireMock 구성

#### kiosk 서비스가 실제 결제 시스템 대신 WireMock을 통해 결제 API를 모킹하도록 구성



## 1. docker-compose.yml 변경

### payments-mock 서비스 추가

```yaml
payments-mock:
  image: wiremock/wiremock:latest
  ports:
    - "19090:8080"
  volumes:
    - ./wiremock/mappings:/home/wiremock/mappings
  networks:
    - atdd-net
  command: ["--verbose"]
```

- **이미지**: `wiremock/wiremock:latest`
- **포트**: 호스트 `19090` → 컨테이너 `8080`
- **볼륨**: `infra/wiremock/mappings` 디렉토리를 마운트하여 정적 매핑 파일 로드
- **네트워크**: `atdd-net`에 합류하여 kiosk에서 접근 가능
- **옵션**: `--verbose`로 요청/응답 로깅

### kiosk 서비스 환경변수 추가

```yaml
kiosk:
  environment:
    - KIOSK_PAYMENT_BASE_URL=http://payments-mock:8080
  depends_on:
    - admin
    - payments-mock  # 추가
```

---

## 2. WireMock 스텁 파일 (정적 매핑)

스텁 관리 방식: **정적 매핑 파일** (`mappings/*.json`)

WireMock 기동 시 `mappings/` 디렉토리의 JSON 파일을 자동으로 로드합니다.

- payment-approve.json (결제 생성 성공)

- payment-confirm-success.json (결제 확정 성공)

- payment-confirm-fail.json (결제 확정 실패)
  - 금액이 `12345`원일 때 실패 응답을 반환


**priority 설정:**
- `priority: 1`: 금액 12345원 조건 매칭 시 실패 응답 (우선적용)
- `priority: 2`: 기본 성공 응답

---

## 3. kiosk application.yml 변경

**경로:** `repos/atdd-camping-kiosk/src/main/resources/application.yml`

```yaml
kiosk:
  payment:
    base-url: ${KIOSK_PAYMENT_BASE_URL:http://localhost:9090}
    secret-key: ${PAYMENTS_SECRET_KEY:test_sk_dummy}
```

- 환경변수 `KIOSK_PAYMENT_BASE_URL`로 외부화
- 기본값: `http://localhost:9090` (로컬 개발용)
- Docker 환경: `http://payments-mock:8080` (docker-compose에서 주입)

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│  Docker Network: atdd-net                                   │
│                                                             │
│  ┌─────────┐      ┌─────────┐      ┌───────────────┐        │
│  │  kiosk  │ ──── │  admin  │ ──── │      DB       │        │
│  │ :18080  │      │ :18081  │      │ (atdd-db)     │        │
│  └─────────┘      └─────────┘      └───────────────┘        │
│       │                                                     │
│       │ POST /v1/payments                                   │
│       │ POST /v1/payments/confirm                           │
│       ▼                                                     │
│  ┌───────────────┐                                          │
│  │ payments-mock │  ← WireMock (정적 매핑)                    │
│  │    :19090     │                                          │
│  └───────────────┘                                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 실행 방법

```bash
# 전체 서비스 기동 (DB + 앱 + WireMock)
./gradlew composeUp

# WireMock 스텁 확인 (선택)
curl http://localhost:19090/__admin/mappings

# 테스트 실행
./gradlew test
```

---

## 참고

- WireMock 공식 문서: https://wiremock.org/docs/
- 스텁 매칭 우선순위: priority 값이 낮을수록 우선 매칭
- JSONPath 문법: `$[?(@.field == value)]`