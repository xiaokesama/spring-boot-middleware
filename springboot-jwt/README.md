# springboot-jwt

JWT 令牌练习。端口 **8082**。当前是**未完成状态**（原始提交信息就是「jwt 没学完」）。

## 现状

- 引了 `spring-boot-starter-security` + `jjwt 0.11.5`（api / impl / jackson 三件套）
- 只有启动类，没有签发与校验逻辑

## 计划补的内容

| 场景 | 说明 |
|---|---|
| 签发 | `Jwts.builder()` 设置 subject / 过期时间 / 自定义 claim，HS256 签名 |
| 解析校验 | `Jwts.parserBuilder().setSigningKey(...)`，捕获 `ExpiredJwtException` 等 |
| 过滤器 | 从 `Authorization: Bearer` 取令牌，写入 `SecurityContext` |
| 无状态安全配置 | `SessionCreationPolicy.STATELESS` + 放行登录接口 |
| 刷新令牌 | accessToken 短、refreshToken 长，分开存储与吊销策略 |

## 已修的坑

**`javax.servlet-api` 已移除**：Spring Boot 3 全面迁移到 `jakarta.servlet`，
继续引 `javax.servlet` 会导致 `ClassNotFoundException` 或过滤器签名不匹配。

## 运行

```powershell
mvn -B spring-boot:run -pl springboot-jwt
```

引入 security 后所有路径默认需要认证，浏览器访问会跳到 `/login`（默认用户 `user`，
密码在启动日志里），这是正常现象。
