package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Playwright管理器
 * Spring管理的单例Bean，在应用启动时自动初始化Playwright实例
 * 本副本只服务 Boss 直聘平台，页面运行在同一个浏览器窗口的标签页中
 */
@Slf4j
@Getter
@Component
@Lazy
public class PlaywrightManager {

    // Playwright实例
    private Playwright playwright;

    // 用持久化上下文启动后没有独立的 Browser 对象，context 本身就代表这个浏览器

    // 浏览器是否已经关闭（用户手动关窗口、Chrome 崩溃等）。
    // 不检测的话：字段还都非空，isInitialized() 依旧返回 true，
    // 但任何页面操作都会抛 TargetClosedError，投递任务随即卡死、按钮再点毫无反应。
    private volatile boolean browserClosed = false;

    // 浏览器上下文
    private BrowserContext context;

    // Boss直聘页面
    private Page bossPage;

    // 登录状态追踪（平台 -> 是否已登录）
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    // 登录状态监听器
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    // 控制是否暂停对bossPage的后台监控，避免与任务执行并发访问同一页面
    private volatile boolean bossMonitoringPaused = false;

    // 默认超时时间（毫秒）
  private static final int DEFAULT_TIMEOUT = 30000;

    // Playwright调试端口
    private static final int CDP_PORT = 7866;

    // 登录状态探针的超时（毫秒）。探针失败只代表"没看到"，绝不能拖住主流程
    private static final double LOCATOR_PROBE_TIMEOUT = 5000;

    // waitForLoadState 的超时（毫秒）。
    // Boss 是 SPA + WebSocket 长连接（cookie 里就有 ws.zhipin.com），
    // NETWORKIDLE 可能永远等不到。不给超时会直接把线程挂死 —— 而且"卡住"不是异常，
    // 外面包多少层 try/catch 都没用，实测把初始化卡了两分钟以上还在等。
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    // Boss 登录态 Cookie：出现任意一个即视为已登录
    private static final Set<String> BOSS_LOGIN_COOKIES = Set.of("bst", "wt2", "zp_at", "geek_zp_token");

    // 持久化上下文的用户数据目录，登录态直接落在这里
    private static final Path USER_DATA_DIR = Paths.get(System.getProperty("user.dir"), "browser-data");

    // Boss URL常量
    private static final String BOSS_URL = "https://www.zhipin.com";
    // 初始化时打开的落地页。
    // 不要用首页：实测 navigate("https://www.zhipin.com") 要 60 秒才回来，页面标签一直转圈，
    // 期间任何 evaluate / locator 调用都会无限期阻塞（这两个 API 都没有默认超时），
    // 表现就是启动奇慢、投递卡死、管理页面点按钮没反应。
    // 岗位列表页在同一个浏览器里秒开，用它作为落地页。
    private static final String BOSS_ENTRY_URL = "https://www.zhipin.com/web/geek/jobs";
    private static final String BOSS_DOMAIN = "zhipin.com";

    @Autowired
    private CookieService cookieService;

    // ------------------------------------------------------------------
    // Playwright 单线程调度
    //
    // Playwright Java 不是线程安全的：Playwright 对象以及由它创建的所有对象，
    // 都必须在创建它的那个线程上调用。这个项目原本同时从 main、ForkJoinPool、
    // scheduling-1、http-nio 多个线程操作同一个 BrowserContext，会随机爆出
    // "Object doesn't exist: request@/response@"，以及导航被打断的 net::ERR_ABORTED
    // （投递任务因此整个挂掉）。
    //
    // 这里把所有 Playwright 调用收敛到一个专用线程上串行执行。
    // ------------------------------------------------------------------
    private static final String PW_THREAD_NAME = "playwright-thread";

    private final ExecutorService playwrightExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, PW_THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    // 排队中 + 执行中的任务数，给定时监控做"忙就跳过"判断，避免任务堆积
    private final AtomicInteger playwrightQueueDepth = new AtomicInteger();

    /** 当前是否已经在 Playwright 线程上（嵌套调用要就地执行，否则自己等自己会死锁） */
    private boolean onPlaywrightThread() {
        return PW_THREAD_NAME.equals(Thread.currentThread().getName());
    }

    /**
     * 在 Playwright 专用线程上执行并等待结果。已经在该线程上时就地执行。
     */
    public <T> T callOnPlaywright(Callable<T> task) {
        if (onPlaywrightThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw toRuntime(e);
            }
        }
        playwrightQueueDepth.incrementAndGet();
        try {
            return playwrightExecutor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    playwrightQueueDepth.decrementAndGet();
                }
            }).get();
        } catch (ExecutionException e) {
            throw toRuntime(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待Playwright任务被中断", e);
        }
    }

    /**
     * 在 Playwright 专用线程上执行并等待完成。
     */
    public void runOnPlaywright(Runnable task) {
        callOnPlaywright(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 给定时监控用：Playwright 线程正忙（比如正在跑投递）时直接跳过这一轮，
     * 不排队、不阻塞调度线程。
     *
     * @return 是否真的提交了任务
     */
    public boolean tryRunOnPlaywright(Runnable task) {
        if (playwrightQueueDepth.get() > 0) {
            return false;
        }
        playwrightQueueDepth.incrementAndGet();
        playwrightExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.debug("Playwright后台任务异常: {}", e.getMessage());
            } finally {
                playwrightQueueDepth.decrementAndGet();
            }
        });
        return true;
    }

    /**
     * 把持久化 profile 里的"上次崩溃退出"标记改回正常。
     * <p>
     * 应用被强制停止（IDEA 的停止按钮、kill、崩溃）后，Chrome 下次启动会弹
     * "要恢复页面吗？"模态框。这个框会挂起所有 CDP 页面操作 —— 表现为 navigate
     * 永远不返回而且连超时都不触发，整个初始化线程焊死。
     * 除了命令行标志，这里再把 Preferences 里的退出状态直接改掉。
     */
    private void sanitizeProfileExitState() {
        Path preferences = USER_DATA_DIR.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(preferences)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(preferences.toFile());
            if (!(root instanceof ObjectNode rootNode)) {
                return;
            }
            JsonNode profile = rootNode.get("profile");
            if (!(profile instanceof ObjectNode profileNode)) {
                return;
            }
            String exitType = profileNode.path("exit_type").asText("");
            boolean exitedCleanly = profileNode.path("exited_cleanly").asBoolean(true);
            if ("Normal".equals(exitType) && exitedCleanly) {
                return;
            }
            profileNode.put("exit_type", "Normal");
            profileNode.put("exited_cleanly", true);
            mapper.writeValue(preferences.toFile(), rootNode);
            log.info("已清除浏览器 profile 的崩溃退出标记（原 exit_type={}），避免弹出\"要恢复页面吗？\"", exitType);
        } catch (Exception e) {
            log.warn("清理 profile 退出状态失败（不影响启动）: {}", e.getMessage());
        }
    }

    /** Playwright 线程上是否有前台任务在跑（投递、初始化等） */
    public boolean isPlaywrightBusy() {
        return playwrightQueueDepth.get() > 0;
    }

    private static RuntimeException toRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable);
    }

    /**
     * 初始化Playwright实例（延迟初始化）。
     * Playwright 对象必须在专用线程上创建，否则后续所有调用都得跨线程。
     */
    public void init() {
        if (isInitialized()) {
            return;
        }
        runOnPlaywright(this::initInternal);
    }

    private void initInternal() {
        if (isInitialized()) {
            return;
        }
        log.info("========================================");
        log.info("  初始化浏览器自动化引擎");
        log.info("========================================");

        try {
            // 启动Playwright，driver 指向 patchright（见 build.gradle.kts 的 installPatchrightDriver）
            playwright = createPlaywright();
            log.info("✓ Playwright引擎已启动 (driver: {})", describeDriver());

            // 清掉上次的"崩溃退出"标记，双保险（命令行标志偶尔不生效）
            sanitizeProfileExitState();

            // 用持久化上下文启动：Patchright 官方明确要求用 launchPersistentContext，
            // 而不是 launch() + newContext()。登录态直接落盘到 USER_DATA_DIR。
            context = playwright.chromium().launchPersistentContext(USER_DATA_DIR,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setChannel(resolveBrowserChannel())   // 复用系统自带浏览器（默认 Edge），不下载 Chromium 内核
                            .setHeadless(false)     // 无头必被检测
                            .setSlowMo(50)          // 放慢操作速度，便于调试
                            .setViewportSize(null)  // 不锁视口，用窗口实际大小
                            .setChromiumSandbox(true) // 不加 --no-sandbox：它本身就是自动化特征
                            .setArgs(List.of(
                                    "--start-maximized",
                                    // Patchright 会加 --disable-blink-features=AutomationControlled，
                                    // Chrome 因此弹"不受支持的命令行标记"黄条，用 --test-type 抑制
                                    "--test-type",
                                    // 上次非正常退出（IDEA 停止按钮、崩溃、kill）后，Chrome 会弹
                                    // "要恢复页面吗？"模态框。那个框会挂起所有 CDP 页面操作，
                                    // 表现为 navigate 永远不返回、连超时都不触发，整个初始化焊死。
                                    "--disable-session-crashed-bubble",
                                    "--hide-crash-restore-bubble",
                                    "--no-first-run",
                                    "--no-default-browser-check",
                                    // 强制直连，不走系统代理（代理出口 IP 更容易被风控标记）
                                    "--no-proxy-server",
                                    "--proxy-bypass-list=*",
                                    // 把 navigator.webdriver 压成 false。
                                    // patchright 的 driver 会自动加这个标志，但它的超时机制是坏的
                                    // （navigate/isVisible/waitForLoadState 的 timeout 全部失效，
                                    // 会把线程无限期挂死）；用官方 driver 就得自己加。
                                    "--disable-blink-features=AutomationControlled"
                            )));
            // 刻意不设置 UserAgent：伪造 UA 只改字符串，改不了 navigator.platform 和
            // navigator.userAgentData，反而制造出"UA 说 Mac、platform 说 Win32"这种致命矛盾。
            browserClosed = false;
            // 浏览器一旦关闭（手动关窗口 / Chrome 崩溃）立刻置位，
            // 让 isInitialized() 变 false，后续操作走重新初始化而不是一路 TargetClosedError
            context.onClose(closed -> {
                browserClosed = true;
                log.warn("检测到浏览器已关闭，下次操作会自动重新初始化");
            });
            log.info("✓ Chrome已启动（持久化上下文: {}）", USER_DATA_DIR);
            log.info("✓ BrowserContext已创建");

            // 顺序创建Page（避免并发创建Page导致的竞态条件）
            log.info("开始创建Boss平台的Page...");

            // 持久化上下文自带的那个启动标签页不能复用！
            // 复用它会出现：标签页永远停在加载中（左上角一直转圈），而 page.evaluate /
            // locator 这些调用没有默认超时，于是无限期阻塞 —— 实测卡 160 秒还不返回，
            // 表现就是初始化奇慢、投递任务卡死、整个应用发卡。
            // 同一个浏览器里新开的标签页则完全正常，所以这里一律新建，最后把启动页关掉。
            List<Page> startupPages = new ArrayList<>(context.pages());

            bossPage = context.newPage();
            bossPage.setDefaultTimeout(DEFAULT_TIMEOUT);
            log.info("✓ Boss Page已创建");

            // 业务页建好了，把持久化上下文自带的启动空白页关掉
            for (Page startup : startupPages) {
                try {
                    startup.close();
                } catch (Exception e) {
                    log.debug("关闭启动空白页失败（忽略）: {}", e.getMessage());
                }
            }
            if (!startupPages.isEmpty()) {
                log.info("✓ 已关闭 {} 个启动空白页", startupPages.size());
            }

            log.info("开始初始化 Boss 平台...");
            setupBossPlatform();

            log.info("✓ 浏览器自动化引擎初始化完成");
            log.info("========================================");
        } catch (Exception e) {
            log.error("✗ 浏览器自动化引擎初始化失败", e);
            throw new RuntimeException("Playwright初始化失败", e);
        }
    }

    /**
     * 创建 Playwright 实例，并把 driver 指向 patchright。
     * <p>
     * 不能只靠 gradle bootRun 传 -Dplaywright.cli.dir —— 从 IDEA 直接跑 main() 时不走那套配置，
     * 会静默退回官方 driver-bundle，反检测全部失效。所以这里自己找一遍，
     * 保证不管用什么方式启动，跑的都是 patchright。
     */
    private static String resolveBrowserChannel() {
        String channel = System.getenv("BROWSER_CHANNEL");
        if (channel != null && !channel.isBlank()) {
            return channel.trim();
        }
        // 本机没装 Chrome 时复用 Windows 自带的 Edge（同为 Chromium 内核，Playwright 原生支持），
        // 避免 Playwright 去下载一整套 Chromium。需要换回真 Chrome 时设 BROWSER_CHANNEL=chrome
        return "msedge";
    }

    private Playwright createPlaywright() {
        if (System.getProperty("playwright.cli.dir") == null) {
            Path driverDir = locatePatchrightDriver();
            if (driverDir != null) {
                System.setProperty("playwright.cli.dir", driverDir.toString());
            } else {
                log.warn("未找到 patchright driver，将退回官方 driver-bundle（反检测能力大幅下降）。"
                        + "请先执行: gradlew installPatchrightDriver");
            }
        }

        Map<String, String> env = new HashMap<>();
        // patchright driver 目录里没有 node，得用本机的
        if (System.getProperty("playwright.cli.dir") != null && System.getenv("PLAYWRIGHT_NODEJS_PATH") == null) {
            String nodePath = locateNodeExecutable();
            if (nodePath != null) {
                env.put("PLAYWRIGHT_NODEJS_PATH", nodePath);
                log.info("使用本机 node: {}", nodePath);
            } else {
                log.warn("PATH 里没找到 node，patchright driver 可能起不来，请先安装 Node.js");
            }
        }

        return env.isEmpty()
                ? Playwright.create()
                : Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    /**
     * 找 patchright driver 目录，判定标准是里面有 package/cli.js。
     */
    private Path locatePatchrightDriver() {
        Path projectDir = Paths.get(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                projectDir.resolve("build").resolve("patchright-driver"),
                projectDir.resolve("patchright-driver"),
                // 从子目录启动时往上找一级
                projectDir.getParent() == null ? projectDir
                        : projectDir.getParent().resolve("build").resolve("patchright-driver"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("package").resolve("cli.js"))) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 从 PATH 里找 node 可执行文件。
     */
    private String locateNodeExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        String exe = windows ? "node.exe" : "node";
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry.trim()).resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignore) {
                // PATH 里可能有非法路径，跳过
            }
        }
        return null;
    }

    /**
     * driver 来源说明，仅用于启动日志，方便确认到底跑的是不是 patchright。
     */
    private String describeDriver() {
        String cliDir = System.getProperty("playwright.cli.dir");
        if (cliDir == null || cliDir.isBlank()) {
            return "官方 driver-bundle（没找到 patchright，反检测能力下降）";
        }
        return "patchright @ " + cliDir;
    }

    /**
     * 设置Boss直聘平台（加载Cookie、导航、监控）
     */
    private void setupBossPlatform() {
        log.info("开始初始化Boss直聘平台...");
        // 尝试从数据库加载Boss平台Cookie到上下文
        try {
            CookieEntity cookieEntity = cookieService.getCookieByPlatform("boss");
            if (cookieEntity != null && cookieEntity.getCookieValue() != null && !cookieEntity.getCookieValue().isBlank()) {
                String cookieStr = cookieEntity.getCookieValue();
                List<Cookie> cookies = filterCookiesByDomain(parseCookiesFromString(cookieStr), BOSS_DOMAIN);

                if (!cookies.isEmpty()) {
                    context.addCookies(cookies);
                    log.info("已从数据库加载Boss Cookie并注入浏览器上下文，共 {} 条", cookies.size());
                } else {
                    log.warn("解析Cookie失败，未能加载任何Cookie");
                }
            } else {
                log.info("数据库未找到Boss Cookie或值为空，跳过Cookie注入");
            }
        } catch (Exception e) {
            log.warn("从数据库加载Boss Cookie失败: {}", e.getMessage());
        }

        // 导航到Boss直聘首页（带重试机制）
        int maxRetries = 3;
        boolean navigateSuccess = false;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                bossPage.navigate(BOSS_ENTRY_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                navigateSuccess = true;
                break;
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                boolean pageAccessible = false;
                try {
                    String url = bossPage.url();
                    pageAccessible = url != null && url.contains("zhipin.com");
                } catch (Exception ignored) {
                }

                if (pageAccessible) {
                    navigateSuccess = true;
                    break;
                }

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!navigateSuccess) {
            log.warn("Boss直聘页面导航失败");
        }

        try {
            // 等待页面网络空闲，确保头部导航渲染完成
            try {
                bossPage.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
            } catch (Exception e) {
                log.debug("等待Boss页面网络空闲失败: {}", e.getMessage());
            }

            // 初始化阶段不主动跳转登录页，仅在导航后设置状态；
            // 加载Cookie并导航后，由业务侧决定是否触发后续登录流程
        } catch (Exception e) {
            log.warn("Boss直聘页面导航失败: {}", e.getMessage());
        }
        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("boss", checkIfLoggedIn());
        // 设置登录状态监控
        setupLoginMonitoring(bossPage);
    }

    /**
     * 检查Boss是否已登录
     */
    private boolean checkIfLoggedIn() {
        // 最可靠的信号是登录态 Cookie：不受页面渲染、跳转、加载速度影响。
        // DOM 探测（头像、登录入口）只作兜底 —— 页面一慢就会误判成未登录，
        // 而 /api/boss/start 是拿这个结果做准入的，误判会直接导致"请先登录"。
        try {
            for (Cookie cookie : context.cookies(BOSS_URL)) {
                if (BOSS_LOGIN_COOKIES.contains(cookie.name)
                        && cookie.value != null && !cookie.value.isBlank()) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // 更稳健的登录判断：优先检测用户头像/昵称是否可见；备用检测登录入口是否可见且包含“登录”文本
        try {
            Locator userLabel = bossPage.locator("li.nav-figure span.label-text").first();
            if (isVisibleQuick(userLabel)) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 有些版本仅展示头像入口，无 label-text
            Locator navFigure = bossPage.locator("li.nav-figure").first();
            if (isVisibleQuick(navFigure)) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            // 未登录时通常有“登录/注册”入口或按钮容器
            Locator loginAnchor = bossPage.locator("li.nav-sign a, .btns").first();
            if (isVisibleQuick(loginAnchor)) {
                String text = loginAnchor.textContent(new Locator.TextContentOptions().setTimeout(LOCATOR_PROBE_TIMEOUT));
                if (text != null && text.contains("登录")) {
                    return false;
                }
            }
        } catch (Exception ignored) {}

        // 无法明确检测到登录特征时，保守返回未登录
        return false;
    }

    /**
     * 带超时的可见性探测。
     * <p>
     * 不能直接用 Locator.isVisible()：Boss 首页会连续做客户端跳转，
     * 期间 frame 反复重建，这个调用会一直等下去（实测把初始化线程焊死了 2 分钟以上都不返回）。
     * 登录检测只是个探针，等不到就当作"没看到"，绝不能阻塞主流程。
     */
    private boolean isVisibleQuick(Locator locator) {

        try {
            return locator.isVisible(new Locator.IsVisibleOptions().setTimeout(LOCATOR_PROBE_TIMEOUT));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                // 事件回调是在别的调用"进行中"被派发的，此时再去调 Playwright 属于重入，
                // 会打断正在进行的导航。所以除了暂停标志，还要避开前台任务运行期。
                if (!bossMonitoringPaused && !isPlaywrightBusy()) {
                    checkLoginStatus(page, "boss");
                }
            }
        });

        log.info("{}平台登录状态监控已启用", "boss");
    }

    /**
     * 检查登录状态
     *
     * @param page     页面实例
     * @param platform 平台名称
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            boolean isLoggedIn = false;
            if (platform.equals("boss")) {
                // 统一复用更稳健的Boss登录判断逻辑
                isLoggedIn = checkIfLoggedIn();
            }
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get(platform);
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLoginSuccess(platform);
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, e.getMessage());
        }
    }

    /**
     * 登录成功回调
     *
     * @param platform 平台名称
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);

        // 更新登录状态并通知（统一使用setLoginStatus方法）
        setLoginStatus(platform, true);

        // 登录成功时保存 Cookie 到数据库（仅 boss 平台）
        if ("boss".equals(platform)) {
            saveBossCookiesToDatabase("login success");
        }
    }

    /**
     * 统一的Boss Cookie保存方法（使用JSON序列化）
     *
     * @param remark 备注信息
     */
    private void saveBossCookiesToDatabase(String remark) {
        try {
            List<com.microsoft.playwright.options.Cookie> cookies = filterCookiesByDomain(context.cookies(), BOSS_DOMAIN);
            // 使用ObjectMapper序列化为JSON字符串
            String cookieJson = new ObjectMapper().writeValueAsString(cookies);
            boolean result = cookieService.saveOrUpdateCookie("boss", cookieJson, remark);
            if (result) {
                log.info("保存Boss Cookie成功，共 {} 条，remark={}", cookies.size(), remark);
            }
        } catch (Exception e) {
            log.warn("保存Boss Cookie失败: {}", e.getMessage());
        }
    }

    /**
     * 主动保存 Boss Cookie 到数据库（用于调试/验证）
     */
    public void saveBossCookiesToDb(String remark) {
        saveBossCookiesToDatabase(remark);
    }

    /**
     * 按平台保存 Cookie 到数据库（本副本只支持 boss）
     *
     * @param platform 平台标识（boss）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        if (!"boss".equals(platform)) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        saveBossCookiesToDatabase(remark);
    }

    /**
     * 清理Boss上下文中的Cookie
     * 用于退出登录时清除浏览器上下文中的所有Cookie
     */
    public void clearBossCookies() {
        try {
            if (context != null) {
                context.clearCookies();
                log.info("已清理共享上下文中的所有Cookie");
            } else {
                log.warn("共享上下文不存在，无法清理Cookie");
            }
        } catch (Exception e) {
            log.error("清理共享上下文Cookie失败: {}", e.getMessage(), e);
            throw new RuntimeException("清理共享上下文Cookie失败", e);
        }
    }

    /**
     * 定时检查登录状态（每3秒）
     * 用于捕获通过DOM元素判断登录状态的场景（无导航也可触发）
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        if (playwright == null || context == null) {
            return;
        }
        // 必须跑在 Playwright 专用线程上；线程正忙（例如正在投递）就直接跳过这一轮，
        // 否则会打断投递流程里正在进行的导航
        tryRunOnPlaywright(() -> {
            try {
                if (bossPage != null && !bossMonitoringPaused) {
                    checkLoginStatus(bossPage, "boss");
                }
            } catch (Exception e) {
                log.debug("定时登录检测异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 暂停Boss页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseBossMonitoring() {
        bossMonitoringPaused = true;
        log.debug("Boss登录监控已暂停");
    }

    /**
     * 恢复Boss页面的后台登录监控
     */
    public void resumeBossMonitoring() {
        bossMonitoringPaused = false;
        log.debug("Boss登录监控已恢复");
    }

    /**
     * 关闭Playwright实例
     * 在Spring容器销毁前自动执行
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭Playwright管理器...");

        try {
            // 关闭Boss页面
            if (bossPage != null) {
                bossPage.close();
                log.info("Boss直聘页面已关闭");
            }

            // 关闭共享的BrowserContext（持久化上下文关闭即等于关浏览器，没有独立的 Browser 对象）
            if (context != null) {
                context.close();
                log.info("共享BrowserContext已关闭，浏览器已退出");
            }

            if (playwright != null) {
                playwright.close();
                log.info("Playwright实例已关闭");
            }

            log.info("Playwright管理器关闭完成！");
        } catch (Exception e) {
            log.error("关闭Playwright管理器时发生错误", e);
        }
    }

    /**
     * 检查Playwright是否已初始化
     */
    public boolean isInitialized() {
        return playwright != null && context != null && bossPage != null && !browserClosed;
    }

    /**
     * 确保浏览器可用：已经关闭就重新拉起来。
     * <p>
     * 浏览器被手动关掉或崩溃之后，各个字段依然非空，界面上看什么都正常，
     * 但每个页面操作都会抛 TargetClosedError —— 投递任务卡死、按钮点了没反应。
     * 发起任务前先过一遍这里。
     */
    public synchronized void ensureReady() {
        if (isInitialized()) {
            return;
        }
        log.warn("浏览器不可用（已关闭或未初始化），正在重新初始化...");
        // 旧对象已经失效，先清干净再重建，避免 init() 里的 isInitialized() 短路
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
            // 浏览器已经没了，关闭失败很正常
        }
        playwright = null;
        context = null;
        bossPage = null;
        browserClosed = false;
        loginStatus.clear();
        init();
    }

    /**
     * 获取CDP端口号
     */
    public int getCdpPort() {
        return CDP_PORT;
    }

    /**
     * 注册登录状态监听器
     *
     * @param listener 监听器
     */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    /**
     * 移除登录状态监听器
     *
     * @param listener 监听器
     */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    /**
     * 获取平台登录状态
     *
     * @param platform 平台名称
     * @return 是否已登录
     */
    public boolean isLoggedIn(String platform) {
        return loginStatus.getOrDefault(platform, false);
    }

    /**
     * 手动设置平台登录状态（会触发SSE通知）
     *
     * @param platform   平台名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previousStatus = loginStatus.get(platform);

        // 只有状态真正发生变化时才更新和通知
        if (previousStatus == null || previousStatus != isLoggedIn) {
            loginStatus.put(platform, isLoggedIn);

            // Boss平台：在设置未登录状态时，顺带引导到登录页并切换二维码扫码
            if ("boss".equals(platform) && !isLoggedIn) {
                try {
                    if (bossPage != null) {
                        String currentUrl = null;
                        try { currentUrl = bossPage.url(); } catch (Exception ignored) {}

                        // 避免重复导航：若当前已在登录页则不再二次跳转
                        if (currentUrl == null || !currentUrl.contains("/web/user/")) {
                            bossPage.navigate(BOSS_URL + "/web/user/?ka=header-login");
                            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }

                        // 尝试切换到二维码登录（点击“APP扫码登录”按钮），优先使用新版选择器
                        try {
                            Locator qrSwitch = bossPage.locator(".btn-sign-switch.ewm-switch").first();
                            if (isVisibleQuick(qrSwitch)) {
                                qrSwitch.click();
                            } else {
                                // 兜底：按文本匹配内部提示
                                Locator tip = bossPage.getByText("APP扫码登录").first();
                                if (isVisibleQuick(tip)) {
                                    tip.click();
                                    log.info("已点击包含文本的二维码登录切换提示（APP扫码登录）");
                                } else {
                                    // 兼容旧版选择器
                                    Locator legacy = bossPage.locator("li.sign-switch-tip").first();
                                    if (isVisibleQuick(legacy)) {
                                        legacy.click();
                                        log.info("已通过旧版选择器切换二维码登录（li.sign-switch-tip）");
                                    } else {
                                        log.info("未找到二维码登录切换按钮，保持当前登录页");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("切换二维码登录失败: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("设置Boss未登录状态时执行登录引导失败: {}", e.getMessage());
                }
            }

            // 通知所有监听器（触发SSE推送）
            LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
            loginStatusListeners.forEach(listener -> {
                try {
                    listener.accept(change);
                } catch (Exception e) {
                    log.error("通知登录状态监听器失败: platform={}, isLoggedIn={}", platform, isLoggedIn, e);
                }
            });

//            log.info("登录状态已更新: platform={}, isLoggedIn={}", platform, isLoggedIn);
        }
    }

    /**
     * 从JSON字符串解析Cookie列表
     *
     * @param cookieJson Cookie的JSON字符串
     * @return Cookie列表
     */
    private List<Cookie> parseCookiesFromString(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cookieJson);

            for (com.fasterxml.jackson.databind.JsonNode node : jsonArray) {
                // 创建Cookie对象（name和value是必需的）
                Cookie cookie = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText()
                );

                // 设置可选字段
                if (node.has("domain") && !node.get("domain").isNull()) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.has("path") && !node.get("path").isNull()) {
                    cookie.path = node.get("path").asText();
                }
                if (node.has("expires") && !node.get("expires").isNull()) {
                    cookie.expires = node.get("expires").asDouble();
                }
                if (node.has("httpOnly") && !node.get("httpOnly").isNull()) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.has("secure") && !node.get("secure").isNull()) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.has("sameSite") && !node.get("sameSite").isNull()) {
                    String sameSite = node.get("sameSite").asText();
                    if (sameSite != null && !sameSite.isEmpty()) {
                        cookie.sameSite = com.microsoft.playwright.options.SameSiteAttribute.valueOf(
                                sameSite.toUpperCase()
                        );
                    }
                }

                cookies.add(cookie);
            }

            log.debug("成功解析Cookie，共 {} 条", cookies.size());
        } catch (Exception e) {
            log.error("解析Cookie JSON失败: {}", e.getMessage(), e);
        }

        return cookies;
    }

    private List<Cookie> filterCookiesByDomain(List<Cookie> cookies, String domainSuffix) {
        if (cookies == null || cookies.isEmpty()) {
            return new ArrayList<>();
        }

        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }

        return filtered;
    }

    /**
     * LoginStatusChange - 登录状态变化DTO
     */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
