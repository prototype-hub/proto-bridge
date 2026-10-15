# 数据库结构、初始化与旧库接入

## 当前启动职责

运行时使用 hsweb 5.0.1 的 EasyORM/R2DBC。`@EnableEasyormRepository("com.gaiys.proto.**.entity")` 注册本地实体，官方系统模块注册自己的实体；`easyorm.auto-ddl=true` 根据元数据同步缺失表、字段和索引。仅移除 `resources/db` 并不会禁用 EasyORM；此前不能自动建表，是因为应用明确配置了 `auto-ddl: false`。当前生产 resources 不再保存建表 SQL，Flyway、PostgreSQL JDBC 和 spring-jdbc 仅在测试依赖中保留。

连接只配置 R2DBC：`DATABASE_R2DBC_URL` 或 `DB_HOST/DB_PORT/DB_DATABASE`、`DB_USERNAME/DB_PASSWORD`。`DB_SCHEMA` 同时用于 R2DBC 与 `easyorm.default-schema`；Compose 沿用 `- "easyorm.auto-ddl=true"` 等实际属性路径。无需 `DATABASE_JDBC_URL`、`FLYWAY_ENABLED` 或 `spring.flyway.*`。数据库和 `DB_SCHEMA` 指定的 schema 须事先存在（默认 `public` 已存在）；EasyORM 创建表，不创建数据库或 schema。数据库账号需能访问目标 schema，并具有创建缺失结构、索引及修改描述的权限。`DATABASE_USER/DATABASE_PASSWORD` 是 application.yml 使用的兼容变量，默认分别引用 `DB_USERNAME/DB_PASSWORD`。

自动同步以实体当前表名为准，保留 `s_*` 和 `p_*`。自动 DDL 不负责识别旧表别名、推断字段重命名或转换历史数据；已有列类型、额外列与数据不因修改实体而自动删除或收窄。旧库存在结构差异时先在恢复副本审核，不能将新建同名业务表当作数据迁移。

结构同步完成后，描述同步器根据实体/字段的 `@Schema(description = "...")` 更新表和列注释，见 实体与字段的 `@Schema(description)` 注释约定。结构或初始化失败会使启动失败，不能通过吞异常继续提供服务。

## 菜单与用户初始化

初始化复用现有响应式 hsweb 服务：维护用户所需的角色维度及默认授权，按配置首次创建管理员，再初始化菜单。密码通过既有 PBKDF2 编码器处理，不在 SQL 中保存明文或固定哈希。资源权限定义继续由 hsweb 框架同步；组织、职位、字典及原型业务数据不导入演示种子。

已有用户、密码、自定义菜单和手工授权保留；重复启动不重新赋予已撤销的权限、不启用已禁用角色。初始化所需的 ADMIN/MEMBER 角色及权限属于用户体系前提。生产不需要额外初始化 SQL；若后续增加部署初始化脚本，其范围仅限菜单和用户及必要的角色授权数据，不把建表、原型项目或其他业务种子混入其中。

菜单初始化仅在 `s_menu` **全表没有任何记录**时导入 classpath 的 `menus/default-menus.json`。资源随 JAR 打包，运行时不读取工程根目录 `data.json`。生成资源保留 **2 个根、14 个菜单节点和 38 个按钮**；任何已有菜单（包括其他 owner 或禁用菜单）都会阻止重新导入。两个实例并发初始化时在 PostgreSQL 事务内锁表并再次判断，解析、校验或保存失败整体回滚。

需要重新生成资源时，先导出自己有权使用的菜单 JSON，并把下面的示例路径替换为实际文件（项目不依赖根目录历史 data.json）：

```bash
python3 scripts/generate-menu-resource.py /path/to/exported-menu.json modules/system-manager/src/main/resources/menus/default-menus.json
```

生成器保留源菜单 ID、父子结构和授权规则，将旧 PrototypeHub 技术 code、组件、路由及三项权限引用规范化为当前命名。`name` 保存中文，`i18nMessages.name.{zh_CN,en_US,en}` 保存翻译，按钮沿用扁平语言映射；已有自定义名称、外部 URL 和翻译不全局替换。详见 当前 `PrototypeHub/prototype-hub` 命名约定。

## 旧数据库副本接入

1. 暂停写入，保存 `pg_dump`、schema-only 导出和同窗口文件快照，在独立数据库中恢复副本。数据库、文件路径和分享凭据不得提交 Git。
2. 运行 `scripts/preflight-db.sql`、`scripts/snapshot-db.sql` 与文件清单核对真实表名、类型、nullable、主键、索引、重复能力密钥及 ID/关联摘要。脚本默认当前 `p_*`，核验 V15 前旧表时显式使用 `-v prototype_hub_table_prefix=protohub_`。
3. 若存在 `proto_*`/`protohub_*` 旧表名、旧权限前缀或不兼容字段，先审核并在副本显式转换；当前应用不自动执行历史 Flyway、不建立 baseline，也不从旧表猜测或覆盖新表数据。保留既有 Flyway history 作为原系统证据。
4. 保持 `storagePath`、`entryFile`、previewKey、share token/accessKey 与文件目录一致。系统用户密码转换独立审核；分享密码保留 BCrypt，不转换成用户密码格式。
5. 用当前应用在副本启动，验证自动同步、注释、菜单/角色/按钮、登录及已有原型分享；比较转换前后行数、ID/关联摘要和文件清单，再安排正式窗口。

初始化不是旧系统数据导入工具，组织、职位、字典和旧用户转换需另行验收。系统集成范围见 [接口说明](API.md)。

## 显式历史升级测试

V1–V19 的原文 SQL 仅保存在 `bootstrap/src/test/resources/test-db/migration/`，不打入运行 JAR。显式历史测试调用 `Flyway.configure().locations("classpath:test-db/migration")`；普通应用集成测试禁用 Flyway，直接验证 EasyORM 冷启动。历史夹具不等同于当前部署初始化脚本，也不授权启动时重放旧 DDL。

这些夹具覆盖旧系统/原型建表、V14 系统表名统一、V15 `p_*` 表名统一、V16/V17 默认中文名称、V18 PrototypeHub 技术引用转换和 V19 分享描述列等已实现历史契约。旧版本非空数据、索引、关系和冲突回滚的断言继续保留；不修改历史 SQL 来适配新的冷启动流程。

验证当前发行候选时，应对实际目标数据库副本检查结构、权限、初始化幂等与旧数据保留；历史通过数字不作为当前版本验收。
