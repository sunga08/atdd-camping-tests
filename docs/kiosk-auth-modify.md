# Kiosk 서비스 쿠키 인증 전달 구현

## 개요

Kiosk 서비스가 클라이언트로부터 전달받은 인증 쿠키를 Admin 서비스로 그대로 전달하여 인증을 통과시키도록 수정한 내용을 정리합니다.

## 변경 전 구조

기존에는 `AdminAuthClient`가 **Lazy Initialization** 패턴으로 자동 로그인을 수행했습니다:

```
테스트: Kiosk /api/products 호출 (인증 없음)
         ↓
AdminClient.getProducts()
         ↓
AdminAuthClient.getCookieHeader() → getToken() → login() 자동 호출
         ↓
Admin 서비스에 자동 로그인 후 쿠키 캐시
         ↓
캐시된 쿠키로 Admin API 호출
```

## 변경 후 구조

클라이언트가 전달한 인증 쿠키를 그대로 Admin 서비스에 전달합니다:

```
테스트: Admin 서비스 로그인 → accessToken 획득
         ↓
테스트: Kiosk /api/products 호출 (Cookie: AUTH_TOKEN={token})
         ↓
ProductController → AdminService → AdminClient
         ↓
Admin /admin/products 호출 (Cookie 헤더 그대로 전달)
         ↓
Admin JwtAuthFilter가 쿠키에서 AUTH_TOKEN 추출하여 인증
```

## 수정 파일 목록

### 1. ProductController.java

**경로:** `repos/atdd-camping-kiosk/src/main/java/com/camping/kiosk/web/ProductController.java`

**변경 내용:** `Cookie` 헤더를 받아서 서비스로 전달

```java
@GetMapping
public ResponseEntity<List<Product>> list(
        @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
    return ResponseEntity.ok(adminService.loadProducts(cookie));
}
```

### 2. AdminService.java

**경로:** `repos/atdd-camping-kiosk/src/main/java/com/camping/kiosk/service/AdminService.java`

**변경 내용:** cookie 파라미터를 AdminClient로 전달

```java
public List<Product> loadProducts(String cookie) {
    return adminClient.getProducts(cookie);
}
```

### 3. AdminClient.java

**경로:** `repos/atdd-camping-kiosk/src/main/java/com/camping/kiosk/external/admin/AdminClient.java`

**변경 내용:** 전달받은 쿠키를 `Cookie` 헤더에 설정하여 Admin 서비스 호출

```java
public List<Product> getProducts(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    if (cookie != null && !cookie.isEmpty()) {
        headers.set(HttpHeaders.COOKIE, cookie);
    }
    ResponseEntity<Product[]> response = restTemplate.exchange(
            adminBaseUrl + "/admin/products",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Product[].class);
    Product[] body = response.getBody();
    return body != null ? List.of(body) : List.of();
}
```

### 4. KioskSteps.java (테스트 코드)

**경로:** `src/test/java/com/camping/tests/steps/KioskSteps.java`

**변경 내용:** `Cookie` 헤더에 `AUTH_TOKEN` 쿠키 포함하여 요청

```java
@만약("상품 목록을 조회한다")
public void 상품_목록을_조회한다() {
    response = given()
            .header("Cookie", "AUTH_TOKEN=" + context.authToken())
            .when()
            .get(context.serviceUrl("kiosk") + "/api/products");
}
```

## Admin 서비스 인증 방식

Admin 서비스의 `JwtAuthFilter`는 두 가지 인증 방식을 지원합니다:

1. **Authorization 헤더**: `Authorization: Bearer {token}`
2. **Cookie**: `Cookie: AUTH_TOKEN={token}`

이 구현에서는 Cookie 방식을 사용합니다.

## 테스트 시나리오

```gherkin
# product.feature
시나리오: 인증된 사용자가 상품 목록을 조회한다
  만약 관리자 계정으로 로그인되어 있다    # AdminSteps: Admin 로그인 후 토큰 저장
  만약 상품 목록을 조회한다              # KioskSteps: Cookie 헤더로 Kiosk API 호출
  그러면 상품 목록이 정상적으로 조회된다   # 응답 검증
```

## 참고 사항

- `AdminAuthClient`의 Lazy Initialization 로직(`cachedToken == null`일 때 `login()` 호출)이 제거된 상태에서 동작합니다.
- 클라이언트가 유효한 인증 쿠키를 전달하지 않으면 Admin 서비스에서 401 Unauthorized가 반환됩니다.