package com.xiaoke.springboothttpclient;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apache HttpClient 的两个基本用法。
 *
 * <p>测试自带一个 JDK 内置的 {@link HttpServer}（监听 8011），不依赖任何外部服务，
 * 这样 {@code mvn test} 在任何机器上都能跑（原版本是打真实后端，跑不通就红）。
 */
@SpringBootTest
class HttpClientTest {

    private static final int PORT = 8011;

    private static HttpServer server;

    @BeforeAll
    static void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // 根路径：返回一个 JSON
        server.createContext("/", exchange -> respond(exchange, 200, "{\"ok\":true}"));

        // 注册接口：把请求体原样回显，便于断言 POST 的 JSON 是否发出去了
        server.createContext("/api/register", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "{\"code\":0,\"msg\":\"registered\",\"echo\":" + body + "}");
        });

        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    @AfterAll
    static void stopMockServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("GET：读状态码与响应体")
    void testGet() throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet("http://127.0.0.1:" + PORT + "/");

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                assertEquals(200, response.getStatusLine().getStatusCode());

                HttpEntity entity = response.getEntity();
                // EntityUtils 只能读一次：HttpEntity 是流，读第二遍会得到空串
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                assertTrue(body.contains("\"ok\":true"), "响应体: " + body);
            }
        }
    }

    @Test
    @DisplayName("POST JSON：设置 Content-Type 并校验回显")
    void testPost() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("username", "test");
        payload.put("password", "123456");

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost("http://127.0.0.1:" + PORT + "/api/register");
            httpPost.setEntity(new StringEntity(payload.toString(), StandardCharsets.UTF_8));
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Accept", "application/json");

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                assertEquals(200, response.getStatusLine().getStatusCode());

                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                assertTrue(body.contains("registered"));
                assertTrue(body.contains("test"), "服务端应收到 username: " + body);
            }
        }
    }
}
