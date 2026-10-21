# 源码与发行发布指南

## 许可证与分发边界

项目有权授予的原创贡献采用 [Apache-2.0](../../LICENSE)，允许商业使用、修改和闭源分发，但须遵守适用的许可证、版权/NOTICE 保留、修改标记和专利条款；许可证不授予商标使用权。第三方组件保持各自许可，不可用项目许可证覆盖。详情以[官方完整条款](https://www.apache.org/licenses/LICENSE-2.0)为准。

源码权属、雇主/贡献者授权与参考实现的可分发范围由维护者核实；项目选择许可证不替代这些事实确认。用户/客户上传内容也不自动归入项目许可。

## 公开源码导出

在仓库根目录执行：

```bash
python3 tools/export-source.py
# 或指定一个不存在、位于源目录外的新目录：
python3 tools/export-source.py --output /tmp/proto-source-review
```

导出只选取 `open-source-export.json` 明确允许的源码、配置与公共文档，不依赖 `.git`。它拒绝符号链接、源目录内部/祖先输出位置及覆盖已有目录；不复制 `.env`、本地配置、运行数据、依赖/构建产物、IDE 文件、内部审计证据、截图或测试追踪。`SOURCE_MANIFEST.json` 记录每个文件的 SHA256 和大小，并记录有限敏感模式扫描的范围。

导出前后均检查敏感模式，并递归校验全部选中文档的本地链接已选入导出。扫描并不证明无所有秘密/无漏洞/已完成权属核实；正式公开前仍需人工审阅清单与实际文件。若引入旧 Git 历史，需要额外审查完整历史；不要将旧 `.git` 或本地目录整包上传。

## 后端发行物

开发构建的模块 JAR 附 `META-INF/LICENSE`、`META-INF/NOTICE`，普通 `test/package` 不要求在线收集许可证。正式分发需 Python 3，运行：

```bash
./mvnw -B -ntp clean verify -Prelease-audit
python3 tools/verify-distribution.py
```

release-audit 按实际运行依赖生成 `META-INF/third-party/THIRD-PARTY.txt`、`inventory.json`、`licenses/` 与 `artifacts/`，并打入发行包；许可文本来自本地工件及 `third-party/license-texts/`、`third-party/license-notices/` 中已固定来源与 SHA 的上游原文，缺失时门禁失败。发行检查成功后生成绑定最终 JAR SHA 的 sidecar；Docker 构建要求它与 JAR 匹配，避免陈旧许可清单混入镜像，该摘要绑定不是数字签名。容器在 `/application/legal` 暴露相应材料。复核清单中的适用声明、多许可选择与分发义务，不以 SPDX 名称或“生成成功”替代人工审查。`collectionComplete=true` 仅指收集完成，清单仍保留 `complianceApproved=false` 与逐组件待复核项。

项目的源码公开、JAR、Docker 镜像与整套离线中间件包分别审核；第三方/基础镜像/操作系统包未必都采用 Apache。特别是 Redis 8、Elasticsearch 官方默认发行物的条款应按版本与实际分发模式核对，不推断网络调用会自动改变本应用许可证。

完整手动检查入口是 `bash tools/verify-open-source.sh`，需要 JDK 21、Python 3、Docker、固定版本安全工具及可访问的审计数据源；可传 `-U`，不接受离线参数，因为 CycloneDX 的许可证解析需要在线资源。它执行工具安全测试、完整 Maven 发行审计、发行包法律字节与覆盖率检查，并扫描拟公开的导出目录，有 Git 时另扫历史；无 Git 时不会声称覆盖历史。设置 `PROTO_RUNTIME_PLAYWRIGHT_DIR` 可额外执行真实 Playwright CLI 生命周期探针，未设置时明确跳过；这些探针不替代实际平台业务演练。工具/漏洞库不可用必须记录为未验收，不能用跳过标记当通过。

漏洞扫描遵循固定 Trivy 版本的[官方默认数据库仓库顺序](https://github.com/aquasecurity/trivy/blob/v0.75.0/pkg/db/db.go)，包括官方 GCR mirror 与 GHCR，不将其限定为单一仓库；数据库 schema、更新时间与 OCI 摘要仍由 Trivy 正常校验，不跳过更新。每次扫描前撤销旧 JSON 报告，下载或扫描失败不得复用先前成功结果；报告与实际使用的数据库元数据一起留在本地验收材料中。

## 干净源码与运行验证

在新导出的源码目录安装依赖并构建，避免复用工作区中的 `target`、本地配置或运行数据。后端使用 Maven Wrapper 和 JDK 21；配套前端按其锁文件安装后执行生产构建。导出清单验证源码范围，构建和运行检查分别验证产物，两者不能互相替代。

完成后端构建后执行：

```bash
python3 tools/verify-runtime.py --jar bootstrap/target/bootstrap-1.0.0.jar --report /tmp/proto-runtime-report.json
# 同时验证已经完成生产构建的配套前端：
python3 tools/verify-runtime.py --jar bootstrap/target/bootstrap-1.0.0.jar --frontend-dir /path/to/proto-bridge-ui --report /tmp/proto-runtime-ui-report.json
```

工具仅创建和销毁自身隔离资源，覆盖独立 JAR 运行、重启、双进程容量竞争、联合恢复及可选真实浏览器流程。报告与实际 JAR 和原创 HTML 样本摘要绑定，不能据此推断其他产物、数据库版本或真实 Axure 包也已通过。浏览器环境、原型样本、反向代理、镜像、许可证与当前漏洞库检查仍按实际发行范围分别验收；运行验证不替代 `release-audit`。

## 手动发布门禁

1. 确认源码/贡献与素材公开权利，审阅源码导出清单和敏感配置检查；保留必要版权与许可证。
2. 在所支持 JDK、数据库及部署环境执行 `verify`/release-audit、发行检查与相关接口回归；记录真实失败、未覆盖项和环境。
3. 对当前 SBOM、应用与系统镜像使用实际更新的漏洞数据库检查，并记录扫描时间；旧结果不代表当前安全。
4. 用非生产测试库验证初始化、权限、上传、分享、原型运行、备份恢复和升级边界，再制作可追溯的版本与工件校验值。
5. 设置 [私密安全报告入口](../../SECURITY.md)，公开已知限制；当前目录不预置远端 CI 工作流，不宣称自动流水线已部署。

## 已知产品边界

原型资源以 opaque sandbox 隔离，不能恢复 `allow-same-origin` 来让上传 HTML 读取管理平台存储。复杂 Axure 依赖原生父子 iframe 对象共享的同步能力可能受限，包括播放器页名和初始目录选中；不能声称所有导出包完全兼容。项目成员级数据权限、完整 SSO/应用/资产集成不作为已完成能力提供。

贡献步骤见 [CONTRIBUTING](../../CONTRIBUTING.md)，常规验证见 [开发指南](DEVELOPMENT.md)。
