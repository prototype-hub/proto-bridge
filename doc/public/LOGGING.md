# 控制台与日志管理

控制台使用 `classpath:logback-spring.xml` 与 Spring Boot 文本布局，包含带时区时间、级别、PID、应用名、线程、logger 与消息，便于本地定位启动和运行问题。`logging.pattern.dateformat` 控制时间格式，包级日志通过 `logging.level.<logger>` 调整。

常用配置入口为 `LOG_LEVEL_ROOT/APP/HSWEB/SPRING/SQL/REDIS/ES` 及实际 `logging.level` 属性；控制台、文件阈值和系统日志采集门槛分别控制不同出口。只把应用包改为 DEBUG 不意味着所有 DEBUG 都会入文件或进入远程存储。

本地结构化文件按 `logging.file.name` 与滚动策略保留，异步队列有界；远程 ES 是可选日志存储。远端失败、队列饱和与保留策略须按实际配置和监控检查，不能把内存队列当可靠消息存储。

日志管理接口需要相应日志权限，普通用户不自动获得全局查询或级别管理：

- `POST /logger/system/_query`：系统日志查询。
- `POST /logger/access/_query`：访问日志查询。
- `GET /logger/level`、`GET /logger/level/{name}`：读取级别。
- `PUT /logger/level/{name}`：修改当前进程级别；传 `level: null` 恢复继承。

应用在各日志出口执行敏感信息脱敏，不应记录认证头、密码、有效分享/预览链接或完整私有载荷。实际网关、CDN、访问日志、错误日志和堆转储也需同样保护；不要仅依赖应用编码器来保护外围日志。

排障时核对运行配置、有效包级别、出口阈值、采集开关和 ES 可用性，提供脱敏错误、请求 ID 与复现条件。DEBUG/SQL日志可能增加开销和数据暴露，应限制范围与持续时间。测试结果以当前候选版本实际执行记录为准，不引用历史“全部通过”作为当前保证。

见 [配置](CONFIGURATION.md)、[运维](OPERATIONS.md) 和 [安全报告](../../SECURITY.md)。
