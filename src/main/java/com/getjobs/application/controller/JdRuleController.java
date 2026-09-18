package com.getjobs.application.controller;

import com.getjobs.application.service.JdRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JD 过滤规则的编辑接口（网页端「配置 → 过滤规则」）。
 *
 * <ul>
 *   <li>{@code GET  /api/boss/jd-rules} —— 读当前配置的规则（结构化列表 + 统计 + 语法告警）</li>
 *   <li>{@code PUT  /api/boss/jd-rules} —— 用整份规则列表覆盖保存（body: {@code {"rules":[...]}}）</li>
 * </ul>
 *
 * <p><b>为什么是"整份覆盖"而不是按条增删</b>：规则只有几组、体量极小，
 * 整份覆盖既不用处理"并发改同一条"，也不用为删除单独设计一套接口 ——
 * 前端拿到的列表就是唯一真相，改完把整份提交回来。
 *
 * <p><b>规则就存在当前生效的配置文件里</b>（{@code jd_rules} 段），不是独立文件 ——
 * 所以切换配置天然就切换了规则，另存为 / 改名 / 删除配置也天然把规则一起带走。
 * 具体读写由 {@code JdRuleService} / {@code ConfigFileService} 按 {@code config/.active} 定位。
 *
 * <p>保存后不需要重启：投递任务开始时执行 {@code JdRuleFilter#reloadFrom(List)}，
 * 下一次点「开始投递」读到的就是新规则。这里刻意<b>不</b>去动正在跑的那份规则快照，
 * 避免一轮投递中途规则被换掉。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/jd-rules")
@CrossOrigin(origins = "*")
public class JdRuleController {

    private final JdRuleService jdRuleService;

    public JdRuleController(JdRuleService jdRuleService) {
        this.jdRuleService = jdRuleService;
    }

    /** 读取规则与解析结果 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> load() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("data", jdRuleService.load());
            response.put("message", "获取 JD 规则成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取 JD 规则失败", e);
            response.put("success", false);
            response.put("message", "获取 JD 规则失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 保存规则列表（body: {"rules":[{"action","name","threshold","note","words"}]}） */
    @PutMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        Object raw = body == null ? null : body.get("rules");
        if (!(raw instanceof List<?> list)) {
            response.put("success", false);
            response.put("message", "缺少 rules 字段（应为规则列表；要清空过滤请传空列表 []）");
            return ResponseEntity.badRequest().body(response);
        }

        List<Map<String, Object>> views = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> view = new HashMap<>();
                map.forEach((k, v) -> view.put(String.valueOf(k), v));
                views.add(view);
            } else {
                response.put("success", false);
                response.put("message", "rules 里混入了非对象元素，已拒绝保存");
                return ResponseEntity.badRequest().body(response);
            }
        }

        try {
            response.put("success", true);
            response.put("data", jdRuleService.save(views));
            response.put("message", "已保存（下一次点「开始投递」即生效）");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // 校验后一条规则都不剩：这正是"看着保存成功、实际规则全空"的来源，必须报出来
            log.warn("JD 规则校验未通过：{}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("保存 JD 规则失败", e);
            response.put("success", false);
            response.put("message", "保存 JD 规则失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
