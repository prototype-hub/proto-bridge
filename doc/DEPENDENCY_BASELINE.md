# 依赖基线与核验

版本入口为根 `pom.xml` 的 BOM、dependencyManagement 和 Enforcer 约束；实际完整依赖以当前构建生成清单为准，不以历史审计数字代替。主要基线为 Java 21、Spring Boot 3.5.16、Spring Framework 6.2.19、hsweb 5.0.2/EasyORM 4.2.2 与响应式 PostgreSQL。升级应保持框架家族版本一致，并验证实际启动和接口边界。

Log4j 通过 2.25.5 BOM 同步 API/bridge，OpenTelemetry 通过 1.62.0 BOM 同步 API/context，Commons Lang 显式使用 3.18.0。本仓导入 Boot BOM 而非继承 Boot parent，独立定义属性不能覆盖已经导入的 BOM；需要覆盖的家族 BOM 放在 Boot BOM 之前，最终核对实际依赖树。hsweb 文件服务保留官方 SPI，由自有品牌图片存储实现接入，不通过移除依赖掩盖漏洞。兼容 patch 升级不代表 Spring、容器或所有第三方告警已清零；Spring 7 涉及 Boot/hsweb 协同迁移，不强制跨大版本覆盖。

hsweb/EasyORM 通过公开 Maven 依赖复用。仅使用公开 API 与扩展点，不复制私有模块或通过反射、Unsafe/字节码绕过可见性。Fastjson 1.x 兼容路径保持 SafeMode，升级框架时需验证整个序列化契约。

业务查询、写入、关联过滤、分页与行锁使用 hsweb/EasyORM。Boot BOM 管理的 `org.jooq:jooq:3.19.35` 仅补齐结构化 DDL 渲染，包括安全注释与非空菜单 code 的部分唯一索引；不引入 JDBC 执行路径或另一套业务仓储。实际连接与验证目标仍为 PostgreSQL，复用方言 API 不等于其他数据库已经适配通过。

当前 Maven 依赖没有 JetLinks/SNAPSHOT 坐标；许可证元数据仍需与完整文本、文件范围和实际分发义务一起审阅。多许可组件不应仅凭名称判为不兼容；测试、构建、运行库和镜像分别处理。

正式发行执行 `./mvnw -B -ntp clean verify -Prelease-audit`，按公共发布指南复核生成的第三方清单、SBOM、发行包内容和安全检查结果。离线缓存验证不等同于空缓存联网构建，旧漏洞扫描不证明当前依赖或镜像无漏洞。Redis/Elasticsearch 服务端及 JDK/镜像不属于 Maven 依赖许可证闭包。

详见 [开发指南](public/DEVELOPMENT.md)、[发布指南](public/RELEASE.md) 与 [来源说明](SOURCE_PROVENANCE.md)。
