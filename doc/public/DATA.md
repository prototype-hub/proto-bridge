# 数据库结构、初始化与旧库接入

## 当前启动职责

运行时使用 hsweb 5.0.2、EasyORM 4.2.2/R2DBC。`@EnableEasyormRepository("com.gaiys.proto.**.entity")` 注册本地实体，官方系统模块注册自己的实体；`easyorm.auto-ddl=true` 根据元数据同步缺失表、字段和索引。仅移除 `resources/db` 并不会禁用 EasyORM；此前不能自动建表，是因为应用明确配置了 `auto-ddl: false`。当前生产 resources 不再保存建表 SQL，Flyway、PostgreSQL JDBC 和 spring-jdbc 仅在测试依赖中保留。

连接只配置 R2DBC：`DATABASE_R2DBC_URL` 或 `DB_HOST/DB_PORT/DB_DATABASE`、`DB_USERNAME/DB_PASSWORD`。`DB_SCHEMA` 同时用于 R2DBC 与 `easyorm.default-schema`；Compose 沿用 `- "easyorm.auto-ddl=true"` 等实际属性路径。无需 `DATABASE_JDBC_URL`、`FLYWAY_ENABLED` 或 `spring.flyway.*`。数据库和 `DB_SCHEMA` 指定的 schema 须事先存在（默认 `public` 已存在）；EasyORM 创建表，不创建数据库或 schema。数据库账号需能访问目标 schema，并具有创建缺失结构、索引及修改描述的权限。`DATABASE_USER/DATABASE_PASSWORD` 是 application.yml 使用的兼容变量，默认分别引用 `DB_USERNAME/DB_PASSWORD`。

自动同步以实体当前表名为准，保留 `s_*` 和 `p_*`。自动 DDL 不负责识别旧表别名、推断字段重命名或转换历史数据；已有列类型、额外列与数据不因修改实体而自动删除或收窄。旧库存在结构差异时先在恢复副本审核，不能将新建同名业务表当作数据迁移。

结构同步完成后，描述同步器根据实体/字段的 `@Schema(description = "...")` 更新表和列注释，见 实体与字段的 `@Schema(description)` 注释约定。结构或初始化失败会使启动失败，不能通过吞异常继续提供服务。

业务持久化、关联过滤、分页、JSON 属性条件和行锁复用 EasyORM 的公开 API。框架当前未完整提供安全表注释更新及部分唯一索引元数据；这些结构操作通过 jOOQ 的结构化 DDL API 按实际方言生成，再沿用 R2DBC 执行，不自行拼接 SQL。已有列、额外列和同名索引保持增量同步策略，描述缺失不清除已有注释；声明存在但实际缺表/列时失败并回滚描述更新。当前受支持并实际验证的数据库为 PostgreSQL，运维 SQL 也明确面向 PostgreSQL；切换数据库仍须核对驱动、JSON、锁、NULL/唯一索引及 DDL 并进行完整回归，不能只替换连接 URL。

安全描述渲染器仅接受 PostgreSQL/H2 方言；PostgreSQL 字符串补固定 `E` 标记并使用 jOOQ 官方反斜线转义，不依赖连接会话设置。部分唯一索引和角色 JSON 条件目前仅适配 PostgreSQL，未适配方言明确拒绝；H2 描述渲染能力不表示整个平台已支持 H2。

冷建表和增列仍委托 EasyORM 的结构生成器，实体表名和列名沿用当前常规标识符规范。描述文本中的单引号、反斜线和中文会安全保留；已经存在且标识符带引号的表可同步描述，但官方 CREATE 对标识符内部含引号的情况不提供完整转义保证，不能据此宣称任意表名都可自动创建。

## 菜单与用户初始化

初始化复用现有响应式 hsweb 服务：维护用户所需的角色维度及默认授权，按配置首次创建管理员，再初始化菜单。密码通过既有 PBKDF2 编码器处理，不在 SQL 中保存明文或固定哈希。资源权限定义继续由 hsweb 框架同步；组织、职位、字典及原型业务数据不导入演示种子。

已有用户、密码、自定义菜单和手工授权保留；重复启动不重新赋予已撤销的权限、不启用已禁用角色。初始化所需的 ADMIN/MEMBER 角色及权限属于用户体系前提。生产不需要额外初始化 SQL；若后续增加部署初始化脚本，其范围仅限菜单和用户及必要的角色授权数据，不把建表、原型项目或其他业务种子混入其中。

菜单全量初始化仅在 `s_menu` **全表没有任何记录**时导入 classpath 的 `menus/default-menus.json`。资源随 JAR 打包，运行时不读取工程根目录 `data.json`。生成资源保留 **2 个根、15 个菜单节点和 42 个按钮**；任何已有菜单（包括其他 owner 或禁用菜单）都会阻止全量重新导入。多个应用实例先通过官方 upsert 确保角色维度行存在，在同一事务锁住该行后再次判断空表；解析、校验或保存失败整体回滚。该锁用于配合本版本的应用启动初始化，不替代部署期间对外部写库工具的停写管理。

回收站入口是针对旧产品树的单项补充：启动时只有内置 `proto` 原型管理父节点仍启用、且没有相同标识、组件编码或路由的菜单时，才新增 `prototype-hub/Recycle`（`/prototype-hub/recycle-bin`）。已有自定义或禁用入口保持原样，不删除/替换菜单，不自动给角色追加授权。多个实例的补充沿用同一初始化事务锁，保存失败可在下次启动重试。已安装的旧回收站入口也不会自动追加版本权限或按钮，需管理员按下节审核更新。

### 手动配置统一回收站入口

前端在同一回收站页提供“项目回收站”和“版本回收站”两个标签，不增加“历史记录”页面或菜单。新建入口时，使用以下字段；已有入口先导出备份，在原菜单上调整，保留其实际 `id`、`parentId`、`owner`、自定义名称和展示配置，避免创建相同路由的第二个入口。下例的父节点为默认“原型管理”，菜单编码与组件编码均为 `prototype-hub/Recycle`，路由为 `/prototype-hub/recycle-bin`。父节点须启用且没有额外查询权限限制，才能供单一查询权限的账号进入。

```json
{
  "id": "prototype-hub-recycle",
  "parentId": "131a644480a0c3253a08103fedfe3280",
  "owner": "proto",
  "name": "回收站",
  "code": "prototype-hub/Recycle",
  "componentCode": "prototype-hub/Recycle",
  "url": "/prototype-hub/recycle-bin",
  "icon": "DeleteOutlined",
  "sortIndex": 2,
  "enabled": true,
  "accessSupport": "unsupported",
  "permissions": [],
  "options": { "show": true },
  "i18nMessages": {
    "name": { "zh_CN": "回收站", "en_US": "Recycle bin", "en": "Recycle bin" }
  },
  "buttons": [
    {
      "id": "queryProjects",
      "name": "查询项目回收站",
      "permissions": [{ "permission": "prototype-hub-project", "actions": ["query"] }],
      "i18nMessages": { "zh_CN": "查询项目回收站", "en_US": "Query Project Recycle Bin", "en": "Query Project Recycle Bin" }
    },
    {
      "id": "queryVersions",
      "name": "查询版本回收站",
      "permissions": [{ "permission": "prototype-hub-version", "actions": ["query"] }],
      "i18nMessages": { "zh_CN": "查询版本回收站", "en_US": "Query Version Recycle Bin", "en": "Query Version Recycle Bin" }
    },
    {
      "id": "restore",
      "name": "恢复项目",
      "permissions": [{ "permission": "prototype-hub-project", "actions": ["query", "save"] }],
      "i18nMessages": { "zh_CN": "恢复项目", "en_US": "Restore Project", "en": "Restore Project" }
    },
    {
      "id": "restoreVersion",
      "name": "恢复版本",
      "permissions": [{ "permission": "prototype-hub-version", "actions": ["query", "save"] }],
      "i18nMessages": { "zh_CN": "恢复版本", "en_US": "Restore Version", "en": "Restore Version" }
    }
  ]
}
```

在菜单管理中将页面“权限配置”设为空，分别维护上述四个按钮。菜单的多个资源要求按 **AND** 合并，不能把两个 `query` 资源同时放进页面 `permissions` 来表达“任一权限”；历史兼容字段 `permission/action` 若非空也会参与授权，需先核对现存配置，不能直接套用默认菜单示例。按钮的 `permissions` 是对象数组，使用 `permission` 和字符串数组 `actions`，不是 `prototype-hub-project:query` 字符串或布尔值。已有 `restore` 按钮保留原 ID，补足查询动作后仍仅管理项目；不要将版本保存权限混入该按钮。

在角色管理中**显式选择该菜单**，再按职责选择“查询项目回收站”或“查询版本回收站”；需要恢复时只选择对应恢复按钮，恢复按钮已包含该类的查询动作。页面和接口读取真实资源动作：只有项目 `query` 时仅可使用项目标签，只有版本 `query` 时仅可使用版本标签，`save` 再配合相应数据范围决定能否恢复。页面入口本身不授予查询或恢复能力；未授予任何查询动作时显示无可用权限状态，不请求回收站列表。沿用旧资源授权、尚未建立菜单选择记录的角色，可能看到该空权限入口，但不会由此获得资源动作。

保存菜单元数据不等于给已有角色追加授权；旧角色是否增加版本查询或恢复应逐个审查，再显式保存角色授权并刷新当前菜单。管理员只能分配自身拥有的资源动作。项目恢复仍仅当前 owner/admin 可操作；版本恢复要求活动父项目及 owner/editor/admin 的编辑能力，viewer 不可恢复。30 天保留期和后端所有权、额度、状态校验保持不变，见[回收站接口](API.md#回收站与逐项恢复)。

需要重新生成资源时，先导出自己有权使用的菜单 JSON，并把下面的示例路径替换为实际文件（项目不依赖根目录历史 data.json）：

```bash
python3 tools/generate-menu-resource.py /path/to/exported-menu.json modules/system-manager/src/main/resources/menus/default-menus.json
```

生成器保留源菜单 ID、父子结构和授权规则，将旧 PrototypeHub 技术 code、组件、路由及三项权限引用规范化为当前命名。`name` 保存中文，`i18nMessages.name.{zh_CN,en_US,en}` 保存翻译，按钮沿用扁平语言映射；已有自定义名称、外部 URL 和翻译不全局替换。详见 当前 `PrototypeHub/prototype-hub` 命名约定。

## 安装身份、容量与创建回执

EasyORM 自动同步 `p_installation` 和 `p_creation_receipt`。应用在结构同步后初始化 `p_installation` 的单例记录，首次生成 UUID；后续启动及多个应用副本共用已有身份，不按进程、机器或 IP 重新生成。身份初始化不以存量是否超限作为启动条件，也不删除或改写已有项目、版本。

容量直接统计 `p_project` 与项目下 `p_version` 的活动记录（`deleted_time IS NULL`），不维护独立累计扣额计数器。默认最多 5 个项目、每项目 5 个逻辑版本；删除释放当前占用，同一逻辑版本更新仅增加修订，不新增逻辑版本。项目创建在事务内锁安装记录，版本创建在 ZIP 校验解压完成后通过短事务依次锁安装记录、所属项目，再检查活动行数、插入资源及回执；同数据库的应用副本使用同一锁边界。历史超限数据继续保留，只有新增受限。

上传或替换的事务最终结果为 `UNKNOWN` 时，系统保留候选目录并记录错误；应先确认数据库中的文件归属再处理目录，不自动按回滚删除文件。

带 `Idempotency-Key` 的成功创建将摘要和资源标识写入 `p_creation_receipt`，与业务插入原子提交；回执不保存原始键或请求内容。删除资源不会删除对应回执，仍删除时旧请求返回冲突，避免误重建。回收站恢复不新增回执；原键在当前权限和请求指纹校验通过后重放同一 ID 与原快照，不额外占用容量。不要把这两张表作为缓存清空，也不要单独重置安装身份或回执；常规数据库备份应包含它们，并与原型文件做同窗口联合备份。备份恢复后保留身份、资源关联和创建重试语义；接口及错误码见[API 说明](API.md)。

## 固定页面工作项引用

EasyORM增补 `p_work_item`，保存 `project_id/version_id/revision_id/page_id`、手工 `url/note`、独立 `config_version` 和创建/更新时间；旧表与业务数据不改写。索引 `(revision_id,page_id,created_at,id)` 支持固定页面数据库分页，另有version_id索引用于版本永久清理。引用固定于原修订，不随current指针更新复制或迁移，也不成为访问外部系统或项目的权限。

写入沿当前账号→项目→版本锁序，与 `p_creation_receipt` 原子提交。回执resource_id归逻辑版本、revision_id归实际修订，原始请求键和备注不写入回执；引用行删除后保留回执，防止未知结果重试复活旧引用。软删保留行和回执，30天永久清理版本时在同一事务清除。备份应保留完整表、固定修订及回执；URL和备注属于项目私有业务资料，不放入公开日志或运行证据。此表不引入文件引用，存储清单仍为v3。

## 不可变修订与旧内容迁移

`p_version` 继续保存逻辑版本和 `current_revision_id`；`storage_path/entry_file` 是当前修订的兼容投影。`p_revision` 保存不可变文件路径、入口、上传时名称/说明、规范化清单 JSON/SHA-256、实际文件数/总字节和创建信息。`p_share`、`p_preview_session`、`p_creation_receipt` 增加 `revision_id`：固定分享和内部预览固定快照，创建回执重放原成功快照。数据库和原型文件必须联合保存全部历史；只备份当前 `p_version.storage_path` 会丢失历史和分享内容。

分享另外保存 `revision_mode/revoked/config_version`。EasyORM 为旧表增补这三个可空列，不重写既有记录；旧值 null 分别按 fixed、未撤销、配置版本 0 解释，首次成功管理变更才写入新状态。新建分享默认 fixed、未撤销、版本 1。latest 的持久 revision_id 为空，公开访问时派生明确修订的凭据；它与未绑定修订的旧 fixed 分享含义不同，不能将后者自动升级为跟随。`access_key` 是服务端派生密钥，成功配置变更就轮换；仅有新 token 或旧密码证明不能恢复已失效的密钥。

升级需前后端一起发布并停止仍接受旧凭据的后端节点，先在恢复副本验证固定/跟随、启停与密码修改后的旧凭据失效。回滚到不识别 revoked 或新密钥协议的旧 JAR 可能重新开放已经撤销的访问，应停写并使用同一窗口备份恢复，不能仅替换二进制。

清单按规范化相对路径排序，记录文件 `path/size/sha256/mediaType`、入口和页面树。字节数与 hash 对应 HTML 检查脚本注入后的实际服务文件；清单本身不含修订 ID/创建时间，同一实际内容可生成相同摘要。它不是原始 ZIP 字节摘要，不能据此反推原导出包。页面树保留 `pageId`，页面身份由修订 ID 和 pageId 共同确定。

清单 JSON 最多 32 MiB；文件数独立受 `prototype-hub.storage.max-zip-files` 限制，目录枚举总节点（含根与隐式父目录）另受 `maxZipFiles * 8 + 1` 预算约束，避免迁移时扫描无界目录树。总字节按实际读取累计校验，包含注入脚本后的服务文件，不能用 ZIP 解压前的声明大小代替。超限应先检查副本及部署限制，不跳过清单建立过程。

同版本修订比较只读取两个已保存的有界清单，不读取或重新散列磁盘文件，不增加表、列、配置或持久化比较缓存。它反映发布时的路径、主页面名称和文件摘要差异；不证明当前磁盘完整性，也不将相同路径视为跨修订的业务身份。清单需符合现有格式、路径唯一性、文件摘要/字节数和页面文件关联约束，损坏记录应拒绝比较，不用 current 或现场文件自动修复。数据备份、完整文件 hash 核验及旧内容显式迁移流程保持适用，接口见[同版本修订比较](API.md#同版本修订比较)。

EasyORM 只补齐上述表、字段、索引及描述，**启动时不自动将旧内容迁移为修订**。升级应停止旧节点和全部写入，联合备份数据库与所有文件，在独立恢复副本执行：

1. 核对 `tools/preflight-db.sql`、`tools/snapshot-db.sql` 和文件清单。对每个待迁移逻辑版本调用 `POST /prototype-hub/versions/{id}/revision-migration`，请求 `{"apply":false}`；仅有效 ADMIN + 版本 save 可执行。检查清单摘要、文件数量/字节数、待绑定旧分享和回执数，预检不写业务数据。
2. 请求 `{"apply":true}`；服务在锁内重验逻辑版本、旧路径/入口和当前指针，再原子插入修订、设置指针并绑定该版本尚无修订的旧分享和创建回执。缺文件、非法路径、并发变化或数据库失败应阻止迁移；修复副本后重新预检。
3. 对照迁移前后逻辑版本 ID、创建信息、分享凭据和文件 hash；审核允许变化的指针/绑定字段，验证分享、旧创建回执和历史预览。再次 apply 不新增修订、不改写既有绑定。
4. 原实现曾在更新时删除旧目录；这些内容不能恢复。迁移只固定当下仍可用内容，旧分享和旧回执绑定这一快照，不能宣称找回它们最初发布/创建的字节。无修订的旧内部会话拒绝授权，需登录后重新换取。确认副本后安排正式迁移和前后端配套切换，不能混跑旧写入节点。

新更新先完成文件校验和清单再进行短事务 CAS 切指针；失败保留原内容，成功也保留上一修订目录。历史增长不计入逻辑版本额度，磁盘容量和历史保留是独立运维问题。

EasyORM 为 `p_project/p_version` 增补可空的 `deleted_time`；null 表示活动，旧记录保持活动，无需批量回填。项目与版本 DELETE 在原项目/版本锁及事务内写入删除时间，保留原 ID、成员谱系、逻辑版本与当前指针、全部修订、公开分享、创建回执及文件；清除内部预览会话，永久撤销并停用保留的分享。查询和计数在数据库侧排除已删除对象及父项目，ADMIN 例外也不开放它们；原始引用/锁/维护查询仍包含保留行。只有活动版本阻止删除项目。删除释放活动占用，不重置安装身份或项目绑定，旧创建键重放仍冲突。

回收站仅在当前恢复范围内查询删除记录，逐项恢复通过类型化 ORM 将 `deleted_time` 置空，保留原 ID 和引用。恢复项目只恢复父项目；恢复版本前必须先恢复其父项目。项目恢复重新取得用户、安装身份、项目锁，版本恢复重新取得用户、安装身份、项目、版本锁，按当前授权与活动行数重验当前有效容量（缺省 5/5）；失败保持原删除标记。旧内部会话和已撤销分享不会恢复，恢复后需重新申请预览或新建分享。接口、授权范围与冲突码见[回收站 API](API.md#回收站与逐项恢复)。

项目和版本各自从 `deleted_time` 起保留固定 **30 × 24 小时**，到期条件为 `deleted_time <= 当前服务端时间 - 30 天`。null 表示活动，未来删除时间不提前到期。未满期限可按原权限逐项恢复；到期立即从回收站 COUNT/分页排除，锁内恢复重验期限并返回 404，不以磁盘文件是否仍在判断可恢复性。升级前已经到期的旧墓碑同样进入自动清理范围，不从升级时刻重新计算 30 天。

后台复用 `prototype-hub.cleanup.interval-ms` 调度（默认 30000 毫秒），有界处理到期记录及文件队列。扫描游标随安装记录持久化，每批先推进游标再释放安装锁并处理目标，损坏或持续失败的记录不会长期挡住后续到期项；到末尾后重新扫描。到期版本在项目→版本锁内重新确认状态、期限，事务内登记每个当前/历史快照的 `expired-snapshot` 任务，再删除相关会话、分享、创建回执、修订和逻辑版本；任何数据库失败全部回滚。父项目只有在所有原始子版本行均已清理后才删除成员、剩余会话、分享、项目回执和项目自身；活动或较新软删子版本保护父项目，不提前级联删除。资源和回执永久清理后原创建键的去重保证终止，后续请求经当前权限和容量检查可能产生新 ID，旧 ID 不复活。

文件删除在数据库提交后由持久化的 `p_file_cleanup` 队列执行，每次最多 50 条并沿用失败退避。`expired-snapshot` 和失败上传的 `upload-candidate` 仅可删除无任何原始版本/修订引用的规范快照目录；`review-image`仅可删除无任何原始消息image_path引用的规范单图目录，内部消息和软删保留引用同样受保护。引用保护包含软删行和旧路径别名，不增加活动过滤，不递归删除项目根目录，不涉及封面、头像或品牌图片。失败、取消或重启保留待办任务，文件已不存在视为幂等成功；因此到期后磁盘回收可能延迟。旧 `project/version` 及未知来源任务继续保留并退避，不猜测历史意图。永久清理不可通过回收站撤销，只有经过验证的同窗口数据库与文件备份可用于恢复；已清理的文件无法凭数据库重新生成。

升级先停止所有旧应用写入节点及可能清理文件的后台进程，取得同窗口联合备份，在独立恢复副本验证期限、活动占用、删除状态、引用保护、清理队列及访问阻断，再配套切换前后端。旧版无限期保留与新版 30 天清理节点不能混跑；升级即可能清理已到期墓碑。也不能混跑忽略 `deleted_time` 的更旧后端；仅回退旧 JAR 可能重新开放已删除对象，须从同一时点数据库与文件备份验证恢复。

存储核验先停写后导出完整引用：`psql -X -qAt -v ON_ERROR_STOP=1 -f tools/export-storage-manifest.sql > references.json`，再运行 `python3 tools/audit-storage.py --root /path/to/snapshot --manifest references.json`。导出包括活动与已删除版本、全部历史修订及未迁移旧投影，校验项目/版本/修订/分享、成员的父项目、内部会话的项目/版本及显式修订、非空创建回执修订的实际资源关联（version、thread或message→thread），以及所有public/internal消息图片的完整元数据和父线程/项目/版本/修订关联；创建回执没有修订的旧请求与项目请求不按猜测类型修复。导出不按 30 天期限过滤，仍持久化的到期引用也必须保护，只有数据库事务真正删掉引用后目录才可能回收。`preflight-db.sql` 另输出活动/软删计数，`snapshot-db.sql` 输出删除状态和完整文件清理队列的摘要，恢复时必须核对，不能只比较 ID 或行数。

完整清单使用format=prototype-hub-storage-references、formatVersion=3、sourceSchema=immutable-revisions-review-images-v1，同时具有 `referencesComplete=true`、`receiptReferencesChecked=true`、`imageReferencesChecked=true` 和 `integrityErrors=0`。原修订entries保持不变，imageEntries为 `{projectId,versionId,messageId,storagePath,size,sha256}`，包含全部保留图片，不导出正文或凭据。旧数组、v2、缺结构的current-only及尚未核验回执或图片关联的清单仅供检查，不授权 `--delete-unreferenced`，不能声称完整图片引用已验。审计先检查全部版本及图片引用，逐图核对真实字节数与SHA-256；缺失、损坏、非法边界、重复引用或符号链接阻止所有删除，不会先删看似孤儿再报告失败。完整v3显式修复仅删除没有任何持久引用的版本或图片候选目录；图片以外文件仍需全量文件路径/内容hash核对。离线修复还必须显式提供 `--offline --confirm /path/to/snapshot`，仅在联合备份的隔离副本审查后使用；不要手改清单完整性标志绕过检查，软删目录仍属于被引用数据。

## 分享邮箱身份数据

EasyORM增加 `p_share.review_enabled/review_allow_unverified/review_allow_comment`，旧空值均按关闭处理，不自动改变现有分享；省略许可或传null的旧客户端保留其已有值。身份、低保障许可及页面反馈分别管理，反馈有效值还要求身份开启及有效fixed修订；旧客户端关闭身份时保留的存储标志不构成有效反馈授权。结构同步沿用仅增缺失表、列和索引的策略，不为身份升级放开全局ALTER/DROP，不降低已有邮箱列的NOT NULL约束。身份不关联或创建 `s_user`：

| 表 | 职责 |
| --- | --- |
| `p_review_guest` | 项目与规范邮箱确定的稳定已验证访客ID、必需邮箱、自述姓名/公司及enabled/config_version管理状态 |
| `p_review_unverified_guest` | 独立随机43字符ID、项目、自述姓名/公司、状态版本及固定八小时expires_at；没有邮箱字段，不按姓名合并 |
| `p_review_challenge` | OTP的BCrypt摘要、投递/尝试/消费状态，以及Cookie、分享、项目、版本、修订、配置代次绑定；可空invitation_id绑定本次邀请 |
| `p_review_session` | 随机Cookie的SHA-256主键、身份等级和范围，最长八小时，不存原始Cookie；verification_level历史NULL按email，未知值拒绝 |
| `p_review_rate` | 项目与邮箱的OTP发送窗口、项目/Cookie未验证身份窗口及独立的邀请创建窗口，跨副本共用 |
| `p_review_invitation` | 随机邀请凭据的摘要、指定规范邮箱、固定项目/版本/分享/修订、分享配置/密钥代次及邮箱访客状态版本、创建/到期/消费时间、撤销标记与并发版本；不存原始凭据 |
| `p_review_thread` | 固定项目/版本/分享/修订/页面及保障等级命名空间中的本人线程；页面元数据、首位访客作者快照、初始摘要、open/resolved状态、并发版本及公开更新时间，另存成员专用负责人和独立并发版本、创建时可选的根页面几何快照 |
| `p_review_message` | 线程纯文本消息、guest/member作者快照、public/internal可见性、创建时间及可空单图元数据；没有消息编辑/删除或通用附件入口 |
| `p_review_event` | 同事务领域事件outbox，仅存相关ID、事件类型、作者种类和时间；不存正文、邮箱、凭据，不代表业务发送任务 |

固定修订的跨页反馈总览复用p_review_thread及原Thread投影，不新增持久字段或表。索引 `idx_review_thread_overview(revision_id,public_updated_at,id)` 支持固定修订按公开更新时间和ID分页；另增复合索引 `(revision_id,assignee_id,public_updated_at,id)` 支持成员责任筛选后的COUNT与分页排序。均由EasyORM增补，保留旧数据，重复同步不重复建索引。成员mine按当前服务端账号匹配assignee_id，unassigned仅匹配NULL，旧空值自然计入；停用或撤员不改变已有非空分派。总览的公开范围限定相同share_id及固定修订，再按guest_id/verification_level本人或当前有效人工授权过滤；内部备注与负责人字段不参与公开排序、计数或投影；整库备份及v3存储引用格式保持不变，不新增恢复覆盖声明。

p_review_thread新增四个可空内部字段 `assignee_id`（64）、`assignee_name`（128）、`assignment_version`（Long）、`assignment_updated_at`（Unix毫秒）。旧空值按未分派、版本0及空更新时间解释，不回填猜测的负责人。姓名是分派时快照，available按当前启用账号及owner/editor绑定计算；停用或撤员不自动清空或转交，同一账号重新合格可再次可用。负责人独立于state/config_version/public_updated_at，不改变原公开Thread投影或排序，也不构成授权。

分派写入按稳定账号顺序取用户锁，再取项目、版本锁并重验当前范围及目标资格；独立回执指纹包含目标、expectedVersion与完整范围。重放只返回当前负责人，不能回写历史目标。同版本同目标仅记录回执，实际变化才递增assignment_version并写内部thread.assigned/thread.unassigned事件。用户删除沿原同步事务发现受影响项目，在项目锁内每批最多50条清除负责人ID/姓名、增加分派版本/时间并写thread.unassigned；失败回滚删除，重建相同用户ID不能继承旧分派。版本/项目永久清理沿原线程图删除事件与回执。

这四列没有新增表或文件引用；整库备份及既有整行内容摘要自然包含它们，离线存储引用清单仍为v3。不要因账号停用、撤员或删除把仍保留的线程/图片标为存储孤儿；也不能把既有空字段恢复测试当作非空分派恢复已验。

p_review_thread另增加一个可空anchor字段，使用官方EasyORM JsonCodec/ColumnType以文本保存类型化根页面几何JSON快照。旧行和省略/null创建均解释为无锚点。非空值只包含space、shape、CSS整数位置/宽高、文档宽高与视口宽高，不存选择器、frame路径、HTML、运行地址或凭据；它不是DOM证据或授权依据。锚点仅在创建时与线程、首消息、事件及回执原子保存，之后不可编辑或自动迁移修订。

省略/null锚点保持历史创建指纹，非空锚点以固定字段顺序加入语义指纹，再追加可选图片摘要；相同键不能把无位置请求改成有位置请求或改变几何。整行备份、摘要及永久清理沿现有线程图覆盖该列，不新增表、文件路径或存储格式；离线引用清单仍为v3。既有恢复结果不代表非空几何快照已专项验收，恢复副本应同时核对锚点与固定修订/页面的原归属。

p_review_message的单图字段为可空image_path、image_content_type、image_size、image_sha256、image_width、image_height；旧消息全部为null，部分缺失或损坏不构成读取授权。image_path只保存服务端生成的相对路径 `projects/{projectId}/review-images/{randomAttemptId}/image`，attempt为43字符随机值，不使用原文件名；实际格式、字节数、SHA-256和宽高来自验证后的原始PNG/JPEG。图片独立于不可变revision/site，不加入已发布manifest，不改变原型包hash或质量报告。

访客初始投稿的单张图片保存在首条public消息的同一组字段中，线程、首消息、thread.created事件和创建回执同事务保存；创建回执仍关联线程，图片归属由首消息的实际引用确定。复用已有图片路径、读取与清理关系，没有新增表、列或存储清单格式，v3导出同样覆盖首消息图片。

软删随现有30天保留，身份/邀请/事件到期不删除消息图片。版本/项目永久清理在删除消息前，同事务登记review-image持久清理任务；文件删除在提交后重查全部原始消息引用，沿既有有界重试worker处理。明确失败与取消使用已有上传所有权补偿，未知事务结果不删除可能已提交的图片；进程突然退出可能留下未引用目录，应在停写副本以完整v3导出和显式离线修复核验，不称自动崩溃回收。

BCrypt和SMTP在现有有界FileWork运行，外部发送不持数据库锁。challenge先以不可验证的pending状态提交，SMTP受理后短事务重验分享才标记delivered；验证在锁内重查并原子消费，错误次数提交后才返回错误。未验证身份不创建challenge、不发送邮件，也不把SMTP失败当作验证通过。

EasyORM为已有邮箱guest补充可空的 `enabled/config_version`，旧空值分别按有效/版本0处理；不会回填猜测的状态。重新OTP只更新自述资料，不重置管理状态或版本。未验证身份保存在单独表中，与邮箱身份及其确定性ID隔离；同一有效未验证会话返回原身份和原到期时间，不续期或按新名字改资料。退出、到期或凭证丢失后没有自助找回、人工关联或自动升格合并。配置换代后的旧Cookie可在取得新分享证明并明确选择后换发另一随机身份；旧记录不被改名、续期或关联，新旧身份各自保留原到期时间。

项目级启停与身份签发共用项目锁。真实状态转换增加版本，并同事务删除该项目对应身份的会话；邮箱身份同时删除该项目规范邮箱的challenge，未验证身份不操作邮箱challenge。任一步失败全部回滚。重新启用不恢复旧Cookie或OTP，不延长未验证身份期限；限频台账保留，停用不是永久删除。

邀请只绑定已开启身份的活动fixed分享及其有效修订，不预建邮箱guest，不创建平台用户或合并未验证身份。有效期固定24小时且不超过分享到期时间；只存高强度随机凭据摘要，原始值仅创建响应返回。邀请记录保存签发时的邮箱guest配置版本，尚无guest或历史空版本按0处理；停用再启用造成版本变化，旧邀请仍失效。列表状态结合当前分享与guest绑定计算，不能用重新启用恢复旧邀请。

challenge的可空invitation_id保持普通OTP兼容；发码、SMTP受理确认及消费时均重验完整邀请绑定。正确OTP在原项目/版本锁事务中一并消费邀请、challenge并保存稳定邮箱guest/session；邀请版本同步递增，任一步失败整体回滚。错误尝试只提交challenge次数，不消费邀请。撤销待用邀请不移除已签发会话，身份撤销仍使用访客管理。

九张评审表（包含消息图片元数据）、创建回执 `p_creation_receipt`、分享配置/密钥与完整原型文件根中的review-images一并备份，不加入原型manifest或匿名存储导出。邮箱、自填资料、反馈正文和可识别摘要只授予必要运维权限，备份不可公开上传。不得单独恢复会话或重置限频。已验证guest随项目保留，不因会话到期删除；未验证guest和邀请有固定到期时间，不能借备份恢复或状态启用续期。身份行不等于永久访问权。

恢复验证应覆盖九表中的非空代表数据及相关回执，在全部写入和清理进程停止后计算确定性行数/内容摘要，并在新数据库启动应用前精确对比。整库备份本身不证明每张表及其业务关系已经验收；只有四张历史表的比较结果不足以代表九表。启动后再检查原身份与保障等级、邀请完整绑定及撤销/消费状态、线程归属、内部可见性和请求键去重；重建CSRF不应延长会话，状态查询不应刷新邀请或身份期限。报告不包含业务正文或凭据，完整进程验收与部署限制见[运维说明](OPERATIONS.md)。

既有保留期调度每次清理过期challenge/session/rate、未验证guest和邀请各最多50行，每次订阅使用新时间；不清除仍有效的未验证身份，也不清理稳定邮箱联系人。领域事件保留30天，每次最多清理50条；事件到期、guest/session清理不级联删除长期线程与消息。软删保留业务行但撤销分享；版本/项目及相关修订永久清理在原图事务移除相应线程、消息、事件及其幂等回执，不留下永久孤立回执。项目永久清理覆盖全部九张评审表中的项目数据。文件继续按原30天回收策略处理，不新建后台线程。

页面反馈只认固定修订manifest中的唯一pageId，服务端生成名称和路径；页面及根文档几何快照不跟随iframe内部导航或迁移到新修订，几何不能覆盖manifest页面归属。公开本人范围匹配完整资源与分享、保障等级及guestId，新分享不继承旧线程。访客作者来自当前会话，成员作者来自当前启用账号；作者资料按消息/首条线程保存快照，不能靠后续改名或同名联系人的资料拼接归属。未验证身份过期后保留历史，但不能据此找回旧线程。

初始线程与消息、回复或状态转换均和领域事件、创建回执同事务。回执按作者命名空间/ID、操作、完整范围隔离，语义指纹使用长度前缀；请求键相同但文本、可见性或并发版本不同产生冲突，当前授权检查先于回执重放。状态版本只在open/resolved转换时递增；访客回复自动重开，PM公开回复保留状态。内部备注不改公开更新时间、状态或版本，公开查询在COUNT和分页之前排除内部消息，不能通过数量或排序观察备注活动。基础事件不经SMTP派发，不产生待发或无限重试队列。

## 项目归属迁移与认领

EasyORM 补齐 `p_project.owner_id/access_epoch`、`p_project_member` 和 `p_preview_session` 的结构与描述，**不自动推断或回填旧项目 owner**。创建者 ID 保留为历史审计字段；新建项目在创建事务内绑定 owner，普通查询以当前 owner/成员为准。`p_project_member` 保存编辑者和只读成员，owner 由项目字段唯一表示。owner 字段为空的待认领项目仅有效 ADMIN 可见，不能作为共享项目向全员兜底；owner 停用时允许管理员重新认领，其他有效成员仍按原角色访问。

在停写并完成数据库与文件联合备份后，先对恢复副本执行显式迁移：

1. 用当前版本启动副本，调用 `POST /prototype-hub/projects/_ownership-migration`，请求 `{"apply":false}`；需要有效 ADMIN 和项目 save。检查响应 `scanned/assigned/pending/unchanged`，预检不写项目数据。
2. 核对项目创建者是否为有效账号、项目/版本/分享的关联和文件清单。请求 `{"apply":true}` 仅将 owner 和授权代次均未初始化、创建者仍有效的历史项目回填为 owner；已有 owner 保持不变。无法回填的项目初始化授权代次并继续待认领，后续重跑不再根据创建者自动授权。重复执行不会覆盖既有归属或重复改写代次。
3. 在项目认领入口或 `POST /prototype-hub/projects/{id}/claim` 提交 `{"userId":"有效用户标识"}`，由 ADMIN 为无可用 owner 的项目显式指派负责人。不能覆盖活跃 owner，也不能通过成员 PUT 把 editor/viewer 提升为 owner。
4. 比较升级前后项目/版本 ID、原创建信息、数量、关联及存储文件 hash；仅审核通过的归属字段允许发生预期改变。确认副本中 owner/成员可读写、非成员关联 ID 不可读、旧内部 key 及撤员凭证被阻断，再安排正式窗口。

删除用户时，在用户删除事务内撤销其成员绑定及内部预览会话，原 owner 置空并轮换授权代次，保留项目和文件供管理员显式认领。hsweb 会为同名重建账号复用用户 ID；新账号不能继承已删除账号的 owner、成员或预览能力，也不能靠再次执行归属迁移恢复旧权限。

创建所有者、成员或内部预览绑定前，事务按用户 ID 的稳定顺序取得官方 `FOR UPDATE` 用户行锁，再取得项目和版本行锁。该锁比原共享用户锁更强，使绑定写入与账号删除互斥；身份读取本身不隐式加锁，避免项目锁后再等待用户锁造成顺序倒置。

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

## 分享访问统计数据

EasyORM增补`p_share_visit`及`p_installation.statistics_started_at/statistics_signing_key`，保留既有项目、修订、分享及部署身份。安装行仅对NULL字段初始化：起点首次写入后不移动；独立随机签名密钥不随重启重新生成，标记`@JsonIgnore`且不从任何统计或公开响应返回。签名用途及长度前缀绑定分享ID、逻辑版本、实际修订、当前分享配置密钥和事件ID/签发时间；旧运行访问密钥不构成签发权限。

访问表主键是服务端签名票据中的随机事件ID，列仅有`project_id/version_id/revision_id/share_id/visit_day/received_at`，不保存公开share token、accessKey、票据、IP、邮箱、指纹或访客身份。首次接收按固定`Asia/Shanghai`自然日写入，重放不改日期。官方EasyORM upsert的唯一键冲突忽略保证跨副本一次保存；数据库结构化COUNT/GROUP BY直接聚合，不使用内存累计、客户端全量计数或另一个日计数副本。查询先下推当前项目/角色/软删范围，索引`(visit_day,project_id)`和`version_id`支撑时间窗与永久清理。当前平台实际支持PostgreSQL；jOOQ统计渲染有PostgreSQL/H2映射，但不代表整个平台已经支持H2或其他数据库。

既有维护周期每次最多删除500条早于“今天减34天”的事件，目标保留含今天的35个自然日；故障或积压可延迟物理删除，但统计窗口最多30天，不会返回到期事件。超过24小时的票据早已失效，不会因清理去重记录而重计。版本永久清理在原项目→版本锁/事务中先删除访问事件；软删期间保留事件但授权聚合隐藏，恢复不复活旧分享。备份须保留完整安装行及访问表，`tools/snapshot-db.sql`输出整表摘要而不输出密钥；存储引用清单仍为v3，统计没有文件资源。没有采集的上线前历史明确未知，不自动补零或从HTTP日志回填。


## Accepted installation credentials

EasyORM additively synchronizes `p_installation.license_document`, `license_id`, `license_sequence`, `license_fingerprint`, `license_accepted_at` and `license_key_id`. All are server-only entity fields; normal entity serialization omits them. The bounded canonical envelope is reverified on each public-provider read against the current trust configuration, installation binding and time. Removed keys make persisted claims untrusted without deleting the accepted sequence or record. No in-memory entitlement cache or second counter is maintained.

Imports use the existing installation row lock inside a reactive transaction, recheck the live administrator, validate the candidate, then replace all accepted fields together. Concurrent copies therefore produce one acceptance and idempotent replays; descending/conflicting sequences cannot overwrite newer records. Project/version admission reads the current enforced capacity under the installation lock; existing resource counts, creation receipts and data are retained, without a separate capacity-debit table. Source-code rollback retains these additive fields and the accepted record; never reset the persistent installation ID. The complete installation row must remain in database backups, and the existing installation digest in `tools/snapshot-db.sql` covers these new fields without printing the original envelope.

A database rollback can also roll back the accepted sequence. Offline verification cannot absolutely prevent cloning, local clock rollback or database rollback. After recovery, compare the installation identity, latest issuer record, accepted sequence and fingerprint before accepting a replacement. Restore does not delete prototypes or disable the basic product.

### 有限容量扩展与安装锁

公共 `PrototypeCapacityPolicy` 是已验签声明到实际容量的同步纯函数，无数据库/文件/远程副作用；缺少扩展时固定使用免费5/5。实际快照包含统一上限及有界的逐项目上限，现有资源COUNT、创建回执和事务仍是占用事实，不新增累计扣额表。凭证替换与新增/恢复采用同一安装锁，再取得项目/版本锁；已有逻辑版本的新修订不消耗新数量。读取状态不缓存凭证，每次按当前信任配置重验。无效凭证或移除扩展保留资源和文件，超限只限制新增占用。


## 原始资产导出的数据边界

固定修订图片/SVG 导出复用 `p_revision.manifest_json/manifest_sha256/storage_path`，无新表、列、迁移、导出任务表或权限缓存。保存清单的 UTF-8 字节须匹配其 SHA256；仅选择规范原路径及 `image/*` 条目，实际原文件在准备附件时逐项校验大小和 SHA256。导出不会重写清单、上传包、源文件或逻辑版本当前修订。

ZIP 内及独立下载的对账 JSON 描述本次固定修订和实际选择集合，不包含平台令牌、分享密钥或内部存储路径。导出临时目录不是持久数据或备份来源；回滚新增控制器/服务即可关闭入口，不需要逆向数据迁移。现有修订与存储备份方式继续适用。

## 历史评审授权与审计

EasyORM加法创建 `p_review_association`，不回填或改写原访客、线程、消息。记录绑定项目、逻辑版本、分享、固定修订、来源unverified身份与目标email身份；保存授权时分享配置版本/私有密钥摘要、目标版本、人工核实依据、创建操作人/时间及撤销操作人/时间/原因。来源不设外键依赖未验证访客行，身份到期清理不能删除授权审计。

`active_scope_key` 是服务端计算的可空唯一槽；未撤销记录占槽，撤销置NULL，PostgreSQL允许多条NULL历史。分享/目标代次失效不释放槽或改换目标；显式撤销后新建才形成新记录。旧分享/邮箱访客的NULL配置版本按0解释，所有新授权版本必填。新增索引及表由现有实体同步管理，不引入独立迁移框架。

授权和撤销在项目→版本锁下复验当前账号、项目范围、代次与CAS，关联操作回执与审计同事务。公开写入/附图最终落库和撤销使用相同锁顺序；撤销先提交后不能再提交新的关联回复或重放。历史作者、已提交回复及图片不因撤销改写或删除，回复作者保留实际目标邮箱访客。

整库备份必须包含新表及相关 `p_creation_receipt`，与原评审数据和文件保持同窗口；新增表不改变v3文件引用格式。软删阻断访问并保留审计，只有授权的项目/版本永久清理才有界删除该范围的关联与创建/撤销回执，不误删其他项目。此保留期不随八小时访客或30天事件清理缩短。

恢复诊断将人工授权作为第十张评审相关表处理，但不套用九张历史评审表的到期删除规则。来源未验证身份行可以已经到期清理，来源线程与原作者标识仍须保持；目标邮箱身份须存在并属于同项目。目标停用、配置代次变化或分享失效可使保留审计不再授予访问，不因此视为数据库关系损坏，也不在恢复中重置这些状态。

## 固定页文案清单数据

EasyORM增量创建 `p_copy_snapshot` 与 `p_copy_entry`，不回填旧版本、不存HTML、不修改原型文件或revision。快照唯一绑定project/version/revision/page，保存规范相对路径、manifest及页面文件摘要、提取规则版本、READY/EMPTY/LIMIT_EXCEEDED与原因、完整静态计数、整页configVersion和提取时间。首次提取（包括空及预算失败）后不可重建或覆盖；新规则不自动迁移已有快照。

条目保存完整同scope及snapshotId、kind（0静态/1人工）、sourceOrder、text、businessTerm、note、requirementUrl、完整occurrencesJson/次数、条目CAS版本及创建/更新时间。text和occurrencesJson映射PostgreSQL TEXT（LONGVARCHAR），不经JSON codec重复编码或截断；全部静态去重文字UTF16总量限2Mi，保留完整出现处。索引沿snapshot/kind/sourceOrder/createdAt/id稳定排序，另有version清理索引。静态id由固定快照及规范化文字摘要确定，人工id由持久请求身份确定；不保存原始请求键。

条目修改、整页版本递增及 `p_creation_receipt` 同事务，账号→项目→版本锁序重验当前权限。预算失败快照与回执先提交再返回422；没有部分静态条目。软删保留文案并阻断读取，永久版本/项目清理按范围先删除条目、再快照及版本回执，不能随普通会话过期清理。联合备份需包括这两表、原revision/文件与回执；应用回滚保留新表和人工数据，不自动删表。

## 当前发行的恢复对账

当前业务图共23张 `p_*` 表：安装、项目、成员、内部预览会话、逻辑版本、修订、工作项、分享，九张历史评审表与人工授权审计，访问事件、文案快照/条目、创建回执及文件清理队列。整库备份另保留系统用户、角色、权限、菜单和配置数据，不把比较清单当成选表备份指令。`tools/snapshot-db.sql` 保留原分类摘要，并输出所有现存业务表的整行摘要及覆盖清单，包含回执fingerprint/create_time和私有审计字段的摘要；不输出原文或凭据。旧schema缺表时仍可诊断，覆盖不足不能作为当前发行通过结果。

`preflight-db.sql` 与v3存储引用导出认可非空修订回执的实际version、thread、message或association资源关系，检查相应项目/版本/修订范围。人工授权另检查来源线程/分享及目标邮箱身份归属；访问记录保留实际历史修订，不强行等于分享后来选择的目标；文案快照核对固定manifest、页面和原文件摘要，条目核对同snapshot及完整页面范围。无修订的历史或项目回执继续保持原解释，不从摘要主键猜测操作类型。

隔离工具在停写后取得确定性行摘要和全部文件hash，在新数据库启动应用前严格逐行对比；完整回执不能只比resource/revision绑定。启动后只接受启动前已登记的到期行身份和本次待办文件差集，长期授权审计、线程、消息、工作项和文案必须保持。访问事件的35自然日保留与统计30天窗口分别核验，重放仍有效的原事件票据不能新增访问。隔离SQL定龄用于验证到期分支，不表示真实生产历史已被演练；当前版本恢复也不等于旧schema升级验收。
