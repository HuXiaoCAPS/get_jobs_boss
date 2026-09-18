package com.getjobs.application.service;

import com.getjobs.worker.platform.filter.JdRuleFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JD 过滤规则文件的读写服务 —— 网页端「配置 → 过滤规则」用它把
 * {@code jd-rules.txt} 当文本编辑。
 *
 * <p><b>为什么不做结构化编辑</b>：规则文件里的注释就是语法文档（三种动作的含义、
 * 判定顺序、哪些词容易误伤都写在注释里）。结构化卡片一存盘就会把注释洗掉，
 * 所以这里只做「原文进、原文出」，另附一份解析结果给前端做校验回显。
 *
 * <p><b>权威来源没有变</b>：网页端保存 = 直接覆盖写项目根目录的 {@code jd-rules.txt}，
 * 用编辑器改和用网页改改的是同一个文件。{@link JdRuleFilter} 在每次投递任务开始时
 * reload()，所以保存后下一次点「开始投递」即生效，不需要重启程序。
 */
@Slf4j
@Service
public class JdRuleService {

    /** 规则文件名，与 {@link JdRuleFilter#DEFAULT_FILE} 保持一致 */
    private static final String FILE = JdRuleFilter.DEFAULT_FILE;

    /**
     * 读取规则原文 + 解析结果。
     *
     * <p>纯读，不会创建文件：文件不存在时返回空文本（{@code source=missing}），
     * 前端据此显示"还没建规则文件"。
     */
    public Map<String, Object> load() {
        Path local = JdRuleFilter.localFile(FILE);
        boolean localExists = Files.isRegularFile(local);
        List<String> lines = JdRuleFilter.readRuleFile(FILE);

        String source;
        String text;
        if (lines == null) {
            // 读异常（被占用、编码损坏…）：不编造内容，如实告诉前端
            source = "unreadable";
            text = "";
        } else if (localExists) {
            source = "file";
            text = String.join("\n", lines);
        } else if (!lines.isEmpty()) {
            // 工作目录没有，读的是 classpath（resources）里那份
            source = "classpath";
            text = String.join("\n", lines);
        } else {
            source = "missing";
            text = "";
        }
        return describe(text, local, source);
    }

    /**
     * 保存规则原文（覆盖写工作目录的 {@code jd-rules.txt}），并回传解析结果。
     *
     * @param text 规则文件全文；null 视作空文件
     */
    public Map<String, Object> save(String text) throws IOException {
        String raw = text == null ? "" : text;
        // 统一成 \n：浏览器 textarea 提交的是 \n，Windows 编辑器存的是 \r\n，
        // 混着写会让 diff 全是看不到的噪声
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        Path local = JdRuleFilter.localFile(FILE);
        Files.writeString(local, normalized, StandardCharsets.UTF_8);
        log.info("已保存 JD 规则文件 {}（{} 行）", local.toAbsolutePath(), countLines(normalized));
        return describe(normalized, local, "file");
    }

    /** 组装给前端的视图：原文 + 路径 + 来源 + 解析结果 */
    private Map<String, Object> describe(String text, Path path, String source) {
        JdRuleFilter.ParseResult parsed = JdRuleFilter.parseText(splitLines(text));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("reject", 0);
        counts.put("require", 0);
        counts.put("warn", 0);

        List<Map<String, Object>> rules = new ArrayList<>();
        for (JdRuleFilter.Rule rule : parsed.rules) {
            counts.computeIfPresent(rule.action, (k, v) -> v + 1);
            rules.add(ruleView(rule));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("path", path.toAbsolutePath().toString());
        data.put("source", source);
        data.put("rules", rules);
        data.put("counts", counts);
        data.put("warnings", parsed.warnings);
        return data;
    }

    private Map<String, Object> ruleView(JdRuleFilter.Rule rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", rule.action);
        view.put("name", rule.name);
        view.put("threshold", rule.threshold);
        view.put("count", rule.words.size());
        view.put("words", rule.words);
        view.put("describe", rule.describe());
        return view;
    }

    /** 按 \n 切行；空文本给空列表（避免 parse 收到 [""]） */
    private List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }

    private int countLines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }
}
