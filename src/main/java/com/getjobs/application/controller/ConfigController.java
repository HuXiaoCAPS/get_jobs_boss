package com.getjobs.application.controller;

import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置控制器
 * 提供配置管理的REST API接口
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @Autowired
    private BossService bossService;

    /**
     * 获取所有配置
     * @return 配置Map
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllConfigs() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, String> configs = configService.getAllConfigsAsMap();

            response.put("success", true);
            response.put("data", configs);
            response.put("message", "获取配置成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取配置失败", e);
            response.put("success", false);
            response.put("message", "获取配置失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 根据配置键获取单个配置
     * @param key 配置键
     * @return 配置值
     */
    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getConfigByKey(@PathVariable String key) {
        Map<String, Object> response = new HashMap<>();

        try {
            var config = configService.getConfigByKey(key);

            if (config != null) {
                response.put("success", true);
                response.put("data", config);
                response.put("message", "获取配置成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "配置不存在");
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("获取配置失败: {}", key, e);
            response.put("success", false);
            response.put("message", "获取配置失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 批量更新配置
     * @param configMap 配置Map，key为config_key，value为config_value
     * @return 更新结果
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> batchUpdateConfigs(@RequestBody Map<String, String> configMap) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (configMap == null || configMap.isEmpty()) {
                response.put("success", false);
                response.put("message", "配置数据不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            int updateCount = configService.batchUpdateConfigs(configMap);

            // 通知相关的键同样要落进 config/boss.yaml —— 配置以文件为权威，
            // 只在网页端改库的话，下次 syncConfigFromFile() 会用文件里的旧值覆盖回去。
            // （BASE_URL / API_KEY / MODEL 刻意不在这里回写：它们只允许在配置文件里改）
            if (configMap.containsKey("HOOK_URL") || configMap.containsKey("BOT_IS_SEND")) {
                bossService.saveAiAndNotifyToFile(null, null, configMap.get("HOOK_URL"), configMap.get("BOT_IS_SEND"));
            }

            response.put("success", true);
            response.put("message", "配置更新成功");
            response.put("updateCount", updateCount);

            log.info("批量更新配置成功，共更新 {} 项", updateCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("批量更新配置失败", e);
            response.put("success", false);
            response.put("message", "配置更新失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 更新单个配置
     * @param key 配置键
     * @param requestBody 请求体包含value
     * @return 更新结果
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @PathVariable String key,
            @RequestBody Map<String, String> requestBody) {

        Map<String, Object> response = new HashMap<>();

        try {
            String value = requestBody.get("value");

            if (value == null) {
                response.put("success", false);
                response.put("message", "配置值不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            boolean success = configService.updateConfig(key, value);

            // 与批量更新同样的理由：通知相关的键要同步写回 config/boss.yaml
            if ("HOOK_URL".equals(key) || "BOT_IS_SEND".equals(key)) {
                bossService.saveAiAndNotifyToFile(
                        null, null,
                        "HOOK_URL".equals(key) ? value : null,
                        "BOT_IS_SEND".equals(key) ? value : null);
            }

            if (success) {
                response.put("success", true);
                response.put("message", "配置更新成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "配置更新失败，配置键可能不存在");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("更新配置失败: {}", key, e);
            response.put("success", false);
            response.put("message", "配置更新失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 健康检查接口
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "ConfigController");
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }
}
