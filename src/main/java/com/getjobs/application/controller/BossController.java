package com.getjobs.application.controller;

import com.getjobs.application.service.CookieService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.platform.PlatformTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Boss 平台控制器：管理页用的 {@code /api/boss/*} 接口。
 *
 * <p><b>底层已经走平台无关的任务壳</b>（{@link PlatformTaskManager}）：启动/停止/状态都不再
 * 由 Boss 专属的 JobService 处理，而是统一交给任务壳 + {@code DeliveryRunner}。
 *
 * <p><b>这里只留 Boss 独有的、平台无关层表达不了的东西</b>：
 * <ul>
 *   <li>登录态（{@code /status} 里的 {@code isLoggedIn}）—— 平台无关层不知道"登录"是啥；</li>
 *   <li>退出登录（{@code /logout}）—— 要同时清数据库 Cookie 与浏览器上下文 Cookie，是 Boss 的做法。</li>
 * </ul>
 * 其余投递控制的权威入口是 {@code /api/platforms/{id}/*}（见 {@code PlatformController}），
 * 前端「投递」页只走那条。历史上这里还有 {@code /api/boss/stream}（旧版进度 SSE）和
 * {@code /api/boss/execute}（旧版启动）两个端点，属于多平台时代的残留：进度已由
 * {@code /api/platforms/{id}/stream} 提供，启动已由 {@code /api/platforms/{id}/start}
 * 提供，因此已删除（连同本类里维护 SSE 订阅者、心跳、ObjectMapper 的那一整套）。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BossController {

    /** 与管理页 / 前端约定的平台 id（本控制器只管 Boss） */
    private static final String PLATFORM = "boss";

    private final PlatformTaskManager taskManager;
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;

    /** POST - 启动Boss投递任务（管理页使用的接口） */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBoss(
            @RequestParam(value = "max", defaultValue = "0") int max) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                response.put("success", false);
                response.put("message", "请先登录Boss直聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }
            Map<String, Object> result = taskManager.start(PLATFORM, max);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                response.put("success", false);
                response.put("message", result.get("message"));
                response.put("status", "running");
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", true);
            response.put("message", "Boss任务启动成功");
            response.put("status", "started");
            log.info("通过API启动Boss任务成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("启动Boss任务失败", e);
            response.put("success", false);
            response.put("message", "启动Boss任务失败: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 停止Boss投递任务 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = taskManager.stop(PLATFORM);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                response.put("success", false);
                response.put("message", result.get("message"));
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", true);
            response.put("message", "Boss任务停止请求已发送");
            log.info("通过API停止Boss任务");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("停止Boss任务失败", e);
            response.put("success", false);
            response.put("message", "停止Boss任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 退出Boss登录 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.setLoginStatus(PLATFORM, false);
            cookieService.clearCookieByPlatform(PLATFORM, "manual logout");
            try {
                playwrightManager.clearBossCookies();
            } catch (Exception e) {
                log.warn("清理Boss上下文Cookie异常: {}", e.getMessage());
            }
            response.put("success", true);
            response.put("message", "Boss已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** GET - 获取Boss任务状态（管理页用；含登录态） */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getBossStatus() {
        Map<String, Object> status = new HashMap<>(taskManager.status(PLATFORM));
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return ResponseEntity.ok(status);
    }
}
