# springboot-httpclient

Apache HttpClient 练习。**无 Web 端口**，测试自带内嵌 HTTP 服务，不依赖外部接口。

## 场景

`src/test/java/.../HttpClientTest.java`：

| 场景 | 说明 |
|---|---|
| GET | 建连接 → 执行 → 读状态行与响应体 |
| POST JSON | `StringEntity` + `Content-Type: application/json` |

测试用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 在 **8011** 端口起了一个假服务，
所以 `mvn test` 不需要任何外部依赖（原始版本是打真实后端，跑不通就红）。

## 关键点

1. **必须关闭响应和客户端**：`CloseableHttpResponse` / `CloseableHttpClient`
   要用 try-with-resources，否则连接池泄漏。
2. **`EntityUtils.toString` 只能读一次**：`HttpEntity` 是流，读两次会得到空字符串。
3. **要打印的是响应体，不是请求体**：原代码 `System.out.println(EntityUtils.toString(entity))`
   里的 `entity` 其实是 `StringEntity`（请求），这是典型的复制粘贴 bug，已修正。
4. **生产用法是连接池 + 超时**：

```java
RequestConfig config = RequestConfig.custom()
        .setConnectTimeout(1000)
        .setSocketTimeout(3000)
        .build();
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(200);
cm.setDefaultMaxPerRoute(50);
```

5. **重试要区分幂等**：GET 可重试，POST 默认不重试（可能重复下单）。

## 测试

```powershell
mvn -B test -pl springboot-httpclient
```
