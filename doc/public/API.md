# API 与权限边界

API 文档由运行实例的 `/v3/api-docs`、`/swagger-ui.html` 提供，是否公开由部署策略决定。Nginx 默认将 `/api/` 转发给后端；兼容前缀由现有过滤器处理，不应在前后端重复拼接。

系统管理包括用户、组织、职位、角色/分组、菜单、权限、字典和系统配置；访问/系统日志可使用本地或 ES 存储。SSO/资产列表在独立版未装相应集成时返回空，不表示完整支持企业应用/资产模块。

用户详情、用户列表与本人资料中的 `type` 统一返回枚举对象，例如中文请求返回 `{"id":"normal","value":"normal","text":"普通用户"}`；管理员类型为 `admin` / `管理员`。`id` 保留兼容，`value` 为标准枚举值。`GET /user/detail/types` 返回这两类筛选选项。文本遵循现有请求语言上下文（如 `Accept-Language: zh-CN` / `en-US`）；前端默认发送中文，无语言头时沿用框架的 JVM 默认语言。筛选条件仍传 `normal`、`admin` 等字符串，账号创建和更新不开放类型写入。历史未知类型原样保留标识和文本，空类型按普通用户展示，读取不会改写存量数据。

本人个人资料及头像沿自助入口校验当前身份；品牌图片使用管理上传权限。前端菜单/按钮不是安全边界，所有管理写入、文件读取、分享与关联操作须由后端执行权限和数据范围校验。多角色授权合并/撤销、禁用状态、分页上限和初始化幂等应有回归。

原型业务包括项目、版本、ZIP上传/替换、预览、页面树、Inspect与受控分享。分享/预览 URL 含能力密钥，禁止公开真实链接；错误与审计记录需脱敏。上传时验证 ZIP 大小/条目/深度、解压路径和内容类型，不执行包内安装脚本。

## HTML 文件夹上传

可以上传已有 ZIP，也可以选择已从 Axure 等工具导出的 HTML 文件夹。文件夹在浏览器 Worker 中打包为 ZIP，保留全部所选文件及中文、空格、嵌套子路径，只剥离所选目录本身这一层；打包完成后仍需确认上传。空目录不由浏览器文件列表表示，零字节文件会保留。此功能不读取 `.rp`，不监听目录变化，也不自动采集本地预览。

两种来源使用相同的已登录 multipart 接口，字段为 `versionName`、`description` 和 `file`：

| 操作 | 请求 | 结果 |
| --- | --- | --- |
| 新建版本 | `POST /prototype-hub/projects/{projectId}/versions` | 创建逻辑版本，接受 `Idempotency-Key`，仍受项目版本容量约束 |
| 替换已有版本 | `PUT /prototype-hub/versions/{id}` | 替换同一逻辑版本，不增加版本占用；不是不可变修订 |

浏览器文件夹入口采用保守预算：最多 5000 个文件、合计 128 MiB、单文件 64 MiB；输入大小预算用于控制浏览器负担，不表示固定内存峰值。超限、浏览器不支持目录选择/Worker 或打包失败时，可使用原有 ZIP 入口。ZIP 入口的浏览器上限仍为 500 MiB；后端独立执行配置中的上传大小、解压大小、条目数、路径和文件类型校验，其默认值分别为 ZIP 500 MiB、解压 2048 MiB、20000 条目。前端预算不会放宽服务器策略，目录中的不合法路径也不会被静默过滤后上传。

打包进度、上传进度及服务器处理状态分别展示。取消打包会终止本地 Worker；取消 HTTP 只结束浏览器等待，不能保证服务端尚未提交。网络中断或响应丢失时不会自动重发创建请求；同一弹窗保留原包和元数据，手动重试沿用原幂等键，后端按下节规则返回首次结果。更换内容或重新打开弹窗代表新的创建意图。

目录选择依赖浏览器的 [`webkitdirectory` / `webkitRelativePath`](https://developer.mozilla.org/en-US/docs/Web/API/File/webkitRelativePath) 能力；打包使用 [fflate](https://github.com/101arrowz/fflate) 的流式 ZIP。当前验证范围为本机 macOS 的 Chrome 与合成 HTML 资源；Safari、Firefox、Windows 及真实 Axure 导出包尚未验证。浏览器兼容与真实 Axure 导出包兼容是不同边界，上传及预览仍执行既有权限和 opaque sandbox 策略。

## 原型容量与创建重试

同一数据库的共享工作区最多保有 **5 个项目**，每个项目最多保有 **5 个逻辑版本**。用量按实际项目/版本记录计算，与操作者、列表筛选和应用副本无关；删除释放占用，编辑项目及 `PUT /prototype-hub/versions/{id}` 原位替换 ZIP 不增加占用。历史数据库已有超限数据时仍可启动、读取、分享、修改和删除，只拒绝继续新增；项目仍有版本时须先删除版本再删除项目。

以下路径相对于后端 API 根路径，响应列给出统一响应体的 `result`：

| 请求 | `result` | 权限 |
| --- | --- | --- |
| `GET /prototype-hub/projects/_usage` | `{installationId, projects: Capacity}` | `prototype-hub-project` 的 `query` |
| `GET /prototype-hub/versions/_usage?projectId=…` | `{projectId, versions: Capacity}` | `prototype-hub-version` 的 `query`，项目须存在 |

`Capacity` 包含 `used`、`limit`、`remaining`、`overLimit`、`canCreate`。当前 `limit=5`，`remaining=max(0,limit-used)`；达到上限时 `canCreate=false`，只有超过上限时 `overLimit=true`。`installationId` 是随数据库持久保存的 UUID，重启和数据库备份恢复不会主动重置，详见[数据说明](DATA.md)。用量为查询时快照，创建时仍由服务端事务重新校验。

`POST /prototype-hub/projects` 与 `POST /prototype-hub/projects/{projectId}/versions` 接受可选的 `Idempotency-Key` 请求头。提供时须为 1–128 个可打印 ASCII 非空白字符；客户端对同一次创建意图的失败重试沿用该键，修改创建内容或开始新的创建意图时换键。键按当前用户、创建动作和所属项目绑定；项目元数据、版本元数据及原始 ZIP 字节的 SHA-256 参与请求指纹。相同键与内容返回首次创建的同一资源，不再占用容量；内容不同或该资源已删除时返回冲突，不重新创建。回执与资源在同一事务提交，回执不保存原始键或完整请求；上传重放会清理本次临时文件。未提供键的客户端保持普通 POST 语义，不保证重复请求只创建一次。

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 409 | `PROTOTYPE_HUB_PROJECT_LIMIT_REACHED` | 工作区项目已达上限 |
| 409 | `PROTOTYPE_HUB_VERSION_LIMIT_REACHED` | 当前项目版本已达上限 |
| 400 | `PROTOTYPE_HUB_IDEMPOTENCY_KEY_INVALID` | 请求键格式无效 |
| 409 | `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT` | 同键内容不一致，或首次创建的资源已删除 |

更改 API/DTO/错误语义时同时更新前端请求类型与契约测试。当前完整接口以实际运行版本 OpenAPI 与源码为准，不能用旧审计路由数量宣称逐路由验收。
