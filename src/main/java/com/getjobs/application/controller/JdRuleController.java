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

import java.util.HashMap;
import java.util.Map;

/**
 * JD 过滤规则文件的编辑接口（网页端「配置 → 过滤规则」）。
 *
 * <ul>
 *   <li>{@code GET  /api/boss/jd-rules} —— 读原文 + 解析结果（规则组、阈值、词数、语法告警）</li>
 *   <li>{@code PUT  /api/boss/jd-rules} —— 覆盖写 jd-rules.txt，并回传解析结果</li>
 * </ul>
 *
 * <p>保存后不需要重启：{@code JdRuleFilter.reload()} 在每次投递任务开始时执行，
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

    /** 读取规则文件与解析结果 */
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

    /** 保存规则文件（body: {"text": "..."}） */
    @PutMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        String text = body == null ? null : body.get("text");
        if (text == null) {
            response.put("success", false);
            response.put("message", "缺少 text 字段（要写入的规则文件全文，可以为空串但必须有）");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            response.put("success", true);
            response.put("data", jdRuleService.save(text));
            response.put("message", "已保存到 jd-rules.txt");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存 JD 规则失败", e);
            response.put("success", false);
            response.put("message", "保存 JD 规则失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
