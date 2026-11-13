# 中央银行 bankmod

Minecraft Java **1.20.1** 的服务器端银行模组。纯 Fabric，**只装在专用服务器上**。原版客户端不用装模组，也不用资源包，就能存取、兑换、开汇票，并看到网点上的全息牌、侧边栏、Boss 条和 Tab 页眉。

账本用 `BigInteger` / `BigDecimal`，不把记分板当账。记分板只有 32 位，恶性通胀时会溢出。

代码版权 **jd guo**，许可 **MIT**（见 [LICENSE](LICENSE)）。经济规则改写自作者自己的银行数据包。按作者要求，本模组不再沿用那个仓库的 GPL-3.0 与 CC BY-NC。界面只用原版物品图标，没有搬数据包里的图片。

Minecraft 是 Mojang Synergies AB 的商标。本项目与 Mojang 无关。

## English

Server-only Fabric 1.20.1 bank. Vanilla clients need no mod and no resource pack. Money is arbitrary precision, not a scoreboard. Open a counter by right-clicking an outlet block an operator placed on a building; `/bank` works only while you are standing in that outlet's radius. MIT, copyright jd guo.

## 安装

服务器需要 Fabric Loader、Fabric API、Java 17 或更高。

1. `./gradlew build`
2. 把 `build/libs/bankmod-1.0.0.jar` 放进服务器的 `mods/`，旁边要有 Fabric API。
3. 启动一次。服务器会写出 `config/bank.json`。
4. 客户端什么都不用装。

这是专用服务器模组（`environment: server`），不要装进客户端。

## 网点

交易发生在管理员指定的方块上，不是在世界任意位置打开一个菜单。

管理员看着建筑上的方块（大约 6 格内），执行：

```
/bank outlet set 主楼
```

没看准方块时，用你脚下的那一格。随后：

- 玩家**右键这个方块**打开 9×6 原版箱子界面。如果这个方块本来是箱子，银行会接过这次点击。
- 站在半径内（默认 4 格）也可以 `/bank`。
- `/bank outlets` 列出坐标。
- 全息字牌浮在方块上方，内容是汇率、兑现覆盖和状态，重启还在。

| 命令 | 谁 | 作用 |
| --- | --- | --- |
| `/bank outlet set <名字>` | OP | 把准星指着的方块设成网点 |
| `/bank outlet move <名字>` | OP | 搬到准星下的新方块 |
| `/bank outlet remove <名字>` | OP | 删网点并拆掉全息 |
| `/bank outlet radius <名字> <1-32>` | OP | 附近 `/bank` 的半径 |
| `/bank outlet lift <名字> <0.5-16>` | OP | 全息离方块多高，默认 2.4 |
| `/bank outlet hologram <名字> on\|off` | OP | 这个网点要不要全息 |
| `/bank outlet tp <名字>` | OP | 传送到网点 |
| `/bank outlets` | 所有人 | 列出网点 |

一个世界可以有多个名字不同的网点。账本是全服一份，不是每个网点各一本。

## 界面

原版 `GENERIC_9X6` 箱子。按钮是带服务器 NBT 的原版物品，拿不走；漏进背包或掉在地上会被清掉。没有自定义 ScreenHandler 注册，也没有自定义物品。

底栏：总览、存入、取出、兑换、汇票、关闭。OP 多一个「信贷」。`/bank admin` 可以在任意地点打开信贷页，普通柜台仍然必须在网点。

- **存入**：把铜锭或钻石放进空格。余额、准备金、负债一起加。
- **取出**：×1 / ×10 / ×64 / 尽可能多。金库不够就拒绝；「尽可能多」会少给。单次最多 4096 个，再大请开汇票。
- **兑换**：选支付货币和获得货币。按钮上写着含手续费的价格。
- **汇票**：从余额开一张成书。可以「持票人」或「仅本人」。把书放进兑现格，钱回到账户。
- **信贷**（OP）：无准备增发、按准备金满额发行、注销、开关纸币兑现。

成功和失败写在聊天里，并在动作条上再闪一次。汇率本身不刷聊天。

## 大厅显示

默认全开。数字很大时用 `k` / `M` / `1.23e40` 这种写法。

- **侧边栏**：每个玩家自己的一份，大约每秒刷新，标题会换颜色。左边是余额、对计价货币（默认钻石）的汇率、费率、兑现覆盖、状态、最近的网点。1.20.1 的原版侧边栏**行尾一定有红色整数**，那些数字只是行序，不是余额，这样才不会被 32 位记分板卡住。
- **全息**：`text_display`，面向玩家，深色半透明底，彩色多行。跟着网点走，区块加载后会更新。
- **Boss 条**：纸币对钻石的价格，进度条是兑现覆盖。紧了变黄，挤兑或停兑变红。
- **Tab 页眉页脚**：银行名和主要汇率。

```
/bank display sidebar on|off
/bank display bossbar on|off
/bank display tab on|off
/bank display global sidebar|bossbar|tab|hologram|join on|off
```

上面三行只关自己的。`global` 是 OP 的总开关，关掉之后个人再开也看不到。全息只有总开关和每个网点的开关。

## 货币和汇率

默认三种，写在 `config/bank.json`。第一次进世界时抄进存档；之后以存档为准。改文件后 `/bank reload` 会更新费率、α、夹紧，并补上新货币，不会清账户。

| id | 种类 | 物品 | 初始准备 / 负债 | 说明 |
| --- | --- | --- | --- | --- |
| `copper` | 实物 | 铜锭 | 1,000,000 | 数据包里的铜 |
| `diamond` | 实物 | 钻石 | 1,000 | 计价货币 |
| `note` | 纸币 | 成书汇票，不收纸 | 100,000 | 银票，1 钻平价 100 张 |

默认手续费 **2%**。每种货币的 α 默认 **1000‰**。汇率**默认不夹紧**，纸币可以飙到非常大。数据包把汇率夹在 1 和 999999，那会把恶性通胀藏住，所以这里不这么做。要用的话：

```
/bank clamp max 999999
/bank clamp min off
/bank fee 2
/bank alpha note 1000
```

α 为 0 时，纸币钉住平价，印钞不改汇率，但准备率仍然下降，金库空了照样拒兑。

### 和数据包的差别

数据包是：

```
EC = R + floor((R - L) × α / 1000)
铜/钻 = EC_铜 / EC_钻
```

开局负债记成 0，α = 1000 时 EC = 2R，相除之后铜/钻仍是 1000。但负债变大时 EC 变小，纸币会看起来更「硬」，这和金圆券式的滥发相反。

本模组把差额的符号反过来，开局把种子准备金同时记成负债（账是平的）：

```
V = R + (L - R) × α / 1000
```

α = 1000 时 V = L。铜/钻开局仍是 `1,000,000 / 1,000 = 1000`。多印负债、准备金不动，V 变大，这种货币更弱。运算用 50 位小数，付款向上取整到整数，不再做 32 位整除。

银票相对钻石：

```
倍数 = (1 - α/1000) + (α/1000) × (负债 / 记账准备)
再和「负债 / (钻石库存 × 平价 × 折扣)」里更差的那个比
银票/钻 = 100 × 这个倍数
```

无准备增发把负债/准备从 1 拉到 2 时，1 钻从 100 张变成 200 张。金库里的钻石被取光、而 α 又不是 0 时，报价直接记成崩溃，兑换拒绝。

存入实物：余额、准备金、负债一起加。取出三者一起减，但不能动已经锁给纸币的那一部分。兑换只搬负债，不动准备金。用纸币（或别的货币）换成实物时，换出来的债权不能超过金库还兑得出的数量。

## 信贷和挤兑

OP，或权限等级 2：

```
/bank credit unbacked <玩家> note 1000000
/bank credit backed <玩家> note 1000
/bank credit retire <玩家> note 1000
/bank credit check <玩家> note 1e12
/bank freeze on
/bank supply
/bank reserve diamond 1000
```

金额可以写 `1_000`、`1.5k`、`2m`、`1e40`。

- **无准备发行**：玩家余额和负债增加，准备金不动。准备率下降，银票对钻石变弱。再加到极大，Boss 条和全息会写成「金圆券 · 挤兑」。
- **按准备金发行**：额度大约是 `钻石库存 × 折扣 × 平价 / 最低准备率` 减去已有银票负债。默认折扣 1、平价 100、最低准备率 1，所以开局 1000 钻正好覆盖初始 10 万银票，额度是 0。先存进钻石，才有新额度。发行会锁住相应的钻石，平价保持不变。
- **注销**：先烧掉没有准备的部分，汇率变好；再烧才会解锁钻石。
- **停止兑现**：只拦住「用纸币换硬通货」。存取实物、拿实物换银票、汇票进出账户仍然可以。

`/bank reserve` 只改库存数字，给运维用。锁住的部分不能被改到比锁更少。

## 汇票

开票得到一本原版成书，NBT 里有编号、币种、金额、出票人、可选抬头和时间。`generation` 设成 3（破损），工作台不能再抄。即便用别的办法复制，服务器存根也只兑付第一次；同一编号的第二本会被没收。金额和存根对不上，同样没收。抬头不是你，书还你，不去动存根。

从余额开票只是把存款换成一张纸，总负债不变。OP 的 `/bank credit check` 是无准备的新负债，玩家必须在线才能拿到书。

## 存档

权威数据在主世界的 `data/bankmod.dat`（NBT 里嵌着同一份 JSON）。旁边还有一份给人看的 `data/bankmod-ledger.json`，每次入账都会写，先写临时文件再替换。金额是十进制字符串。

卸掉模组不会删这些文件。想清空银行，停服后删掉这两个文件。余额不在记分板上，卸掉模组之后记分板里不会留下一本账。

可选导入，**不会自动跑**：

```
/bank import-scores
```

它读取记分板目标 `copper` 和 `diamond` 上、能对上玩家 UUID 的正数，当成一次存款（余额、准备金、负债都加）。每个玩家只导入一次。名字以 `#` 开头的假玩家会被跳过。这和旧数据包的 `#C` / `#D` 准备金不是同一套数；要对上旧金库，用 `/bank reserve`。

## 配置片段

`config/bank.json` 里和数据包对齐的部分：

```json
{
  "numeraire": "diamond",
  "feePercent": "2",
  "clampMin": null,
  "clampMax": null,
  "outletRadius": 4.0,
  "currencies": [
    { "id": "copper", "kind": "commodity", "item": "minecraft:copper_ingot", "initialReserve": "1000000", "alphaPermille": "1000" },
    { "id": "diamond", "kind": "commodity", "item": "minecraft:diamond", "initialReserve": "1000", "alphaPermille": "1000" },
    { "id": "note", "kind": "virtual", "name": "银票", "initialReserve": "100000", "issuable": true, "backing": "diamond", "parPerBacking": "100", "haircut": "1", "minReserveRatio": "1", "alphaPermille": "1000" }
  ]
}
```

实物货币的物品 id 不能重复。纸币的 `backing` 必须是实物货币。

## 这一阶段没有的东西

没有订单簿，没有挂牌，没有股价。以后如果要做市场，从 `dev.jdguo.bank.market.MarketHooks` 接已结算的转账即可，不要把行情写进这本账的来源里。

## 构建

```
./gradlew build
./gradlew test
```

`test` 不启动 Minecraft。覆盖的内容：存取和准备金不足、兑换不动金库、手续费、开局铜/钻 = 1000、汇票只兑一次、涂改金额无效、无准备发行压低准备率并让银票对钻石变弱、按准备金发行保持平价并锁钻石、α = 0 时印钞不改平价、极大整数不溢出、JSON 往返。
