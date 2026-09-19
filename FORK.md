# FORK.md — 本 fork 的说明

> 本文件说明**这个 fork 相对上游做了什么、后续打算做什么、以及怎么跟上上游**。
> 面向"clone 下来要用/要改"的人。内部工作笔记（`PLAN.md`）含本机路径，只留在本地、不随仓库发布。

---

## 这是什么

[`loks666/get_jobs`](https://github.com/loks666/get_jobs) 的**个人改造 fork**：原项目是一个"多平台 + 图形界面"的
自动投递工具，本 fork 把它收窄成**只服务 Boss 直聘、面向技术用户、配置即文件**的版本，
并把投递流程重构成**平台可插拔**的结构。

> ⚠️ 这是个人自用取向的改造，**不是上游的替代品**。需要猎聘 / 前程无忧 / 智联招聘，
> 或者需要开箱即用的图形界面，请直接用[上游](https://github.com/loks666/get_jobs)。
> 协议沿用上游的 **PolyForm Noncommercial 1.0.0**（允许非商业使用，禁止商业使用）。

- 本 fork 仓库：https://github.com/HuXiaoCAPS/get_jobs_boss
- 上游仓库：https://github.com/loks666/get_jobs

---

## 定位与取舍

**做什么**
- 只做 **Boss 直聘**（把另外三个平台的代码整块删掉，而不是留着不用）
- **配置即文件**：`config/boss.yaml` 是权威来源，可以 git diff、批量编辑、给不同求职方向建分支
- 面向技术用户：命令行 `gradlew` 能跑、日志说得清、出错能定位

**刻意不做**
- **不加平台**：删掉的三个平台不再维护。（真要用，抽象已经就位，加回来是"加一个包"的事，见下文）
- **不做自由 SQL / 裸表管理页**：数据页复用既有 API，避免注入面与误操作
- **不追求通用**：默认值是按"数据/后端求职"这类场景调的，别的方向需要自己改配置

---

## 相对上游改了什么

### 1. 平台收敛：只留 Boss

删掉 **37 个后端文件**（35 个平台专属 + 2 个被新架构取代）：

| 类别 | 数量 | 内容 |
|---|---|---|
| `worker/{liepin,job51,zhilian}` | 7 | 三个平台的主类 / Config / Locators |
| `worker/service/*JobService` | 5 | 三个平台的 JobService + `BossJobService`、`JobPlatformService`（被任务壳/新契约取代）|
| `application/controller` | 3 | JobController(51job) / LiepinController / ZhilianController |
| `application/service` | 3 | LiepinService / Job51Service / ZhilianService |
| `application/entity` | 9 | 三个平台的 Config / 主实体 / Option |
| `application/mapper` | 9 | 上面对应的 Mapper |
| `application/init` | 1 | ZhilianOptionInitializer |

`PlaywrightManager` 从 2069 行减到 1007 行（只初始化与监控 Boss 页面）；`ConfigService` 只剩 Boss。

> 上游是"接口 + 四个平台各写一遍"（每个平台一套 `xxx(keyword)` / `getSearchUrl()` / 页面流程，零复用）。
> 本 fork 把这些**平行副本**换成了真正的抽象（下一节）。

### 2. 投递流程：可插拔架构

上游的"总体流程"其实已经平台无关（搜索 → 详情 → 过滤 → AI → 打招呼 → 落库 → 限速），
平台特有的只是括号里的**页面动作**。本 fork 把它拆成：

```
                          ┌──────────────── JVM（单进程）────────────────┐
HTTP/SSE ─→ PlatformController ─→ PlatformTaskManager ─→ DeliveryRunner ─→ JobPlatform(实现)
            /api/platforms/{id}    任务壳：状态/停止/SSE    流程：过滤/AI/落库/限速     ↑
                                                 │                          └─ DeliveryStore(实现)
                                                 └─ PlatformRegistry（Spring 自动发现）
                                   配置侧：DeliveryPolicyService（投递策略，平台无关）
```

| 组件 | 位置 | 职责 |
|---|---|---|
| `JobPlatform` | `worker/platform/` | **平台契约**：`id` / `displayName` / `isLoggedIn` / `ensureSession` / `pauseMonitoring` / `resumeMonitoring` / `runTask` / `buildQueries` / `search` / `openDetail` / `matchesCity` / `sendGreeting` / `supportsImageResume` / `scanReplies` / `store()`（后 5 个有默认实现）。签名里不出现 Locator/Page/JSON/平台配置类 |
| `DeliveryRunner` | 同上 | **流程层**：搜索→详情→过滤→AI→落库→限速。不 import 任何平台包、无 Playwright 类型 |
| `DeliveryStore` | 同上 | 数据读写契约；实现由**平台自报**（`BossDeliveryStore` → `boss_data`，假平台 → 内存） |
| `PlatformTaskManager` | 同上 | 任务壳：start/stop/status/SSE + 强制复位看门狗，**不含任何平台字眼** |
| `PlatformRegistry` | 同上 | `ObjectProvider<JobPlatform>` 自动发现；**零实现只打警告、不抛异常** |
| `BossPlatform` | `worker/boss/` | Boss 的 `JobPlatform` 实现，只做页面操作（上游 `Boss.java` 2223 → 1207 行） |

**"缺平台也能运行"是硬要求**（不是口号）：把 `worker/boss` 整个包移走，程序照常启动、管理页照常打开，
只是没有可投递的平台。四个支柱：① 平台靠 Spring 自动发现（`ObjectProvider`，零实现安全）；
② 流程层只认接口与模型；③ 每个平台的"数据实现 / 线程要求"由平台自己声明；
④ 前端按 `GET /api/platforms` 渲染。

**顺带抽出的平台无关纯逻辑**：`ReplyClassifier`（回复是否算拒绝）、`HrActivity`（HR 活跃度解析）、
`CityFilter`（省份→城市展开）、`JdRuleFilter`（`config/jd-rules*.txt` 规则，原在 `worker/boss` 包下）。

### 3. 配置外置为文件

`config/` 下的 YAML 是配置的**权威来源**（默认 `config/boss.yaml`，模板见 `config/boss.yaml.example`，
不含密钥、可提交）。可以放多份配置（如 `数据开发.yaml`），当前生效的那份记在 `config/.active`；
网页端「配置 → 配置文件」里可切换 / 新建 / 重命名 / 删除，切换后整页配置项与过滤规则一起换。

```
启动 / 每次投递 → syncConfigFromFile()
   ├─ 文件不存在 + 库里有配置 → 自动导出成文件（首次迁移，配置不丢）
   └─ 文件存在               → 以文件为准，同步写回 boss_config / ai / config 三张表
网页端点保存 → 先写文件（在现有内容上合并）+ 立刻回写库
```

涉及 `ConfigFileService`（纯 YAML I/O）与 `BossService` 的 sync/export/save 三件套。
**API Base URL / API Key / 模型只认文件**，网页端不提供编辑入口。

### 4. 投递策略与平台解耦

兜底招呼语 / 岗位间隔 / AI 开关 / HR 活跃阈值 / 是否发图片简历 / 同公司去重 / 调试模式 ——
这些属于"这轮投递怎么跑"，与用哪个网站招人无关，因此**不在平台接口上**，
由全局的 `DeliveryPolicyService` 从 `config/boss.yaml` 的 `delivery` 段读取。

> 顺带修掉一个上游遗留问题：配置里的 `filter_dead_hr` 开关此前**不生效**（总是按活跃度过滤），
> 现在关闭开关就真的不判活跃度。

### 5. 搜索语义：多城市两种模式

Boss 的搜索一次只认一个城市码（这是平台限制，不是实现偷懒），所以：

| 模式 | 配置 | 行为 |
|---|---|---|
| **轮换**（默认） | `search.city_filter_mode: false` | 对 `city` 列表里每个城市各搜一轮 |
| **过滤** | `search.city_filter_mode: true` | 搜索直接用全国码 `100010000`，再按岗位自身城市筛（命中才投） |

另外支持 **`search.city_exclude`**：排除城市/省份。填省份名会自动展开成该省城市
（如 `广东` → 广州/深圳/东莞…），省得逐个枚举 —— 因为 Boss 的岗位地点是「深圳·南山区」，里面**没有省份**。

### 6. 前端重组

侧栏 → **顶部工具栏**（无渐变），四个页面：

| 页面 | 内容 |
|---|---|
| `/deliver` 投递 | 左列平台（来自 `GET /api/platforms`）、右侧开始/停止 + 当次上限、SSE 实时进度 |
| `/boss` 配置 | 单页配置中心：配置文件（另存为/载入/选择/保存）/ 搜索条件 / 投递行为 / AI 提示词与我的资料 / 通知 / 黑名单 / **过滤规则（JD）** |
| `/data` 数据 | 平台选择 + KPI + 筛选条 + 分页表格（平台无关：`/api/platforms/{id}/jobs` + `/stats`）+ 每行「查看详情」弹窗 |
| `/appearance` 外观 | 主题、每页条数、表格密度（存 localStorage） |

删掉了上游的 ai-config / env-config / 三个平台页与旧的岗位分析页。
**平台列表为空时页面显示提示而不是报错** —— 这是"缺平台也能跑"在界面上的体现。

数据页与投递页一样是**按平台渲染**的：它面对 `JobRecord` / `JobStats` 这类平台无关模型，
真正查询由各平台自己的 `DeliveryStore#listJobs / #jobStats` 实现。
早先它写死 `/api/boss/list`、`/api/boss/stats` 与「来自 boss_data 表」，
字段直接对着表结构 —— 那样"加平台"在数据侧等于零支持，
所谓"预留接入能力"只覆盖了投递链路。平台没实现数据能力时接口返回 **501**
（而不是空列表），页面据此提示"该平台暂不支持查看数据"。
`/api/boss/list|stats` 保留为兼容层，上游的 `BossAnalyticsController` 未改动。

### 7. 其它增强（多为上游已有能力的落地或修复）

- **诊断**：`BossDiagnostics` 在关键失败点打印「步骤名 + URL + 关键选择器命中数」并落 `target/boss-diagnose.txt`，
  改版时能一眼看出是哪个选择器失效（上游是静默跳过）
- **同一家公司不重复投递**：可按 `delivery.skip_delivered_company` 开关（默认开）
- **停止反馈**：状态里新增 `stopping`，按钮变「正在停止…」，不再像卡死
- **多值字段兼容全角逗号**（`，`）、顿号、中文分号 —— 手打配置时很容易踩
- **浏览器内核可选**：`browser.channel`（`msedge` / `chrome` / `chromium`）—— 原先靠环境变量
  `BROWSER_CHANNEL`，已改到配置文件，环境变量一律不再参与（`MANAGE_BROWSER` 同样去掉）
- **配置文件可多份并随网页切换**：见上文「配置外置为文件」
- **JD 过滤规则**：`reject`（命中即拒）/ `require`（必须命中）/ `warn`（只记提示）三类，
  每条带阈值与备注；匹配文本 = 岗位名 + JD 正文 + showSkills。
  规则存在**当前配置文件的 `jd_rules` 段**里，网页端「配置 → 过滤规则」按"一条规则"为单位
  增删改（弹窗表单：动作 / 名称 / 阈值 / 备注 / 词表），改完下一次点「开始投递」即生效，不需重启。
  因为规则与配置同文件，切换配置天然切换规则，另存为 / 改名 / 删除配置也天然把规则带走 ——
  不用维护任何"规则文件 ↔ 配置文件"的对应关系
- **首次无缓存登录不再被弹回登录页**：此前只要探测到一次"未登录"就强制导航到登录页，
  而扫码成功后页面要连跳几步、检测会短暂误判 —— 现在只在**从未登录过**时才自动引导
- **在自动化浏览器中打开岗位**（数据页详情弹窗）：管理页跑在用户自己的浏览器里，那里
  **没有**自动化 profile 的登录态，点开岗位详情只会看到登录页。所以「打开」走后端，
  由平台自己的浏览器（Boss 用持久化 profile `browser-data/`）新开标签页打开。
  这是平台能力（`JobPlatform#openInBrowser`，默认不支持），**域名校验交给平台**
  （Boss 只认 `zhipin.com`）—— 否则网页端就成了"用已登录浏览器打开任意地址"的跳板。
  投递进行中会拒绝（`isPlaywrightBusy()`），免得打断正在跑的流程
- **数据页表格**：列表只留 岗位/公司/薪资/城市/经验/学历/状态/备注/操作，
  HR 与 HR 活跃、公司信息、JD 全文都收进「查看详情」弹窗；页面容器宽度由 1152px 放宽到 1600px
- **遗留数据清理**：启动时幂等删掉上游多平台时代留下的 9 张空表（`job51_*` / `liepin_*` / `zhilian_*`）
  与 `cookie` 表里 3 行遗留记录 —— 免得后来的人猜"哪个表还有用"
- **死代码清理**：`BossIndustry` 三件套（数据库里根本没有这张表，一调就 SQLException）、
  `/api/boss/execute`、`/api/boss/stream` 及配套的旧版 SSE 桥接（进度统一走 `/api/platforms/{id}/stream`）
- **API 形状**：新增 `/api/platforms`（列表）、`/api/platforms/{id}/{start,stop,status,stream}`、
  `/api/platforms/{id}/{jobs,stats}`（岗位数据浏览，平台无关模型）、`/api/boss/jd-rules`（读写过滤规则）、
  `/api/boss/config-files`（多份配置的列出/切换/另存为/载入/改名/删除）；
  `/api/boss/{start,stop,status,logout}` 与 `/api/boss/{list,stats}` 保留为兼容层
  （登录态、退出登录是 Boss 特有的，平台无关层表达不了）

---

## 是否跟随上游

**不同步。** 本 fork 是独立历史，与上游没有共同祖先，合不了 —— `git merge upstream/main` 直接报
`fatal: refusing to merge unrelated histories`。上游有需要的修复，手工挑即可。

**验收方式**（没有单元测试，只能这样验）
```bash
./gradlew compileJava                        # 编译
./gradlew bootRun                            # 启动：日志应出现「已注册求职平台 1 个: [boss]」
curl -X POST http://localhost:9527/api/dev/fake-delivery
#   预期 {"result":{"delivered":1,"filtered":3,"skipped":1}} —— 这是纯内存的假平台，
#   不碰浏览器、不碰数据库，用来证明"流程层与平台解耦"没被破坏
```

---

## 后续计划

按优先级排列，含"未接线功能"的说明（这些不是死代码，别误删）。

### 待接线：这些功能有配置入口、有实现，但**没有调用点**

| 功能 | 现状 |
|---|---|
| **企业微信通知** | 前端「通知」区块能配 `HOOK_URL` / `BOT_IS_SEND`，`worker/utils/Bot.java` 也实现了发送，但**零调用点** → 配了也收不到推送。**计划：在投递结束处调用一次 `Bot`**，发送本轮汇总 |
| **薪资过滤** | `boss_config.expected_salary_min/max` 与 `BossConfig.expectedSalary` 已无消费方，对应上游 README 宣称但未实现的"自动过滤目标薪资"。计划：要么实现，要么连字段一起删 |

### 计划中的功能

- **数据页**：CSV 导出（导出当前筛选结果）、日期范围筛选、投递趋势
- **加回平台**：抽象已就位，加平台 = 加一个包 + `@Component` + 一个 `DeliveryStore` 实现。
  真要做的话会先补 `FakePlatform` 那套验收再上真平台
- **`boss_option` 字典的开箱可用**：城市 / 行业下拉的字典数据只存在于本地库，
  新 clone 的人需要自备 `db/getjobs.db`（见「快速开始」），可以考虑做成种子文件或启动时拉取

### 计划中的清理（纯死代码，删了无副作用）

- `dead_status`（`BossConfigEntity.deadStatus` / `BossConfig.deadStatus`）—— 消费方已删

> 已经做完的清理（`BossIndustry` 三件套、`/api/boss/execute`、`/api/boss/stream`）见上文
> 「相对上游改了什么 → 7. 其它增强」。

---

## 快速开始

与上游的差别只有"配置从哪来"和"页面在哪"，其余环境要求一致（**JDK 21**、Gradle、Node.js）。

```bash
git clone https://github.com/HuXiaoCAPS/get_jobs_boss.git
cd get_jobs_boss

# 0) JDK 21 若装在"非标准位置"（不在 Gradle 自动扫描目录里），把这行写进**用户级**配置，
#    不要写进仓库内的 gradle.properties（本 fork 特意让后者保持通用、不含本机路径）：
#      ~/.gradle/gradle.properties
#      org.gradle.java.installations.paths=<你的 JDK 目录>
#    漏了这行时命令行 ./gradlew 会报 "Failed to calculate ... property 'javaCompiler'"

# 1) 数据库：db/ 目录已随仓库提供（.gitkeep 占位），首次启动会自动建库、建表；
#    但「城市 / 行业」等下拉字典在 boss_option 表里（属数据不属结构），需要一份可用库：
#    按上游 README 从 release 下载 getjobs.db.template → 重命名为 getjobs.db → 放 db/
#    老用户直接用自己已有的 db/getjobs.db

# 2) 配置：复制模板后修改（这是本 fork 与上游最大的使用差异）
cp config/boss.yaml.example config/boss.yaml
#   必填：ai.api_key / ai.model / ai.base_url
#   常用：search.keywords、search.city、search.city_exclude、delivery.wait_time
#   可选：manage_page.browser（管理页用哪个浏览器打开，默认 msedge）

# 3) 运行
./gradlew bootRun          # API: 9527，管理页: 6866（启动后自动打开）

# 4) 前端若要自己改：改完必须重新构建进静态资源
./gradlew buildFrontend -PrebuildFrontend
```

- 环境配置（浏览器/chromedriver 等）见上游 wiki：https://github.com/loks666/get_jobs/wiki/环境配置
- 想关掉"同一家公司不重复投递"：`delivery.skip_delivered_company: false`
- 想看整个投递的过滤原因：日志里搜「被过滤」，或看 `target/boss-diagnose.txt`

---

## 免责声明

本 fork 仅用于个人学习与技术研究，请遵守目标网站的服务条款，控制请求频率（`delivery.wait_time`），
不要用于商业用途 —— 协议沿用上游的 PolyForm Noncommercial 1.0.0。
