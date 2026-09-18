package com.getjobs.worker.platform;

import com.getjobs.worker.dto.JobProgressMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 平台任务壳 —— 管"某个平台现在是不是在跑"，并把进度推给前端。
 *
 * <p>它取代了原来"每个平台一个 {@code XxxJobService}"的写法（那种写法每加一个平台就要抄一遍
 * 启动/停止/状态/看门狗）。现在这里只有一份逻辑：
 * 从 {@link PlatformRegistry} 找平台 → 用 {@link DeliveryRunner} 跑 → 广播进度 → 维护状态。
 *
 * <p><b>它不知道任何平台的存在</b>：没有 boss / liepin 之类的字眼，也没有 Playwright 类型。
 * 线程约束由平台自己声明（{@link JobPlatform#runTask}）。
 */
@Slf4j
@Component
public class PlatformTaskManager {

    private final PlatformRegistry platformRegistry;
    private final DeliveryRunner deliveryRunner;

    /** 平台 id -> 运行状态 */
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    /** 平台 id -> SSE 订阅者 */
    private final ConcurrentMap<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** 进程内的进度监听器（供旧接口桥接，见 broadcast 的说明） */
    private final List<BiConsumer<String, JobProgressMessage>> progressListeners = new CopyOnWriteArrayList<>();

    /** 停止指令发出后，等待任务自行退出的宽限时间（超过就强制复位状态，让界面能重开） */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;

    public PlatformTaskManager(PlatformRegistry platformRegistry, DeliveryRunner deliveryRunner) {
        this.platformRegistry = platformRegistry;
        this.deliveryRunner = deliveryRunner;
    }

    /** 单个平台的运行状态 */
    public static class TaskState {
        volatile boolean running = false;
        volatile boolean shouldStop = false;
        volatile long startedAt = 0L;
        volatile int maxDeliveries = 0;
        volatile int delivered = 0;
        volatile int filtered = 0;
        volatile int skipped = 0;
        volatile String lastMessage = "";
    }

    /**
     * 启动某个平台的一轮投递。
     *
     * @param platformId    平台 id
     * @param maxDeliveries 本轮最多投出去几个；&lt;= 0 表示不限
     * @return 结果说明（success/message）
     */
    public Map<String, Object> start(String platformId, int maxDeliveries) {
        JobPlatform platform = platformRegistry.find(platformId).orElse(null);
        if (platform == null) {
            return result(false, "平台未注册：" + platformId
                    + "（当前已注册：" + platformRegistry.ids() + "）");
        }

        TaskState state = tasks.computeIfAbsent(platformId, k -> new TaskState());
        synchronized (state) {
            if (state.running) {
                return result(false, "该平台的任务已在运行中");
            }
            state.running = true;
            state.shouldStop = false;
            state.startedAt = System.currentTimeMillis();
            state.maxDeliveries = maxDeliveries;
            state.delivered = 0;
            state.filtered = 0;
            state.skipped = 0;
            state.lastMessage = "任务已启动";
        }

        log.info("[{}] 收到投递请求（本次上限：{}）", platformId, maxDeliveries <= 0 ? "不限" : maxDeliveries);
        Thread worker = new Thread(() -> execute(platform, state, maxDeliveries),
                "platform-task-" + platformId);
        worker.setDaemon(true);
        worker.start();

        return result(true, "任务已启动");
    }

    /** 真正的执行：交给平台自己的线程，再跑流程 */
    private void execute(JobPlatform platform, TaskState state, int maxDeliveries) {
        String platformId = platform.id();
        try {
            platform.runTask(() -> {
                DeliveryRunner.RunResult runResult = deliveryRunner.run(
                        platform,
                        message -> {
                            if (message != null && message.getMessage() != null) {
                                state.lastMessage = message.getMessage();
                            }
                            broadcast(platformId, message);
                        },
                        () -> state.shouldStop,
                        maxDeliveries);
                state.delivered = runResult.delivered;
                state.filtered = runResult.filtered;
                state.skipped = runResult.skipped;
            });
            broadcast(platformId, JobProgressMessage.success(platformId,
                    String.format("投递任务结束：投递 %d 个，过滤 %d 个，跳过 %d 个",
                            state.delivered, state.filtered, state.skipped)));
        } catch (Exception e) {
            log.error("[{}] 投递任务执行失败", platformId, e);
            broadcast(platformId, JobProgressMessage.error(platformId, "投递失败: " + e.getMessage()));
        } finally {
            synchronized (state) {
                state.running = false;
                state.shouldStop = false;
                state.startedAt = 0L;
            }
            log.info("[{}] 任务状态已复位", platformId);
        }
    }

    /** 请求停止：先给任务时间自己退出，超时就强制复位状态 */
    public Map<String, Object> stop(String platformId) {
        TaskState state = tasks.get(platformId);
        if (state == null || !state.running) {
            return result(false, "当前没有正在运行的任务");
        }
        log.info("[{}] 收到停止请求", platformId);
        state.shouldStop = true;

        Thread watchdog = new Thread(() -> {
            long deadline = System.currentTimeMillis() + FORCE_RESET_GRACE_MS;
            while (state.running && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (state.running) {
                log.warn("[{}] 任务在 {} 秒内没响应停止指令，强制复位状态（浏览器里可能还有残留操作）",
                        platformId, FORCE_RESET_GRACE_MS / 1000);
                state.running = false;
                state.startedAt = 0L;
                broadcast(platformId, JobProgressMessage.warning(platformId, "已强制停止（任务未在宽限期内退出）"));
            }
        }, "platform-stop-watchdog-" + platformId);
        watchdog.setDaemon(true);
        watchdog.start();

        return result(true, "停止指令已发送");
    }

    /** 某个平台的状态 */
    public Map<String, Object> status(String platformId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", platformId);
        out.put("registered", platformRegistry.find(platformId).isPresent());

        TaskState state = tasks.get(platformId);
        out.put("isRunning", state != null && state.running);
        // 正在停止：已收到停止指令但任务还没退出（前端据此把按钮变成"正在停止…"，
        // 免得看起来像卡住）
        out.put("stopping", state != null && state.running && state.shouldStop);
        long runningFor = (state == null || state.startedAt == 0L)
                ? 0L : System.currentTimeMillis() - state.startedAt;
        out.put("runningForMillis", runningFor);
        out.put("maxDeliveries", state == null ? 0 : state.maxDeliveries);
        out.put("delivered", state == null ? 0 : state.delivered);
        out.put("filtered", state == null ? 0 : state.filtered);
        out.put("skipped", state == null ? 0 : state.skipped);
        out.put("lastMessage", state == null ? "" : state.lastMessage);
        return out;
    }

    /** 所有已注册平台的状态（投递页一次性拉取） */
    public List<Map<String, Object>> statusAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JobPlatform platform : platformRegistry.all()) {
            Map<String, Object> item = status(platform.id());
            item.put("name", platform.displayName());
            list.add(item);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // SSE 进度推送
    // ------------------------------------------------------------------

    /** 订阅某个平台的进度 */
    public SseEmitter subscribe(String platformId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        subscribers.computeIfAbsent(platformId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeSubscriber(platformId, emitter));
        emitter.onTimeout(() -> removeSubscriber(platformId, emitter));
        emitter.onError(e -> removeSubscriber(platformId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected")
                    .data(Map.of("platform", platformId, "message", "已连接投递进度推送")));
        } catch (IOException e) {
            removeSubscriber(platformId, emitter);
        }
        return emitter;
    }

    /**
     * 注册一个"进程内进度监听器"。
     *
     * <p>用途：旧的 {@code /api/boss/stream} 要把进度转发给老前端，而那条 SSE 通道
     * 由 BossController 自己维护（含心跳）。有了这个钩子，进度只需从一处产生（本类），
     * 两道 SSE 通道都能收到，不用把广播逻辑抄两份。
     */
    public void addProgressListener(BiConsumer<String, JobProgressMessage> listener) {
        if (listener != null) {
            progressListeners.add(listener);
        }
    }

    private void removeSubscriber(String platformId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(platformId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    /** 把一条进度消息推给所有订阅者；推送失败的订阅者直接摘掉 */
    private void broadcast(String platformId, JobProgressMessage message) {
        if (message == null) {
            return;
        }
        // 先通知"进程内监听器"（例如 BossController 的旧版 /api/boss/stream 桥接）
        for (BiConsumer<String, JobProgressMessage> listener : progressListeners) {
            try {
                listener.accept(platformId, message);
            } catch (Exception e) {
                log.debug("进度监听器异常：{}", e.getMessage());
            }
        }
        List<SseEmitter> list = subscribers.get(platformId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(message));
            } catch (Exception e) {
                removeSubscriber(platformId, emitter);
            }
        }
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("message", message);
        return out;
    }
}
