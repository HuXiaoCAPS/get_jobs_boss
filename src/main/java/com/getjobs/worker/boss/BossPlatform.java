package com.getjobs.worker.boss;

import com.getjobs.application.service.BossService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.platform.CityFilter;
import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.JobPlatform;
import com.getjobs.worker.platform.model.ChatReply;
import com.getjobs.worker.platform.model.DeliveryPolicy;
import com.getjobs.worker.platform.model.JobCandidate;
import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.SearchQuery;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 *
 * <p><b>平台实现</b>：本类实现 {@link JobPlatform}，只负责"Boss 这个网站怎么操作"
 * （开列表页、读卡片、点详情、发招呼语、扫聊天页、读配置）。
 * 「一轮投递怎么跑」（关键词循环、过滤、AI、落库、限速）在
 * {@code DeliveryRunner} 里，与本类无关。
 *
 * <p>黑名单、去重、JD 规则、AI 生成、落库、限速都在平台无关的 {@code DeliveryRunner} 里，
 * 所以删掉这个包，程序依然能启动与运行，只是没有 Boss 这个平台可用。
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class BossPlatform implements JobPlatform {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final PlaywrightManager playwrightManager;
    private final BossDeliveryStore deliveryStore;

    /** 外部（任务壳）注入的停止信号；null 表示不检查 */
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService / playwrightManager / deliveryStore

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 其余准备（黑名单 / 已投递集合 / JD 规则）都是平台无关的，
        // 由 DeliveryRunner + BossDeliveryStore 负责，不再在这里加载。
    }

    /**
     * 只读地扫一遍聊天页，返回「公司名 → 最新一条消息」。
     *
     * <p>刻意<b>不带副作用</b>：拉黑、写快照、报告"谁回了我"这些副作用
     * 由平台无关的流程层 {@code DeliveryRunner} 统一处理。
     */
    private Map<String, String> readChatSessions() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        Map<String, String> current = new LinkedHashMap<>();
        boolean shouldBreak = false;
        while (!shouldBreak) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束聊天页扫描");
                break;
            }
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);
                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;
                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                break;
                            }
                            PlaywrightUtil.sleep(1);
                        }
                    }
                    if (companyName == null || message == null) {
                        continue;
                    }
                    companyName = companyName.replaceAll("\\.{3}", "").trim();
                    message = message.trim();
                    if (companyName.isEmpty()) {
                        continue;
                    }
                    current.put(companyName, message);
                } catch (Exception e) {
                    log.debug("读取会话项异常：{}", e.getMessage());
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.warn("聊天页滚动出错，停止扫描：{}", e.getMessage());
                break;
            }
        }
        return current;
    }


    /**
     * 滚到列表底部，把岗位卡片全部加载出来，返回卡片数量。
     *
     * <p>老路径 {@link #postJobByCity(String)} 与接口实现 {@link #search(SearchQuery)} 共用同一份。
     */
    private int loadAllCards(String keyword) {
        int lastCount = -1;
        int stableTries = 0;
        int staleHits = 0;
        // 上限 300 轮：原来写的是 5000，而且 stableTries 只触发强制触底、从不退出循环，
        // 一旦 footer 选择器匹配不到就会空转几千轮，每轮一次 evaluate + count，
        // 能把 playwright 线程占死几十分钟 —— 整个应用跟着卡住、投递任务也永远结束不了。
        boolean loadedAll = false;
        for (int i = 0; i < 300; i++) {
            // 停止检查：滚动加载过程中也要及时响应
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("【{}】停止指令已触发，结束岗位加载", keyword);
                break;
            }
            // 滚动加载期间 Boss 会频繁增删 frame，Playwright 派发这些事件时可能
            // 引用到已销毁的 frame（Object doesn't exist: frame@...），异常会顺着
            // 当时在飞的那个调用抛出来。这类异常和调用本身无关，跳过这一轮继续滚就行。
            try {
                // footer 可见不能立刻就当作"加载完了"：窗口最大化时首屏很短，
                // 第一轮 footer 就是可见的，会导致只拿到首屏 15 个岗位就退出
                // （实测同样的搜索条件，正常滚完是 300 个）。
                // 必须先滚一段、并且连着几轮没有新增岗位，footer 才算数。
                boolean footerVisible = false;
                Locator footer = page.locator("div#footer, #footer");
                if (footer.count() > 0 && footer.first().isVisible()) {
                    footerVisible = true;
                }
                if (footerVisible && stableTries >= 2) {
                    log.info("【{}】已滚动到底部且连续 {} 轮无新增，判定加载完毕", keyword, stableTries);
                    loadedAll = true;
                    break;
                }
                // 按视口高度的90%渐进滚动，触发懒加载
                page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");

                // 获取卡片数量变化，判断是否需要强制触底
                Locator cardsProbe = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                int currentCount = cardsProbe.count();
                if (currentCount == lastCount) {
                    stableTries++;
                } else {
                    stableTries = 0;
                }
                lastCount = currentCount;

                if (stableTries >= 3) { // 连续多次无新增，则强制触底一次
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    // 触底不再等待，继续检测 footer 出现
                }
                // 强制触底之后仍然连着好几轮没有新岗位，就认定加载完了。
                // 不能只靠 footer —— Boss 有些版式根本没有 #footer，只等它就是死循环。
                if (stableTries >= 8) {
                    log.info("【{}】连续 {} 轮没有新增岗位，判定已加载完毕", keyword, stableTries);
                    loadedAll = true;
                    break;
                }
            } catch (Exception e) {
                if (!isStaleObjectError(e)) {
                    throw e;
                }
                staleHits++;
                if (staleHits > 20) {
                    log.warn("【{}】滚动期间反复出现失效对象异常({}次)，停止继续加载", keyword, staleHits);
                    break;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        if (!loadedAll) {
            log.warn("【{}】滚动到达 300 轮上限仍未确认加载完毕，按当前已加载的岗位继续", keyword);
        }
        int loadedCount = countJobCards();
        log.info("【{}】岗位已全部加载，总数:{}", keyword, loadedCount);

        // 回到页面顶部
        page.evaluate("window.scrollTo(0, 0);");
        PlaywrightUtil.sleep(1);
        return loadedCount;
    }

    // ==================================================================
    // JobPlatform 实现：Boss 这个网站"怎么操作"
    //
    // 「一轮投递怎么跑」（关键词循环、过滤、AI、落库、限速）在 DeliveryRunner 里，
    // 与本类无关；这里只提供页面交互能力。
    // ==================================================================

    /** 平台标识（落库 / 接口 / 前端都用它） */
    public static final String PLATFORM_ID = "boss";

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "Boss直聘";
    }

    @Override
    public boolean isLoggedIn() {
        try {
            return playwrightManager.isLoggedIn(PLATFORM_ID);
        } catch (Exception e) {
            log.debug("读取登录态失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public void ensureSession() {
        // 浏览器可能已被关掉（手动关窗口 / 崩溃）：先确保可用，必要时重新拉起
        playwrightManager.ensureReady();
        this.page = playwrightManager.getBossPage();
        if (this.page == null) {
            throw new IllegalStateException("Boss 页面未初始化");
        }
        if (this.config == null) {
            this.config = bossService.loadBossConfig();
        }
        prepare(); // 黑名单 / 已投递集合 / JD 规则
    }

    @Override
    public void pauseMonitoring() {
        playwrightManager.pauseBossMonitoring();
    }

    @Override
    public void resumeMonitoring() {
        playwrightManager.resumeBossMonitoring();
    }

    @Override
    public void runTask(Runnable task) {
        // Boss 的所有页面操作都必须跑在 Playwright 专用线程上
        playwrightManager.runOnPlaywright(task);
    }

    @Override
    public DeliveryStore store() {
        return deliveryStore;
    }

    @Override
    public boolean openInBrowser(String url) {
        // 只允许打开 zhipin.com 自己的页面：这个动作是"拿已登录的浏览器打开一个地址"，
        // 不校验域名就等于给网页端开了一个可以驱动登录态浏览器访问任意站点的跳板。
        if (!isBossUrl(url)) {
            log.warn("拒绝在自动化浏览器中打开非 Boss 地址：{}", url);
            return false;
        }
        return playwrightManager.openNewPage(url);
    }

    /** 地址是否属于 Boss（host 为 zhipin.com 或其子域） */
    private static boolean isBossUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = new URL(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            String h = host.toLowerCase(java.util.Locale.ROOT);
            return h.equals("zhipin.com") || h.endsWith(".zhipin.com");
        } catch (Exception e) {
            // URL 都解析不出来，自然谈不上"属于 Boss"
            return false;
        }
    }

    // 注：投递策略（间隔 / AI 开关 / HR 阈值 / 兜底招呼语…）不在这里 ——
    // 它不是平台知识，由全局的 application.service.DeliveryPolicyService 统一提供。

    /**
     * 把配置翻译成"要执行的搜索"。
     *
     * <p>两种城市模式：轮换（每个城市各搜一轮，默认）与过滤（只搜一次全国，城市交给
     * {@link #matchesCity(JobDetail)} 逐岗位筛）—— 因为 Boss 的搜索一次只认一个城市码。
     */
    @Override
    public List<SearchQuery> buildQueries() {
        BossConfig cfg = (config != null) ? config : bossService.loadBossConfig();
        List<String> keywords = cfg.getKeywords() == null ? List.of() : cfg.getKeywords();
        List<String> cityCodes = cfg.getCityCode() == null ? List.of() : cfg.getCityCode();
        boolean filterMode = Boolean.TRUE.equals(cfg.getCityFilterMode());

        List<SearchQuery> queries = new ArrayList<>();
        if (filterMode || cityCodes.isEmpty()) {
            for (String keyword : keywords) {
                queries.add(new SearchQuery(keyword, null,
                        filterMode ? "全国(过滤)" : "不限",
                        filterMode ? cfg.getCityNames() : null));
            }
            return queries;
        }
        for (String cityCode : cityCodes) {
            for (String keyword : keywords) {
                queries.add(new SearchQuery(keyword, cityCode, cityCode, null));
            }
        }
        return queries;
    }

    @Override
    public List<JobCandidate> search(SearchQuery query) {
        String searchUrl = getSearchUrl(query.getCityCode());
        String url = searchUrl + (searchUrl.contains("?") ? "&" : "?")
                + "query=" + URLEncoder.encode(query.getKeyword(), StandardCharsets.UTF_8);
        openJobListWithRetry(query.getKeyword(), url, query.getCityCode());

        int loadedCount = loadAllCards(query.getKeyword());

        List<JobCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < loadedCount; i++) {
            JobCandidate candidate = new JobCandidate();
            candidate.setPlatform(PLATFORM_ID);
            candidate.setIndex(i);
            candidates.add(candidate);
        }
        return candidates;
    }

    @Override
    public JobDetail openDetail(JobCandidate candidate) {
        return openDetailAt(candidate == null ? 0 : candidate.getIndex());
    }

    @Override
    public boolean matchesCity(JobDetail detail) {
        BossConfig cfg = (config != null) ? config : bossService.loadBossConfig();
        String city = detail.getCity();

        // 1) 排除优先：排除表里的城市/省份命中就跳过（轮换与过滤两种模式下都生效）。
        //    填省份（如「广东」）会由 CityFilter 展开成该省城市，省得逐个城市枚举。
        java.util.Set<String> excludes = CityFilter.expand(cfg.getCityExclude());
        if (CityFilter.isExcluded(city, excludes)) {
            return false;
        }

        // 2) 轮换模式：城市是靠搜索条件定的，这里不用再筛
        if (!Boolean.TRUE.equals(cfg.getCityFilterMode())) {
            return true;
        }

        // 3) 过滤模式：只投「允许城市」名单里的（空 / 含「不限」= 都行）
        List<String> wanted = cfg.getCityNames();
        if (wanted == null || wanted.isEmpty() || wanted.contains("不限")) {
            return true;
        }
        if (city == null || city.isEmpty()) {
            return true; // 拿不到岗位城市就别拦（宁可多投也别漏投）
        }
        for (String want : wanted) {
            if (want != null && !want.isEmpty() && !"不限".equals(want) && city.contains(want)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean sendGreeting(JobDetail detail, String message) {
        if (detail == null || detail.getDetailUrl() == null || detail.getDetailUrl().isEmpty()) {
            log.warn("没有详情链接，无法发送招呼语 | {}",
                    detail == null ? "(无详情)" : detail.describe());
            return false;
        }
        String text = isValidString(message)
                ? message
                : (config != null ? config.getSayHi() : null);
        return submitGreeting(page, detail.getDetailUrl(), detail.getJobName(), text);
    }

    /** Boss 支持发图片简历（需要把 resume.jpg 放到 src/main/resources/） */
    @Override
    public boolean supportsImageResume() {
        return true;
    }

    @Override
    public List<ChatReply> scanReplies() {
        Map<String, String> sessions = readChatSessions();
        List<ChatReply> replies = new ArrayList<>();
        for (Map.Entry<String, String> entry : sessions.entrySet()) {
            replies.add(new ChatReply(entry.getKey(), entry.getValue()));
        }
        return replies;
    }

    /**
     * 点开列表里第 index 张卡片，等岗位详情接口返回并解析成 {@link JobDetail}。
     *
     * <p>TODO 批 2：老卡片循环里还有一份等价的内联实现（见 {@link #postJobByCity(String)}），
     * 等老流程删掉后两边合并成这一份。
     */
    private JobDetail openDetailAt(int index) {
        JobDetail detail = new JobDetail();
        detail.setPlatform(PLATFORM_ID);
        detail.setIndex(index);
        try {
            String cardSelector = "//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]";
            Locator cards = page.locator(cardSelector);
            int total = cards.count();

            Response detailResp;
            if (index == 0 && total > 1) {
                // 第一张卡片默认是展开的、不会触发请求：先点第二张再点回第一张，并在点回时监听响应
                final Locator secondCard = cards.nth(1);
                secondCard.click();
                PlaywrightUtil.sleep(1);
                final Locator firstCard = cards.nth(0);
                detailResp = page.waitForResponse(BossPlatform::isDetailResponse, firstCard::click);
            } else {
                final Locator cardToClick = cards.nth(index);
                detailResp = page.waitForResponse(BossPlatform::isDetailResponse, cardToClick::click);
            }
            PlaywrightUtil.sleep(1);

            if (detailResp == null) {
                BossDiagnostics.report(page, "打开岗位详情", "detail.json 未抓到（第 " + (index + 1) + " 个卡片）");
                return detail; // parsed = false，流程层会跳过
            }

            String body = detailResp.text();
            appendRawJson(body);

            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
            JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
            JSONObject bossInfo = zpData != null ? zpData.optJSONObject("bossInfo") : null;
            if (jobInfo == null) {
                BossDiagnostics.report(page, "打开岗位详情", "detail.json 里没有 jobInfo");
                return detail;
            }

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            detail.setExternalId(encryptId);
            detail.setRecruiterId(encryptUserId);
            detail.setJobName(jobInfo.optString("jobName", null));
            detail.setSalary(jobInfo.optString("salaryDesc", null));
            detail.setCity(jobInfo.optString("locationName", null));
            detail.setExperience(jobInfo.optString("experienceName", null));
            detail.setDegree(jobInfo.optString("degreeName", null));
            detail.setJdText(buildRulesText(jobInfo));
            detail.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            detail.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            detail.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            detail.setHrActiveText(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);
            if (encryptId != null && !encryptId.isEmpty()) {
                detail.setDetailUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }
            detail.setRaw(body);
            detail.setParsed(true);
            return detail;
        } catch (Exception e) {
            log.warn("打开岗位详情失败（第 {} 个）：{}", index + 1, e.getMessage());
            return detail;
        }
    }

    /** 岗位详情接口的响应判定（waitForResponse 的谓词） */
    private static boolean isDetailResponse(Response response) {
        try {
            return response.url() != null
                    && response.url().contains("/wapi/zpgeek/job/detail.json")
                    && "GET".equalsIgnoreCase(response.request().method());
        } catch (Throwable ignore) {
            return false;
        }
    }


    // 安全获取单个文本内容

    // 安全获取多个文本内容

    // Boss姓名+活跃状态拆分

    // Boss公司+职位拆分

    // 匹配命中词条（用于日志输出过滤原因）

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", normalizeCityCode(cityCode)));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    /**
     * 把字典里的城市码映射成 boss 真正认的码。
     * <p>
     * 字典里「不限」的 code 是 0，但 boss 的「全国」码是 <b>100010000</b>
     * （boss_option 是项目自带的字典，"不限"那条的 0 并不是 boss 的编码）。
     * 直接传 city=0 会被 boss 忽略，它会沿用浏览器上次记忆的城市 ——
     * 实测表现就是「不管配置里怎么改，投递始终锁在深圳」。
     */
    private static String normalizeCityCode(String cityCode) {
        if (cityCode == null || cityCode.trim().isEmpty() || "0".equals(cityCode.trim())) {
            return "100010000";
        }
        return cityCode;
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            input.click();
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                searchBtn.click();
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /**
     * 导航并重试一次。
     * <p>
     * Boss 页面自己会做客户端跳转，撞上时 Playwright 报 net::ERR_ABORTED；
     * 这类失败重试一次基本就过了，不该让整个投递任务因此中止。
     */
    private void navigateTo(String url) {
        // Boss 的 SPA 在网络不佳或被限流时，15 秒常常不够，之前实测连着两次都超时
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        try {
            page.navigate(url, options);
        } catch (Exception first) {
            // 导航期间页面自己又跳了一次时，Playwright 会抛 "Object doesn't exist: request@/frame@"，
            // 但页面其实已经到位了。先看落地 URL，别白白重试一遍。
            if (landedOn(url)) {
                log.debug("导航报了失效对象异常但页面已到位，忽略：{}", first.getMessage());
                settleAfterNavigation();
                return;
            }
            log.warn("导航失败，1秒后重试一次：{} | {}", url, first.getMessage());
            PlaywrightUtil.sleep(1);
            try {
                page.navigate(url, options);
            } catch (Exception second) {
                if (!landedOn(url)) {
                    throw second;
                }
                log.debug("重试同样报失效对象异常但页面已到位，忽略：{}", second.getMessage());
            }
        }
        settleAfterNavigation();
    }

    /**
     * 判断页面是否已经落在目标地址上（只比较路径，查询参数会被 Boss 改写）。
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            String targetPath = targetUrl.split("\\?")[0];
            return current.startsWith(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        PlaywrightUtil.sleep(2);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * 进入岗位列表并等列表渲染出来，整段带重试。
     * Boss 首页/列表页在登录态下会连着跳好几次，一次失败很正常。
     */
    private void openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        // 只重试一次：每次都要走导航(最长45秒×2) + 等列表(15秒×3)，
        // 试三轮的话一个关键词失败要耗掉好几分钟，界面上看着就是"卡住不动"
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                openJobList(keyword, targetUrl, cityCode);
                waitForJobList();
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("【{}】进入岗位列表失败（第{}/2次）：{}", keyword, attempt,
                        e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0]);
                if (attempt < 2) {
                    PlaywrightUtil.sleep(3);
                }
            }
        }
        throw last;
    }

    /**
     * 统计当前列表里的岗位卡片数量，对失效对象异常做重试。
     */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return page.locator(JOB_LIST_SELECTOR).count();
            } catch (Exception e) {
                if (!isStaleObjectError(e) || attempt == 3) {
                    if (isStaleObjectError(e)) {
                        log.warn("统计岗位数量始终失败，按 0 处理：{}", e.getMessage());
                        return 0;
                    }
                    throw e;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        return 0;
    }

    /**
     * 等待岗位列表容器出现，带重试。
     * 页面在这期间可能还在跳转，一次失败不代表真的没有列表。
     */
    private void waitForJobList() {
        // Boss 改版频繁，推荐页和搜索结果页的容器类名不一样。
        // 用逗号把候选选择器拼成一个，让 Playwright 一次性等"任意一个先出现"，
        // 不要逐个 8 秒串行试 —— 那样一轮就要 48 秒，页面正常时也慢得像卡死。
        String containers = String.join(", ",
                "ul.rec-job-list",
                "ul.job-list-box",
                ".job-list-box",
                ".search-job-result",
                "li.job-card-box",
                "li.job-card-wrapper");

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.waitForSelector(containers,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                log.info("岗位列表容器已出现（第{}次尝试）", attempt);
                return;
            } catch (Exception e) {
                log.warn("等待岗位列表失败（第{}/3次），当前页面: {}", attempt, safeUrl());
                settleAfterNavigation();
            }
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现，当前页面: " + safeUrl());
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控/验证页，日志里能直接看出是哪一步触发的。
     */
    private void warnIfSecurityCheck(String keyword) {
        String url = page.url();
        if (url == null) {
            return;
        }
        if (isSecurityVerifyUrl(url)) {
            log.warn("【{}】进入岗位列表时被跳转到验证页：{}", keyword, url);
            waitForSliderVerify(page);
        }
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    /**
     * 纯发送：打开详情页 → 立即沟通 → 输入招呼语 → 发送 →（可选）图片简历 → 关页。
     *
     * <p><b>不含</b>任何状态更新 / 结果收集 —— 那些属于"流程"：老路径里由
     * {@link #resumeSubmission(String, Job, String)} 做，新路径里由 {@code DeliveryRunner} 做。
     *
     * @return true = 消息确实发出去了
     */
    private boolean submitGreeting(Page contextPage, String detailUrl, String jobName, String message) {
        Page detailPage = null;
        try {
            Page opened = contextPage.context().newPage();
            detailPage = opened;
            opened.navigate(detailUrl);
            PlaywrightUtil.sleep(1);

            // 查找"立即沟通"按钮
            Locator chatBtn = opened.locator("a.btn-startchat, a.op-btn-chat");
            boolean foundChatBtn = false;
            for (int i = 0; i < 5; i++) {
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    log.info("停止指令已触发，结束查找聊天按钮 | 岗位：{}", jobName);
                    return false;
                }
                if (chatBtn.count() > 0 && (chatBtn.first().textContent().contains("立即沟通"))) {
                    foundChatBtn = true;
                    break;
                }
                PlaywrightUtil.sleep(1);
            }
            if (!foundChatBtn) {
                BossDiagnostics.report(opened, "查找立即沟通按钮", "未找到 a.btn-startchat / a.op-btn-chat");
                log.warn("未找到立即沟通按钮，跳过岗位: {}", jobName);
                return false;
            }
            chatBtn.first().click();
            PlaywrightUtil.sleep(1);

            // 等待聊天输入框
            Locator inputLocator = opened.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
            boolean inputReady = false;
            for (int i = 0; i < 10; i++) {
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    log.info("停止指令已触发，结束等待聊天输入框 | 岗位：{}", jobName);
                    return false;
                }
                if (inputLocator.count() > 0 && inputLocator.first().isVisible()) {
                    inputReady = true;
                    break;
                }
                PlaywrightUtil.sleep(1);
            }
            if (!inputReady) {
                BossDiagnostics.report(opened, "等待聊天输入框", "输入框未出现或不可见");
                log.warn("聊天输入框未出现，跳过: {}", jobName);
                return false;
            }

            // 输入打招呼语
            Locator input = inputLocator.first();
            input.click();
            Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
            if (tagObj instanceof String && ((String) tagObj).equals("textarea")) {
                input.fill(message);
            } else {
                // 对 contenteditable 节点写入文本并派发 input 事件
                input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
            }

            // 点击发送按钮（div.send-message 或 button.btn-send）
            Locator sendText = opened.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
            boolean sendSuccess = false;
            if (sendText.count() > 0) {
                sendText.first().click();
                PlaywrightUtil.sleep(1);
                sendSuccess = true;
                try {
                    opened.locator("i.icon-close").first().click();
                } catch (Exception e) {
                    log.error("发送文本小窗口关闭失败！");
                }
            } else {
                BossDiagnostics.report(opened, "发送招呼语", "未找到发送按钮 div.send-message / button.btn-send");
                log.warn("未找到发送按钮，自动跳过！岗位：{}", jobName);
            }

            // 发送图片简历（只在招呼语确实发出去之后才发，避免"话没发出去、简历先过去了"）
            boolean imgResume = false;
            if (sendSuccess && Boolean.TRUE.equals(config.getSendImgResume())) {
                imgResume = sendImageResume(opened);
            }

            log.info("投递{} | 岗位：{} | 招呼语：{} | 图片简历：{}",
                    sendSuccess ? "完成" : "失败", jobName, message, imgResume ? "已发送" : "未发送");
            return sendSuccess;
        } catch (Exception e) {
            log.warn("发送招呼语异常 | 岗位：{} | {}", jobName, e.getMessage());
            return false;
        } finally {
            if (detailPage != null) {
                try {
                    detailPage.close();
                } catch (Exception ignore) {
                }
            }
            PlaywrightUtil.sleep(1);
        }
    }


    


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }


    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = BossPlatform.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    boolean chooserHandled = false;
                    try {
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            imgContainer.first().click();
                        });
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException ignore) {
                        // 未弹出系统文件选择器，继续常规流程
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            imageInput.setInputFiles(imagePath);
            PlaywrightUtil.sleep(1);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = BossPlatform.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = BossPlatform.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }



    /**
     * 判断是不是 Boss 的安全验证页。
     * <p>
     * 实测密集遍历后会被弹到 /web/passport/zp/verify.html（极验滑块，页面上是
     * div.geetest_success_correct 那一套），而原来的判断只认 verify-slider，
     * 导致真正遇到验证时程序把关键词当失败跳过，人也不知道要去过验证。
     */
    private static boolean isSecurityVerifyUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("/web/user/safe/verify-slider")
                || url.contains("/web/passport/zp/verify")
                || url.contains("/web/passport/zp/security")
                || url.contains("security-check");
    }

    private void waitForSliderVerify(Page page) {
        // 最多等待5分钟（防呆，防止死循环）
        long start = System.currentTimeMillis();
        while (true) {
            String url = page.url();
            if (isSecurityVerifyUrl(url)) {
                System.out.println("\n【滑块验证】请手动完成Boss直聘滑块验证，通过后在控制台回车继续…");
                try {
                    System.in.read();
                } catch (Exception e) {
                    log.error("等待滑块验证输入异常: {}", e.getMessage());
                }
                PlaywrightUtil.sleep(1);
                // 验证通过后页面url会变，循环再检测一次
                continue;
            }
            if ((System.currentTimeMillis() - start) > 5 * 60 * 1000) {
                throw new RuntimeException("滑块验证超时！");
            }
            break;
        }
    }


    // JD 规则过滤（reject / require / warn）已迁移到独立的 JdRuleFilter 类，
    // 规则文件为项目根目录的 jd-rules.txt，在 prepare() 里 reload()。

    /** 明确拒绝的强特征：只有命中这些词才算 HR 拒绝（务必保持「宁漏不误杀」） */
    private static final String[] REJECT_REPLY_PHRASES = {
            "很遗憾", "不合适", "不符合", "不匹配", "祝您找到", "祝您早日",
            "已招满", "岗位已关闭", "无法安排", "暂时不考虑"
    };


    /**
     * 构造用于规则匹配的文本：<b>岗位名</b> + 岗位描述正文 + showSkills 标签。
     * <p>
     * 岗位名必须拼进来——有些关键信息只写在标题里（「研发助理」「爬虫」「兼职」），
     * 只在正文里找是找不到的。实测踩过：在 reject 里写了「研发助理」却照样投递，
     * 就是因为当时没把标题纳入匹配。
     * <p>
     * showSkills 大多是「包住 / 周末双休 / 接受无数据开发经验」这类福利与门槛标签，
     * 很少是技术栈；但实测这些标签不会出现在正文里，属于纯增量信息，
     * 拼进去只会让规则更容易命中（少漏岗位），不会凭空多挡岗位。
     */
    private static String buildRulesText(org.json.JSONObject jobInfo) {
        if (jobInfo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String jobName = jobInfo.optString("jobName", "");
        if (!jobName.isBlank()) {
            sb.append(jobName);
        }
        String jd = jobInfo.optString("postDescription", "");
        if (!jd.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(jd);
        }
        org.json.JSONArray skills = jobInfo.optJSONArray("showSkills");
        if (skills != null) {
            for (int i = 0; i < skills.length(); i++) {
                String skill = skills.optString(i, "");
                if (skill != null && !skill.isBlank()) {
                    sb.append(' ').append(skill);
                }
            }
        }
        return sb.toString();
    }


}
