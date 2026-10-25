# API 与权限边界

API 文档由运行实例的 `/v3/api-docs`、`/swagger-ui.html` 提供，是否公开由部署策略决定。Nginx 默认将 `/api/` 转发给后端；兼容前缀由现有过滤器处理，不应在前后端重复拼接。

系统管理包括用户、组织、职位、角色/分组、菜单、权限、字典和系统配置；访问/系统日志可使用本地或 ES 存储。SSO/资产列表在独立版未装相应集成时返回空，不表示完整支持企业应用/资产模块。

用户详情、用户列表与本人资料中的 `type` 统一返回枚举对象，例如中文请求返回 `{"id":"normal","value":"normal","text":"普通用户"}`；管理员类型为 `admin` / `管理员`。`id` 保留兼容，`value` 为标准枚举值。`GET /user/detail/types` 返回这两类筛选选项。文本遵循现有请求语言上下文（如 `Accept-Language: zh-CN` / `en-US`）；前端默认发送中文，无语言头时沿用框架的 JVM 默认语言。筛选条件仍传 `normal`、`admin` 等字符串，账号创建和更新不开放类型写入。历史未知类型原样保留标识和文本，空类型按普通用户展示，读取不会改写存量数据。

本人个人资料及头像沿自助入口校验当前身份；品牌图片使用管理上传权限。前端菜单/按钮不是安全边界，所有管理写入、文件读取、分享与关联操作须由后端执行权限和数据范围校验。多角色授权合并/撤销、禁用状态、分页上限和初始化幂等应有回归。

原型业务包括项目、逻辑版本、不可变修订、ZIP上传/更新、预览、页面树、Inspect与受控分享。分享/预览 URL 含能力密钥，禁止公开真实链接；错误与审计记录需脱敏。上传时验证 ZIP 大小/条目/深度、解压路径和内容类型，不执行包内安装脚本。

## 项目与版本分页查询

管理页使用 `POST /prototype-hub/projects/_query` 和 `POST /prototype-hub/versions/_query`。版本查询可附加 `?projectId=…`；指定的项目仍须在当前账号可读范围内，否则返回 404。两个入口分别要求项目、版本的 `query` 动作，不因已经登录或加入项目而免除动作校验。

```json
{
  "pageIndex": 0,
  "pageSize": 20,
  "keyword": "首页",
  "sortBy": "createTime",
  "sortOrder": "desc"
}
```

| 字段 | 默认值与约束 |
| --- | --- |
| `pageIndex` | 默认 0，范围 0–10000；从零计页 |
| `pageSize` | 默认 20，范围 1–100 |
| `keyword` | 可选，最长 128 字符；去除首尾空白后对项目名称或版本名称作字面包含匹配，空白表示不筛选。匹配区分大小写，`%`、`_`、`\` 均为普通字符 |
| `sortBy` | 默认 `createTime`；仅接受 `createTime` 或 `name`，版本的 `name` 对应 `versionName` |
| `sortOrder` | 默认 `desc`；仅接受 `asc` 或 `desc` |

响应沿用统一封装，`result` 为 `{data,total,pageIndex,pageSize}`。`total` 只统计与 `data` 相同的当前项目授权范围和关键词条件；权限、空白 owner 的旧数据限制及筛选均在数据库计数和分页之前执行，不返回全局总量。排序同值时以 ID 升序保证稳定顺序。越界页保留请求页码与实际总量，`data` 为空；不会因客户端提供 `total`、`rePaging` 等值而改写范围或自动跳页。非法分页或排序参数返回 400。

分页响应保留原有项目角色、能力及版本修订信息；版本 `revisionCount` 仅对本页最多 100 个版本作一次分组统计，不读取修订文件清单。发布过的 `GET /prototype-hub/projects`、`GET /prototype-hub/versions?projectId=…` 继续返回数组，供既有调用方兼容；需要有界查询的新调用方应使用分页入口。

## 项目成员与授权

项目数据按持久化归属隔离。项目、版本、页面树、上传/替换、分享管理及内部预览均先由 hsweb 检查通用资源动作，再与当前项目角色取交集；前端按钮显示不能代替后端授权。关联版本、分享 ID 由后端回溯其真实项目，不信任客户端额外传入的 projectId。非成员按不存在返回 404，项目内角色不足返回 403。

| 项目角色 | 项目范围内能力（仍须具备相应通用动作） |
| --- | --- |
| `viewer` | 查看项目、版本、页面树及换取内部预览凭证 |
| `editor` | 只读能力，修改项目资料、上传/替换/删除版本及管理公开分享 |
| `owner` | 编辑能力，管理成员及删除空项目 |
| `admin` | 有效 ADMIN 角色的显式平台例外，管理访问由审计记录；账号名称或 `type=admin` 不授予该例外 |

新建项目的当前用户成为 owner；`createdBy` 继续表示历史创建者。项目响应增加 `ownerId`、当前 `role`、`claimRequired` 和 `capabilities`（`read/edit/manageMembers/deleteProject/manageShares`）。owner 字段为空的待认领项目仅管理员可见，不会因自动建表而向全员开放；owner 停用时允许管理员重新认领，其他有效成员仍按原角色访问。旧库迁移及认领流程见[数据说明](DATA.md#项目归属迁移与认领)。

| 请求 | 行为与授权 |
| --- | --- |
| `GET /prototype-hub/projects/{id}/members` | owner/admin 查询成员，响应 `[{userId,name,username,role}]`，包含 owner |
| `PUT /prototype-hub/projects/{id}/members/{userId}` | owner/admin 添加或调整 `{"role":"editor"}` / `{"role":"viewer"}`；目标须为有效用户 |
| `DELETE /prototype-hub/projects/{id}/members/{userId}` | owner/admin 撤员；不能借成员接口改写或移除 owner |
| `GET /prototype-hub/projects/{id}/member-candidates?keyword=…` | owner/admin 搜索有效用户，名称或用户名忽略大小写作字面包含匹配，`%`/`_` 不作通配符；按用户名、ID 稳定顺序最多返回 20 个 `userId/name/username`，不返回密码或联系资料 |
| `POST /prototype-hub/projects/{id}/claim` | ADMIN + 项目 save；`{"userId":"…"}` 为无可用 owner 的项目指派有效用户，不覆盖活跃 owner |
| `POST /prototype-hub/projects/_ownership-migration` | ADMIN + 项目 save；`{"apply":false}` 只预检，`true` 显式幂等回填；响应 `scanned/assigned/pending/unchanged` |

删除账号会同步清除其成员和预览授权；原所有项目进入待认领。同名重建账号不会自动继承原归属，需管理员显式认领或重新添加成员。

普通成员不能通过自己的项目角色获得平台用户管理、任意角色授权或其他项目的数据访问。公开分享是一项独立外部授权：撤员会撤销内部凭证，但不会自动撤销已经创建的公开分享；要阻止持有公开链接者继续访问，应暂停、永久撤销或删除对应分享。

## 内部预览凭证

`POST /prototype-hub/versions/{id}/preview-session` 在当前成员和 `prototype-hub-version.query` 校验后返回 `{runtimeBaseUrl,previewUrl,expiresAt}`，有效期 5 分钟。同一用户/版本最多保留 8 个凭证，旧凭证会被裁减；不要将这些 URL 放进公开日志或 Issue。版本列表、详情、上传和替换响应中的 `runtimeBaseUrl`、`previewUrl` 保留为 null，不再随普通查询发放长期匿名访问能力。

凭证固定签发时的不可变 `revisionId`；默认入口解析当前修订，也可通过下节的修订入口选择历史。每次运行文件读取都会重新校验账号、通用版本查询权限、当前项目归属及授权代次。撤员后重新加入不会复活旧凭证，调整成员也会使此前内部凭证失效；更新 ZIP 后旧凭证在剩余有效期内继续读取原修订，既不会漂移到新内容，也不因此获得其他修订权限。到期后前端可在当前身份下重新获取，失败时清除原预览上下文。已经返回浏览器的内容无法远程收回。

持久化旧 `previewKey` **立即停止授权**。旧的内部嵌入 URL 需从管理平台重新打开换取短期凭证；前后端须配套部署，不能混跑仍接受长期 key 的旧后端节点。公开分享使用自身的 token/accessKey 与有效期，仍沿独立公开入口访问。所有原型运行文件维持 `no-store`、`no-referrer` 与 opaque sandbox，不注入平台令牌、不开放 `allow-same-origin`。

## 不可变修订与更新冲突

`versionId` 表示逻辑版本，`revisionId` 表示一次已校验发布的文件快照。版本响应包含 `currentRevisionId`、本响应所属 `revisionId`、历史数量 `revisionCount` 和 `migrationRequired`；通常两个 ID 相同，创建回执重放时可能不同。修订响应包含 `id/projectId/versionId/versionName/description/entryFile/manifestSha256/fileCount/totalBytes/createdBy/createdAt`，名称和说明保留上传时值，不返回内部存储路径或完整清单。

| 请求 | 行为 |
| --- | --- |
| `GET /prototype-hub/versions/{id}/current-revision` | 解析当前修订身份 |
| `GET /prototype-hub/versions/{id}/revisions?after=…&limit=50` | 历史数组，按创建时间和 ID 倒序；`limit` 为 1–100，`after` 必须属于该逻辑版本 |
| `GET /prototype-hub/versions/{id}/revisions/{rid}` | 固定修订元数据 |
| `GET /prototype-hub/versions/{id}/revisions/{rid}/pages` | 发布时保存的页面树 |
| `POST /prototype-hub/versions/{id}/revisions/{rid}/preview-session` | 换取固定修订的五分钟内部预览 |

这些入口沿用 `prototype-hub-version.query` 与当前项目读取范围，不能通过拼接其他项目或逻辑版本的修订 ID 越权。页面树新增 `pageId=SHA256(规范化相对路径 UTF-8)`；页面身份是 `(revisionId,pageId)`，相同路径跨修订不表示相同业务状态。当前 `/versions/{id}/pages`、默认预览及省略修订的新建固定分享均解析当前修订。

更新 multipart 可增加 `expectedRevisionId`，值取用户开始更新时的 `currentRevisionId`。省略时服务端也会在接收和文件校验前捕获当前指针；最终短事务重新检查权限和指针，仅一份并发更新可成功。冲突返回 409 / `PROTOTYPE_HUB_VERSION_CONFLICT`，需刷新后明确重新选择，不能自动覆盖。ZIP、清单或数据库提交失败时保留上一修订与文件；取消 HTTP 不等于取消已经提交的更新。成功更新保留历史文件，不再删除上一目录。

新建公开分享默认固定创建时的当前修订；也可按下节选择历史修订或显式跟随最新。删除项目和逻辑版本使用软删除，契约见下节；不提供删除单修订的入口。

历史版本没有 `currentRevisionId` 时，列表和详情可显示 `migrationRequired=true`；当前修订、页面、更新及新建分享返回 409 / `PROTOTYPE_HUB_REVISION_MIGRATION_REQUIRED`。无修订绑定的旧公开分享不可用，旧内部会话不再授权。管理员在停写并完成联合备份后调用 `POST /prototype-hub/versions/{id}/revision-migration`，请求 `{"apply":false}` 预检、`true` 显式幂等迁移；需要有效 ADMIN 和版本 save。响应为 `versionId/migrationRequired/revisionId/manifestSha256/fileCount/totalBytes/sharesToBind/receiptsToBind/applied`，`migrationRequired` 表示操作完成后状态，两个数量表示操作前待绑定数量。详细升级步骤见[数据说明](DATA.md#不可变修订与旧内容迁移)。

## 固定修订交付包质量报告

`GET /prototype-hub/versions/{id}/revisions/{rid}/quality` 按保存清单及该固定修订的实际 HTML/CSS 字节检查交付包。沿用 `prototype-hub-version.query` 与当前项目读取范围；活动父项目、逻辑版本和修订必须精确关联，不接受公开分享证明，也不将历史目标替换成 current。每次请求重新鉴权，不保存报告或修改文件。

统一响应的 `result` 包含 `versionId/revisionId/manifestSha256/entryFile`、`summary`、`limits`、`complete` 和 `findings`。客户端只对这份有界结果分页和下载 JSON，不调用额外下载接口；下载内容对应已显示的固定修订，无访问令牌、内部存储根路径或完整 manifest。

| 字段 | 含义 |
| --- | --- |
| `summary.files/scannedDocuments/scannedBytes/checkedReferences` | 清单文件数、已解析 HTML/CSS 文档数及字节数、已处理静态引用数；引用数包含显式列出的未检查类别 |
| `summary.errors/warnings/findingsTotal/omittedFindings/skippedDocuments` | 去重后的错误、警告、问题总数、因结果上限省略数，以及无法完整读取/解析的文档数 |
| `summary.uncheckedReferences` | `external/rootAbsolute/fragment/unsupportedScheme` 四类未核验引用计数；外部、站点根地址、片段和非文件协议不表示已检查成功 |
| `limits` | 单文档 `maxTextBytes=2097152`、累计 `maxTotalTextBytes=33554432`、最多 `maxReferences=100000` 个引用和 `maxFindings=1000` 条问题；客户端不能提高上限 |
| `findings[]` | `code/severity/sourcePath/targetPath/reference`；均以纯文本展示，原始引用最多 1024 字符，按来源、代码、目标和引用稳定排序 |

问题代码为 `entry_missing`（入口缺失）、`file_missing`（清单文件实际缺失）、`missing_resource`（资源引用缺失）、`broken_internal_link`（内部页面链接无目标）、`chinese_path`（中文路径兼容性提示）、`text_unreadable`（无法解析文本）、`document_limit/reference_limit/findings_limit`（对应预算限制）以及 `invalid_reference`（无法安全解析的本地引用）。级别为 `error` 或 `warning`；中文路径本身不阻止预览或判为错误。

`complete=true` 只表示声明的本地静态检查已完成，允许存在已报告的问题；预算耗尽、文件缺失/无法读取或省略问题时为 false。外部 URL、根绝对地址、片段目标和非文件协议单独计为未检查，不因这一计数将 complete 强制改为 false。平台注入的 Inspector 脚本不计作交付包缺失资源。报告不发送网络请求、不执行 JavaScript，也不证明动态链接、交互状态或业务流程正确；它不替代上传安全、ZIP 路径检查或人工验收。

损坏或不支持的清单返回 400 / `PROTOTYPE_HUB_MANIFEST_INVALID`；未登录 401，缺通用查询动作 403，目标不存在/跨范围/已软删 404，未迁移版本 409。磁盘内容缺失作为报告问题返回，不能给出“全部通过”的结论。

## 同版本修订比较

`GET /prototype-hub/versions/{id}/revisions/_compare` 比较同一活动逻辑版本的两个明确修订。它沿用 `prototype-hub-version.query` 与当前项目读取权限，不增加菜单或权限项；公开分享不提供比较或额外历史授权。

| 参数 | 默认值与约束 |
| --- | --- |
| `leftRevisionId`、`rightRevisionId` | 必填、非空且均属于路径中的版本；允许相同修订，不回退到 current |
| `pagesPageIndex`、`filesPageIndex` | 分别从 0 计页，非负 |
| `pagesPageSize`、`filesPageSize` | 分别默认 50，范围 1–100 |
| `includeUnchanged` | 默认 false；true 时包含未变化项 |

统一响应的 `result` 包含 `versionId`、`left/right` 修订元数据、`summary.pages/files` 的 `added/removed/changed/unchanged` 完整计数，以及 `pages/files` 两个独立的 `{data,total,pageIndex,pageSize}`。`total` 统计应用 includeUnchanged 后的行数，summary 始终统计全部路径；结果按区分大小写的相对路径升序，越界页为空，不自动重定位。

变化方向为 left → right。文件以清单中的精确路径配对，同路径 SHA-256 或字节数不同为 changed；文件项两侧包含 `size/sha256/mediaType`，仅单侧存在时另一侧为 null。页面排除文件夹节点，但继续遍历其子节点及具有子节点的页面，同路径仅取第一个别名；名称或对应文件内容变化为 changed。页面项两侧保留 `pageId/name/path`，旧合法清单的 null pageId 不会被伪造补齐。它不识别重命名、不作语义或像素级比较，也不修改当前修订。

两侧预览分别通过既有固定修订会话接口新取凭证，并保持各自选页、到期处理和 opaque sandbox。可分享的管理端比较定位只携带版本、修订及页面标识，不携带 token、accessKey 或预览 URL；定位本身不授予读取权限。

非法参数或损坏清单返回 400，未登录返回 401，缺少通用动作或项目内角色不足沿既有规则返回 403；修订不存在、关联其他版本/项目或目标已软删返回 404，未迁移版本返回 409。每次比较重新校验当前授权和目标状态；保留数据及旧预览 URL 不扩大读取范围。

## 项目与版本软删除

`DELETE /prototype-hub/versions/{id}` 保留逻辑版本、当前指针、全部修订、创建回执与文件，在原事务锁边界内写入 `deletedTime`（Unix 毫秒）。`DELETE /prototype-hub/projects/{id}` 同样保留项目与成员谱系；有活动版本时仍返回 409，只有已删除版本不阻止删除项目。重复访问已删除对象按不存在处理，不能用 ADMIN 例外读取已删除业务对象。

删除释放当前项目或逻辑版本占用，新的创建使用新的 ID；安装身份及项目绑定保持不变。尚未永久清理的已删除资源，其原创建键重放返回 409 / `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT`，不复活资源，也不重新消耗容量；父项目随后删除仍保留该回执冲突语义。

列表、分页总量、直接 ID、成员、上传、修订、页面树和新预览授权均排除已删除对象与已删除父项目。删除在同一事务中清除内部预览会话，并永久撤销、停用其保留的公开分享；新公开信息/密码校验以及删除前发出的分享 HTML、资源和页面树请求均返回不可用（404）。删除前签发的内部 runtime 后续请求维持 403。引用保留仅保护数据，不授予继续访问；已下载的内容无法远程收回。

已删除项目和版本从各自 `deletedTime` 起保留 **30 × 24 小时**。到期条件为 `deletedTime <= 服务端当前时间 - 30 天`；到期即从回收站计数和分页中排除，恢复返回 404，即使后台尚未物理清理或文件仍存在，也不能恢复。保留期固定，不提供手工永久删除或单修订删除接口。后台在原数据库锁内重新检查删除状态和期限，删除到期版本、全部修订、分享、会话及关联创建回执，并在同一事务登记文件清理；父项目只在没有任何子版本行后清理成员等关联及自身，不提前级联删除较新的子版本。文件由原有有界队列异步重试删除，I/O 失败或重启可能延迟磁盘回收，不能将 30 天理解为文件在到期瞬间已消失。历史不明来源任务继续隔离。文件和数据库需同窗口联合备份，详见[数据说明](DATA.md#不可变修订与旧内容迁移)。

## 回收站与逐项恢复

| 请求 | 授权与行为 |
| --- | --- |
| `POST /prototype-hub/projects/_deleted/_query` | 项目 `query` 动作；只查询当前 owner 或有效 ADMIN 可恢复的已删除项目，editor/viewer 不返回该项目 |
| `POST /prototype-hub/versions/_deleted/_query?projectId=…` | 版本 `query` 动作；父项目须活动且当前账号有编辑能力，owner/editor/admin 可查询，viewer 不可查询 |
| `POST /prototype-hub/projects/{id}/_restore` | 项目 `save` 动作与当前 owner/admin；只恢复父项目，返回同一 ID 的项目响应，已删除子版本仍留在回收站 |
| `POST /prototype-hub/versions/{id}/_restore` | 版本 `save` 动作与活动父项目的编辑能力；只恢复指定逻辑版本，返回同一 ID 的版本响应 |

两种回收站查询复用上方分页 DTO、大小限制、名称字面匹配及稳定排序；授权、删除状态与未满 30 天的期限在数据库 COUNT 和分页前生效，不接受客户端 `includeDeleted` 来扩大普通查询。响应增加 `deletedTime`，活动对象为 null，已删除对象为删除时的 Unix 毫秒；不增加客户端可改写的到期字段。

恢复按当前身份、当前项目角色及默认 5/5 容量重新校验，不沿用删除时的权限或配额。恢复和新建使用相同数据库锁边界，多副本争抢最后名额只有一次成功。容量不足返回原项目/版本容量 409；对象已经活动返回 409 / `PROTOTYPE_HUB_RESOURCE_ACTIVE`；不存在、保留期已满或父项目仍删除返回 404。权限不足沿用项目范围的 403/404 规则，失败不会清空删除标记、改变引用或占用额度。

恢复保留原项目/版本 ID、成员谱系、当前修订指针、全部修订和创建回执，不新增回执。恢复后同一原创建键仍须通过当前权限和指纹校验，成功只重放同一 ID 与原成功快照，不额外占用名额。删除记录尚未永久清理时旧键仍冲突；资源及关联回执永久清理后，该键不再提供去重保证，相同键的新请求仍须通过当前权限和容量校验，可能创建新 ID，不会复活旧 ID。恢复父项目不会复活其子版本、旧内部预览或已经撤销的分享。恢复后需重新打开内部预览或新建分享；旧公开链接、旧密码授权及旧运行文件凭据继续拒绝。

## 修订页面深链

平台内预览路由为 `/preview/{projectId}/{versionId}?revisionId={rid}&pageId={pageId}`。未指定修订的旧链接只在进入时解析一次当前修订，随后将实际修订和选中页面写入地址；刷新会重新校验当前身份并换取该固定修订的短期凭证，后续上传不会让链接悄悄切到新内容。浏览器前进、后退只切换同一已授权修订内的页面，切换修订则重新鉴权。显式错误、重复或不存在的定位参数不可自动退回当前修订或首页。

`pageId` 必须精确匹配本次授权修订页面树中的唯一页面，运行路径取自该节点，不接受地址栏传入任意文件路径；目录不能作为页面目标。页面名称和路径查找只在已授权树内进行，不扩大数据范围。页面树中没有稳定标识的旧节点可以展示，但不能生成声称固定页面的链接。页面身份始终是 `(revisionId,pageId)`，不能仅凭相同路径跨修订定位。

公开预览路由为 `/share/{token}?revisionId={rid}&pageId={pageId}`，仍按原分享策略重新获取授权；查询参数不授予历史修订访问权。`latest` 分享发布 R2 后，原来固定 R1 的页面深链重新打开时会显示目标已变化或不可用，不能自动展示 R2，也不能据此重新获取 R1 的授权；原先已经签发的 R1 凭据仍按分享生命周期规则校验。需要长期固定 R1 的公开链接时，应使用绑定 R1 的 `fixed` 分享。分享密码、派生访问密钥、短期内部凭证和平台登录令牌均不写入深链或复制结果。

无成员范围的内部定位请求返回 404，项目内角色或通用动作不足维持原 403；已签内部运行凭证到期、撤权也维持原 403。公开分享过期、暂停、撤销或删除后返回原有不可用错误，深链不能绕过这些校验。错误时清除旧预览，不继续显示上一次目标。

## 分享生命周期

管理接口沿用 `prototype-hub-share` 的 `query/save/delete` 动作，并与项目的分享管理范围取交集；owner、editor 和有效 ADMIN 例外可管理，viewer 不能借通用 save 权限获得分享管理能力。管理响应增加 `revisionMode/revisionId/revoked/configVersion`，不返回派生密钥、密码散列或密码明文。公开信息中的 `revisionId` 始终为本次实际授权的修订。

| 请求 | 参数与行为 |
| --- | --- |
| `POST /prototype-hub/versions/{id}/shares` | 原 `password/expiresAt/description` 保留；可选 `revisionMode`，默认 `fixed`。fixed 可指定该逻辑版本下的历史 `revisionId`，省略则绑定锁内当前修订；latest 禁止传修订 ID。 |
| `PUT /prototype-hub/shares/{id}` | 完整配置替换：`expectedVersion/description/expiresAt/revisionMode/revisionId/passwordAction/password`。description、expiresAt 为 null 时清空；不改变启停状态。 |
| `PUT /prototype-hub/shares/{id}/enabled` | `{"expectedVersion":1,"enabled":false}` 暂停；true 重新启用。 |
| `POST /prototype-hub/shares/{id}/rotate-link` | `{"expectedVersion":1}` 原子轮换链接 token 与访问密钥，记录 ID 和内容策略保持不变。 |
| `POST /prototype-hub/shares/{id}/revoke` | `{"expectedVersion":1}` 永久撤销，保留管理记录；撤销后不能编辑、启用或轮换。 |
| `DELETE /prototype-hub/shares/{id}` | 保留原有物理删除语义及 delete 权限，不要求 expectedVersion。 |

`passwordAction` 必填并使用 `keep/replace/remove`：keep 保留密码，replace 必须同时提供新密码，remove 去除密码；新密码遵守 BCrypt 的 UTF-8 72 字节上限。普通 fixed → fixed 编辑省略 revisionId 时保留已有修订，不因上传新包而移动；latest → fixed 必须明确选择修订。配置中的有效期为 Unix 毫秒，新的非空有效期必须在未来。

新记录的 `configVersion=1`；每次成功编辑、启停、撤销或轮换递增，客户端提交最近读到的 expectedVersion。并发或过期版本返回 409，需刷新记录后由用户确认重试，不能自动覆盖。所有这些管理变更都会轮换内部派生密钥，暂停后重新启用也不会复活旧的 HTML、资源或页面树凭据；轮换链接另外使旧 token 不再可用。

`fixed` 在打开时读取指定修订，`latest` 在公开信息/密码校验时解析一次当前修订。两者均返回明确包含修订的 `runtimeBaseUrl/previewUrl/pagesUrl`，客户端应使用返回 URL，不能自行拼接当前修订。一次授权后的页面树、HTML 与相对资源保持该快照，即使期间上传新修订；每次资源读取仍检查最新分享启停、有效期、撤销状态及密钥。修改密码后，正在完成的旧密码校验也不能取得新配置的密钥。受密码保护的信息在校验前不包含运行或页面树访问凭据；公开客户端不发送平台登录 token。

新规范路径为 `/prototype-hub/public/shares/{token}/{derivedKey}/revisions/{rid}/runtime/**` 和同级 `/pages`。已发布的无 rid 分享资源路径仅作为固定分享兼容入口，接受原有效访问密钥或当前固定修订的派生密钥，均固定到持久化的修订；跟随模式不接受该入口。既有空 revisionId 分享仍须显式旧内容迁移，不能退回读取当前版本。已发给浏览器的内容无法远程收回，外围代理与日志也须避免记录完整凭据。

## 分享邮箱身份

访问者身份由分享管理员逐条开启，默认关闭，不影响免账号预览。默认使用邮箱OTP；管理员还可单独允许只填姓名的低保障身份，界面明确标为“未验证”。两种身份都不创建平台用户，姓名/公司均为自述，邮箱验证也不代表实名或公司认证。固定分享支持手动复制定向邀请，仍需指定邮箱重新OTP；开启页面反馈后可进行文字反馈与网页回复，不含人工身份关联或业务邮件提醒。

`GET /prototype-hub/shares/{id}/review` 返回 `{enabled,allowUnverified,allowComment,mailAvailable,configVersion}`，需要分享query及项目SHARE范围；`PUT` 同路径接受 `{enabled,allowUnverified?,allowComment?,expectedVersion}`，需要分享save及同一范围。两个许可的旧空值均按false，旧客户端省略或传null时保留已有设置，不因普通分享编辑或SMTP故障而开启。低保障入口要求身份总开关与该许可同时启用。页面反馈还要求有效固定分享及修订；enabled=false同时显式allowComment=true返回400，关闭身份时新界面同时提交allowComment=false。旧客户端关闭身份可保留存储标志，但返回的有效allowComment为false。策略更新沿现有CAS并轮换分享凭据，旧预览和身份失效，重新开启不恢复旧会话；409后须刷新并重新确认。已通过密码授权的公开分享提供 `reviewEnabled/reviewAvailable/reviewUnverifiedAllowed`；最后一项仅在分享授权、身份总开关和低保障许可同时有效时为true，身份context也返回两个许可的有效值。

公开入口相对于 `/prototype-hub/public/shares/{token}/review`，均为JSON POST，始终提交当前分享证明 `{revisionId,accessKey}`。固定与跟随模式都绑定本次明确修订，不隐式切换current。所有请求要求精确受信Origin，拒绝缺失、null及其他站点；代理配置见[运维说明](OPERATIONS.md#邮箱身份与smtp)。

| 子路径 | 额外字段 | `result` |
| --- | --- | --- |
| `/context` | 无 | `{revisionId,enabled,allowUnverified,allowComment,mailAvailable,csrfToken,session}`，匿名session为null |
| `/challenges` | `email,displayName,company?,invitationToken?` | `{challengeId,expiresAt,resendAfter}`，不返回验证码或邮箱 |
| `/verify` | `challengeId,code` | 同context，成功session非空，轮换Cookie与CSRF |
| `/unverified` | `displayName,company?`，不收集邮箱 | 同context，明确返回未验证身份；仅显式允许时可用，不发送邮件 |
| `/logout` | 无 | `{loggedOut:true}`，仅撤销当前身份并轮换匿名Cookie |

context设置 `prototype-review` Cookie：HttpOnly、默认Secure、SameSite=Strict、无Domain、最长八小时，Path固定为 `/api/prototype-hub/public/shares/{token}/review`。其余四项同时要求Cookie与context返回的 `X-Review-CSRF`。退出后重取context；Cookie/CSRF不进入原型iframe、URL、postMessage或本地持久存储。只有专用review客户端使用同源Cookie，其他公开预览客户端仍省略凭据。

身份仅含 `{guestId,displayName,company,maskedEmail,verificationLevel,expiresAt}`。`verificationLevel`为 `email` 或 `unverified`；邮箱身份返回脱敏邮箱，未验证身份的 `maskedEmail`为null。姓名trim后非空且最多100字符，公司可空且最多200字符；姓名和公司拒绝Unicode控制字符，不以trim吞掉非法字符。邮箱OTP请求中的邮箱trim并整段按不区分大小写规范化，最多254字符。同项目同邮箱跨设备重新OTP恢复相同guestId，其他项目独立；恢复仍需当前分享和密码证明，不按姓名合并。

未验证身份使用服务端随机ID，与已验证联系人隔离。八小时从首次签发起固定计算；对同一有效未验证会话重复提交只返回原身份及原到期时间，不改姓名/公司、不续期。有效邮箱身份调用 `/unverified` 返回409 / `PROTOTYPE_HUB_REVIEW_IDENTITY_EXISTS`，需先显式退出。会话丢失、退出、到期或被撤销后，不能按姓名、公司、邮箱或OTP找回该未验证身份；重新申请得到另一身份。后续邮箱验证也不自动合并历史未验证身份。分享配置换代等使旧会话失效后，持有新分享证明的访客可再次明确申请新的随机身份，即使浏览器仍带旧Cookie；这不会找回旧ID或延长旧身份。未知会话等级仍被拒绝。当前没有人工恢复或关联入口，需联系项目管理员线下处理。

验证码六位ASCII数字、五分钟有效、最多五次错误、一次使用；错误尝试持久提交，并发正确验证仅一次成功。同项目同邮箱重发使旧码失效。每项目每小时最多60封，项目内每邮箱5封并冷却60秒，数据库保证跨副本一致；SMTP失败也占发送限额。pending、失败或取消发送不能验证，不自动重试不确定发送或降低验证等级。

新建未验证身份使用独立的数据库限频域：每项目每小时60次、当前Cookie每小时5次且冷却60秒；不占用或退还OTP发送窗口。同一有效会话的原样返回不计新建。Cookie限频不等同实名或设备识别，清除Cookie不能绕过项目总量上限。SMTP未配置或发送失败时，仍须由管理员显式允许、由访客主动选择此入口，不自动降级。

未知、错误、过期、已用或不匹配的challenge统一400 / `PROTOTYPE_HUB_REVIEW_CODE_INVALID`。非法输入400，分享证明/Origin/CSRF不符或未允许低保障身份403，分享不可用404，配置或身份状态冲突409，限频429；SMTP未配置或失败只使邮箱发码返回503。分享修改、停用、撤销、到期或软删均逐请求阻断旧身份，重新启用不复活旧授权；邮箱身份到期需重新OTP，未验证身份到期不能恢复原ID。

### 固定分享邀请

邀请只确认指定邮箱在同一固定分享下的身份，不将分享变为“仅受邀者访问”，不改变普通邮箱OTP或管理员已允许的未验证入口。邀请链接不代替分享密码或访问证明，不是免验证登录链接，也不会自动发送邀请邮件。

管理接口基于 `/prototype-hub/shares/{shareId}/review-invitations`，查询要求 `prototype-hub-share.query`，创建/撤销要求 `prototype-hub-share.save`；各项均同时检查当前项目SHARE能力。

| 请求 | 输入/结果 |
| --- | --- |
| `POST` 基础路径 | `{email,expectedShareVersion}`；返回 `{invitation,invitationToken}`，原始邀请凭据仅本次响应提供 |
| `POST /_query` | `{pageIndex:0,pageSize:20}`；返回标准服务端分页，记录不含原始凭据或完整邮箱 |
| `PUT /{invitationId}/revoke` | `{expectedVersion}`；返回当前邀请记录，不能撤回已成功签发的访客会话 |

`invitation` 为 `{id,maskedEmail,status,configVersion,createdAt,expiresAt,consumedAt}`，时间使用Unix毫秒，未消费的consumedAt为null。状态为 `pending/consumed/revoked/expired/invalidated`；列表根据当前分享绑定与访客状态判定，不仅依赖存储的撤销标记。页码0–10000、每页1–100，按创建时间降序、ID升序；越界页保留total并返回空data，不接收任意查询表达式。

只允许活动、已开启访问者身份且已绑定有效修订的 `fixed` 分享创建邀请；`latest` 须先显式改为固定修订。创建提交刚读取的分享配置版本，陈旧版本返回409；邀请有效期为创建后24小时与分享有效期的较早者，后续上传不会移动邀请目标。创建使用独立数据库限频：每项目每小时60次、项目内每收件邮箱5次并冷却60秒，不占用或退还OTP发送窗口。

撤销前先校验邀请的expectedVersion；同版本已撤销记录可原样返回，pending变更为revoked并增加版本。已消费、到期或失效记录返回409，不能重新启用。分享配置换代、暂停、撤销、到期、关闭身份或资源回收使旧待用邀请失效；邮箱访客停用及重新启用后的状态版本也必须匹配，不能让旧邀请复活。撤销邀请不撤销已建立的身份，需要时另用访问者管理停用该访客。

管理员从现有同源分享地址生成 `#reviewInvitation=TOKEN` 链接并明确复制，关闭弹窗或切换分享即清空原始凭据。公开外壳先捕获并清除片段，仅在当前同一分享的内存中保留；它不进入查询字符串、原型URL、postMessage、本地存储或日志。访客自己输入指定收件邮箱，不提供公开邀请详情或邮箱回显。即使已有身份，也须先明确退出并完成本次新的邮箱OTP；退出不自动放弃同页待用邀请。

带邀请的challenge在准备、SMTP受理确认和验证消费时重验邮箱、固定修订、分享配置/密钥代次及访客状态版本。invitationToken非null时必须为43位URL安全字符，格式非法返回400；格式合法但未知、已用、撤销、到期或收件人/范围不匹配的邀请统一返回403 / `PROTOTYPE_HUB_REVIEW_INVITATION_INVALID`，不静默回退普通发码。verify仍只提交原有 `{revisionId,accessKey,challengeId,code}`，邀请关联从challenge读取，不能另传替换邀请。正确OTP在同一事务消费邀请与challenge并建立邮箱身份/会话，并发只能成功一次；错误验证码提交尝试次数但不消费邀请。SMTP仍只发必要验证码，失败不自动重试或降低身份等级。用户可明确放弃邀请后独立使用普通身份入口，不自动关联未验证身份。

### 项目访问者管理

项目详情按邮箱已验证与未验证身份分别分页，不创建平台用户或新的全局菜单。以下接口分别要求 `prototype-hub-share:query/save`，并同时检查当前项目的 SHARE 能力；项目所有者、编辑者和有效管理员按现有能力操作，只读成员不能查询或修改。跨项目或跨身份等级替换guestId返回404；已到期未验证身份的状态更新也返回404，不能仅凭访客ID访问。

| 请求 | 输入/结果 |
| --- | --- |
| `POST /prototype-hub/projects/{projectId}/review-guests/_query` | `{verificationLevel:'email',pageIndex:0,pageSize:20}`；等级可为 `unverified`，省略时查email；返回标准分页，含真实total和data |
| `PUT /prototype-hub/projects/{projectId}/review-guests/{guestId}/enabled?verificationLevel=unverified` | `{enabled,expectedVersion}`；查询参数省略时操作email身份，返回当前状态 |

等级仅接受精确的 `email`、`unverified`，无效值返回400，不能回退为邮箱查询或合并两类数据；状态更新的查询参数只允许单个值，显式空值或重复参数也返回400。页码0–10000，每页1–100；按更新时间降序、ID升序排序。越界页保留真实total并返回空data，不回退最后一页；不接收任意查询表达式。列表与修改结果仅含 `{id,displayName,company,maskedEmail,verificationLevel,enabled,configVersion,createTime,modifyTime,expiresAt}`，不提供完整邮箱、会话或验证码摘要；邮箱身份expiresAt为null，未验证身份返回固定到期时间及null maskedEmail。

旧邮箱行的enabled空值视为true、configVersion空值视为0。修改必须提交刚读取的版本；陈旧版本返回409，刷新后由操作者重新确认，不自动重试。相同版本和相同状态无副作用；实际转换才增加版本和修改时间，并在项目锁事务中移除对应身份会话，邮箱身份还撤销该项目规范邮箱的验证码。未验证身份没有邮箱验证码，操作它不会修改同名邮箱联系人。

停用后已打开设备的context返回session:null，匿名原型预览仍依分享自身策略。重新启用不会恢复任何旧Cookie、旧验证码或到期时间：邮箱身份必须重新OTP且保留原guestId；未验证身份无法重新证明原身份，新建也不会找回旧ID。发送中的邮件可能已被SMTP受理，但状态切换阻止该旧验证码用于验证，平台不能撤回已投递邮件。管理状态变更不退还发送或身份创建限额，不改变其他身份或项目的数据。

## 固定页面文字反馈

反馈绑定平台外壳选中的固定修订与唯一manifest页面，不跟随iframe内部导航。仅启用身份和allowComment的有效fixed分享可用；旧页面没有唯一pageId、页面不在清单或分享跟随最新时不提供反馈入口。服务端按清单校验pageId并生成pageName/pagePath，不接受客户端提供作者或页面路径作为授权依据。当前支持纯文字线程、网页双向回复、内部备注及解决/重开；不含点/矩形/椭圆、附件、指派、消息编辑删除或业务通知。

公开接口基于 `/prototype-hub/public/shares/{token}/review`，所有请求均要求受信Origin、当前HttpOnly Cookie、`X-Review-CSRF`，返回no-store，并提交 `{revisionId,accessKey,pageId}`：

| 请求 | 额外输入 | 结果 |
| --- | --- | --- |
| `POST /threads/_query` | `{pageIndex,pageSize,state?}` | 本人线程的PagerResult |
| `POST /threads/{threadId}/_detail` | 无 | 当前本人范围内的单个Thread，不受列表分页或state筛选影响 |
| `POST /threads` | `{text,clientRequestId}` | Thread，含初始公开消息的原子创建结果 |
| `POST /threads/{threadId}/messages/_query` | `{pageIndex,pageSize}` | 仅公开消息的PagerResult |
| `POST /threads/{threadId}/messages` | `{text,clientRequestId}` | Message，作者始终来自当前访客会话 |
| `PUT /threads/{threadId}/status` | `{state:'open',expectedVersion,clientRequestId}` | Thread，访客只能重开 |

本人范围同时匹配项目、逻辑版本、分享、固定修订、页面、保障等级命名空间和guestId；不能读取其他客户、其他分享或另一保障等级的线程。同项目邮箱重新OTP可找回同一身份，但仍须当前相同分享授权；新分享不继承原分享线程，未验证身份丢失或到期后不能按姓名/邮箱恢复。分享或访客停用、身份过期、禁反馈后，公开读取与写入均失效，不删除已有反馈。

管理接口基于 `/prototype-hub/versions/{versionId}/review-threads`，查询要求版本query，写入要求版本save，且始终同时要求当前项目EDIT。仅有项目READ的查看者不能读取客户反馈或内部备注：

| 请求 | 输入 | 结果 |
| --- | --- | --- |
| `POST /_query` | `{revisionId,pageId,pageIndex,pageSize,state?}` | Thread的PagerResult |
| `POST /{threadId}/_detail` | `{revisionId,pageId}` | 当前管理范围内的单个Thread，不受列表分页或state筛选影响 |
| `POST /{threadId}/messages/_query` | `{revisionId,pageId,pageIndex,pageSize}` | 含内部备注的Message分页 |
| `POST /{threadId}/messages` | `{revisionId,pageId,text,visibility:'public'|'internal',clientRequestId}` | Message，成员作者从当前登录身份获取 |
| `PUT /{threadId}/status` | `{revisionId,pageId,state:'open'|'resolved',expectedVersion,clientRequestId}` | Thread |

管理端不新建客户顶层线程。分享或访客停用后，有权成员仍可处理归档反馈；项目、版本或修订本身不可用时继续拒绝。线程为 `{id,revisionId,pageId,pageName,pagePath,summary,authorName,authorCompany,verificationLevel,state,configVersion,createdAt,updatedAt}`，summary取初始公开文本前200个Java字符，作者资料与保障等级为首次投稿快照。消息为 `{id,threadId,text,authorType,authorName,authorCompany,verificationLevel,visibility,createdAt}`；authorType为guest/member，成员verificationLevel为null。响应不返回作者ID、完整邮箱或凭据。

线程详情沿用上述页面与当前身份校验，不从threadId反推或授权另一页面。缺失、其他分享/访客/身份等级或不匹配项目、版本、修订、页面的线程统一返回404 / `PROTOTYPE_HUB_REVIEW_THREAD_UNAVAILABLE`；分享策略、会话和页面自身失效仍返回各自既有错误。详情只返回同一Thread投影，不附带内部消息数量或时间，不修改状态、限频、回执或领域事件。消息仍通过原分页接口读取。

成员预览和公开分享可在原页面深链中加入 `threadId`：`?revisionId={rid}&pageId={pageId}&threadId={threadId}`。threadId为43位URL安全随机标识；线程目标必须是三个单值、非空、格式有效的完整标识，重复、非法或不完整目标显示错误，不自动打开首页、另一线程或新建投稿。URL仅定位，仍须完成当前项目授权或分享密码、访客身份及待用邀请流程；完成后继续定位，不自动发码、退出身份、放弃邀请或提交文字。未验证身份丢失后，链接不能恢复原身份。

指定线程通过详情接口独立读取，允许不在当前列表页或筛选结果中；不扫描全部列表、不插入伪列表行或修改total。复制当前线程链接只保留当前成员预览或公开分享外壳路径和上述三个标识，不携带访问证明、邀请片段、Cookie、CSRF、正文或运行URL，也不自动把成员链接转换成公开链接。普通页面复制仍仅含修订与页面。明确选择页面会清除线程目标，浏览器前进后退可恢复有效目标；范围或身份变化清空旧详情，取消与代次检查拒绝迟到响应。链接定位线程，不定位特定消息页，线程数据及凭据不传入原型iframe。

分页pageIndex为0–10000、pageSize为1–100；线程按公开updatedAt降序、ID升序，消息按createdAt升序、ID升序。公开消息先按visibility过滤再计数、分页，不能暴露内部备注内容、数量、时间或其他元数据。内部备注不改变线程公开updatedAt、状态、configVersion或排序；无额外messageCount。公开新回复会更新updatedAt，访客回复自动将resolved重开并只递增一次状态版本；PM公开回复保留原状态。只有状态转换改变configVersion；同版本同状态请求无额外状态事件，陈旧expectedVersion返回409。

text经strip后为1–4000个Java字符，拒绝Unicode空白文本，仅按纯文本存储和显示，不解释HTML或Markdown。state/visibility仅接受上述精确字符串，数字枚举或未知值返回400。所有写入必填1–128个可打印ASCII非空白字符的clientRequestId。当前作者、操作和完整范围共同隔离幂等键，指纹包含文本、可见性、expectedVersion等语义字段；重新授权后先重放回执，再检查限频和CAS。同键同内容返回原资源当前结果，不重复消息、事件或旧状态转换；同键不同内容返回409 / `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT`。网络结果不明确时保留原键并由用户明确重试，不能自动重新投稿。

访客新写入使用独立限频：每项目每小时300次、每保障等级与guestId每小时60次；幂等重放不重复占用次数，PM写入不占该额度。线程/消息、领域事件outbox和回执同事务，失败整体回滚。基础版只保留30天领域事件，由现有周期每次最多清理50条，不建立业务发送任务或调用邮件通道；事件到期不删除线程或消息。

安全错误为403 / `PROTOTYPE_HUB_REVIEW_COMMENTS_DISABLED`、403 / `PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED`，后者允许显式重新验证但不能自动提交；线程缺失或不属当前本人范围统一404 / `PROTOTYPE_HUB_REVIEW_THREAD_UNAVAILABLE`，页面无效为404 / `PROTOTYPE_HUB_REVIEW_PAGE_INVALID`，状态CAS冲突为409 / `PROTOTYPE_HUB_REVIEW_THREAD_CONFLICT`。原分享、Origin、Cookie、CSRF和429限频错误保持，不回显输入或内部备注信息。

## HTML 文件夹上传

可以上传已有 ZIP，也可以选择已从 Axure 等工具导出的 HTML 文件夹。文件夹在浏览器 Worker 中打包为 ZIP，保留全部所选文件及中文、空格、嵌套子路径，只剥离所选目录本身这一层；打包完成后仍需确认上传。空目录不由浏览器文件列表表示，零字节文件会保留。此功能不读取 `.rp`，不监听目录变化，也不自动采集本地预览。

两种来源使用相同的已登录 multipart 接口，字段为 `versionName`、`description` 和 `file`：

| 操作 | 请求 | 结果 |
| --- | --- | --- |
| 新建版本 | `POST /prototype-hub/projects/{projectId}/versions` | 创建逻辑版本，接受 `Idempotency-Key`，仍受项目版本容量约束 |
| 更新已有版本 | `PUT /prototype-hub/versions/{id}` | 校验完成后新增不可变修订并切换当前指针；不增加逻辑版本占用 |

浏览器文件夹入口采用保守预算：最多 5000 个文件、合计 128 MiB、单文件 64 MiB；输入大小预算用于控制浏览器负担，不表示固定内存峰值。超限、浏览器不支持目录选择/Worker 或打包失败时，可使用原有 ZIP 入口。ZIP 入口的浏览器上限仍为 500 MiB；后端独立执行配置中的上传大小、解压大小、条目数、路径和文件类型校验，其默认值分别为 ZIP 500 MiB、解压 2048 MiB、20000 条目。前端预算不会放宽服务器策略，目录中的不合法路径也不会被静默过滤后上传。

打包进度、上传进度及服务器处理状态分别展示。取消打包会终止本地 Worker；取消 HTTP 只结束浏览器等待，不能保证服务端尚未提交。网络中断或响应丢失时不会自动重发创建请求；同一弹窗保留原包和元数据，手动重试沿用原幂等键，后端按下节规则返回首次结果。更换内容或重新打开弹窗代表新的创建意图。

目录选择依赖浏览器的 [`webkitdirectory` / `webkitRelativePath`](https://developer.mozilla.org/en-US/docs/Web/API/File/webkitRelativePath) 能力；打包使用 [fflate](https://github.com/101arrowz/fflate) 的流式 ZIP。当前验证范围为本机 macOS 的 Chrome 与合成 HTML 资源；Safari、Firefox、Windows 及真实 Axure 导出包尚未验证。浏览器兼容与真实 Axure 导出包兼容是不同边界，上传及预览仍执行既有权限和 opaque sandbox 策略。

## 原型容量与创建重试

同一数据库的部署最多保有 **5 个项目**，每个项目最多保有 **5 个逻辑版本**。用量按未软删除的项目/逻辑版本记录计算，与操作者、列表筛选和应用副本无关；删除释放占用，编辑项目及 `PUT /prototype-hub/versions/{id}` 更新 ZIP 仅增加修订，不增加逻辑版本占用。历史数据库已有超限数据时仍可启动、读取、分享、修改和删除，只拒绝继续新增；项目仍有活动版本时须先删除版本再删除项目，保留的已删除版本不占用额度。

以下路径相对于后端 API 根路径，响应列给出统一响应体的 `result`：

| 请求 | `result` | 权限 |
| --- | --- | --- |
| `GET /prototype-hub/projects/_usage` | `{installationId, projects: Capacity}` | `prototype-hub-project` 的 `query` |
| `GET /prototype-hub/versions/_usage?projectId=…` | `{projectId, versions: Capacity}` | `prototype-hub-version` 的 `query`，项目须可见 |

`Capacity` 包含 `used`、`limit`、`remaining`、`overLimit`、`canCreate`。当前 `limit=5`，`remaining=max(0,limit-used)`；达到上限时 `canCreate=false`，只有超过上限时 `overLimit=true`。`installationId` 是随数据库持久保存的 UUID，重启和数据库备份恢复不会主动重置，详见[数据说明](DATA.md)。用量为查询时快照，创建或恢复时仍由服务端事务重新校验。

`POST /prototype-hub/projects` 与 `POST /prototype-hub/projects/{projectId}/versions` 接受可选的 `Idempotency-Key` 请求头。提供时须为 1–128 个可打印 ASCII 非空白字符；客户端对同一次创建意图的失败重试沿用该键，修改创建内容或开始新的创建意图时换键。键按当前用户、创建动作和所属项目绑定；项目元数据、版本元数据及原始 ZIP 字节的 SHA-256 参与请求指纹。相同键与内容在重新校验当前项目访问范围后返回首次创建的同一资源，不再占用容量；版本创建重放中的 `revisionId`、名称和说明对应原成功快照，`currentRevisionId` 与 `revisionCount` 对应当前真实状态，重放不会把当前指针切回旧修订；失去范围后不能借旧回执读取资源。内容不同或该资源的删除记录尚未永久清理时返回冲突，不重新创建。30 天到期后，后台永久清理资源及其回执，旧键的去重期限随之结束；后续请求可能按当前权限和容量创建新 ID。回执与资源在同一事务提交，回执不保存原始键或完整请求；上传重放会清理本次临时文件。未提供键的客户端保持普通 POST 语义，不保证重复请求只创建一次。

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 409 | `PROTOTYPE_HUB_PROJECT_LIMIT_REACHED` | 工作区项目已达上限 |
| 409 | `PROTOTYPE_HUB_VERSION_LIMIT_REACHED` | 当前项目版本已达上限 |
| 400 | `PROTOTYPE_HUB_IDEMPOTENCY_KEY_INVALID` | 请求键格式无效 |
| 409 | `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT` | 同键内容不一致，或首次创建的资源尚处于保留的删除状态 |

更改 API/DTO/错误语义时同时更新前端请求类型与契约测试。当前完整接口以实际运行版本 OpenAPI 与源码为准，不能用旧审计路由数量宣称逐路由验收。
