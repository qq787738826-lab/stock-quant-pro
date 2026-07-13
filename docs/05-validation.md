# 交付前验证

已执行：

- Python `quant-ai/app/main.py` 语法编译通过
- Python动态指标与严格候选过滤使用合成K线运行通过
- 新增Java服务、控制器和配置使用JDK 17及最小依赖桩编译通过
- Vue/TypeScript `vue-tsc` 检查通过
- Vue生产构建通过
- Electron主进程TypeScript编译通过
- Electron Builder进入打包阶段；因当前环境无法访问 GitHub 下载运行时，未生成Windows安装包
- POM、JSON、Flyway SQL和项目目录检查通过

环境限制：

- 当前执行环境无法访问 Apache Maven 下载站，因此未完成真实 Spring Boot Maven 依赖构建。
- 用户现有Windows环境已经能够运行1.2.1；覆盖1.2.2后，Flyway会自动执行 `V3__selection_validation.sql`。
- Windows端应重新加载Maven并启动一次，完成最终数据库迁移和集成验证。
