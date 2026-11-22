# springboot-minio

MinIO 对象存储练习。端口 **8081**，依赖 MinIO `9000`。

## 场景

| 场景 | 接口 | 说明 |
|---|---|---|
| 上传对象 | `POST /api/upload`（multipart，字段 `file`） | 桶不存在时自动创建 |
| 列目录 | `GET /api/list` | 递归列出对象与大小 |
| 预签名 URL | `GET /api/url?object=xxx.png&expireMinutes=60` | 客户端凭签名 URL 直连下载，不用经过后端 |
| 上传页面 | <http://localhost:8081/> | 一个最简单的上传表单 |

## 启动前置

本仓库**没有**自动安装 MinIO。需要自己起一个：

```bash
# WSL2 里用单二进制启动
wget https://dl.min.io/server/minio/release/linux-amd64/minio -O /usr/local/bin/minio
chmod +x /usr/local/bin/minio
MINIO_ROOT_USER=minioadmin MINIO_ROOT_PASSWORD=minioadmin minio server /var/lib/minio --console-address :9001
```

> 注意 MinIO 默认占 **9000**，与 ClickHouse 的 Native 端口冲突。
> 两者同时使用时，ClickHouse 走 HTTP 8123 即可（本项目就是这么做的）。

## 配置

密钥不要写进仓库，用环境变量：

```powershell
$env:MINIO_ACCESS_KEY="你的accessKey"
$env:MINIO_SECRET_KEY="你的secretKey"
mvn -B spring-boot:run -pl springboot-minio
```

未设置时回退到 MinIO 默认的 `minioadmin/minioadmin`。

## 关键点

1. **`bucketExists` + `makeBucket` 是幂等的**，适合放启动时或首次上传时调用。
2. **预签名 URL 用的是 endpoint 地址**（本项目是 `localhost:9000`），
   如果服务部署在内网，客户端拿到的是内网地址，需要配置外网 endpoint。
3. **`PutObjectArgs` 的最后两个参数是 `partSize` 和 `objectSize`**：
   已知大小传 `-1` 表示由客户端自动决定分片大小。
4. **MinIO 的 SDK 异常体系**：`ErrorResponseException` 带 code/message，
   排查权限、桶不存在这些问题时先看 code。

## 测试

```powershell
mvn -B test -pl springboot-minio
```

只有 `contextLoads`：`MinioClient` 是懒加载的，不连服务也能构建上下文。
真实的桶操作需要 MinIO 起来后手工验证。
