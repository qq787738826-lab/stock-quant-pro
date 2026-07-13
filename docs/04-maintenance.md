# 维护与下一阶段

当前交付是可运行的专业基础版，核心边界与扩展点已完成。生产实盘前还必须完成：

- 券商官方 API 的具体适配、合规检查与沙箱联调
- 全市场证券主数据同步和交易状态校验
- 真实分钟行情与断线重连
- 复权、停牌、涨跌停、除权除息的精确回测处理
- 组合级事件驱动回测和基准比较
- 新闻公告的授权数据源
- 在线模型密钥加密存储与费用限额
- 安装包内置 JRE/Python、自动升级、代码签名
- 单元、集成、压力、故障恢复与交易演练

维护原则：代码是真实状态，`docs/` 是单一需求基线，每次修改记录到 `docs/CHANGELOG.md`。

## 模拟账户参数

模拟交易参数保存在 `app_settings`：

```text
portfolio.max_positions
portfolio.max_position_weight
portfolio.commission_rate
portfolio.minimum_commission
portfolio.stamp_duty_rate
portfolio.transfer_fee_rate
portfolio.default_trailing_stop_pct
```

修改参数后无需重新编译，但应在没有待确认委托时调整，以避免前后规则不一致。
