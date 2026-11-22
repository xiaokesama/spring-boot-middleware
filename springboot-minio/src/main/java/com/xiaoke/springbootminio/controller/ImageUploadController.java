package com.xiaoke.springbootminio.controller;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 三个典型用法：上传、列目录、预签名 URL。
 *
 * <p>启动前置：本地起一个 MinIO（默认 9000 端口），并准备好桶。
 * 本仓库没有自动启动 MinIO，见模块 README。
 */
@RestController
@RequestMapping("/api")
public class ImageUploadController {

    private final MinioClient minioClient;

    @Value("${minio.bucket.name}")
    private String bucketName;

    public ImageUploadController(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /** 场景 1：上传对象（桶不存在时自动创建） */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            ensureBucket();
            String fileName = file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return ResponseEntity.ok("File uploaded successfully: " + fileName);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("File upload failed: " + e.getMessage());
        }
    }

    /** 场景 2：列出桶内对象 */
    @GetMapping("/list")
    public ResponseEntity<List<String>> list() {
        try {
            ensureBucket();
            List<String> names = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).recursive(true).build())) {
                names.add(result.get().objectName() + " (" + result.get().size() + " bytes)");
            }
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of(e.getMessage()));
        }
    }

    /**
     * 场景 3：预签名 URL。
     *
     * <p>客户端不经过后端、也不持有密钥，直接凭带签名的 URL 下载对象；
     * 有效期到了链接自动失效，适合做临时分享。
     */
    @GetMapping("/url")
    public ResponseEntity<String> presignedUrl(@RequestParam String object,
                                               @RequestParam(defaultValue = "60") int expireMinutes) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(object)
                    .expiry(expireMinutes, TimeUnit.MINUTES)
                    .build());
            return ResponseEntity.ok(url);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }
}
