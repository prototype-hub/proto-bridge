# 数据库结构、初始化与旧库接入

## 当前启动职责

运行时使用 hsweb 5.0.2、EasyORM 4.2.2/R2DBC。`@EnableEasyormRepository("com.gaiys.proto.**.entity")` 注册本地实体，官方系统模块注册自己的实体；`easyorm.auto-ddl=true` 根据元数据同步缺失表、字段和索引。仅移除 `resources/db` 并不会禁用 EasyORM；此前不能自动建表，是因为应用明确配置了 `auto-ddl: false`。当前生产 resources 不再保存建表 SQL，Flyway、PostgreSQL JDBC 和 spring-jdbc 仅在测试依赖中保留。

连接只配置 R2DBC：`DATABASE_R2DBC_URL` 或 `DB_HOST/DB_PORT/DB_DATABASE`、`DB_USERNAME/DB_PASSWORD`。`DB_SCHEMA` 同时用于 R2DBC 与 `easyorm.default-schema`；Compose 沿用 `- "easyorm.auto-ddl=true"` 等实际属性路径。无需 `DATABASE_JDBC_URL`、`FLYWAY_ENABLED` 或 `spring.flyway.*`。数据库和 `DB_SCHEMA` 指定的 schema 须事先存在（默认 `public` 已存在）；EasyORM 创建表，不创建数据库或 schema。数据库账号需能访问目标 schema，并具有创建缺失结构、索引及修改描述的权限。`DATABASE_USER/DATABASE_PASSWORD` 是 application.yml 使用的兼容变量，默认分别引用 `DB_USERNAME/DB_PASSWORD`。

自动同步以实体当前表名为准，保留 `s_*` 和 `p_*`。自动 DDL 不负责识别旧表别名、推断字段重命名或转换历史数据；已有列类型、额外列与数据不因修改实体而自动删除或收窄。旧库存在结构差异时先在恢复副本审核，不能将新建同名业务表当作数据迁移。

结构同步完成后，描述同步器根据实体/字段的 `@Schema(description = "...")` 更新表和列注释，见 实体与字段的 `@Schema(description)` 注释约定。结构或初始化失败会使启动失败，不能通过吞异常继续提供服务。

## 菜单与用户初始化

初始化复用现有响应式 hsweb 服务：维护用户所需的角色维度及默认授权，按配置首次创建管理员，再初始化菜单。密码通过既有 PBKDF2 编码器处理，不在 SQL 中保存明文或固定哈希。资源权限定义继续由 hsweb 框架同步；组织、职位、字典及原型业务数据不导入演示种子。

已有用户、密码、自定义菜单和手工授权保留；重复启动不重新赋予已撤销的权限、不启用已禁用角色。初始化所需的 ADMIN/MEMBER 角色及权限属于用户体系前提。生产不需要额外初始化 SQL；若后续增加部署初始化脚本，其范围仅限菜单和用户及必要的角色授权数据，不把建表、原型项目或其他业务种子混入其中。

菜单初始化仅在 `s_menu` **全表没有任何记录**时导入 classpath 的 `menus/default-menus.json`。资源随 JAR 打包，运行时不读取工程根目录 `data.json`。生成资源保留 **2 个根、14 个菜单节点和 38 个按钮**；任何已有菜单（包括其他 owner 或禁用菜单）都会阻止重新导入。两个实例并发初始化时在 PostgreSQL 事务内锁表并再次判断，解析、校验或保存失败整体回滚。

需要重新生成资源时，先导出自己有权使用的菜单 JSON，并把下面的示例路径替换为实际文件（项目不依赖根目录历史 data.json）：

```bash
python3 tools/generate-menu-resource.py /path/to/exported-menu.json modules/system-manager/src/main/resources/menus/default-menus.json
```

生成器保留源菜单 ID、父子结构和授权规则，将旧 PrototypeHub 技术 code、组件、路由及三项权限引用规范化为当前命名。`name` 保存中文，`i18nMessages.name.{zh_CN,en_US,en}` 保存翻译，按钮沿用扁平语言映射；已有自定义名称、外部 URL 和翻译不全局替换。详见 当前 `PrototypeHub/prototype-hub` 命名约定。

## 安装身份、容量与创建回执

EasyORM 自动同步 `p_installation` 和 `p_creation_receipt`。应用在结构同步后初始化 `p_installation` 的单例记录，首次生成 UUID；后续启动及多个应用副本共用已有身份，不按进程、机器或 IP 重新生成。身份初始化不以存量是否超限作为启动条件，也不删除或改写已有项目、版本。

容量直接统计 `p_project` 与项目下的 `p_version`，不维护独立累计扣额计数器。默认最多 5 个项目、每项目 5 个逻辑版本；删除释放当前占用，同一逻辑版本更新仅增加修订，不新增逻辑版本。项目创建在事务内锁安装记录，版本创建在 ZIP 校验解压完成后通过短事务锁所属项目，再检查实际行数、插入资源及回执；同数据库的应用副本使用同一锁边界。历史超限数据继续保留，只有新增受限。

上传或替换的事务最终结果为 `UNKNOWN` 时，系统保留候选目录并记录错误；应先确认数据库中的文件归属再处理目录，不自动按回滚删除文件。

带 `Idempotency-Key` 的成功创建将摘要和资源标识写入 `p_creation_receipt`，与业务插入原子提交；回执不保存原始键或请求内容。删除资源不会删除对应回执，旧请求再次到达时返回冲突，避免误重建。不要把这两张表作为缓存清空，也不要单独重置安装身份或回执；常规数据库备份应包含它们，并与原型文件做同窗口联合备份。恢复后保留身份、资源关联和创建重试语义；接口及错误码见[API 说明](API.md)。

## 不可变修订与旧内容迁移

`p_version` 继续保存逻辑版本和 `current_revision_id`；`storage_path/entry_file` 是当前修订的兼容投影。`p_revision` 保存不可变文件路径、入口、上传时名称/说明、规范化清单 JSON/SHA-256、实际文件数/总字节和创建信息。`p_share`、`p_preview_session`、`p_creation_receipt` 增加 `revision_id`：分享和内部预览固定快照，创建回执重放原成功快照。数据库和原型文件必须联合保存全部历史；只备份当前 `p_version.storage_path` 会丢失历史和分享内容。

清单按规范化相对路径排序，记录文件 `path/size/sha256/mediaType`、入口和页面树。字节数与 hash 对应 HTML 检查脚本注入后的实际服务文件；清单本身不含修订 ID/创建时间，同一实际内容可生成相同摘要。它不是原始 ZIP 字节摘要，不能据此反推原导出包。页面树保留 `pageId`，页面身份由修订 ID 和 pageId 共同确定。

清单 JSON 最多 32 MiB；文件数独立受 `prototype-hub.storage.max-zip-files` 限制，目录枚举总节点（含根与隐式父目录）另受 `maxZipFiles * 8 + 1` 预算约束，避免迁移时扫描无界目录树。总字节按实际读取累计校验，包含注入脚本后的服务文件，不能用 ZIP 解压前的声明大小代替。超限应先检查副本及部署限制，不跳过清单建立过程。

EasyORM 只补齐上述表、字段、索引及描述，**启动时不自动将旧内容迁移为修订**。升级应停止旧节点和全部写入，联合备份数据库与所有文件，在独立恢复副本执行：

1. 核对 `tools/preflight-db.sql`、`tools/snapshot-db.sql` 和文件清单。对每个待迁移逻辑版本调用 `POST /prototype-hub/versions/{id}/revision-migration`，请求 `{"apply":false}`；仅有效 ADMIN + 版本 save 可执行。检查清单摘要、文件数量/字节数、待绑定旧分享和回执数，预检不写业务数据。
2. 请求 `{"apply":true}`；服务在锁内重验逻辑版本、旧路径/入口和当前指针，再原子插入修订、设置指针并绑定该版本尚无修订的旧分享和创建回执。缺文件、非法路径、并发变化或数据库失败应阻止迁移；修复副本后重新预检。
3. 对照迁移前后逻辑版本 ID、创建信息、分享凭据和文件 hash；审核允许变化的指针/绑定字段，验证分享、旧创建回执和历史预览。再次 apply 不新增修订、不改写既有绑定。
4. 原实现曾在更新时删除旧目录；这些内容不能恢复。迁移只固定当下仍可用内容，旧分享和旧回执绑定这一快照，不能宣称找回它们最初发布/创建的字节。无修订的旧内部会话拒绝授权，需登录后重新换取。确认副本后安排正式迁移和前后端配套切换，不能混跑旧写入节点。

新更新先完成文件校验和清单再进行短事务 CAS 切指针；失败保留原内容，成功也保留上一修订目录。历史增长不计入 5 个逻辑版本额度，磁盘容量和历史保留是独立运维问题。当前仅支持整体删除逻辑版本，不提供单修订回收/回滚或软删恢复；删除事务登记全部历史路径后撤销记录，后台每次最多处理 50 条清理任务。仍被逻辑版本或修订引用的路径保留文件及任务并退避重试，不能通过手工清理绕过引用保护。

存储核验先停写后导出完整引用：`psql -X -qAt -v ON_ERROR_STOP=1 -f tools/export-storage-manifest.sql > references.json`，再运行 `python3 tools/audit-storage.py --root /path/to/snapshot --manifest references.json`。新版导出包含历史修订和未迁移旧投影，并输出结构及关联完整性结果。旧数组或缺少修订表/列的 current-only 清单仅允许只读诊断；关联异常或不完整清单禁止 `--delete-unreferenced`。离线修复还必须显式提供 `--offline --confirm /path/to/snapshot`，仅在联合备份的隔离副本审查后使用；不要手改清单的完整性标志来绕过检查。

## 项目归属迁移与认领

EasyORM 补齐 `p_project.owner_id/access_epoch`、`p_project_member` 和 `p_preview_session` 的结构与描述，**不自动推断或回填旧项目 owner**。创建者 ID 保留为历史审计字段；新建项目在创建事务内绑定 owner，普通查询以当前 owner/成员为准。`p_project_member` 保存编辑者和只读成员，owner 由项目字段唯一表示。owner 字段为空的待认领项目仅有效 ADMIN 可见，不能作为共享项目向全员兜底；owner 停用时允许管理员重新认领，其他有效成员仍按原角色访问。

在停写并完成数据库与文件联合备份后，先对恢复副本执行显式迁移：

1. 用当前版本启动副本，调用 `POST /prototype-hub/projects/_ownership-migration`，请求 `{"apply":false}`；需要有效 ADMIN 和项目 save。检查响应 `scanned/assigned/pending/unchanged`，预检不写项目数据。
2. 核对项目创建者是否为有效账号、项目/版本/分享的关联和文件清单。请求 `{"apply":true}` 仅将 owner 和授权代次均未初始化、创建者仍有效的历史项目回填为 owner；已有 owner 保持不变。无法回填的项目初始化授权代次并继续待认领，后续重跑不再根据创建者自动授权。重复执行不会覆盖既有归属或重复改写代次。
3. 在项目认领入口或 `POST /prototype-hub/projects/{id}/claim` 提交 `{"userId":"有效用户标识"}`，由 ADMIN 为无可用 owner 的项目显式指派负责人。不能覆盖活跃 owner，也不能通过成员 PUT 把 editor/viewer 提升为 owner。
4. 比较升级前后项目/版本 ID、原创建信息、数量、关联及存储文件 hash；仅审核通过的归属字段允许发生预期改变。确认副本中 owner/成员可读写、非成员关联 ID 不可读、旧内部 key 及撤员凭证被阻断，再安排正式窗口。

删除用户时，在用户删除事务内撤销其成员绑定及内部预览会话，原 owner 置空并轮换授权代次，保留项目和文件供管理员显式认领。hsweb 会为同名重建账号复用用户 ID；新账号不能继承已删除账号的 owner、成员或预览能力，也不能靠再次执行归属迁移恢复旧权限。

内部预览的随机能力只以摘要持久化，绑定用户、项目、逻辑版本、不可变修订、项目授权代次与到期时间。成员或归属变更轮换代次；逻辑版本更新不改变旧会话绑定内容，逐资源读取重新检查当前权限；同一用户/版本最多保留 8 条，发放时清理过期和旧凭证。该表是短期授权数据，应随数据库备份保留；重新发放产生的变化应与 `p_project`/`p_project_member` 的稳定归属分别核对，不能忽略后两张表或单独重置代次。

旧 previewKey 列仍保留历史值，但当前运行入口不再接受它；公开分享 token/accessKey 不因这次成员迁移自动轮换。升级应停止旧后端节点并配套发布前端，避免旧节点继续接受长期凭证。回滚须先停写并在维护窗口从同一时点的数据库、文件和版本备份验证恢复；直接换回旧 JAR 可能重新开放旧预览 URL，不能视为安全回滚。

## 旧数据库副本接入

1. 暂停写入，保存 `pg_dump`、schema-only 导出和同窗口文件快照，在独立数据库中恢复副本。数据库、文件路径和分享凭据不得提交 Git。
2. 运行 `tools/preflight-db.sql`、`tools/snapshot-db.sql` 与文件清单核对真实表名、类型、nullable、主键、索引、重复能力密钥及 ID/关联摘要。脚本默认当前 `p_*`，核验 V15 前旧表时显式使用 `-v prototype_hub_table_prefix=protohub_`。
3. 若存在 `proto_*`/`protohub_*` 旧表名、旧权限前缀或不兼容字段，先审核并在副本显式转换；当前应用不自动执行历史 Flyway、不建立 baseline，也不从旧表猜测或覆盖新表数据。保留既有 Flyway history 作为原系统证据。
4. 保持 `storagePath`、`entryFile`、share token/accessKey 与文件目录一致；previewKey 历史列不删除，但停止授予访问。旧项目再按上节预检并显式迁移归属。系统用户密码转换独立审核；分享密码保留 BCrypt，不转换成用户密码格式。
5. 用当前应用在副本启动，验证自动同步、注释、菜单/角色/按钮、登录及已有原型分享；比较转换前后行数、ID/关联摘要和文件清单，再安排正式窗口。

初始化不是旧系统数据导入工具，组织、职位、字典和旧用户转换需另行验收。系统集成范围见 [接口说明](API.md)。

## 显式历史升级测试

V1–V19 的原文 SQL 仅保存在 `bootstrap/src/test/resources/test-db/migration/`，不打入运行 JAR。显式历史测试调用 `Flyway.configure().locations("classpath:test-db/migration")`；普通应用集成测试禁用 Flyway，直接验证 EasyORM 冷启动。历史夹具不等同于当前部署初始化脚本，也不授权启动时重放旧 DDL。

这些夹具覆盖旧系统/原型建表、V14 系统表名统一、V15 `p_*` 表名统一、V16/V17 默认中文名称、V18 PrototypeHub 技术引用转换和 V19 分享描述列等已实现历史契约。旧版本非空数据、索引、关系和冲突回滚的断言继续保留；不修改历史 SQL 来适配新的冷启动流程。

验证当前发行候选时，应对实际目标数据库副本检查结构、权限、初始化幂等与旧数据保留；历史通过数字不作为当前版本验收。
