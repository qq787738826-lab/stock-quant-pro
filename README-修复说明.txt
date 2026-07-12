使用方法：
1. 解压本修复包。
2. 将 quant-web、quant-desktop 和 06-fix-npm-install.cmd 复制到 D:\stock-quant-pro。
3. 选择“替换目标中的文件”。
4. 双击 D:\stock-quant-pro\06-fix-npm-install.cmd。
5. 成功后再运行 scripts\03-start-dev.cmd。

修复内容：
- 将 package-lock.json 中错误的内部 npm 地址替换为 https://registry.npmjs.org/
- 清理残留 node_modules
- 重新安装 quant-web 与 quant-desktop 依赖
