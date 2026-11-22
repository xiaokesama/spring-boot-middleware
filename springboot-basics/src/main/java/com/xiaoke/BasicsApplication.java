package com.xiaoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 基础能力 Demo 的启动类。
 *
 * <p>本模块不是 Web 应用，只用来运行 com.xiaoke.aop / com.xiaoke.annotation /
 * com.xiaoke.threadLocal 下的纯 Java 示例：
 * <ul>
 *   <li>{@code com.xiaoke.aop.Test} —— JDK 动态代理手写 AOP</li>
 *   <li>{@code com.xiaoke.annotation.AnnotationProcessor} —— 自定义注解 + 反射</li>
 *   <li>{@code com.xiaoke.threadLocal.ThreadLocalExample} —— ThreadLocal 隔离</li>
 * </ul>
 * 直接运行这三个类的 main 方法即可，无需启动 Spring 容器。
 */
@SpringBootApplication(scanBasePackages = "com.xiaoke")
public class BasicsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicsApplication.class, args);
    }
}
