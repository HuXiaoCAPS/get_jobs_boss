package com.getjobs.worker.platform.filter;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * JD 规则过滤器：从 {@code jd-rules.txt} 读入多组「词表 + 阈值 + 动作」，对岗位描述做判定。
 *
 * <p>文件格式（块状，块与块之间用空行分隔；空行与 # 注释忽略）：
 * <pre>
 * [reject] 明确高门槛 1
 * 仅研究生
 * 需 3 年以上
 *
 * [require] 大数据方向 2
 * Hive
 * Spark
 * </pre>
 * 规则头语法为 {@code [动作] 名称 阈值}，阈值为可选，省略时按 1 处理。
 *
 * <p>三种动作：
 * <ul>
 *   <li>{@code reject} —— 命中（命中词数 &ge; 阈值）即拒绝投递</li>
 *   <li>{@code require} —— 必须命中，未达标（命中词数 &lt; 阈值）即拒绝投递</li>
 *   <li>{@code warn} —— 命中只记录提示，不影响投递（用于"可以谈"的条件）</li>
 * </ul>
 *
 * <p>判定顺序：JD 为空 → 直接放行（fail-open，宁可多投也不误杀）；
 * 再判 reject（任一命中即拒）；再判 require（任一未达标即拒）；最后收集 warn。
 *
 * <p>阈值统计的是「命中了多少个<b>不同的</b>词」——遍历词表时每个词最多计一次，
 * 所以 JD 里把同一个词重复提很多遍不会灌水。
 *
 * <p>关于生效时机与文件占用：{@link #reload()} 在每次投递任务开始时调用，
 * 所以改完文件下次投递即生效，无需重启程序；读文件用一次性读入并立即关闭，
 * 不长期持有句柄；若读取过程抛异常（文件正被其它程序写入/占用等），
 * 会保留上一次成功加载的规则，避免因此整轮不过滤。
 */
@Slf4j
public class JdRuleFilter {

    /** 规则文件名：优先取工作目录下的同名文件，找不到再取 classpath（src/main/resources） */
    public static final String DEFAULT_FILE = "jd-rules.txt";

    private static final String ACTION_REJECT = "reject";
    private static final String ACTION_REQUIRE = "require";
    private static final String ACTION_WARN = "warn";

    /** 当前生效的规则；reload() 成功后整体替换 */
    private volatile List<Rule> rules = Collections.emptyList();
    /** 是否曾经成功加载过（用于区分"读失败，沿用旧规则"和"从没加载过"） */
    private volatile boolean everLoaded = false;

    /** 一组规则：动作 + 名称 + 阈值 + 词表 */
    public static final class Rule {
        public final String action;
        public final String name;
        public final int threshold;
        public final List<String> words;

        Rule(String action, String name, int threshold, List<String> words) {
            this.action = action;
            this.name = name;
            this.threshold = threshold;
            this.words = words;
        }
    }

    /** 判定结果 */
    public static final class Result {
        /** 是否应跳过投递 */
        public final boolean rejected;
        /** 拒绝原因（rejected 为 true 时非空，可直接打日志） */
        public final String reason;
        /** warn 规则命中的描述（不影响投递，供落库/日志） */
        public final List<String> warnings;
        /** JD 是否为空（为空时未做任何判定，调用方应打日志说明） */
        public final boolean jdEmpty;

        Result(boolean rejected, String reason, List<String> warnings, boolean jdEmpty) {
            this.rejected = rejected;
            this.reason = reason;
            this.warnings = warnings;
            this.jdEmpty = jdEmpty;
        }

        static Result pass(boolean jdEmpty) {
            return new Result(false, null, Collections.emptyList(), jdEmpty);
        }
    }

    /**
     * 重新加载默认规则文件。读失败时保留上一次成功的规则。
     */
    public void reload() {
        reload(DEFAULT_FILE);
    }

    /**
     * 重新加载指定规则文件。
     */
    public synchronized void reload(String fileName) {
        List<String> lines = readLines(fileName);
        if (lines == null) {
            // 读文件抛异常（被占用、正在写入等）：沿用上一次成功的规则，
            // 不让"临时读不到"变成"这一轮完全不过滤"
            if (everLoaded) {
                log.warn("JD 规则文件 {} 读取失败，沿用上一次成功加载的 {} 组规则", fileName, rules.size());
            } else {
                log.warn("JD 规则文件 {} 读取失败，本轮不启用 JD 规则过滤", fileName);
            }
            return;
        }
        List<Rule> parsed = parse(lines);
        this.rules = parsed;
        this.everLoaded = true;
        log.info("已加载 JD 规则 {}：共 {} 组（reject {} / require {} / warn {}）", fileName,
                parsed.size(), countByAction(parsed, ACTION_REJECT),
                countByAction(parsed, ACTION_REQUIRE), countByAction(parsed, ACTION_WARN));
    }

    /**
     * 对岗位描述做判定。
     *
     * @param jd 岗位描述正文（postDescription）
     * @return 判定结果；jd 为空或没有任何规则时一律放行
     */
    public Result evaluate(String jd) {
        List<Rule> snapshot = rules;
        if (jd == null || jd.isBlank()) {
            return Result.pass(true);
        }
        if (snapshot.isEmpty()) {
            return Result.pass(false);
        }

        String haystack = jd.toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();

        for (Rule rule : snapshot) {
            List<String> hits = matchWords(rule, haystack);
            boolean hit = hits.size() >= rule.threshold;

            if (ACTION_REJECT.equals(rule.action)) {
                if (hit) {
                    return new Result(true, String.format("规则[%s]命中【%s】(%d/%d)",
                            rule.name, String.join("、", hits), hits.size(), rule.threshold),
                            warnings, false);
                }
            } else if (ACTION_REQUIRE.equals(rule.action)) {
                if (!hit) {
                    String hitDesc = hits.isEmpty() ? "无" : String.join("、", hits);
                    return new Result(true, String.format("规则[%s]未达标(命中 %d/%d：%s)",
                            rule.name, hits.size(), rule.threshold, hitDesc),
                            warnings, false);
                }
            } else if (ACTION_WARN.equals(rule.action)) {
                if (hit) {
                    warnings.add(String.format("规则[%s]命中【%s】(%d/%d)",
                            rule.name, String.join("、", hits), hits.size(), rule.threshold));
                }
            }
        }
        return new Result(false, null, warnings, false);
    }

    /** 命中词列表；每个词最多贡献一次，天然去重 */
    private List<String> matchWords(Rule rule, String lowerJd) {
        List<String> hits = new ArrayList<>();
        for (String word : rule.words) {
            if (lowerJd.contains(word.toLowerCase(Locale.ROOT))) {
                hits.add(word);
            }
        }
        return hits;
    }

    /**
     * 读取规则文件内容。
     *
     * @return 行列表；文件不存在时返回空列表；读取异常时返回 null（调用方据此沿用旧规则）
     */
    private List<String> readLines(String fileName) {
        try {
            Path local = Paths.get(System.getProperty("user.dir"), fileName);
            if (Files.isRegularFile(local)) {
                // readAllLines 内部打开并立即关闭，不留文件句柄
                return Files.readAllLines(local, StandardCharsets.UTF_8);
            }
            URL resource = JdRuleFilter.class.getResource("/" + fileName);
            if (resource != null) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                return lines;
            }
            log.info("未找到 JD 规则文件 {}，本轮不启用 JD 规则过滤", fileName);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("读取 JD 规则文件 {} 异常：{}", fileName, e.getMessage());
            return null;
        }
    }

    /** 解析块状规则文件 */
    private List<Rule> parse(List<String> lines) {
        List<Rule> result = new ArrayList<>();
        String action = null;
        String name = null;
        int threshold = 1;
        List<String> words = new ArrayList<>();

        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("[")) {
                flush(result, action, name, threshold, words);
                words = new ArrayList<>();

                int end = line.indexOf(']');
                if (end < 0) {
                    log.warn("JD 规则文件：规则头缺少 ']'，已跳过该行：{}", line);
                    action = null;
                    continue;
                }
                String act = line.substring(1, end).trim().toLowerCase(Locale.ROOT);
                String rest = line.substring(end + 1).trim();
                String ruleName = rest;
                int th = 1;
                int lastSpace = rest.lastIndexOf(' ');
                if (lastSpace > 0) {
                    String tail = rest.substring(lastSpace + 1).trim();
                    try {
                        th = Integer.parseInt(tail);
                        ruleName = rest.substring(0, lastSpace).trim();
                    } catch (NumberFormatException ignore) {
                        // 最后一段不是数字，说明整串都是名称，阈值用默认值
                        ruleName = rest;
                    }
                }
                if (th < 1) {
                    th = 1;
                }

                if (!ACTION_REJECT.equals(act) && !ACTION_REQUIRE.equals(act) && !ACTION_WARN.equals(act)) {
                    log.warn("JD 规则文件：未知动作 [{}]，支持 reject/require/warn，该组已跳过", act);
                    action = null;
                    continue;
                }
                action = act;
                name = ruleName.isEmpty() ? act : ruleName;
                threshold = th;
            } else if (action != null) {
                words.add(line);
            } else {
                log.debug("JD 规则文件：忽略无归属的词「{}」（应写在某个规则头之后）", line);
            }
        }
        flush(result, action, name, threshold, words);
        return result;
    }

    private void flush(List<Rule> out, String action, String name, int threshold, List<String> words) {
        if (action == null || words.isEmpty()) {
            return;
        }
        out.add(new Rule(action, name, threshold, new ArrayList<>(words)));
    }

    private int countByAction(List<Rule> list, String action) {
        int n = 0;
        for (Rule rule : list) {
            if (action.equals(rule.action)) {
                n++;
            }
        }
        return n;
    }
}
