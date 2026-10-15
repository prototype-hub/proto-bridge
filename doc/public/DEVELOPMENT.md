# 开发与验证

使用 JDK 21 与项目 Maven Wrapper。模块职责遵循根 README；Java 包根为 `com.gaiys.proto`。实体放在 entity，服务与控制器沿模块现有结构组织，不按小功能过度拆分。新增框架适配复用公开 API/扩展点，不复制私有源码或使用反射/Unsafe绕过访问限制。

响应式请求保持 Mono/Flux 链路，阻塞文件操作放到已有受控调度边界；权限、数据范围和事务由后端执行。用户可见错误复用已有 i18n key；复杂安全/并发/兼容分支用简短注释说明原因，不记录令牌、密码或完整用户载荷。

```bash
./mvnw -B -ntp clean verify
python3 tools/verify-identifiers.py
```

集成测试需要 Docker 与可用的测试中间件环境，不使用生产数据库。确需格式化时运行 `./mvnw spotless:apply` 后审阅差异。选择性回归须写明实际范围，不能把单类通过描述成全量通过。

正式分发增加 release-audit 与发行检查，见 [发布指南](RELEASE.md)。版本入口和组件核验见 [依赖基线](../DEPENDENCY_BASELINE.md)；测试报告生成在 target 内，不纳入公开源码导出。

本地实际配置从资源示例复制到 `config/application-local.yml` 并显式通过启动参数加载；该目录不公开。改名不移动既有原型存储目录，避免破坏分享与版本关联。
