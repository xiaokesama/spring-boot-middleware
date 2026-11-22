# springboot-basics

不依赖任何中间件的 Spring / Java 基础练习。**无 Web 端口**。

## 三个 Demo（直接跑 main 方法）

### 1. JDK 动态代理手写 AOP —— `com.xiaoke.aop.Test`

`ProxyFactory` 用 `Proxy.newProxyInstance` 生成代理对象，在方法调用前后插入切面逻辑。
这是 Spring AOP 的底层原理：**AOP 本质是代理**。

- `Aspect`：切面接口（before / after）
- `LoggingAspect`、`CustomLoggingAspect`：两个实现
- `UserService` / `UserServiceImpl`：被代理的目标
- 关键点：JDK 动态代理**只能代理接口**，代理类需要实现同样的接口；
  CGLIB 才能代理类（Spring Boot 2.x 起默认用 CGLIB）。

### 2. 自定义注解 + 反射 —— `com.xiaoke.annotation.AnnotationProcessor`

用 `Method#isAnnotationPresent` + `getAnnotation` 读取注解元数据。
关键点：注解必须 `@Retention(RUNTIME)` 才能被反射读到，`@Target(METHOD)` 限定位置。

### 3. ThreadLocal —— `com.xiaoke.threadLocal.ThreadLocalExample`

两个线程各自 `set` 互不影响，演示线程隔离。
关键点：线程池场景下 ThreadLocal 会**残留**，用完必须 `remove()`，否则内存泄漏 + 数据串号。

## 编译

```powershell
mvn -B package -pl springboot-basics
```

（原项目把这些类放在根目录 `src/` 下，而根 POM 的 `packaging=pom` 根本不会编译它们，
等于代码「放着但从不构建」。现在收进独立模块，可以正常编译。）
