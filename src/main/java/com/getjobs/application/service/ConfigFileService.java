package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Boss 配置的文件化存储：读写 {@code config/} 目录下的 YAML 配置文件。
 *
 * <p>这个类只负责 YAML 的 I/O 和"哪一份在生效"的记账，不做任何业务转换
 * （name↔code、列表解析等都在 {@link BossService} 里做），避免和它形成循环依赖。
 *
 * <p><b>多份配置</b>：{@code config/} 下可以平铺任意多份 {@code *.yaml}
 * （如 {@code 数据开发.yaml} / {@code 数仓实习.yaml}），每份是一套完整的投递配置。
 * 当前生效的那份记在 {@code config/.active}（一行文件名），<b>切换配置 = 改写这一个文件</b>，
 * 所以既不用重启，也不用把配置内容搬来搬去。
 * {@code boss.yaml} 是默认值：{@code .active} 缺失、内容非法、或它指向的文件被删时回退到它。
 *
 * <p>为什么不用数据库存配置：配置属于"代码"而不是"数据"——放文件才能 git diff、
 * 手工批量编辑、给不同求职方向各留一份。投递记录、HR 回复这类真正的数据仍然放库里。
 */
@Slf4j
@Service
public class ConfigFileService {

    /** 配置目录：项目根目录下的 config/ */
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.dir"), "config");

    /** 记录"当前生效的配置文件"：一行文件名；切换配置就是改写它 */
    private static final Path ACTIVE_FILE = CONFIG_DIR.resolve(".active");

    /** 默认配置文件；{@code .active} 不可用时回退到它 */
    public static final String DEFAULT_NAME = "boss.yaml";

    /** 新建配置文件时的模板（没有可复制的来源就用它） */
    private static final String TEMPLATE_NAME = "boss.yaml.example";

    /** 只有以此结尾的文件才会被当成配置文件 */
    private static final String YAML_SUFFIX = ".yaml";

    /** YAML 写出风格：多行文本用 | 字面块（好读好改），并且去掉 "---" 文档头 */
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                    .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build());

    /** 连模板都找不到时的兜底骨架，保证新建出来的配置是能解析的完整结构 */
    private static final String DEFAULT_BODY =
            "manage_page:\n" +
            "  browser: msedge\n" +
            "browser:\n" +
            "  channel: msedge\n" +
            "search:\n" +
            "  keywords: []\n" +
            "  city: []\n" +
            "delivery:\n" +
            "  say_hi: \"\"\n" +
            "  wait_time: 10\n" +
            "  enable_ai: false\n" +
            "ai:\n" +
            "  base_url: \"\"\n" +
            "  api_key: \"\"\n" +
            "  model: \"\"\n" +
            "  introduce: \"\"\n" +
            "  prompt: \"\"\n" +
            "notify:\n" +
            "  hook_url: \"\"\n" +
            "  bot_is_send: false\n" +
            "jd_rules: []\n";

    /** 配置文件里 JD 过滤规则那一段的键名（规则与配置同文件，见 writeJdRules） */
    public static final String JD_RULES_KEY = "jd_rules";

    // ==================== 当前生效的文件 ====================

    /**
     * 当前生效的配置文件名。
     *
     * <p>指针文件缺失、内容非法、或它指向的文件已经不存在时，一律回退 {@link #DEFAULT_NAME}。
     * 这样"删掉了正在用的配置"之类的情况不会让整个应用读不到配置。
     */
    public String activeName() {
        try {
            if (Files.isRegularFile(ACTIVE_FILE)) {
                String name = Files.readString(ACTIVE_FILE, StandardCharsets.UTF_8).trim();
                if (isValidName(name) && Files.isRegularFile(resolve(name))) {
                    return name;
                }
                log.debug("config/.active 指向的配置不可用（{}），回退 {}", name, DEFAULT_NAME);
            }
        } catch (Exception e) {
            log.debug("读取 config/.active 失败，回退 {}：{}", DEFAULT_NAME, e.getMessage());
        }
        return DEFAULT_NAME;
    }

    /** 当前生效的配置文件路径 */
    public Path activePath() {
        return resolve(activeName());
    }

    /** 配置目录（用于前端提示"文件放在哪"） */
    public Path dir() {
        return CONFIG_DIR;
    }

    /**
     * 切换到指定配置文件（改写 {@code config/.active}）。
     *
     * @return 目标文件不存在或名字非法时返回 false
     */
    public boolean switchTo(String name) throws IOException {
        if (!isValidName(name) || !Files.isRegularFile(resolve(name))) {
            return false;
        }
        Files.createDirectories(CONFIG_DIR);
        Files.writeString(ACTIVE_FILE, name, StandardCharsets.UTF_8);
        log.info("当前配置文件已切换为 {}", name);
        return true;
    }

    // ==================== 文件的增删改查 ====================

    /** 列出 config/ 下所有可用的配置文件（不含 .active 与 *.example） */
    public List<String> listNames() {
        try (Stream<Path> stream = Files.list(CONFIG_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(ConfigFileService::isValidName)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("列出 {} 下的配置文件失败：{}", CONFIG_DIR, e.getMessage());
            return List.of();
        }
    }

    /**
     * 新建一份配置文件。
     *
     * @param name     新文件名（必须以 .yaml 结尾）
     * @param copyFrom 复制来源；为空或不存在时用 {@code boss.yaml.example}，再不行就用内置骨架
     * @return 已存在同名文件、或名字非法时返回 false
     */
    public boolean create(String name, String copyFrom) throws IOException {
        if (!isValidName(name)) {
            return false;
        }
        Path target = resolve(name);
        if (Files.exists(target)) {
            return false;
        }
        Files.createDirectories(CONFIG_DIR);

        Path source = (copyFrom != null && isValidName(copyFrom) && Files.isRegularFile(resolve(copyFrom)))
                ? resolve(copyFrom)
                : resolve(TEMPLATE_NAME);

        if (Files.isRegularFile(source)) {
            Files.copy(source, target);
            log.info("已新建配置文件 {}（复制自 {}）", name, source.getFileName());
        } else {
            Files.writeString(target, header(name) + DEFAULT_BODY, StandardCharsets.UTF_8);
            log.info("已新建配置文件 {}（内置骨架）", name);
        }
        return true;
    }

    /**
     * 重命名配置文件。若改的正是当前生效的那份，指针一并跟着改，避免指向已消失的文件。
     *
     * @return 源不存在、目标已存在、或名字非法时返回 false
     */
    public boolean rename(String from, String to) throws IOException {
        if (!isValidName(from) || !isValidName(to) || from.equals(to)) {
            return false;
        }
        Path src = resolve(from);
        Path dst = resolve(to);
        if (!Files.isRegularFile(src) || Files.exists(dst)) {
            return false;
        }
        // 必须在 move 之前判断：move 之后 activeName() 会因为源文件消失而回退
        boolean wasActive = activeName().equals(from);
        Files.move(src, dst);
        if (wasActive) {
            Files.writeString(ACTIVE_FILE, to, StandardCharsets.UTF_8);
        }
        log.info("配置文件 {} 已重命名为 {}{}", from, to, wasActive ? "（当前生效）" : "");
        return true;
    }

    /**
     * 删除配置文件。
     *
     * <p><b>不允许删当前生效的那份</b>：那样指针会静默回退到 boss.yaml，用户看到"配置变了"
     * 却不知道为什么。要删就先把当前配置切走。
     *
     * @return 目标不存在、是当前生效文件、或名字非法时返回 false
     */
    public boolean delete(String name) throws IOException {
        if (!isValidName(name) || activeName().equals(name)) {
            return false;
        }
        Path target = resolve(name);
        if (!Files.isRegularFile(target)) {
            return false;
        }
        Files.delete(target);
        log.info("配置文件 {} 已删除", name);
        return true;
    }

    // ==================== 读写 ====================

    /** 当前生效的配置文件是否存在 */
    public boolean exists() {
        return Files.isRegularFile(activePath());
    }

    /**
     * 读出当前生效的整份配置（分层结构，对应 YAML 的 search / delivery / ai / notify 等节点）。
     * 文件不存在或解析失败时返回空 Map —— 调用方据此回退到数据库。
     */
    public Map<String, Object> read() {
        return readFile(activePath());
    }

    /**
     * 读指定文件的整份配置。
     *
     * <p>单独抽出来，是为了让"不当前生效的那份"也能被读 ——
     * 配置列表要显示每份配置有没有规则，不能为了看一眼就切过去。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Collections.emptyMap();
            }
            Map<String, Object> data =
                    YAML.readValue(Files.readString(path, StandardCharsets.UTF_8), Map.class);
            return data == null ? Collections.emptyMap() : data;
        } catch (Exception e) {
            log.warn("读取 {} 失败，本次改用数据库里的配置：{}", path, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 覆盖写入当前生效的配置。失败时返回 false，调用方可以据此给出提示。
     */
    public boolean write(Map<String, Object> data) {
        Path path = activePath();
        try {
            Files.createDirectories(path.getParent());
            String body = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(path, header(path.getFileName().toString()) + body, StandardCharsets.UTF_8);
            log.info("已写入配置文件 {}", path);
            return true;
        } catch (Exception e) {
            log.warn("写入 {} 失败：{}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 用现成的 YAML 文本覆盖当前配置（网页端「载入」外部文件用）。
     *
     * <p>必须先解析校验：直接把这坨字节写进去，只要它不是合法 YAML（或顶层不是映射），
     * 下一次 {@code syncConfigFromFile()} 读到的就是空配置 —— 用户的搜索条件、招呼语、
     * AI 提示词会一起变成默认值，而文件已经被覆盖，退不回去。
     *
     * <p>写出去的是<b>解析后再序列化</b>的规范化版本（外加文件头），所以不会把外部文件里
     * 五花八门的缩进/锚点/自定义标签带进来。
     *
     * @return 内容为空、不是合法 YAML、或顶层不是非空映射时返回 false
     */
    @SuppressWarnings("unchecked")
    public boolean writeRaw(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        Map<String, Object> parsed;
        try {
            parsed = YAML.readValue(content, Map.class);
        } catch (Exception e) {
            log.warn("导入的 YAML 解析失败：{}", e.getMessage());
            return false;
        }
        if (parsed == null || parsed.isEmpty()) {
            log.warn("导入的 YAML 不是非空映射（顶层应是 search / delivery / ai / notify 这样的键）");
            return false;
        }
        return write(parsed);
    }

    // ==================== JD 过滤规则那一段 ====================

    /**
     * 读出当前配置里的 JD 过滤规则段（原始结构，不解析字段含义 —— 那是
     * {@code JdRuleFilter} 的事）。
     *
     * <p><b>规则为什么在配置文件里而不是单独一个文件</b>：早先是
     * {@code boss.yaml} + {@code jd-rules.yaml} 两个文件靠命名约定关联，
     * 于是"另存为 / 改名 / 删除"都得额外跟着搬一次规则文件，还得在 .gitignore 里
     * 单独放行规则文件。合并成一段之后，一个配置就是一个文件，
     * 复制 / 改名 / 删除天然带上规则，没有任何对应关系需要维护。
     *
     * @return 规则列表；没有这一段或格式不对时返回空列表
     */
    public List<Map<String, Object>> readJdRules() {
        return readJdRules(activeName());
    }

    /** 读指定配置文件里的 JD 过滤规则段（配置列表用它判断"哪份还没配规则"） */
    public List<Map<String, Object>> readJdRules(String name) {
        return extractJdRules(readFile(resolve(name)));
    }

    /** 从一份已解析的配置里取出规则段（两份 readJdRules 共用） */
    private static List<Map<String, Object>> extractJdRules(Map<String, Object> root) {
        Object raw = root.get(JD_RULES_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rules = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> rule = new java.util.LinkedHashMap<>();
                map.forEach((k, v) -> rule.put(String.valueOf(k), v));
                rules.add(rule);
            }
        }
        return rules;
    }

    /**
     * 写入 JD 过滤规则段，<b>其余段落原样保留</b>。
     *
     * <p>必须"读 → 只改这一段 → 写回"：整份覆盖会把 search / delivery / ai / notify
     * 一起清空（网页端「过滤规则」的保存与配置页的「保存」是两个独立按钮，
     * 各自只该改自己那一段）。
     */
    public boolean writeJdRules(List<?> rules) {
        Map<String, Object> root = new java.util.LinkedHashMap<>(read());
        root.put(JD_RULES_KEY, rules == null ? Collections.emptyList() : rules);
        return write(root);
    }

    // ==================== 小工具 ====================

    /** 配置文件名是否合法：以 .yaml 结尾，且不含路径分隔符与 ..（防目录穿越） */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (!n.endsWith(YAML_SUFFIX) || n.equals(YAML_SUFFIX)) {
            return false;
        }
        return !n.contains("/") && !n.contains("\\") && !n.contains("..");
    }

    /** 文件名 → 配置目录下的路径（不做合法性校验，调用方负责） */
    public Path resolve(String name) {
        return CONFIG_DIR.resolve(name);
    }

    /** 每次写文件时带的说明头，写上文件名以便一眼看出在编辑哪一份 */
    private static String header(String name) {
        return "# ============================================================\n" +
                "# Boss 投递配置：" + name + "\n" +
                "# ------------------------------------------------------------\n" +
                "# 生效时机：每次点「开始投递」时重新读取 —— 改完重跑任务即生效，不用重启程序。\n" +
                "# 维护方式：直接编辑本文件，或在网页端配置页保存（两者等价）。\n" +
                "# 切换配置：用配置页右上角的「另存为 / 载入 / 选择 / 保存」，或在 config/.active 里写文件名。\n" +
                "# 本文件含 API KEY，已在 .gitignore 中；结构模板见同目录的 boss.yaml.example。\n" +
                "# 过滤规则也在本文件的 jd_rules 段里（跟配置一起走，不再是单独的文件）。\n" +
                "# ============================================================\n\n";
    }
}
