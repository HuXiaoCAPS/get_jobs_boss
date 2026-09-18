package com.getjobs.application.controller;

import com.getjobs.worker.platform.JobPlatform;
import com.getjobs.worker.platform.PlatformRegistry;
import com.getjobs.worker.platform.PlatformTaskManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台接口：列表 + 投递任务的启动/停止/状态/进度。
 *
 * <p>全平台共用一套路径（{@code /api/platforms/{id}/...}），前端只要遍历
 * {@code GET /api/platforms} 就能渲染出"哪个平台、在不在跑、要不要开始"，
 * <b>不需要为每个平台写一个页面，也不需要知道任何平台的细节</b>。
 *
 * <p>这是"插件式"的对外体现：
 * <ul>
 *   <li>删掉某个平台的代码 → 列表里自然没有它，其余平台照常；</li>
 *   <li>一个平台都没有 → 列表为空、页面提示"没有可用平台"，应用仍正常运行；</li>
 *   <li>加平台 → 前端零改动。</li>
 * </ul>
 *
 * <p><b>登录态刻意不在这里返回</b>：{@code isLoggedIn()} 会碰浏览器页面，
 * 而本接口跑在 HTTP 线程上、Playwright 对象只能在专用线程使用
 * （登录态请走各平台自己的接口，例如 Boss 的 {@code /api/boss/status}）。
 */
@RestController
@RequestMapping("/api/platforms")
@CrossOrigin(origins = "*")
public class PlatformController {

    private final PlatformRegistry platformRegistry;
    private final PlatformTaskManager taskManager;

    public PlatformController(PlatformRegistry platformRegistry, PlatformTaskManager taskManager) {
        this.platformRegistry = platformRegistry;
        this.taskManager = taskManager;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JobPlatform platform : platformRegistry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", platform.id());
            item.put("name", platform.displayName());
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("count", items.size());
        result.put("platforms", items);
        if (items.isEmpty()) {
            result.put("message", "当前没有注册任何平台（应用仍可正常运行）");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 投递任务
    // ------------------------------------------------------------------

    /** 状态：单个平台 */
    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable("id") String id) {
        return taskManager.status(id);
    }

    /** 状态：所有已注册平台（投递页一次拉全） */
    @GetMapping("/status")
    public Map<String, Object> statusAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("platforms", taskManager.statusAll());
        return result;
    }

    /**
     * 开始投递。
     *
     * @param max 本次最多投出去几个；不传或 &lt;= 0 表示不限
     */
    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable("id") String id,
                                     @RequestParam(value = "max", defaultValue = "0") int max) {
        return taskManager.start(id, max);
    }

    /** 停止投递 */
    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable("id") String id) {
        return taskManager.stop(id);
    }

    /** 进度推送（SSE）：前端用 EventSource 接 */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String id) {
        return taskManager.subscribe(id);
    }
}
