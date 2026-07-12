# 交付前验证

已执行：

- Python `app/main.py` 语法编译通过
- `quant-core` 全部 Java 源码使用 JDK 17 编译通过
- Vue/TypeScript 生产构建通过
- Electron 主进程 TypeScript 编译通过
- POM XML、目录结构和关键文件检查通过

环境限制：交付环境无法联网解析 Maven 依赖，因此 Spring Boot 完整 Maven 构建未在此环境完成。项目提供 `mvnw.cmd`，用户首次运行时会下载固定 Maven 3.9.16，再完成依赖解析和构建。
