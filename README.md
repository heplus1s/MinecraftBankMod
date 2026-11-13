# 中央银行

[English version below.](#central-bank)

这是一个 Minecraft 1.20.1 的 Fabric 模组。连到专用服务器时，玩家用原版客户端就能来银行存钱、取钱、换汇、开汇票，不用再装模组或资源包。想自己单人试，把模组装进 Fabric 客户端，打开世界即可。

钱记在模组自己的账本里，不用记分板，数字再大也不会溢出。

## 安装

单人测试：用 Fabric 1.20.1 的客户端，把 jar 和 Fabric API 一起放进客户端的 `mods/`，然后打开一个世界。银行跑在这个世界自带的集成服务器上。

专用服务器还是原来的装法。服务器需要 Fabric Loader、Fabric API，以及 Java 17 或更高版本。

1. 在项目目录执行 `./gradlew build`
2. 把 `build/libs/bankmod-1.0.0.jar` 放到服务器的 `mods/`，并确保同目录里有 Fabric API
3. 启动一次服务器，会自动生成 `config/bank.json`

连到专用服务器的原版客户端不用装这个模组，也不用资源包。

## 先设一个网点

银行不是在世界里随便一个地方打开的，要先由管理员在建筑上指定一个柜台。

看准要当柜台的方块（大约 6 格以内），执行：

```
/bank outlet set lobby
```

如果没看准方块，会用你脚下那一格。网点名字只能是 ASCII，1 到 32 个字符：开头是字母或数字，后面只能是字母、数字、下划线或连字符。已经存下来的旧名字，包括中文，还能用 move、remove、tp、radius、lift、hologram 指到。

设好以后，方块上会停一辆写着「中央银行 · 名字」的箱子矿车，原版客户端也能看见。右键方块或这辆矿车都会打开箱子界面，矿车会跟着存档留下；`/bank outlet remove` 和 `move` 会把它拆掉或挪走。如果这格本来就是箱子，右键方块会改由银行处理。站在网点附近（默认 4 格）也可以输入 `/bank`。全息字牌会浮在柜台上方，服务器重启后还在。

一个世界可以设多个网点。账是全服共用的，不是每个柜台一本。

| 命令 | 谁能用 | 做什么 |
| --- | --- | --- |
| `/bank outlet set <name>` | 管理员 | 把准星指着的方块设成网点 |
| `/bank outlet move <name>` | 管理员 | 把网点搬到准星下的新方块 |
| `/bank outlet remove <name>` | 管理员 | 删掉网点，并拆掉全息字牌 |
| `/bank outlet radius <name> <1-32>` | 管理员 | 附近可以用 `/bank` 的半径 |
| `/bank outlet lift <name> <0.5-16>` | 管理员 | 全息字牌离方块的高度，默认 2.4 |
| `/bank outlet hologram <name> on\|off` | 管理员 | 这个网点要不要显示全息字牌 |
| `/bank outlet tp <name>` | 管理员 | 传送到网点 |
| `/bank outlets` | 所有人 | 列出网点 |

## 怎么用

界面是原版的大箱子。格子里的按钮是普通物品，拿不走；万一进了背包或掉在地上，也会被清掉。

打开柜台先看到「储蓄」，也就是你自己的存款。底栏可以切换：储蓄、存入、取出、兑换、汇票、交易、个人发行。还没有个人银行券时，个人发行页只选择浮动、钉住或定额。名字和开价用指令填写，不在箱子里起名。「管理」只有管理员能看见，里面是央行的准备金、发行额、汇率，以及央行发行、注销和暂停兑付。普通玩家必须在网点才能开柜台；管理员用 `/bank admin` 可以在别处打开管理页。

- **存入**：把铜锭、钻石，或纸质银行券放进空格。铜锭和钻石会让余额、准备金和发行额一起增加。银行券只是把纸上的余额存回账户，发行额不变。
- **取出**：铜锭和钻石可以按 1、10、64 或「尽可能多」来取。库存不够会拒绝；选「尽可能多」时，会按实际库存少给。银行券取出来是原版纸，面额相同的叠成一摞，64 张是一摞。一次最多取 4096 张，再多可以用 `/bank withdraw note <amount>`，会给一张这个面额的纸，同样的面额还能继续叠。
- **兑换**：先选付出哪种货币、得到哪种货币。按钮上写的是已经算上手续费的价格。
- **汇票**：从余额里开出一本成书，可以写成持票人或仅本人。把书放进兑付格，钱会回到账户。
- **发行**：先选汇率制度，再用 `/bank note create` 起名和开价。记名之后可以按准备金发行，也可以虚发（不锁定准备金）。央行的增发、注销和暂停兑付在管理员的「管理」页。

做成或被拒绝时，会在聊天里说一声，动作栏也会闪一下。中文客户端看到数据包那种说法，例如 `[银行] 存入 铜锭 ×64 成功 ｜余额：铜 64，钻 0，银行券 0`，取出和兑换也带上这笔余额。英文客户端看到对应的英文，例如 `[Bank] Deposited copper ×64 | Balance: Cu 64, Dia 0, bank note 0`。汇率本身不写进每一条聊天。

## 大厅里能看到什么

这些显示默认都是开着的。数字很大时会写成 `k`、`M`，或用科学计数法。

- **侧边栏**：每人一份，大概每秒更新一次。上面有余额、对钻石的汇率、手续费、兑付情况和最近的网点。1.20.1 的侧边栏行尾会带一个红色数字，那只是排列用的序号，不是余额。
- **全息字牌**：浮在网点上方，面向玩家，显示汇率和状态。
- **Boss 条**：开局写着 `汇率：1000 铜/钻`。进度表示银行券还能兑出多少。紧了会变黄，无法兑付或暂停兑付会变红。
- **玩家列表**：页眉和页脚会显示银行名称和主要汇率。

```
/bank display sidebar on|off
/bank display bossbar on|off
/bank display tab on|off
/bank display global sidebar|bossbar|tab|hologram|join on|off
```

前三条只管自己。`global` 是管理员的总开关，关掉以后，个人再开也看不到。全息字牌只有总开关，以及每个网点自己的开关。

## 货币和汇率

默认有三种货币，写在 `config/bank.json`。第一次进世界时会抄进存档，之后以存档为准。改完配置后用 `/bank reload`，可以更新费率、权重和上下限，也能加上新货币，但不会清空账户。

| id | 种类 | 物品 | 初始准备 / 发行额 | 说明 |
| --- | --- | --- | --- | --- |
| `copper` | 实物 | 铜锭 | 1,000,000 | 铜 |
| `diamond` | 实物 | 钻石 | 1,000 | 计价货币 |
| `note` | 纸币 | 原版纸，同面额可叠放；汇票仍是成书 | 1,000 | 银行券，开局 1 张兑 1 钻 |

默认手续费是 2%。银行券活期存款的默认年利率也是 2%，按 365 个游戏日计息，利息只从手续费形成的利息池里付。个人银行券各自有利息池，只按账面持有计息，默认年利率是 0。纸币计入 M0 和 M1，但不计息。每种货币的差额权重 α 默认是 1000‰。汇率默认不设上下限，这样银行券贬值时，数字可以变得很大。如果要限制，可以用：

```
/bank clamp max 999999
/bank clamp min off
/bank fee 2
/bank interest 2
/bank interest personal 0
/bank alpha note 1000
```

如果把 α 设成 0，银行券会钉在平价上，多印也不会改汇率。不过准备金率照样会下降，库存空了还是兑不出来。

默认准备金是 1,000,000 铜锭和 1,000 钻石，所以一进世界就是 1 钻 = 1,000 铜，和数据包行动栏上的 `汇率：1000 铜/钻` 一样。和旧版银行数据包相比，这里把差额的方向改了一下：多印发行额、准备金不动时，这种货币会变弱，而不是变硬。开局把种子准备金同时记作发行额，所以账是平的。计算用高精度小数，付款时向上取整到整数。

有效数量按下面这个式子算：

```
V = R + (L - R) × α / 1000
```

其中 `R` 是准备金，`L` 是发行额。α 为 1000 时，`V` 就等于发行额。银行券对钻石还会再看一层：发行额相对准备金越高，1 钻要换的银行券就越多；无准备金增发如果把这个比例从 1 拉到 2，1 钻就会从 1 张变成 2 张。库存里的钻石被取光，且 α 不是 0 时，报价会标成无法报价，并拒绝再兑。钻石不够，或者钻石价值凑不成整数时，差额按当时的铜/钻汇率用铜锭补上，开局就是 1 钻 = 1,000 铜。

存入实物时，余额、准备金和发行额一起加。取出时一起减，但已经锁给银行券的那部分钻石取不走。兑换只调整发行额，不动库存。用银行券换实物时，不能超过库存实际还能兑出的数量。

## 央行发行

管理员（或权限等级 2）可以用：

```
/bank credit unbacked <player> note 1000000
/bank credit backed <player> note 1000
/bank credit retire <player> note 1000
/bank credit check <player> note 1e12
/bank freeze on
/bank supply
/bank reserve diamond 1000
```

金额可以写成 `1_000`、`1.5k`、`2m`、`1e40`。

- **无准备金发行**：给玩家加余额和发行额，准备金不变。银行券会贬值。发得特别多时，Boss 条和全息字牌会显示无法兑付。
- **按准备金发行**：额度按钻石库存和设置计算。默认 1 张银行券兑 1 钻，开局 1000 钻正好覆盖已有的 1000 张，所以一开始额度为 0，要先存进钻石才有新额度。这样发出去的银行券会锁住对应的钻石，平价不变。
- **注销**：先销掉没有准备金的部分，汇率会好转；再往下销，才会解锁钻石。
- **暂停兑付**：只暂停用银行券兑换铜锭或钻石。普通存取、用铜锭或钻石换银行券、汇票进出账户都还可以。

`/bank reserve <currency> <amount>` 用来改这种货币的开局准备金。玩家已经存进来的钱还在，库存和发行额会跟着开局数一起挪，所以汇率和能不能兑会对得上新账，而不是只改一个库存数字。锁住的部分不能改得比锁定数量更少。改完聊天里会报库存、发行额、锁定和现在的铜/钻汇率。

个人准备金是另一本账，留给以后的个人发币，跟存款、跟央行库存都分开。管理员可以用 `/bank personal get <player>` 查看，用 `/bank personal set <player> <currency> <amount>` 记下。设成 0 就是清掉。这不会改玩家余额，也不会改库存。

## 个人银行券

这和存款、个人准备金、央行的银行券都不是一回事。每个人只能有一张。名字最多三个汉字或英文字母，不能和别人重名，并且只能用下面的指令起名。创建时选定汇率制度，之后不能改。浮动按市价，钉住不跟买卖走，定额只兑面额上的那种实物。`/bank note issue` 会锁定个人准备金；`/bank note unbacked` 是虚发，不锁定准备金。

```
/bank note create diamond <name> <par>
/bank note create copper <name> <par>
/bank note create float diamond|copper <name> <par>
/bank note create peg diamond|copper|note <name> <rate>
/bank note create hard diamond|copper <name> <face>
/bank note issue <amount>
/bank note unbacked <amount>
/bank note bankrupt
/bank note bankrupt <name>
/bank note rate
/bank note rate <name>
/bank note buy <name> <amount>
/bank note sell <name> <amount>
/bank note swap <pay> <receive> <amount>
/bank note redeem <name> <currency> <amount>
/bank note redeem paper <currency>
```

`<currency>` 是 `diamond`、`copper` 或 `note`。`<amount>` 在互换里是要换入的张数。`/bank note bankrupt` 让这张券作废并留下账。已经兑出或不够的准备金记成欠央行的债，剩下的抵押收归央行，不退回发行人。之后发行人身上和附近新出现的铜锭、钻石，大约每秒划走一次用来还债。

`<par>` 和 `<amount>` 可以用 `1_000`、`1.5k`、`2m` 这种写法，适合箱子按钮上没有的数量。底栏「发行」用来记名和按 1、10、64、100 发行；常见开价是 1 钻、10 钻、1000 铜，名字用你游戏名里前三个汉字或字母。「交易」可以按央行银行券买卖，也可以两张个人银行券按市价互换，或按市价通兑成铜锭、钻石、银行券。别的数量用上面的指令。

个人银行券记在储蓄页上，和铜锭、钻石、央行银行券放在一起。二级买卖和互换只在账户之间过户，发行人的准备金不动。通兑才注销流通、解开锁定，并且只从已经锁定的准备金里付钱。市价高于准备金能付的部分时，按库存兑付，不会多付。发行人不能在二级市场跟自己对做，但可以通兑自己的券。

手里的个人银行券也是这种纸，名字写成「个人银行券「名字」」，不会和央行银行券叠在一起。取出用柜台按钮，或：

```
/bank note withdraw <amount>
/bank note withdraw <name> <amount>
/bank withdraw <currency> <amount>
/bank deposit
```

`/bank deposit` 存的是主手上的那一摞纸。已经入过账的，以及多出来的复制件，都会被收回，不会再次入账。

发行前，个人准备金至少要盖住「已发行加这次要发的数量 × 开价 × 覆盖倍数」。覆盖倍数默认是 1，管理员用 `/bank note coverage <ratio>` 改。

买卖的计价是央行银行券。开局 1 张央行银行券 = 1 钻。不够整张，或者钻石那边凑不齐，就按 1 钻 = 1,000 铜用铜锭找零。

价钱这样算。M1 是已经发出去的张数。有效准备是个人准备金和发行时锁住的数里较小的那个，再除以开价，得到能撑住多少张，记成准备张数。M2 = M1 + 准备张数。

```
强度 = 有效准备 / (开价 × M1)
流向 = (买入张数 - 卖出张数) / M1
流向系数 = 1 + 0.1 × 流向    （夹在 0.25 和 4 之间）
钻价 = 开价换成钻 × 强度 × 流向系数
```

还没发行时，钻价就等于开价换成钻。买入会把流向系数抬高，卖出会把它压低。准备不够，强度就小于 1，券会变便宜。

## 汇票

开出来的是一本原版成书，里面记着编号、币种、金额和出票人等信息，并标成「破损」，不能用工作台再复制。就算用别的办法复制了，服务器也只认第一次兑付，同一编号的第二本会被没收。书上的金额如果和存根对不上，同样没收。如果汇票写了抬头，而你不是那个人，书会还给你，存根不动。

从自己余额开票，只是把存款换成一张汇票，总发行额不变。管理员的 `/bank credit check` 则是新印出来的发行额，玩家必须在线才能拿到书。纸质银行券是另一样东西：用来把账户里的银行券拿进背包，不是汇票。

## 存档和配置

账记在主世界的 `data/bankmod.dat`，旁边还有一份方便查看的 `data/bankmod-ledger.json`。卸掉模组不会自动删除这些文件。如果要清空银行，先停服，再删掉这两个文件。

如果以前用过记分板版的银行数据包，可以执行一次：

```
/bank import-scores
```

它会把能对上玩家的 `copper`、`diamond` 正数当作存款导进来，每个玩家只导一次。名字以 `#` 开头的条目会跳过。旧数据包里的库存数字不会自动过来，需要对齐时用 `/bank reserve`。

`config/bank.json` 里和默认经济设定对应的部分如下：

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
    { "id": "note", "kind": "virtual", "name": "银行券", "nameEn": "bank note", "initialReserve": "1000", "issuable": true, "backing": "diamond", "parPerBacking": "1", "haircut": "1", "minReserveRatio": "1", "alphaPermille": "1000" }
  ]
}
```

实物货币的物品 id 不能重复。纸币必须指定一种实物货币作为准备金。

## 还没有的功能

现在还没有股市。以后如果要做，会另开模块，不会把行情写进这本银行账里。

## 编译

```
./gradlew build
./gradlew test
```

`./gradlew test` 不会启动游戏。它主要检查存取、兑换、汇票是否只兑一次、纸质银行券拆开或复制后会不会多出来、滥发后银行券会不会贬值、按准备金发行会不会锁钻，以及大数字会不会算爆。

许可是 MIT，版权归 heplus1s。Minecraft 是 Mojang 的商标，本项目与 Mojang 无关。

---

# Central Bank

[中文在上方。](#中央银行)

This is a Fabric mod for Minecraft 1.20.1. On a dedicated server, players can deposit, withdraw, exchange currencies, and issue bills of exchange with a vanilla client. No client mod or resource pack is needed. To try it alone, install the mod on a Fabric client and open a world.

Balances are stored in the mod's own ledger, not on the scoreboard, so the numbers can get arbitrarily large without overflowing.

## Installation

Singleplayer: use a Fabric 1.20.1 client, put the jar in the client's `mods/` folder together with Fabric API, then open a world. The bank runs on the integrated server that comes with that world.

A dedicated server is installed as before. The server needs Fabric Loader, Fabric API, and Java 17 or newer.

1. Run `./gradlew build` in the project directory
2. Put `build/libs/bankmod-1.0.0.jar` in the server's `mods/` folder, and make sure Fabric API is there too
3. Start the server once. It will create `config/bank.json`

A vanilla client connecting to a dedicated server still should not install this mod, and does not need a resource pack.

## Set up an outlet first

You can't just open the bank anywhere in the world. An operator has to mark a counter on a building first.

Look at the block you want as the counter (within about 6 blocks) and run:

```
/bank outlet set lobby
```

If you aren't looking at a block, it uses the one under your feet. Outlet names have to be ASCII, 1 to 32 characters: start with a letter or digit, then only letters, digits, underscore, or hyphen. Names already saved, including old non-ASCII ones, can still be used with move, remove, tp, radius, lift, and hologram.

After that, a chest minecart labeled "中央银行 · name" sits on the block. Vanilla clients can see it. Right-clicking the block or the cart opens the chest menu, and the cart is saved with the world. `/bank outlet remove` and `move` delete it or place it again. If the block is already a chest, right-clicking the block is handled by the bank instead. Players standing near the outlet (4 blocks by default) can also type `/bank`. A hologram floats above the counter and comes back after a restart.

A world can have more than one outlet. The ledger is shared across the server, not one book per counter.

| Command | Who can use it | What it does |
| --- | --- | --- |
| `/bank outlet set <name>` | operators | Turn the block you're looking at into an outlet |
| `/bank outlet move <name>` | operators | Move the outlet to the block you're looking at |
| `/bank outlet remove <name>` | operators | Remove the outlet, its minecart, and its hologram |
| `/bank outlet radius <name> <1-32>` | operators | How close you have to be to use `/bank` |
| `/bank outlet lift <name> <0.5-16>` | operators | How high the hologram sits above the block, default 2.4 |
| `/bank outlet hologram <name> on\|off` | operators | Whether this outlet shows a hologram |
| `/bank outlet tp <name>` | operators | Teleport to the outlet |
| `/bank outlets` | everyone | List outlets |

## How to use it

The interface is a vanilla large chest. The buttons in the slots are ordinary items and can't be taken. If one ends up in an inventory or on the ground, it gets cleared.

The counter opens on Savings, which is your own deposits. The bottom row switches pages: savings, deposit, withdraw, exchange, bills, personal trading, and personal issuance. Before you have a personal note, that page only lets you choose floating, pegged, or hard convertibility. The name and the par are entered with a command, not in the chest. Management is operators only: central reserves, amount issued, rates, and central-bank issuance, retirement, and the freeze control. Regular players can only open the counter at an outlet. Operators can use `/bank admin` to open Management elsewhere.

- **Deposit**: Put copper ingots, diamonds, or bank-note paper in an empty slot. Copper and diamonds increase your balance, the reserves, and the amount issued together. Bank-note paper only moves that balance back onto the account. The amount issued does not change.
- **Withdraw**: Copper and diamonds can be taken as 1, 10, 64, or "as many as possible". If the stock doesn't have enough, the request is refused; "as many as possible" gives you whatever is actually in stock. Bank notes come out as vanilla paper. The same face value stacks, and 64 sheets are one pile. One withdrawal gives at most 4096 sheets. For more, `/bank withdraw note <amount>` gives a single sheet of that face, and another withdrawal of the same face stacks with it.
- **Exchange**: First pick what you pay and what you get. The button already shows the price with the fee included.
- **Bills**: Issue a written book from your balance, payable to bearer or to yourself only. Put the book in the redeem slot and the money goes back to the account.
- **Issuance**: Choose the exchange-rate regime, then name the note and set its par with `/bank note create`. After that you can issue against reserves, or issue unbacked without locking reserves. Central issue, retire, and freeze live on the operator Management page.

Successful or refused actions get a chat message and a flash on the action bar. A Chinese client sees the datapack wording, for example `[银行] 存入 铜锭 ×64 成功 ｜余额：铜 64，钻 0，银行券 0`. Withdrawals and exchanges include that balance too. An English client sees the matching English line, for example `[Bank] Deposited copper ×64 | Balance: Cu 64, Dia 0, bank note 0`. The rate itself is not repeated in every chat line.

## What you see in the lobby

These displays are on by default. Very large numbers are written as `k`, `M`, or in scientific notation.

- **Sidebar**: One per player, updated about once a second. It shows balances, rates against diamond, the fee, convertibility, and the nearest outlet. On 1.20.1 the end of each sidebar line has a red number. That's just a sort index, not a balance.
- **Hologram**: Floats above the outlet, faces the player, and shows rates and status.
- **Boss bar**: It opens on `汇率：1000 铜/钻` (1 diamond = 1000 copper). The bar fill shows how many bank notes can still be redeemed. It turns yellow when tight, and red when redemption is unavailable or suspended.
- **Player list**: The header and footer show the bank name and the main rates.

```
/bank display sidebar on|off
/bank display bossbar on|off
/bank display tab on|off
/bank display global sidebar|bossbar|tab|hologram|join on|off
```

The first three commands only affect you. `global` is the operator master switch; once it's off, turning your own display back on does nothing. Holograms only have the master switch, plus a per-outlet switch.

## Currencies and exchange rates

There are three default currencies, written in `config/bank.json`. They are copied into the world save the first time you enter it, and the save is what counts after that. After editing the config, `/bank reload` can update fees, weights, and limits, and can add new currencies, without wiping accounts.

| id | Kind | Item | Opening reserve / amount issued | Notes |
| --- | --- | --- | --- | --- |
| `copper` | commodity | copper ingot | 1,000,000 | copper |
| `diamond` | commodity | diamond | 1,000 | the unit of account |
| `note` | paper | Vanilla paper that stacks by face value; bills stay written books | 1,000 | bank note. At the start, 1 bank note buys 1 diamond |

The default fee is 2%. Demand deposits of bank notes also earn a default 2% a year over 365 Minecraft days, paid only from the fee interest pool. Each personal note has its own pool and pays only book-entry holdings. The default personal-note rate is 0. Paper counts in M0 and M1, and it does not earn interest. The difference weight α for each currency defaults to 1000‰. Rates have no min/max limit by default, so the numbers can get very large when bank notes depreciate. If you want limits:

```
/bank clamp max 999999
/bank clamp min off
/bank fee 2
/bank interest 2
/bank interest personal 0
/bank alpha note 1000
```

If you set α to 0, notes stay at par and printing more doesn't change the rate. The reserve ratio still falls, and you still can't redeem once the stock is empty.

The default reserves are 1,000,000 copper ingots and 1,000 diamonds, so a fresh world starts at 1 diamond = 1,000 copper, the same `汇率：1000 铜/钻` the datapack showed on the action bar. Compared with the old bank datapack, the sign of the difference term is flipped: when the amount issued grows without adding reserves, the currency gets weaker, not stronger. Opening books record the seed reserves as amount issued as well, so the books start square. Calculations use high-precision decimals; payments are rounded up to a whole number.

Effective quantity is:

```
V = R + (L - R) × α / 1000
```

`R` is reserves, `L` is the amount issued. When α is 1000, `V` equals the amount issued. Bank notes versus diamonds have an extra layer: the more that has been issued relative to reserves, the more bank notes it takes to buy one diamond. If unbacked issuance pulls that ratio from 1 to 2, one diamond goes from 1 bank note to 2. When the diamond stock is emptied and α is not 0, the quote is marked as unavailable and further conversion is refused. If diamonds run short, or the diamond value is not a whole number, the remainder is paid in copper at the current copper-per-diamond rate, which opens at 1 diamond = 1,000 copper.

Depositing a commodity increases the balance, reserves, and the amount issued together. Withdrawals decrease all three, but diamonds already locked to bank notes can't be taken out. Exchange only adjusts the amount issued, not the stock. Converting a bank note into a commodity can't exceed what the stock can actually still redeem.

## Central issuance

Operators (or permission level 2) can use:

```
/bank credit unbacked <player> note 1000000
/bank credit backed <player> note 1000
/bank credit retire <player> note 1000
/bank credit check <player> note 1e12
/bank freeze on
/bank supply
/bank reserve diamond 1000
```

Amounts can be written as `1_000`, `1.5k`, `2m`, or `1e40`.

- **Unbacked issuance**: Adds to the player's balance and to the amount issued, without changing reserves. Notes depreciate. If you issue a great deal, the boss bar and hologram will show that redemption is unavailable.
- **Backed issuance**: The allowance is computed from diamond stock and the settings. By default 1 bank note is worth 1 diamond, and the opening 1000 diamonds already cover the existing 1,000 bank notes, so the starting allowance is 0. You have to deposit diamonds first to get more room. Notes issued this way lock the corresponding diamonds and stay at par.
- **Retirement**: Unbacked amounts are retired first, which improves the rate; only further retirement unlocks diamonds.
- **Freeze convertibility**: Only blocks exchanging paper for copper or diamonds. Ordinary deposits and withdrawals, exchanging copper or diamonds for notes, and bills in or out of accounts still work.

`/bank reserve <currency> <amount>` sets that currency's opening reserve. Money players have already deposited stays. The stock and the amount issued move with the new opening figure, so the quote and what can be redeemed match the new books instead of only one stock number changing. You can't set it below the locked amount. The chat reply reports the stock, the amount issued, the locked amount, and the current copper-per-diamond rate.

Personal reserves are a separate ledger reserved for later personal currency issuance. They are not savings deposits and they are not central stock. Operators can read them with `/bank personal get <player>` and record them with `/bank personal set <player> <currency> <amount>`. Setting 0 clears that entry. It does not change the player's balance or the stock.

## Personal notes

These are not savings, not the personal reserve, and not the central bank note. Each player can have one. The name is at most three Chinese characters or English letters, it can't match someone else's, and it is set only with the command below. The exchange-rate regime is chosen at creation and is not switched later. Floating follows the market, a peg ignores trades, and hard convertibility pays only the face commodity. `/bank note issue` locks personal reserves; `/bank note unbacked` does not.

```
/bank note create diamond <name> <par>
/bank note create copper <name> <par>
/bank note create float diamond|copper <name> <par>
/bank note create peg diamond|copper|note <name> <rate>
/bank note create hard diamond|copper <name> <face>
/bank note issue <amount>
/bank note unbacked <amount>
/bank note bankrupt
/bank note bankrupt <name>
/bank note rate
/bank note rate <name>
/bank note buy <name> <amount>
/bank note sell <name> <amount>
/bank note swap <pay> <receive> <amount>
/bank note redeem <name> <currency> <amount>
/bank note redeem paper <currency>
```

`<currency>` is `diamond`, `copper`, or `note`. In a swap, `<amount>` is how many notes you receive. `/bank note bankrupt` voids the note and keeps the ledger. Reserves already paid out, and any shortfall, become a debt to the central bank. Leftover collateral is taken by the bank, not handed back. Copper and diamonds on the issuer, including new ones that appear nearby, are levied about once a second and applied to that debt.

`<par>` and `<amount>` accept `1_000`, `1.5k`, and `2m`, for amounts the chest buttons don't show. The Issuance tab names the note and issues 1, 10, 64, or 100. Common opening prices are 1 diamond, 10 diamonds, or 1000 copper, and the name is the first three Chinese characters or letters of your player name. The trading tab buys and sells against central notes, swaps two personal notes at the market cross rate, or redeems a personal note into copper, diamonds, or central notes at the live quote. Use the commands above for anything else.

Personal notes sit on the savings page with the other balances. Secondary buys, sells, and swaps only transfer account holdings. The issuer's posted reserve does not move. Universal redemption is what retires circulation and unlocks the reserve, and it pays only from that posted reserve. If the market value is higher than the reserve can fund, the payout is limited to the reserve. An issuer cannot take the other side of their own note, and can still redeem it.

Personal notes use the same paper. The name reads as a personal note, so it does not stack with central bank notes. Withdraw them from the counter, or with:

```
/bank note withdraw <amount>
/bank note withdraw <name> <amount>
/bank withdraw <currency> <amount>
/bank deposit
```

`/bank deposit` deposits the pile in your main hand. Paper that was already deposited, and any extra copies, are taken back and are not credited again.

Before issuing, the personal reserve has to cover `(already issued + this issue) × par × coverage`. Coverage defaults to 1. Operators change it with `/bank note coverage <ratio>`.

Trades are priced in the central bank note. At the start, 1 central bank note = 1 diamond. Anything short of a whole note, or a diamond the stock can't pay, is settled in copper at 1 diamond = 1,000 copper.

The price works like this. M1 is notes already issued. Effective backing is the smaller of the personal reserve and the amount locked at issue, divided by par, which is how many notes that reserve can still support. M2 = M1 + that reserve-note count.

```
strength = effective backing / (par × M1)
flow = (notes bought - notes sold) / M1
flow factor = 1 + 0.1 × flow    (clamped between 0.25 and 4)
diamond price = par in diamonds × strength × flow factor
```

Before anything is issued, the diamond price is just the par converted into diamonds. Buying pushes the flow factor up, selling pushes it down. If the reserve runs thin, strength drops below 1 and the note gets cheaper.

## Bills of exchange

What you get is a vanilla written book. It records an id, the currency, the amount, the issuer, and so on, and is marked tattered so it can't be copied at a crafting table. Even if it is copied some other way, the server only honors the first redemption; a second book with the same id is confiscated. If the amount on the book doesn't match the stub, it's confiscated too. If the bill has a named payee and you aren't that person, the book is returned and the stub is left alone.

Issuing a bill from your own balance just turns a deposit into a bill. The total amount issued doesn't change. An operator's `/bank credit check` is newly issued money, and the player has to be online to receive the book. Bank-note paper is separate: it moves note balances into the inventory, and it is not a bill.

## Saves and config

The ledger lives in the overworld at `data/bankmod.dat`, with a human-readable copy at `data/bankmod-ledger.json`. Uninstalling the mod does not delete these files. To wipe the bank, stop the server and delete both.

If you previously used the scoreboard bank datapack, run this once:

```
/bank import-scores
```

It imports positive `copper` and `diamond` scores for players it can match, once per player. Entries whose names start with `#` are skipped. Vault figures from the old datapack are not imported automatically; use `/bank reserve` if you need to line them up.

The part of `config/bank.json` that matches the default economy looks like this:

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
    { "id": "note", "kind": "virtual", "name": "银行券", "nameEn": "bank note", "initialReserve": "1000", "issuable": true, "backing": "diamond", "parPerBacking": "1", "haircut": "1", "minReserveRatio": "1", "alphaPermille": "1000" }
  ]
}
```

Commodity item ids can't be reused. A paper currency has to name a commodity as its backing.

## What's not here yet

There's no stock market yet. If that gets built later, it will be a separate module. Market prices won't be written into this bank ledger.

## Building

```
./gradlew build
./gradlew test
```

`./gradlew test` does not start the game. It mainly checks deposits, withdrawals, exchange, that bills redeem only once, that split or copied bank-note paper cannot mint extra value, that unbacked issuance depreciates notes, that backed issuance locks diamonds, and that huge numbers don't blow up the math.

The license is MIT, copyright heplus1s. Minecraft is a trademark of Mojang. This project is not affiliated with Mojang.
