# 部署与运维

根 README 提供本地启动、Docker 和 Nginx 示例。Compose 同栈模式使用容器服务名及内部端口；宿主机工具才使用映射端口。首次填入独立 PostgreSQL/Redis/管理员密码，已有数据库卷不会因改环境变量自动改密。

默认数据端口仅绑定 loopback；无认证的 ES 仅作本机开发示例。服务器部署应采用认证/TLS、适当网络隔离和最小端口暴露。若接入外部中间件，覆盖实际地址/认证并用 `docker compose up -d --no-deps proto` 启动应用，避免误启动本地示例服务。

只将反向代理入口暴露给用户；原型 runtime 的 CORS 由后端控制，代理不应增加重复 Access-Control-Allow-Origin。使用 HTTPS，保留 Vue history 回退和正确的 `/api` 剥离语义。

应用、系统图片、原型、上传临时目录需可写并按当前存储配置持久化。备份同窗口数据库、文件、配置和镜像版本；GC日志/堆转储可能含敏感内容，限制访问与保留周期，不公开上传。恢复或升级先在副本核验登录、权限、分享、文件关联与 readiness。

应用自动 DDL 不提供破坏性回滚，也不创建数据库/schema。不能只回退 JAR 或删除数据重建代替恢复；停写后恢复同一时间点的数据库与文件，核验后再开放流量。

日志只收集白名单元数据并在落盘前脱敏；网关/CDN/错误日志同样不能记录完整分享URL、认证头或密码。安全事件按 [SECURITY](../../SECURITY.md) 私密报告。

见 [配置](CONFIGURATION.md)、[数据库](DATA.md)、[日志](LOGGING.md) 和 [发布指南](RELEASE.md)。

原型存储以实际 `prototype-hub.storage.root` 为准。Linux 路径区分大小写，默认配置的 `protoHub` 与本地/Compose 示例的 `protohub` 并非同一目录；已有部署保持原目录和配置，不将示例路径视为迁移指令。
