# 免费 Provider 证据登记册

## 1. Hash 口径

`SHA-256` 列同时记录 Hash 的对象范围：

- `artifact`：下载的官方包文件；
- `page-body`：受控 GET 得到的页面 body；
- `safe-live-summary`：不含行情具体值的安全摘要；
- `repository-file`：仓库既有审计或实现文件；
- `normalized-evidence`：浏览器读取结果的标题、关键结论和状态组成的固定文本。浏览器没有
  提供原始响应 body 时，不把该 Hash 冒充页面 body Hash。

检索时间采用审计会话可证明的日期级时间。为避免伪造精确瞬间，未持久化原始响应的证据
明确写成“date-level”；这不影响内容 Hash 或资格结论。

## 2. 登记表

| evidenceId | Provider | 来源类型 | 页面/包标题 | URL | retrieval time | 版本 | SHA-256（scope） |
|---|---|---|---|---|---|---|---|
| `F0-EVID-BAO-PYPI-001` | BaoStock | PyPI 项目元数据 | baostock 0.9.3 | https://pypi.org/project/baostock/ | 2026-07-28 Asia/Shanghai（date-level） | 0.9.3，2026-07-10 上传 | wheel/sdist 见后两项 |
| `F0-EVID-BAO-WHEEL-001` | BaoStock | PyPI artifact | `baostock-0.9.3-py3-none-any.whl` | https://pypi.org/project/baostock/ | 2026-07-28/29 Asia/Shanghai | 0.9.3 | `acbd19403285bc4e254cee8297cf0e2646ae2276e5af7e549deed3988ab02293` (`artifact`) |
| `F0-EVID-BAO-SDIST-001` | BaoStock | PyPI artifact | `baostock-0.9.3.tar.gz` | https://pypi.org/project/baostock/ | 2026-07-28 Asia/Shanghai | 0.9.3 | `16699d82d05037a8c133577fcdeb9ac0d5a7f31edc2432c4e883004e0a95e3f7` (`artifact`) |
| `F0-EVID-BAO-WEB-001` | BaoStock | Provider 网站 | disclaimer / Python API shell | https://baostock.com/disclaimer | 2026-07-28 Asia/Shanghai（date-level） | 页面未暴露正文版本 | `92d4eaeb5c101f3029f0b4136fab3fa88668129d2ee61684884f273a07ade836` (`page-body`) |
| `F0-EVID-BAO-LIVE-001` | BaoStock | 受控 Live 探针 | F0 安全摘要 | n/a（TCP `public-api.baostock.com`） | 2026-07-29 Asia/Shanghai（date-level） | client 0.9.3 / summary V1 | `f97779bb9d6138faa3b049abb5f1f6da98105e359644ecc785002518086ffd0b` (`safe-live-summary`) |
| `F0-EVID-AKS-DOC-001` | AKShare | 官方 GitHub 文档 | AKShare introduction | https://github.com/akfamily/akshare/blob/main/docs/introduction.md | 2026-07-28 Asia/Shanghai（date-level） | 页面标注 2026-07-24 更新 | `a253112c224e51af19a050ff25191a2d74c9da5cc20963f4b07abab1a5541611` (`normalized-evidence`) |
| `F0-EVID-AKS-CODE-001` | AKShare/Tencent/Sina/Eastmoney | 仓库既有调用链 | `quant-ai/app/main.py` | [仓库文件](../../quant-ai/app/main.py) | 2026-07-29 | 集成基线 `c47b88e` | `54be94cf8af4c8ab908670aa6f2eabd9ee20e26e682afcf25cecafcf8bf9702f` (`repository-file`) |
| `F0-EVID-AKS-CNINFO-001` | AKShare/CNINFO | 仓库既有 Provider Bridge | `quant-ai/app/announcement_provider.py` | [仓库文件](../../quant-ai/app/announcement_provider.py) | 2026-07-29 | AKShare 1.18.64 / contract V1 | `82d9e9f142ede43427157419585ec17946647f72e850f35fef6ed9b98146b410` (`repository-file`) |
| `F0-EVID-TENCENT-001` | Tencent via AKShare | 仓库既有受控审计 | PIT market facts V2 design | [任务书](tasks/3ar3a-pit-market-facts-v2-design.md) | 2026-07-29 复核 | 3A-R3A final | `3c403898a0e045d08a18ebe83a022b3f914ee95eb0f9b41c8654ebf2ef322d2c` (`repository-file`) |
| `F0-EVID-CNINFO-001` | CNINFO | 法定披露平台页面 | 信息披露/最新公告 | https://www.cninfo.com.cn/new/commonUrl?url=disclosure%2Flist%2Fnotice | 2026-07-28 Asia/Shanghai（date-level） | 页面无版本号 | `92c003200d93ed8cd5b9ee8eb3c0871c7bd5d9d539dbacf82c2296f95d71163a` (`normalized-evidence`) |
| `F0-EVID-SSE-001` | SSE | 交易所官方页面 | Trading Schedule | https://english.sse.com.cn/start/trading/schedule/ | 2026-07-28 Asia/Shanghai（date-level） | 页面无版本号 | `6b8ab6ffcd1b2ae09876f6c792e0950d99540527c4f683cfe4293eaa2cb760b1` (`normalized-evidence`) |
| `F0-EVID-SZSE-001` | SZSE | 交易所官方页面 | Trading Overview | https://www.szse.cn/English/services/trading/tradOverview/ | 2026-07-28 Asia/Shanghai（date-level） | TIMEOUT | `c96a39c592f4c8b39c96b7c93307fe4614002dd588a0ffc948cd9e5422242c7f` (`normalized-evidence`) |
| `F0-EVID-SZSE-002` | SZSE | 交易所官方页面 | Data Services | https://investor.szse.cn/English/services/dataServices/index.html | 2026-07-28 Asia/Shanghai（date-level） | TIMEOUT | `c250ebf58c465b5ea3a185ae05be3e96e279042e462ce0e8af0ebb5ff9a7bbfa` (`normalized-evidence`) |
| `F0-EVID-SZSI-001` | 深圳证券信息有限公司 | 官方页面 | Overseas Services | https://www.szsi.cn/cpfw/overseas/ | 2026-07-28 Asia/Shanghai（date-level） | TIMEOUT | `d6ffb76a6b18656a32adfc6f758434c4b3faa0cb5220ed31f55e964f8ab330e6` (`normalized-evidence`) |

## 3. 支持与不支持的结论

| evidenceId | 支持的具体结论 | 不支持的结论 | 许可解释 | 需书面确认 | 过期风险 | 备注 |
|---|---|---|---|---|---|---|
| `F0-EVID-BAO-PYPI-001` | 当前正式分发版本、上传日期、包声明、项目定位 | 底层数据归属、数据许可、SLA、revision | BSD 是包代码元数据，不是数据授权 | 是 | 中 | PyPI 标记部分元数据未经平台验证 |
| `F0-EVID-BAO-WHEEL-001` | wheel 字节、公开导出、函数签名、TCP endpoint、依赖 | 服务器端语义、数据权利、旧版本 | 只能审计客户端实现 | 是 | 中 | 本阶段临时安装在仓库外 |
| `F0-EVID-BAO-SDIST-001` | sdist 字节与版本 | 同上 | 同上 | 是 | 中 | 未提交 artifact |
| `F0-EVID-BAO-WEB-001` | 两个 URL 可访问且返回同一 JS shell | disclaimer/API 正文、明确许可、数据字典 | 没有可引用正文就不能批准用途 | 是 | 高 | API URL body Hash 与 disclaimer 相同 |
| `F0-EVID-BAO-LIVE-001` | 匿名单会话、8 个受控调用、字段形态、行数、空值/0、Hash、清理 | 具体行情值、长期稳定、DAILY_EXACT、许可、revision | 技术可用不等于用途获准 | 是 | 中 | 原始响应未保留，残留 0 |
| `F0-EVID-AKS-DOC-001` | 多公开上游、学术研究定位、商业风险、接口随网页变化 | 任一上游的许可、版本语义、SLA | AKShare 项目本身不能授权上游数据 | 是 | 高 | 必须逐函数拆上游 |
| `F0-EVID-AKS-CODE-001` | 当前 Tencent/Sina/Eastmoney 函数、字段和 fallback | Provider 许可、revision、生产 V13 资格 | 旧 current projection 不能升级 | 是 | 中 | Tencent amount/volume 映射存在资格冲突 |
| `F0-EVID-AKS-CNINFO-001` | 固定公开函数、CNINFO 域名门、字段与错误处理 | PDF 语义、完整修订关系、正式许可 | 2G 固定 RESEARCH | 是 | 中 | 不扩大本阶段 Live 调用 |
| `F0-EVID-TENCENT-001` | 两次响应一致、`version=18` 一致、链路字段损失 | `version=18` 的 revision 语义、旧版本、发布时间 | 不得接入 sourceRevision | 是 | 中 | 结论仍为 PROVIDER_REVISION_UNVERIFIED |
| `F0-EVID-CNINFO-001` | 法定披露平台身份、公告元数据、免责声明、独立数据服务入口 | 批量 PDF、本地库、回测、Agent、商业权利、修订完整性 | 官方公开展示不等于批量数据许可 | 是 | 中 | 只承担 OFFICIAL_EVIDENCE_ONLY |
| `F0-EVID-SSE-001` | 官方交易时段和休市安排 | 免费 EOD、本地保存、回测、商业许可、版本化 API | 日历证据不授予行情权利 | 是 | 中 | 只承担 OFFICIAL_EVIDENCE_ONLY |
| `F0-EVID-SZSE-001` | 本轮无法取得页面 | 任何能力或许可肯定结论 | TIMEOUT 不等于禁止或允许 | 是 | 高 | 未绕过、未继续重试 |
| `F0-EVID-SZSE-002` | 本轮无法取得页面 | 数据服务内容和许可 | 同上 | 是 | 高 | 未绕过、未继续重试 |
| `F0-EVID-SZSI-001` | 本轮无法取得页面；CNINFO 导航表明存在独立行情服务 | 服务字段、价格、许可、SLA | 必须向授权数据机构确认 | 是 | 高 | 未绕过、未继续重试 |

## 4. 证据边界

- 页面/包 Hash 只证明本次检索的字节或规范化证据，不证明历史可见性；
- Live 原始响应 Hash 只证明本次内容，不是 Provider revision；
- 文件 Hash 只证明当前仓库审计事实，不授予外部数据权利；
- TIMEOUT 证据只支持“未取得”，不能推导 `UNAVAILABLE` 或许可禁止；
- 所有需书面确认的问题集中在
  [free-provider-written-permission-questions.md](free-provider-written-permission-questions.md)；
- Codex 没有发送邮件、提交表单、创建账号或代表用户接受条款。
