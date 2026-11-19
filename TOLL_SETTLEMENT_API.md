# 收费桥接（本地待验证）

`dev.jdguo.bank.api.TollSettlement` 仅供可信服务端模组在服务器主线程调用，不暴露客户端自选收款方的网络端点。调用方负责管理员权限、领地所有权和计费事件验证。

- `String currencies()`：JSON 数组 `{id,name}`，名称仅显示，交易必须保存稳定 ID。暂停/无准备金币种仍可列出，是否能付费由交易校验。
- `TxResult transfer(UUID from, UUID to, String currency, BigDecimal amount, String ref)`：私人收款，原币原额到账。不清算个人币准备金。
- `TxResult collectSystem(UUID payer, String currency, BigDecimal amount, String ref)`：独立 `toll` 系统账户，最后只入央行券。实物从银行储蓄整数扣款，不操作背包；个人币逐层扣准备金、回收入库。释放的终端实物由央行自有央行券购买，央行券不足则整笔拒绝，不增发、不使用其他储户资金。转为央行自有的实物同步减少其原托管资产及对应储蓄负债，不挪用剩余托管余额。
- `TxResult receipt(String ref)`：`ok` 表示已落账，`unrecorded` 表示未记录。不是参数匹配证明；恢复应重放完全相同参数，让指纹校验。

金额必须正数，最多 32 位整数、4 位小数；实物必须整数。终端资产乘以汇率后的价值若不能精确写入 4 位账本，返回 `below_precision`，不会自动舍入或补差。TxResult 的旧 amount 字段为整数，不应用它重建小数金额。

收费端先持久化订单和稳定 ref（非空、最多120字符），再调用银行；恢复使用同 ref、同付款人、同收款人/系统类型、同币种 ID、同金额。银行账本内使用 toll 命名空间及持久化指纹；重放不重复扣款，参数不一致返回 `reference_conflict`。持久化未确认时不能收船或宣称收费失败。

只有 `insufficient_funds` 表示付款人可用余额不足。`insufficient_treasury`、`insufficient_backing`、`no_current_rate`、`below_precision`、暂停/抵押链错误不应触发玩家欠费处罚。

`bank_not_ready`、`save_failed`、`invalid_transaction` 保留原订单并查明/重试；`reference_conflict` 冻结人工核查，不能换新 ref 再扣。配置/精度/暂停等拒绝不要对同一订单无限自动重试。

银行在同一 copy-on-write、checkpoint、原子替换事务内写入全部资产和幂等记录；收费点订单/船/通行效果仍由收费模组的独立持久化状态机负责，两者不是分布式原子事务。新增 `previewSystem(currency,amount)` 在账本副本上只读预检当时抵押链和终端结算精度，创建收费点可据此拒绝不可精确结算的金额。实际扣款仍完整复验。个人币每层清算沿用既有 quotePersonalClearing 四位向下精度规则，不修改旧经济算法；最终乘牌价不追加舍入。

此接口没有退款或自动归集。自 Bank v1.0.19 起，央行管理员可查看专户余额和最近 30 笔成功付款，并确认一次性将专户全部余额划入央行自有储蓄；不改变既有交易凭证，也不混入商店、地产。

验证：165 项账本与已有回归测试通过；真实 BankRuntime/API 配合测试替身验证保存失败不发布、同 ref 重试、非主线程拒绝。另用真实 BankWorldState/BankRuntime 在本地文件系统完成原子写盘、目录创建失败不发布、旧 NBT 与新 JSON checkpoint 重启选择、重放不重复扣款测试（仅 Minecraft 宿主类型为替身）。未做完整 Fabric 构建、真实服务器运行或物理断电，不能宣称已经验证这些场景。

## Windows 与同账本权威状态

新增 `loadState()` 返回 `{success:true,revision,payload}`（payload 为原始 TollStore JSON 字符串，未迁移时 null）。`saveState(expectedRevision,payload)` 在同一个银行经济事务写入状态；成功 `TxResult.amount` 为新 revision。冲突返回 `state_conflict`，非法或超过 16 MiB UTF-8 的 JSON 对象返回 `bad_state`，保存失败不发布新 revision。版本冲突或故障应暂停，不能覆盖新版或另造 ref。

收费端启动后以该 payload 为唯一权威；首次为空时可把旧文件连同旧订单 transport 一次性迁移，成功后不再回退旧文件。发现持久化迁移标记（存放在旧银行也会保留的 marketReceipts）或 toll-v1 扣款凭证，但权威状态缺失时返回 `state_missing`，防止被旧银行写入器丢字段后误当首次安装。降级不能成为恢复方法，需备份核查。

新收费必须先把 PENDING 完整参数、稳定 ref、transport 和 blocked 区域状态通过 saveState 持久化，再扣款。后续银行扣款复制整个快照，必然带上这些恢复信息。若只完成扣款尚未把收费状态改为 PAID，重启读取 PENDING 并用原参数重放，由同快照的扣款凭证去重。若断电造成最新银行快照整体丢失，状态与这笔资金变动共同回退，不再依赖 Windows 能否单独 fsync 收费目录。

本设计不宣称绝对断电安全：Windows/设备可能丢最后一次确认写入；银行原子替换仍须可用，否则拒绝操作。银行文件、整个世界或备份被人为不一致回滚，不在自动保证内。船实体/游戏世界不在银行事务中，收费端必须继续保守处理未知结果和没收恢复。

新增测试后共 169 项模型回归通过。真实 BankWorldState 磁盘测试覆盖：PENDING+blocked 先存、扣款落盘、旧世界 NBT 加载最新 JSON checkpoint、同 ref 重放、状态写盘失败不发布、CAS 冲突。没有 Windows 实机和物理断电验证。

迁移后禁止单独回滚旧收费模组：旧收费写入本地文件而不更新银行权威 payload，新包不能自动合并这两条历史。需配套升级银行及收费包；回滚需停服并恢复一致的整套世界备份，不能把仅旧 jar 替换视为受支持操作。临时 CI 构建仍标记 1.0.16，只是测试产物，不替换已发布版本。
