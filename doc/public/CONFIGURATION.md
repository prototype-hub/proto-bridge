# 配置分层与中间件

`bootstrap/src/main/resources/application.yml` 定义真实属性层级、固定限额和框架配置；`application-default.yml` 提供常用连接、日志、存储和登录/展示变量。资源目录中的 `application-local.yml.example` 是不含实际密码的本地模板。

当前只自动导入 classpath 的公共默认变量。本地配置应显式加载：

```bash
mkdir -p config
cp bootstrap/src/main/resources/application-local.yml.example config/application-local.yml
# 填写自己的连接、密码和存储路径后：
java -jar bootstrap/target/bootstrap-1.0.0.jar \
  --spring.config.additional-location=file:./config/application-local.yml
```

仅创建本地文件、激活旧 default/dev/local profile 或填写不存在的变量文件，不会自动改变加载机制。IDE 设后端根目录为工作目录并使用同一启动参数；其他目录使用绝对 `file:` 路径。环境变量/命令行可覆盖实际属性。`config/`、任意位置的实际 application-local.yml/.yaml 与 `.env` 不公开、不随发行包分发。

## 数据库与首次初始化

配置 `DB_HOST`、`DB_PORT`、`DB_DATABASE`、`DB_SCHEMA`、`DB_USERNAME`、`DB_PASSWORD`；完整连接串使用 `DATABASE_R2DBC_URL`。`DATABASE_USER/DATABASE_PASSWORD` 兼容变量默认引用 DB 用户/密码。数据库与 schema 需先存在，应用账号具备所需 DDL 权限；`easyorm.auto-ddl` 同步表、字段及索引，不代替数据迁移或数据库创建。

`ADMIN_USERNAME/ADMIN_USER_PASSWORD` 仅用于首次管理员创建，`ADMIN_PASSWORD` 兼容变量继续有效。密码必须符合当前校验，不预置通用初始密码；已有管理员不会因改启动变量自动改密。数据库与初始化边界见 [数据库指南](DATA.md)。

## Redis 与 Elasticsearch

单实例开发可关闭 Redis：`REDIS_ENABLED=false` 并配合 `HSWEB_CACHE_TYPE=caffeine`；关闭 ES 使用 `ES_ENABLED=false`。启用时填写各自连接与认证，部署覆盖使用 `proto.redis.enabled`、`spring.data.redis.*`、`hsweb.cache.type`、`proto.logging.elasticsearch.enabled`、`spring.elasticsearch.*` 等真实属性路径。

Redis 使用专用受信数据库、限制网络与写权限；多实例需要一致的会话/缓存配置。TLS 连接使用真实信任链与主机名校验，不跳过证书检查。ES 基本认证、API key 与自签 CA 通过现有日志属性配置；避免将实际 key 放到公开示例或命令日志。无认证的本机示例不适合直接用于外部服务器。

Docker Compose 使用 `.env.example` 指定的必要变量插值，不自动读取宿主机本地 YAML。保留 `- "属性路径=值"` 的覆盖方式；同栈容器使用服务名/内部端口，外部模式填写可达的地址和认证，详见 [运维指南](OPERATIONS.md)。

## 邮件验证客户端

标准 `spring.mail.*` 及SMTP/SMTPS传输属性集中在application.yml，默认host为空，不影响无邮件部署；保持application-default.yml中的已有变量分层。可用 `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`、`SMTP_FROM`，其他属性按主配置声明的变量或精确属性路径覆盖。默认SMTP587要求认证及STARTTLS，SMTPS需显式选择协议/端口；连接、读、写默认各五秒。发件凭据放环境或私密配置，不提交源码；仅loopback测试允许显式关闭认证/TLS。具体Origin、Cookie、本机身份和超时边界见[运维说明](OPERATIONS.md#邮箱身份与smtp)。

## 日志与公开展示

`logging.level` 是 logger 名称到级别的映射，`logging.level.io.r2dbc=WARN` 无需预先在 YAML 中声明同名字段。`logging.pattern.dateformat` 控制时间/时区；包级别、控制台/文件阈值与系统日志采集门槛需共同核对。运行时级别修改只影响当前进程，持久策略应改配置后部署。详见 [日志指南](LOGGING.md)。

FRONT_TITLE、FRONT_LOGO、FRONT_ICO、FRONT_BACKGROUND、主题、备案与版权等展示变量会经公开配置接口暴露给浏览器，不得填写秘密。图片填浏览器可访问的 URL，空值使用前端默认图形；自定义内容的授权由部署者确认。

验证码、登录加密和管理 CORS 参照当前 application-default.yml 的变量与 application.yml 对应层级；RSA 不代替 HTTPS。runtime 使用 opaque origin，CORS由后端最小范围控制，代理不能叠加全站通配头。

存储路径在 Linux 上区分大小写：当前默认配置为 `./data/protoHub`，本地配置示例与 Compose 显式设置为 `./data/protohub`。已有部署应保留实际生效配置和目录的大小写；示例不代表目录重命名或数据迁移，不要仅为统一文字而切换存储位置。
