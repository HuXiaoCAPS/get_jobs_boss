package com.getjobs.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.getjobs.application.service.ConfigFileService;
import com.getjobs.worker.manager.PlaywrightManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 应用启动后自动打开管理页面
 * 优先级：前端服务 > 静态资源 > 不打开
 */
@Slf4j
@Component
public class StartupRunner implements ApplicationRunner {

    @Value("${server.port:9527}")
    private int backendPort;

    private static final int FRONTEND_PORT = 6866;
    private static final String FRONTEND_URL = "http://localhost:" + FRONTEND_PORT;
    private static final String BACKEND_URL = "http://localhost:";

    @Autowired
    private PlaywrightManager playwrightManager;

    /** 读 config/boss.yaml —— 管理页用哪个浏览器就配在那个文件里 */
    @Autowired
    private ConfigFileService configFileService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String urlToOpen = determineUrlToOpen();
        if (urlToOpen != null) {
            openBrowser(urlToOpen);
        } else {
            log.info("未找到可用的管理页面，跳过自动打开浏览器");
        }

        // 在尝试打开管理页面之后，初始化 Playwright（满足“先打开管理页，再实例化”）
        try {
            playwrightManager.init();
        } catch (Exception e) {
            log.error("Playwright 初始化失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 确定要打开的URL
     * 优先级：前端服务 > 后端静态资源 > null
     */
    private String determineUrlToOpen() {
        // 1. 检查前端服务是否在运行
        if (isServiceRunning(FRONTEND_PORT)) {
            return FRONTEND_URL;
        }

        // 2. 检查后端是否有静态资源（dist文件夹）
        if (hasStaticResources()) {
            log.info("检测到后端静态资源，使用后端URL");
            return BACKEND_URL + backendPort;
        }

        // 3. 都没有，返回null
        log.info("未检测到前端服务或静态资源");
        return null;
    }

    /**
     * 检查指定端口的服务是否在运行
     * 支持 IPv4 和 IPv6
     */
    private boolean isServiceRunning(int port) {
        // 尝试多个地址：IPv4 和 IPv6
        String[] hosts = {"127.0.0.1", "[::1]", "localhost"};

        for (String host : hosts) {
            try {
                // 使用URI替代已过时的URL构造函数
                HttpURLConnection connection = (HttpURLConnection) URI.create("http://" + host + ":" + port)
                        .toURL()
                        .openConnection();

                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                // 接受所有2xx, 3xx, 4xx响应码（说明服务在运行）
                if (responseCode >= 200 && responseCode < 500) {
                    log.debug("检测到端口 {} 的服务 (地址: {})", port, host);
                    return true;
                }
            } catch (Exception e) {
                log.debug("端口 {} HTTP服务检测失败 ({}): {}", port, host, e.getMessage());
            }
        }

        return false;
    }

    /**
     * 检查是否存在静态资源文件夹
     * 检查 src/main/resources/dist/ 是否存在且包含文件
     */
    private boolean hasStaticResources() {
        try {
            // 检查dist文件夹是否存在
            java.io.File distDir = new java.io.File("src/main/resources/dist");
            if (distDir.exists() && distDir.isDirectory()) {
                // 检查是否有文件（不包括隐藏文件）
                java.io.File[] files = distDir.listFiles(file -> !file.getName().startsWith("."));
                if (files != null && files.length > 0) {
                    log.info("发现静态资源文件夹: {}, 文件数: {}", distDir.getAbsolutePath(), files.length);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("检查静态资源时出错: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 打开管理页的浏览器。
     *
     * <p>取值来自 {@code config/boss.yaml} 的 <b>{@code manage_page.browser}</b>
     * （环境变量 {@code MANAGE_BROWSER} 可临时覆盖），支持三种写法：
     * <ul>
     *   <li>{@code default} —— 交给系统默认浏览器</li>
     *   <li>{@code msedge} —— 优先 Microsoft Edge（默认值；自动化本来就用 Edge），找不到再回退默认浏览器</li>
     *   <li>其它值 —— 当作可执行文件路径或命令名直接用，例如
     *       {@code C:/Program Files/Google/Chrome/Application/chrome.exe}；起不来则回退默认浏览器</li>
     *   <li>（{@code none} —— 不自动打开，只把地址打进日志）</li>
     * </ul>
     *
     * <p>为什么不直接用系统默认浏览器：自动化跑的就是 Edge，管理页放 Edge 里，
     * 视觉上只需盯一个浏览器，不用在"默认浏览器"和 Edge 之间来回切。
     *
     * <p><b>注意</b>：这里打开的是用户日常那个 Edge（只是新开一个标签页）；
     * 自动化用的 Edge 是独立持久化 profile（{@code browser-data/}），<b>两者不共享登录态</b>，
     * 别把它们当成同一个浏览器。
     */
    private void openBrowser(String url) {
        String browser = resolveManageBrowser();

        if ("none".equalsIgnoreCase(browser)) {
            log.info("manage_page.browser=none，跳过自动打开浏览器。管理页面: {}", url);
            return;
        }

        String os = System.getProperty("os.name").toLowerCase();
        log.info("管理页将用「{}」打开（配置项 manage_page.browser）", browser);

        try {
            // 1) 自定义路径/命令：既不是 default 也不是 msedge 时，直接执行它
            if (!isSystemDefault(browser) && !isEdge(browser)) {
                if (tryExec(browser, url)) {
                    log.info("已用指定浏览器打开管理页: {} <- {}", url, browser);
                    return;
                }
                log.warn("指定的浏览器起不来（{}），回退到默认方式", browser);
            }

            // 2) 明确要求系统默认浏览器
            if (isSystemDefault(browser)) {
                openWithSystemDefault(os, url);
                return;
            }

            // 3) msedge / edge（或不认识的写法）：优先 Edge，失败回退系统默认
            if (openWithEdge(os, url)) {
                return;
            }
            openWithSystemDefault(os, url);
        } catch (IOException e) {
            log.error("打开浏览器失败: {}", e.getMessage());
            log.info("请手动访问管理页面: {}", url);
        }
    }

    /** 管理页浏览器配置：写在 config/boss.yaml 的顶层段里，与投递配置同一个文件 */
    private static final String MANAGE_PAGE_SECTION = "manage_page";
    private static final String MANAGE_PAGE_KEY = "browser";

    /** 默认值：优先 Edge（自动化本来就用 Edge） */
    private static final String DEFAULT_MANAGE_BROWSER = "msedge";

    private static boolean isSystemDefault(String value) {
        return "default".equalsIgnoreCase(value);
    }

    private static boolean isEdge(String value) {
        return "msedge".equalsIgnoreCase(value) || "edge".equalsIgnoreCase(value);
    }

    /**
     * 决定用哪个浏览器打开管理页。
     *
     * <p>优先级：环境变量 {@code MANAGE_BROWSER}（临时覆盖、不必改文件）
     * → {@code config/boss.yaml} 的 {@code manage_page.browser}
     * → 内置默认 {@code msedge}。
     */
    private String resolveManageBrowser() {
        String fromEnv = System.getenv("MANAGE_BROWSER");
        if (fromEnv != null && !fromEnv.isBlank()) {
            log.info("管理页浏览器取自环境变量 MANAGE_BROWSER={}", fromEnv.trim());
            return fromEnv.trim();
        }
        try {
            Object section = configFileService.read().get(MANAGE_PAGE_SECTION);
            if (section instanceof java.util.Map<?, ?> map) {
                Object value = map.get(MANAGE_PAGE_KEY);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString().trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取 manage_page.browser 失败，用默认值：{}", e.getMessage());
        }
        return DEFAULT_MANAGE_BROWSER;
    }

    /** 用系统默认浏览器打开 */
    private void openWithSystemDefault(String os, String url) throws IOException {
        if (os.contains("win")) {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", url).start();
        } else if (os.contains("nix") || os.contains("nux")) {
            new ProcessBuilder("xdg-open", url).start();
        } else {
            log.warn("未知操作系统类型，无法自动打开浏览器，请手动访问: {}", url);
            return;
        }
        log.info("已用系统默认浏览器打开管理页: {}", url);
    }

    /** Windows 上 Edge 的常见安装位置（按常见程度排序） */
    private static final String[] WINDOWS_EDGE_PATHS = {
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
            System.getenv("LOCALAPPDATA") == null ? ""
                    : System.getenv("LOCALAPPDATA").replace('\\', '/') + "/Microsoft/Edge/Application/msedge.exe",
    };

    private static final String MAC_EDGE = "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
    private static final String LINUX_EDGE = "microsoft-edge";

    /**
     * 用 Edge 打开 url。
     *
     * <p>先按已知安装路径直接执行 exe（不经过 cmd，省得踩 {@code start} 的参数解析坑：
     * 它的第一个引号参数会被当成窗口标题）。都不存在时再退回命令名 {@code msedge} ——
     * Edge 安装时会把 exe 注册进 App Paths，PATH 里没有也能被找到。
     */
    private boolean openWithEdge(String os, String url) {
        if (os.contains("mac")) {
            if (tryExec(MAC_EDGE, url)) {
                log.info("已用 Edge 打开管理页: {}", url);
                return true;
            }
        } else if (!os.contains("win")) {
            if (tryExec(LINUX_EDGE, url)) {
                log.info("已用 Edge 打开管理页: {}", url);
                return true;
            }
        } else {
            for (String exe : WINDOWS_EDGE_PATHS) {
                if (exe != null && !exe.isEmpty() && new java.io.File(exe).isFile() && tryExec(exe, url)) {
                    log.info("已用 Edge 打开管理页: {}", url);
                    return true;
                }
            }
            if (tryExec("msedge", url)) {
                log.info("已用 Edge 打开管理页: {}", url);
                return true;
            }
        }
        log.debug("未找到 Edge，改用系统默认浏览器");
        return false;
    }

    /** 执行一个"打开 url"的命令；失败返回 false（由调用方决定是否回退） */
    private boolean tryExec(String command, String url) {
        try {
            new ProcessBuilder(command, url).start();
            return true;
        } catch (IOException e) {
            log.debug("用 {} 打开失败: {}", command, e.getMessage());
            return false;
        }
    }
}
