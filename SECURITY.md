# 安全报告

请勿在公开 Issue、PR、日志或截图中提交有效密码、认证头、分享链接、预览密钥、个人信息或完整的私有原型包。

## 私密报告渠道

发布到 GitHub 后，维护者应在仓库设置中启用 **Private vulnerability reporting**。启用后，通过仓库 **Security → Report a vulnerability** 提交复现条件、受影响版本、最小化脱敏样本和影响说明。该入口只有维护者启用后才会出现；本工程不预设仓库地址或邮箱。

若入口尚未启用，不要改为公开提交利用细节。维护者应先设置并在本文件公布可实际使用的私密联系渠道，报告者再通过该渠道提交。当前没有承诺响应时限或覆盖所有历史版本。

## 发布与部署检查

1. 从 `open-source-export.json` 定义的公开文件集导出源码，复核 `SOURCE_MANIFEST.json`。不提交 `.env`、本地配置、运行数据、备份、上传文件、IDE 文件或原始测试追踪。
2. 对拟公开的目录执行敏感信息检查；若引入 Git 历史，额外检查完整历史。工作区扫描不能证明旧凭据已轮换，忽略规则也不能保护手工整目录打包。
3. 对同一发布候选运行各仓库的类型/构建/测试、许可证和依赖安全检查。记录实际命令、数据库时点及未覆盖项；现有手动脚本不等同于已部署 CI。
4. 使用 HTTPS，限制中间件和管理端口，保护持久化目录并验证备份恢复。原型 HTML 作为主动内容隔离运行，不得为兼容关闭管理平台与原型间的存储/DOM隔离。

导出器只检查有限的高置信敏感模式，不代替代码审查、漏洞扫描、权属确认或上线验收。第三方组件和镜像使用各自的安全公告与更新流程。

GitHub 功能说明：https://docs.github.com/en/code-security/security-advisories/working-with-repository-security-advisories/privately-reporting-a-security-vulnerability
