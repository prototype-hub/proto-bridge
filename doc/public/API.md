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

## 项目与版本软删除

`DELETE /prototype-hub/versions/{id}` 保留逻辑版本、当前指针、全部修订、创建回执与文件，在原事务锁边界内写入 `deletedTime`（Unix 毫秒）。`DELETE /prototype-hub/projects/{id}` 同样保留项目与成员谱系；有活动版本时仍返回 409，只有已删除版本不阻止删除项目。重复访问已删除对象按不存在处理，不能用 ADMIN 例外读取已删除业务对象。

删除释放当前项目或逻辑版本占用，新的创建使用新的 ID；安装身份及项目绑定保持不变。已删除资源的原创建键重放返回 409 / `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT`，不复活资源，也不重新消耗容量；父项目随后删除仍保留该回执冲突语义。

列表、分页总量、直接 ID、成员、上传、修订、页面树和新预览授权均排除已删除对象与已删除父项目。删除在同一事务中清除内部预览会话，并永久撤销、停用其保留的公开分享；新公开信息/密码校验以及删除前发出的分享 HTML、资源和页面树请求均返回不可用（404）。删除前签发的内部 runtime 后续请求维持 403。引用保留仅保护数据，不授予继续访问；已下载的内容无法远程收回。

已删除项目和版本可在回收站单独查询、逐项恢复；普通列表及详情继续排除它们。当前无限期保留，不提供保留期、永久删除、单修订删除或自动 GC。删除操作不登记文件回收；历史来源无法证明的旧清理任务保留并退避，只有明确标识的失败上传候选在引用校验后可由原有有界任务清理。文件和数据库需同窗口联合备份，详见[数据说明](DATA.md#不可变修订与旧内容迁移)。

## 回收站与逐项恢复

| 请求 | 授权与行为 |
| --- | --- |
| `POST /prototype-hub/projects/_deleted/_query` | 项目 `query` 动作；只查询当前 owner 或有效 ADMIN 可恢复的已删除项目，editor/viewer 不返回该项目 |
| `POST /prototype-hub/versions/_deleted/_query?projectId=…` | 版本 `query` 动作；父项目须活动且当前账号有编辑能力，owner/editor/admin 可查询，viewer 不可查询 |
| `POST /prototype-hub/projects/{id}/_restore` | 项目 `save` 动作与当前 owner/admin；只恢复父项目，返回同一 ID 的项目响应，已删除子版本仍留在回收站 |
| `POST /prototype-hub/versions/{id}/_restore` | 版本 `save` 动作与活动父项目的编辑能力；只恢复指定逻辑版本，返回同一 ID 的版本响应 |

两种回收站查询复用上方分页 DTO、大小限制、名称字面匹配及稳定排序；授权与删除状态在数据库 COUNT 和分页前生效，不接受客户端 `includeDeleted` 来扩大普通查询。响应增加 `deletedTime`，活动对象为 null，已删除对象为删除时的 Unix 毫秒。

恢复按当前身份、当前项目角色及默认 5/5 容量重新校验，不沿用删除时的权限或配额。恢复和新建使用相同数据库锁边界，多副本争抢最后名额只有一次成功。容量不足返回原项目/版本容量 409；对象已经活动返回 409 / `PROTOTYPE_HUB_RESOURCE_ACTIVE`；不存在或父项目仍删除返回 404。权限不足沿用项目范围的 403/404 规则，失败不会清空删除标记、改变引用或占用额度。

恢复保留原项目/版本 ID、成员谱系、当前修订指针、全部修订和创建回执，不新增回执。恢复后同一原创建键仍须通过当前权限和指纹校验，成功只重放同一 ID 与原成功快照，不额外占用名额。删除状态下旧键仍冲突；恢复父项目不会复活其子版本、旧内部预览或已经撤销的分享。恢复后需重新打开内部预览或新建分享；旧公开链接、旧密码授权及旧运行文件凭据继续拒绝。

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

`POST /prototype-hub/projects` 与 `POST /prototype-hub/projects/{projectId}/versions` 接受可选的 `Idempotency-Key` 请求头。提供时须为 1–128 个可打印 ASCII 非空白字符；客户端对同一次创建意图的失败重试沿用该键，修改创建内容或开始新的创建意图时换键。键按当前用户、创建动作和所属项目绑定；项目元数据、版本元数据及原始 ZIP 字节的 SHA-256 参与请求指纹。相同键与内容在重新校验当前项目访问范围后返回首次创建的同一资源，不再占用容量；版本创建重放中的 `revisionId`、名称和说明对应原成功快照，`currentRevisionId` 与 `revisionCount` 对应当前真实状态，重放不会把当前指针切回旧修订；失去范围后不能借旧回执读取资源。内容不同或该资源已删除时返回冲突，不重新创建。回执与资源在同一事务提交，回执不保存原始键或完整请求；上传重放会清理本次临时文件。未提供键的客户端保持普通 POST 语义，不保证重复请求只创建一次。

| HTTP 状态 | 错误码 | 含义 |
| --- | --- | --- |
| 409 | `PROTOTYPE_HUB_PROJECT_LIMIT_REACHED` | 工作区项目已达上限 |
| 409 | `PROTOTYPE_HUB_VERSION_LIMIT_REACHED` | 当前项目版本已达上限 |
| 400 | `PROTOTYPE_HUB_IDEMPOTENCY_KEY_INVALID` | 请求键格式无效 |
| 409 | `PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT` | 同键内容不一致，或首次创建的资源已删除 |

更改 API/DTO/错误语义时同时更新前端请求类型与契约测试。当前完整接口以实际运行版本 OpenAPI 与源码为准，不能用旧审计路由数量宣称逐路由验收。
