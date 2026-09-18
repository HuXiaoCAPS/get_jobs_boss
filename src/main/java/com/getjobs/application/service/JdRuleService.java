package com.getjobs.application.service;

import com.getjobs.worker.platform.filter.JdRuleFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JD 过滤规则的读写服务 —— 网页端「配置 → 过滤规则」用它按「一条规则」为单位增删改。
 *
 * <p><b>规则存在哪</b>：当前生效的配置文件里的 {@code jd_rules} 段，<b>不是</b>独立文件。
 * 于是"切换配置"天然就切换了规则，"另存为 / 改名 / 删除"天然把规则一起带走 ——
 * 没有任何一份规则需要单独跟着搬（这是早先"两个 yaml 靠命名约定关联"那套的替代）。
 *
 * <pre>
 * config/boss.yaml        → 里面的 jd_rules: [...]
 * config/数据开发.yaml    → 里面的 jd_rules: [...]
 * </pre>
 *
 * <p><b>校验只有一条路径</b>：保存时先解析网页端传来的结构，再把它规范化后写回，
 * 最后<b>重新读一遍</b>交给前端。落盘的永远是"能读出来的那一份"，
 * 不会出现"存进去了、读出来是空的"。
 *
 * <p><b>权威来源没有变</b>：规则以配置文件为准，用编辑器改和用网页改改的是同一处。
 * {@code DeliveryRunner} 在每轮投递开始时重新加载，所以保存后下一次点「开始投递」即生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdRuleService {

    private final ConfigFileService configFileService;

    /**
     * 读取当前配置的规则（结构化），并给出数据来源。
     *
     * <p>纯读，不写文件：前端据此区分"还没配规则"和"配置文件读不出来"。
     */
    public Map<String, Object> load() {
        Map<String, Object> root = configFileService.read();
        boolean exists = configFileService.exists();
        // 文件在、但整份读出来是空的 → 多半是 YAML 语法错了（ConfigFileService 会 log.warn）。
        // 这必须说出来，不能静默当成"没配规则"，否则用户会以为规则被清掉了。
        String source = !exists ? "missing" : root.isEmpty() ? "unreadable" : "file";
        return describe(JdRuleFilter.parseRules(configFileService.readJdRules()), source);
    }

    /**
     * 保存规则（写回当前配置文件的 {@code jd_rules} 段，其余段落不动）。
     *
     * @param views 网页端传来的规则列表，每项是 {@code {action, name, threshold, note, words}}
     * @throws IllegalArgumentException 校验后一条规则都不剩、且本来就有语法问题
     *                                  （避免"看着保存成功、实际规则全空"）
     */
    public Map<String, Object> save(List<Map<String, Object>> views) {
        JdRuleFilter.ParseResult parsed = JdRuleFilter.parseRules(views);

        if (parsed.rules.isEmpty()) {
            if (!parsed.warnings.isEmpty()) {
                throw new IllegalArgumentException("规则校验未通过：" + String.join("；", parsed.warnings));
            }
            log.info("保存的规则列表为空 —— 这套配置将不再过滤任何岗位");
        }

        if (!configFileService.writeJdRules(JdRuleFilter.toRuleMaps(parsed.rules))) {
            throw new IllegalStateException("写入配置文件失败，规则未保存");
        }

        // 写完后重新读回来：前端看到的必须就是"下次投递会用到的那一份"
        return describe(JdRuleFilter.parseRules(configFileService.readJdRules()), "file");
    }

    // ==================== 组装给前端的视图 ====================

    /** 规则列表 + 归属配置 + 来源 + 统计 + 告警 */
    private Map<String, Object> describe(JdRuleFilter.ParseResult parsed, String source) {
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
        data.put("source", source);
        // 让前端能显示"这套规则属于哪个配置"，换配置后不会看错
        data.put("profile", configFileService.activeName());
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
        view.put("note", rule.note == null ? "" : rule.note);
        view.put("count", rule.words.size());
        view.put("words", rule.words);
        view.put("describe", rule.describe());
        return view;
    }
}
