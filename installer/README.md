# Windows 安装包

执行 `scripts/04-build-all.cmd` 后，Electron Builder 会在 `quant-desktop/dist` 生成 NSIS 安装程序。

正式发布前建议：

1. 为 EXE 配置可信代码签名证书。
2. 将 JRE 17 与 Python 3.11 环境做成独立运行时，避免依赖用户全局环境。
3. 将数据库安装、迁移与备份做成安装向导步骤。
4. 把 `.env` 移到 Windows 用户数据目录并限制文件权限。
