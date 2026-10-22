# 开发与验证

使用 JDK 21 与项目 Maven Wrapper。模块职责遵循根 README；Java 包根为 `com.gaiys.proto`。实体放在 entity，服务与控制器沿模块现有结构组织，不按小功能过度拆分。新增框架适配复用公开 API/扩展点，不复制私有源码或使用反射/Unsafe绕过访问限制。

响应式请求保持 Mono/Flux 链路，阻塞文件操作放到已有受控调度边界；权限、数据范围和事务由后端执行。用户可见错误复用已有 i18n key；复杂安全/并发/兼容分支用简短注释说明原因，不记录令牌、密码或完整用户载荷。

普通持久化使用 EasyORM 的 `createQuery/createUpdate/createDelete`；关联查询使用 `QueryHelper`，JSON 属性条件使用官方 JSON term。条件、排序、分页和项目范围交给数据库，不拼接 SQL 或先读取全部数据再过滤。空 IN、空 NOT IN、缺失 JSON 属性和 SQL NULL 的行为须显式保留，不能让框架忽略空条件后扩大访问范围。幂等初始化使用官方 `upsert(...).doNothingOnConflict(true)`，不在同一失败事务中捕获唯一键错误后继续执行。

并发写入使用同事务 `forUpdate()`，遵守稳定的用户 ID、项目、版本锁顺序；外部 I/O 不扩大锁事务。读取历史记录时按主键游标每批最多 100 条，完整消费并释放读取后再批量写入；跨页状态仅保留最小游标，不保留已消费的实体页；后台清理每次最多 50 条。单连接池、跨实例容量竞争、取消及事务回滚都是应保留的验证边界。DDL 中官方框架尚不能完整表达的注释和部分唯一索引由结构化 jOOQ API 生成，禁止使用 plain SQL 模板替代字符串拼接。支持范围与升级限制见[数据库说明](DATA.md)。

角色属性在 PostgreSQL 显式注册官方 JSON 条件特性；未适配方言必须拒绝该条件，避免静默忽略后扩大结果。所有权迁移、授权纪元轮换和删除账号时的所有权清除是内部授权维护，通过框架提供的上下文标记保留原创建、修改审计字段；用户主动编辑仍按正常审计规则处理。

```bash
./mvnw -B -ntp clean verify
python3 tools/verify-identifiers.py
```

集成测试需要 Docker 与可用的测试中间件环境，不使用生产数据库。确需格式化时运行 `./mvnw spotless:apply` 后审阅差异。选择性回归须写明实际范围，不能把单类通过描述成全量通过。

独立进程验证使用实际打包 JAR，需要 macOS/Linux、JDK 21、Python 3.9 及以上版本与可用的 Docker CLI：

```bash
python3 tools/verify-runtime.py --jar bootstrap/target/bootstrap-1.0.0.jar --report /tmp/proto-runtime-report.json
```

工具自行创建随机 PostgreSQL 容器、测试数据库和私有临时目录，不接收已有数据库连接。它验证登录、上传、密码分享、完整进程重启、多进程容量竞争，以及停写后数据库、原型文件和系统图片的联合恢复；分享验证包含固定/跟随快照、启停后的旧 HTML/资源/页面树拒绝、密码替换与去除、过期配置版本、轮换链接和永久撤销。品牌图片及本人头像经实际文件接口上传，恢复后核对数据库引用、匿名下载与文件摘要。该演练使用本地共享文件根，不代表其他文件服务或生产配置已验证；结束时只清理自身资源。默认使用 `postgres:16-alpine`，可用 `--postgres-image` 指定拟验证的镜像，结果仅覆盖实际执行的数据库版本；`--java` 可指定 JDK 可执行文件的绝对路径，默认从 `JAVA_HOME` 或 `PATH` 查找。

需要联合前端时，先在配套前端目录安装依赖并完成生产构建，再增加 `--frontend-dir /path/to/proto-bridge-ui`。工具向真实浏览器用例提供本次隔离实例的临时连接和样本，不加载用户本地应用配置。报告保留 JAR 摘要、样本摘要及检查结果，密码、令牌和分享密钥不进入报告；失败时生成权限受限的诊断日志，临时清理失败时记录待处理目录。报告和诊断均只在本地排查，不将运行数据或备份加入发行包。该原创多页 HTML 样本用于验证运行链路，不代表真实 Axure 导出包或生产环境兼容性。

工具自身的隔离、报告和清理边界可单独验证；设置已安装前端目录后，额外运行真实 Playwright CLI 超时/中断探针：

```bash
python3 -m unittest discover -s tools -p 'test_verify_runtime.py'
PROTO_RUNTIME_PLAYWRIGHT_DIR=/path/to/proto-bridge-ui python3 -m unittest discover -s tools -p 'test_verify_runtime.py'
```

未设置该变量时两项真实 CLI 探针会明确跳过，不能计为已验证。探针使用自产 HTTP 服务验证清理，不代表实际平台浏览器业务测试通过。

正式分发使用 `bash tools/verify-open-source.sh` 执行完整发行门禁，包含上述工具自身的基础安全测试；设置 `PROTO_RUNTIME_PLAYWRIGHT_DIR` 时同时执行真实 CLI 生命周期探针。实际 JAR 与浏览器业务演练仍需单独运行。release-audit 与发行检查详见 [发布指南](RELEASE.md)。版本入口和组件核验见 [依赖基线](../DEPENDENCY_BASELINE.md)；测试报告生成在 target 内，不纳入公开源码导出。

本地实际配置从资源示例复制到 `config/application-local.yml` 并显式通过启动参数加载；该目录不公开。改名不移动既有原型存储目录，避免破坏分享与版本关联。
