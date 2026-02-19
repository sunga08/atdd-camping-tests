package com.camping.tests.steps;

import io.cucumber.java.ko.그러면;
import io.cucumber.java.ko.만약;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

public class KioskSteps {
    private final CommonContext context;
    private Response response;

    public KioskSteps(CommonContext context) {
        this.context = context;
    }

    @만약("상품 목록을 조회한다")
    public void 상품_목록을_조회한다() {
        response = given()
                .header("Cookie", "AUTH_TOKEN=" + context.authToken())
                .when()
                .get(context.serviceUrl("kiosk") + "/api/products");
    }

    @그러면("상품 목록이 정상적으로 조회된다")
    public void 상품_목록이_정상적으로_조회된다() {
        response.then()
                .statusCode(200)
                .body("$.size()", greaterThanOrEqualTo(1))
                .body("[0].id", notNullValue())
                .body("[0].name", notNullValue())
                .body("[0].price", notNullValue())
                .body("[0].stockQuantity", notNullValue())
                .body("[0].productType", notNullValue());
    }
}
