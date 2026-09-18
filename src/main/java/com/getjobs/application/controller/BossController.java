package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.platform.PlatformTaskManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss 平台控制器：管理页用的旧接口（{@code /api/boss/*}）。
 *
 * <p><b>底层已经走平台无关的任务壳</b>（{@link PlatformTaskManager}）：启动/停止/状态都不再
 * 由 Boss 专属的 JobService 处理，而是统一交给任务壳 + {@code DeliveryRunner}。
 * 保留这些路径只是为了不让旧管理页（已构建进 dist）的按钮失效 —— 新前端请用
 * {@code /api/platforms/{id}/*}。
 *
 * <p>登录态与退出登录仍然是 Boss 特有的，留在这里。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BossController {

    /** 与管理页 / 前端约定的平台 id（本控制器只管 Boss） */
    private static final String PLATFORM = "boss";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final PlatformTaskManager taskManager;
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;

    /** 旧版进度 SSE 的订阅者（含心跳，见 heartbeatBossProgress） */
    private final List<SseEmitter> bossProgressEmitters = new CopyOnWriteArrayList<>();

    /**
     * 把任务壳产生的进度转发到旧的 SSE 通道。
     *
     * <p>这样进度只在任务壳一处产生，两条 SSE（旧的 {@code /api/boss/stream}
     * 与新的 {@code /api/platforms/{id}/stream}）都能收到，不用抄两份广播逻辑。
     */
    @PostConstruct
    void bridgeProgress() {
        taskManager.addProgressListener((platformId, message) -> {
            if (PLATFORM.equals(platformId)) {
                if (message != null && message.getMessage() != null) {
                    log.info("[{}] {}", platformId, message.getMessage());
                }
                sendBossProgress(message);
            }
        });
    }

    /** SSE - Boss投递任务进度推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBossProgress() {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        bossProgressEmitters.add(emitter);

        emitter.onCompletion(() -> {
            log.info("Boss进度SSE连接已完成");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.info("Boss进度SSE连接超时");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("Boss进度SSE连接错误", e);
            bossProgressEmitters.remove(emitter);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到Boss投递进度推送")));
        } catch (IOException e) {
            log.error("发送SSE连接消息失败", e);
        }
        return emitter;
    }

    /** POST - 启动Boss投递任务（旧路径，等价于 /start） */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeBoss() {
        Map<String, Object> result = taskManager.start(PLATFORM, 0);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(Map.of("status", "started", "message", result.get("message")));
        }
        return ResponseEntity.ok(Map.of("status", "already_running", "message", result.get("message")));
    }

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

    private void sendBossProgress(JobProgressMessage message) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(message)));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开，移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度消息失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }

    /** 心跳 - Boss进度 SSE */
    @Scheduled(fixedRate = 30000)
    public void heartbeatBossProgress() {
        if (bossProgressEmitters.isEmpty()) return;
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开（心跳），移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度心跳失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }
}
