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
`CityFilter`（省份→城市展开）、`JdRuleFilter`（`jd-rules.txt` 规则，原在 `worker/boss` 包下）。

### 3. 配置外置为文件

`config/boss.yaml` 是配置的**权威来源**（模板见 `config/boss.yaml.example`，不含密钥、可提交）：

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
| `/boss` 配置 | 单页配置中心：搜索条件 / 投递行为 / AI 提示词与我的资料 / 通知 / 黑名单 / **过滤规则（JD）** |
| `/data` 数据 | KPI + 筛选条 + 分页表格（复用 `/api/boss/list` + `/api/boss/stats`） |
| `/appearance` 外观 | 主题、每页条数、表格密度（存 localStorage） |

删掉了上游的 ai-config / env-config / 三个平台页与旧的岗位分析页。
**平台列表为空时页面显示提示而不是报错** —— 这是"缺平台也能跑"在界面上的体现。

### 7. 其它增强（多为上游已有能力的落地或修复）

- **诊断**：`BossDiagnostics` 在关键失败点打印「步骤名 + URL + 关键选择器命中数」并落 `target/boss-diagnose.txt`，
  改版时能一眼看出是哪个选择器失效（上游是静默跳过）
- **同一家公司不重复投递**：可按 `delivery.skip_delivered_company` 开关（默认开）
- **停止反馈**：状态里新增 `stopping`，按钮变「正在停止…」，不再像卡死
- **多值字段兼容全角逗号**（`，`）、顿号、中文分号 —— 手打配置时很容易踩
- **管理页浏览器可选**：`manage_page.browser`（`msedge` / `default` / 可执行文件路径 / `none`）
- **`jd-rules.txt` 规则过滤**：`[reject|require|warn] 名称 阈值` 多列表，匹配文本 = 岗位名 + JD 正文 + showSkills；
  可在「配置 → 过滤规则」里**直接编辑**（原文进出、不吞注释，保存后回显解析出的规则组与语法告警），
  改完下一次点「开始投递」即生效，不需要重启
- **遗留数据清理**：启动时幂等删掉上游多平台时代留下的 9 张空表（`job51_*` / `liepin_*` / `zhilian_*`）
  与 `cookie` 表里 3 行遗留记录 —— 免得后来的人猜"哪个表还有用"
- **死代码清理**：`BossIndustry` 三件套（数据库里根本没有这张表，一调就 SQLException）、
  `/api/boss/execute`、`/api/boss/stream` 及配套的旧版 SSE 桥接（进度统一走 `/api/platforms/{id}/stream`）
- **API 形状**：新增 `/api/platforms`（列表）、`/api/platforms/{id}/{start,stop,status,stream}`、
  `/api/boss/jd-rules`（读写过滤规则）；`/api/boss/{start,stop,status,logout}` 保留为管理页兼容层
  （登录态与退出登录是 Boss 特有的，平台无关层表达不了）

---

## 是否跟随上游

**结论：结构上跟随，但已主动偏离 —— 同步时以"重新删掉上游新增的其他平台"为主要工作量。**

> 历史说明：本 fork 的 git 历史已清除本机路径等私有信息（重写了部分 commit hash）。
>
> 但注意：**本 fork 与 upstream/main 没有共同祖先**。实测本地 11 个提交、上游 660 个，
> `git merge-base main upstream/main` 无输出（退出码 1），`git merge upstream/main` 会直接报
> `fatal: refusing to merge unrelated histories` —— **同步上游不能靠 merge/rebase**。
> 本地最老的提交本来就没有父提交（快照式起头），并非这次清洗造成。可行做法见下面「建议的同步姿势」。

**跟随的一面**（为的是方便对照上游改动）
- 保留上游的目录结构、包名、类名（`BossService` / `BossConfig` / `BossController` / `PlaywrightManager` …）
- 改上游文件时尽量小：优先**新增文件**（`worker/platform/**`、`ConfigFileService`、新前端页面），
  少动上游原有文件
- 数据库表结构与上游保持一致，只**加列**（`city_filter_mode` / `city_exclude` / `skip_delivered_company`
  在启动时由 `initSchema()` 自动补）

**偏离的一面**（同步时的预期冲突）
- **上游新增的平台文件会被"复活"**：同步后需要重新删一遍（删除的文件上游仍在维护）
- **`PlaywrightManager`**：上游是四平台交织，本 fork 只留 Boss —— 这块几乎必然要手工取舍，
  建议以本 fork 为准，只挑上游在 Boss 分支上的修复
- **`Boss` → `BossPlatform`** 改名 + 老投递流程删除：上游对 `Boss.java` 的任何改动都要手工搬
- **前端整块重组**：上游的前端页面改动无法直接合并
- 上游的 `BossConfig` 字段删改（本 fork 移除了 `expectedSalary` 的消费方）

**建议的同步姿势**

```bash
# 一次性：把 origin 指到自己的 fork，上游加为 upstream
git remote set-url origin https://github.com/HuXiaoCAPS/get_jobs_boss.git
git remote add upstream https://github.com/loks666/get_jobs.git

# 每次同步上游（⚠️ 不能 merge —— 两边无共同祖先，属 unrelated histories）
git fetch upstream
git log --oneline --since=2026-09-01 upstream/main   # 看上游最近改了什么
git diff main upstream/main -- <文件路径>             # 单文件对照，手工搬需要的修复
git cherry-pick <上游提交SHA>                       # cherry-pick 不要求共同祖先
git checkout upstream/main -- <文件路径>            # 谨慎：会覆盖该文件的本地改动
# 然后按上面的"预期冲突"逐项处理，重点是：
#   1) 重新删掉 worker/{liepin,job51,zhilian} 与对应 Controller/Service/entity/mapper
#   2) PlaywrightManager 只保留 Boss 分支
#   3) 跑 compileJava + fake-delivery 验收（见下）
```

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
