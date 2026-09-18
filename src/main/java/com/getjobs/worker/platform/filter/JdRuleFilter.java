package com.getjobs.worker.platform.filter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JD 规则过滤器：把「词表 + 阈值 + 动作」的规则列表编译成判定逻辑，对岗位描述做判定。
 *
 * <p><b>规则存放在哪</b>：当前生效的配置文件里的 {@code jd_rules} 段（见
 * {@code ConfigFileService}）。这个类<b>不碰文件</b> —— 规则由调用方读出来再喂进来
 * （{@link #reloadFrom(List)}），它只负责"解析这段结构 + 判定"。
 * 早先它自己读一个独立的 {@code jd-rules.yaml}，结果是"配置"和"规则"两个文件
 * 靠命名约定关联；合并成配置文件里的一段后，这个类顺势把文件 IO 交了出去。
 *
 * <p>规则结构（每条一个映射）：
 * <pre>
 * jd_rules:
 *   - action: reject
 *     name: 明确高门槛
 *     threshold: 1
 *     note: 挡研究生 / 3 年以上 / 非实习岗位
 *     words: [仅研究生, 硕士研究生及以上, 需 3 年以上]
 * </pre>
 *
 * <p>各字段：
 * <ul>
 *   <li>{@code action} —— 见下表，必填，只认 {@code reject} / {@code require} / {@code warn}</li>
 *   <li>{@code name} —— 规则名，只用于日志与网页端展示；留空时取动作名</li>
 *   <li>{@code threshold} —— 命中多少个<b>不同</b>的词才算命中，可选，默认 1，小于 1 按 1 处理</li>
 *   <li>{@code note} —— 备注，纯给人看，可选（网页端「过滤规则」里就是它那一列）</li>
 *   <li>{@code words} —— 词表，必填且不能为空（空词表的规则会被跳过并告警）</li>
 * </ul>
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
 * <p><b>解析宽容度</b>：单条规则出错只跳过那一条并给出警告，不影响其它规则 ——
 * 这段结构是手改和网页改两条路都能改的，一处笔误不该让整套过滤失效。
 */
@Slf4j
public class JdRuleFilter {

    private static final String ACTION_REJECT = "reject";
    private static final String ACTION_REQUIRE = "require";
    private static final String ACTION_WARN = "warn";

    /** 当前生效的规则；reloadFrom() 成功后整体替换 */
    private volatile List<Rule> rules = Collections.emptyList();
    /** 是否曾经加载过（用来区分"从没配过规则"和"配了但被清空"） */
    private volatile boolean everLoaded = false;

    /** 一组规则：动作 + 名称 + 阈值 + 备注 + 词表 */
    public static final class Rule {
        public final String action;
        public final String name;
        public final int threshold;
        /** 备注，纯展示用；可为 null */
        public final String note;
        public final List<String> words;

        /** 一行摘要，例如 {@code [reject] 明确高门槛 阈值≥1 · 词 8} */
        public String describe() {
            return String.format("[%s] %s 阈值≥%d · 词 %d", action, name, threshold, words.size());
        }

        public Rule(String action, String name, int threshold, String note, List<String> words) {
            this.action = action;
            this.name = name;
            this.threshold = threshold;
            this.note = note;
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

    /** 解析结果：规则列表 + 语法告警 */
    public static final class ParseResult {
        public final List<Rule> rules;
        public final List<String> warnings;

        public ParseResult(List<Rule> rules, List<String> warnings) {
            this.rules = rules;
            this.warnings = warnings;
        }
    }

    // ==================================================================
    // 解析（静态；网页端保存前校验也走这里）
    // ==================================================================

    /**
     * 解析规则列表结构。
     *
     * <p>逐条容错：某一条不合法只跳过那一条并记一条 warning。
     * 网页端保存前也用这个方法校验 —— "写进去的"和"读出来的"必须是同一套规则，
     * 不能两套校验逻辑各说各话。
     */
    public static ParseResult parseRules(List<?> rawRules) {
        List<String> warnings = new ArrayList<>();
        List<Rule> parsed = new ArrayList<>();

        if (rawRules == null || rawRules.isEmpty()) {
            return new ParseResult(parsed, warnings);
        }

        int index = 0;
        for (Object item : rawRules) {
            index++;
            if (!(item instanceof Map<?, ?> m)) {
                warnings.add("第 " + index + " 条规则不是映射，已跳过");
                continue;
            }

            String action = str(m.get("action")).toLowerCase(Locale.ROOT);
            if (!isValidAction(action)) {
                warnings.add("第 " + index + " 条规则的动作「" + action
                        + "」无效（只支持 " + ACTION_REJECT + "/" + ACTION_REQUIRE + "/" + ACTION_WARN + "），已跳过");
                continue;
            }

            String name = str(m.get("name"));
            if (name.isEmpty()) {
                name = action;
            }

            int threshold = 1;
            Object rawThreshold = m.get("threshold");
            if (rawThreshold != null) {
                try {
                    threshold = Integer.parseInt(str(rawThreshold));
                } catch (NumberFormatException e) {
                    warnings.add("第 " + index + " 条规则的阈值「" + rawThreshold + "」不是整数，按 1 处理");
                    threshold = 1;
                }
                if (threshold < 1) {
                    warnings.add("第 " + index + " 条规则的阈值 " + threshold + " 小于 1，按 1 处理");
                    threshold = 1;
                }
            }

            List<String> words = new ArrayList<>();
            Object rawWords = m.get("words");
            if (rawWords instanceof List<?> wordList) {
                for (Object o : wordList) {
                    String word = str(o);
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
            } else if (rawWords != null) {
                warnings.add("第 " + index + " 条规则的 words 不是列表，已按空词表处理");
            }
            if (words.isEmpty()) {
                warnings.add("第 " + index + " 条规则「" + name + "」没有任何词，已跳过（空词表没法判定）");
                continue;
            }

            parsed.add(new Rule(action, name, threshold, str(m.get("note")), words));
        }

        return new ParseResult(parsed, warnings);
    }

    /**
     * 规则对象 → 可直接写进配置文件的映射结构。
     *
     * <p>存在的理由：网页端保存时统一走"视图 → 解析校验 → 规范化结构 → 落盘"，
     * 落盘的永远是解析器认得的那份，不会出现"能存进去、却读不出来"。
     */
    public static List<Map<String, Object>> toRuleMaps(List<Rule> rules) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rules == null) {
            return list;
        }
        for (Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", r.action);
            m.put("name", r.name);
            m.put("threshold", r.threshold);
            if (r.note != null && !r.note.isBlank()) {
                // note 为空就不写这一行，免得文件里出现一堆 note: ""
                m.put("note", r.note);
            }
            m.put("words", r.words);
            list.add(m);
        }
        return list;
    }

    // ==================================================================
    // 加载与判定
    // ==================================================================

    /**
     * 用给定的规则结构替换当前生效的规则。
     *
     * <p>没有无参重载是刻意的：规则必须由调用方按"当前生效的配置"读出来再喂进来，
     * 留一个"自己去读某个默认文件"的版本，迟早有人踩到"配置换了、规则还是旧的"。
     *
     * @param rawRules 规则列表结构（可为 null / 空，表示这套配置不启用过滤）
     */
    public synchronized void reloadFrom(List<?> rawRules) {
        ParseResult parsed = parseRules(rawRules);
        for (String w : parsed.warnings) {
            log.warn("JD 规则：{}", w);
        }
        this.rules = parsed.rules;
        this.everLoaded = true;
        log.info("已加载 JD 规则：共 {} 组（reject {} / require {} / warn {}）",
                parsed.rules.size(), countByAction(parsed.rules, ACTION_REJECT),
                countByAction(parsed.rules, ACTION_REQUIRE), countByAction(parsed.rules, ACTION_WARN));
    }

    /** 当前生效的规则快照（只读展示用，不影响判定） */
    public List<Rule> snapshot() {
        return List.copyOf(rules);
    }

    /** 是否曾经加载过规则（即使加载结果是空列表） */
    public boolean hasLoaded() {
        return everLoaded;
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

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 动作是否合法 */
    private static boolean isValidAction(String action) {
        return ACTION_REJECT.equals(action) || ACTION_REQUIRE.equals(action) || ACTION_WARN.equals(action);
    }

    /** 取字符串：null → 空串，其余 trim（结构里写成数字/布尔也照样收） */
    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static int countByAction(List<Rule> list, String action) {
        int n = 0;
        for (Rule rule : list) {
            if (action.equals(rule.action)) {
                n++;
            }
        }
        return n;
    }
}
