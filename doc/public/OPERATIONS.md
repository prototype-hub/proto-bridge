# 部署与运维

根 README 提供本地启动、Docker 和 Nginx 示例。Compose 同栈模式使用容器服务名及内部端口；宿主机工具才使用映射端口。首次填入独立 PostgreSQL/Redis/管理员密码，已有数据库卷不会因改环境变量自动改密。

默认数据端口仅绑定 loopback；无认证的 ES 仅作本机开发示例。服务器部署应采用认证/TLS、适当网络隔离和最小端口暴露。若接入外部中间件，覆盖实际地址/认证并用 `docker compose up -d --no-deps proto` 启动应用，避免误启动本地示例服务。

只将反向代理入口暴露给用户；原型 runtime 的 CORS 由后端控制，代理不应增加重复 Access-Control-Allow-Origin。使用 HTTPS，保留 Vue history 回退和正确的 `/api` 剥离语义。

应用、系统图片、原型、上传临时目录需可写并按当前存储配置持久化。备份同窗口数据库、文件、配置和镜像版本；GC日志/堆转储可能含敏感内容，限制访问与保留周期，不公开上传。恢复或升级先在副本核验登录、权限、分享、文件关联与 readiness。

删除资源保留固定 30 × 24 小时，到期即不可从回收站恢复；数据库清理及磁盘删除异步执行，失败会留在持久化队列退避重试。升级时原有已到期墓碑立即符合清理条件。升级前停止全部旧写入及清理节点，先完成同窗口数据库和文件备份，并在隔离副本验证；不能混跑旧无限期保留节点和新清理节点。

应用自动 DDL 不提供破坏性回滚，也不创建数据库/schema。不能只回退 JAR 或删除数据重建代替恢复；停写后恢复同一时间点的数据库与文件，核验后再开放流量。

恢复演练必须停止所有写入副本及可能修改文件的后台进程，再取得数据库与文件副本。恢复到独立数据库和新文件根，核对安装身份、逻辑版本/全部修订/当前指针、分享与创建回执固定的修订、项目与版本的删除状态、预览文件与内容摘要；不能只比较行数。保留 `p_installation`、尚存的 `p_creation_receipt` 和 `p_file_cleanup`，不要因演练重置部署身份或丢弃已提交的文件清理任务。软删仍须备份所有持久化行、成员谱系及全部修订文件，不能仅按 30 天期限从备份清单排除数据。恢复后启动前应严格比对数据库、队列及文件摘要；启动后已到期记录与待办任务会继续清理，只能按本次明确验证的到期对象核对预期差集，不可忽略其他变化。活动占用应一致；仍保留资源的旧创建键保持冲突，永久清理资源及回执后原键去重终止，新的请求只能经当前权限与容量产生新 ID，不能复活旧 ID 或旧授权。

分享恢复还要核对 fixed/latest 模式、固定目标、revoked 和 configVersion，以及启停、密码和已轮换的密钥状态。latest 分享的空 revisionId 是合法策略，不能按待迁移 fixed 分享自动回填；预检脚本会分别计数，快照脚本输出不含明文凭据的完整分享摘要。恢复副本应验证永久撤销的分享和编辑前的旧凭据仍被拒绝，不能只验证新链接可打开。

可用 [独立进程验证工具](../../tools/verify-runtime.py) 演练隔离测试数据的重启、双进程和联合恢复，调用方式见[开发指南](DEVELOPMENT.md)。演练包含原型更新后的历史/当前修订及其分享和创建回执，另包含已删除的两次修订版本、随后删除的父项目与空项目、保留成员及旧凭据；恢复后核验文件 hash、释放后补位的新 ID、创建重放冲突以及管理/公开入口和旧凭据仍拒绝。另有独立的同 ID 逐项恢复组，与保留墓碑组一起备份；验证父子分别恢复、原创建键不重复扣额、旧凭据保持失效、新预览和分享可用，以及两进程新建/恢复争抢最后名额。独立到期组验证自动清理完整数据库图与文件、旧键去重结束后仅新建 ID、旧凭据仍拒绝；停写窗口另登记仅属于本次演练的已提交待办目录与任务，验证队列及文件共同备份、重启和新库新文件根恢复后的继续处理，只允许该组文件的精确预期差集。覆盖数据库、全部历史原型及本次上传的品牌图片和头像，恢复后核对图片引用与匿名下载内容。它不读取现有库或生产备份，也不替代真实部署中全部系统图片、配置、密钥和完整文件卷的备份策略；实际恢复仍需按原部署配置在副本核验。

升级软删版本前必须停止全部旧写入/清理节点，在副本核对 nullable `deleted_time`、活动占用及保留引用，再配套发布前后端。禁止混跑忽略软删标志或仍立即物理删除的旧版本；历史已经清理的内容无法从增列恢复。回收站恢复使用当前权限和容量，先恢复项目，再按需逐项恢复其版本，保留原 ID 与全部修订；恢复失败先检查30天期限、授权、父项目状态和名额，不手改删除标记。旧内部会话与旧分享仍无效，需重新申请预览或新建分享。未满30天的保留文件仍占用磁盘，到期文件由持久化队列在无引用后异步清理；不将人工删除目录当作释放业务额度。

## 邮箱身份与SMTP

邮箱身份默认关闭，由分享管理员逐条开启。无SMTP时原型预览仍可用，身份验证显示不可用，不自动跳过验证；邮件仅发必要验证码，不提供邀请或业务提醒。

SMTP客户端配置集中声明在 `bootstrap/src/main/resources/application.yml`：`spring.mail.host` 默认为空，未配置时不启用发送，也不在启动时连接服务商。填写标准 `spring.mail.host/port/username/password`，默认SMTP端口587、认证及STARTTLS启用且要求升级TLS，服务端身份校验开启；按服务商要求覆盖真实属性并使用有效TLS信任链。SMTPS需明确设置protocol为smtps及端口465，使用同一properties中的mail.smtps配置。发件地址使用 `spring.mail.properties.mail.smtp.from`，非空配置优先，空值取非空username，仍为空才取本机身份prototype-hub@localhost；实际对外服务应显式配置服务商许可的发件地址。连接、读、写默认各五秒，可用同级 `mail.smtp.connectiontimeout/timeout/writetimeout` 覆盖；这些是socket超时，不是DNS解析或第三方调用的整体耗时上限。SMTP/SMTPS的 `mail.smtp.localhost/mail.smtps.localhost` 缺省为localhost，避免为EHLO反查本机DNS；服务商要求FQDN时应在 `spring.mail.properties` 中显式填写并保留有效DNS配置。SMTP debug必须关闭。不确定发送不自动重试，失败仍占限额，不能标为已验证。

会话的 `spring.mail.properties.mail.from` 缺省复用上述发件地址，让MIME Message-ID直接取得发件域，避免生成邮件标识时反查本机DNS；显式填写的值会保留。该属性与SMTP的EHLO主机名职责不同，真实邮件服务仍需正确的服务端DNS、TLS及认证配置。

HTTPS终止或开发代理改写Host时，显式设置 `prototype-hub.review.trusted-origin=https://your-platform.example`；本地Vite通常为 `http://localhost:5173`，以浏览器实际地址为准，localhost与127.0.0.1不同。该值只能是单一完整HTTP/HTTPS origin，不含路径、查询、片段或账号；设置后仅接受它，不同时放开后台地址。缺省严格比较请求URI，不为此新增任意Forwarded信任或凭据CORS。

`prototype-hub.review.cookie-secure` 默认true，仅本机HTTP测试设false。生产使用HTTPS；Cookie固定Path要求保留现有 `/api` 公开前缀及代理剥离语义，不能改为根路径解决代理问题。Cookie只发往review子路径，runtime与iframe不携带访客身份。

原生环境变量为 `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`、`PROTOTYPEHUB_REVIEW_TRUSTEDORIGIN`、`PROTOTYPEHUB_REVIEW_COOKIESECURE`，也可沿Compose现有格式填写 `- "prototype-hub.review.trusted-origin=https://your-platform.example"`。主配置同时声明SMTP/SMTPS的认证、STARTTLS/SSL、服务端身份校验、连接/读/写超时及EHLO身份，发件地址可用 `SMTP_FROM`；TLS等map参数建议使用私有YAML或精确命令行属性，不提交真实密码，完整可用项以application.yml为准。模板见 `application-local.yml.example`。只有隔离的loopback测试接收端显式关闭认证/TLS，不沿用到生产。

备份全部 `p_review_*` 与分享配置及密钥，恢复副本核验未过期身份与旧撤销状态。自动化只用隔离本地SMTP和 `example.invalid` 夹具，不向真实客户发送测试邮件。

项目详情的访客管理沿分享查询/保存动作与项目SHARE权限，列表仅显示脱敏邮箱。停用立即阻断旧身份并删除旧会话/验证码，重新启用要求新OTP；不改变分享本身的匿名预览策略。更新服务前停止忽略guest状态的旧后端节点，配套发布前后端，避免混跑使停用失效；备份/恢复须保留enabled/config_version。旧版本JAR会忽略这些状态，不能直接换回作为安全回滚。

当前通知传输仍位于原型模块，仅提供身份验证用途。邮件与短信传输后续会抽离为独立通知模块，复用同一NotificationGateway与配置；短信、通知管理界面和业务投递尚未实现。基础OTP保持独立可用，业务通知仍需各自的权限与用途检查，不把任意业务内容伪装验证码。

日志只收集白名单元数据并在落盘前脱敏；网关/CDN/错误日志同样不能记录完整分享URL、认证头或密码，也不得记录邮箱请求体、完整邮箱、验证码、Cookie或CSRF。安全事件按 [SECURITY](../../SECURITY.md) 私密报告。

见 [配置](CONFIGURATION.md)、[数据库](DATA.md)、[日志](LOGGING.md) 和 [发布指南](RELEASE.md)。

原型存储以实际 `prototype-hub.storage.root` 为准。Linux 路径区分大小写，默认配置的 `protoHub` 与本地/Compose 示例的 `protohub` 并非同一目录；已有部署保持原目录和配置，不将示例路径视为迁移指令。
