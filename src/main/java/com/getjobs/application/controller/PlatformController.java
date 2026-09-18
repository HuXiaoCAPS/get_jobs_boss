package com.getjobs.application.controller;

import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.JobPlatform;
import com.getjobs.worker.platform.PlatformRegistry;
import com.getjobs.worker.platform.PlatformTaskManager;
import com.getjobs.worker.platform.model.JobPage;
import com.getjobs.worker.platform.model.JobQuery;
import com.getjobs.worker.platform.model.JobStats;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台接口：列表 + 投递任务 + <b>岗位数据浏览</b>。
 *
 * <p>全平台共用一套路径（{@code /api/platforms/{id}/...}），前端只要遍历
 * {@code GET /api/platforms} 就能渲染出"哪个平台、在不在跑、要不要开始、有哪些岗位"，
 * <b>不需要为每个平台写一个页面，也不需要知道任何平台的细节</b>。
 *
 * <p>这是"插件式"的对外体现：
 * <ul>
 *   <li>删掉某个平台的代码 → 列表里自然没有它，其余平台照常；</li>
 *   <li>一个平台都没有 → 列表为空、页面提示"没有可用平台"，应用仍正常运行；</li>
 *   <li>加平台 → 前端零改动。</li>
 * </ul>
 *
 * <p><b>岗位数据为什么也放在这里</b>：{@code /api/boss/list} 这类接口把
 * {@code boss_data} 的表结构写死在了路径与响应里，网页端「数据」页因此只认 Boss
 * （换平台就得重写页面）。改走 {@code /api/platforms/{id}/jobs|stats} 后，
 * 页面面对的是 {@link JobPage} / {@link JobStats} 这类平台无关模型，
 * 真正的查询由各平台自己的 {@link DeliveryStore} 实现 —— 加平台时前端零改动。
 *
 * <p>平台没实现数据浏览时返回 {@code 501 Not Implemented}，而不是一个空列表：
 * 空列表会让人以为"真的没数据"。
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

    // ------------------------------------------------------------------
    // 岗位数据浏览（网页端「数据」页）
    // ------------------------------------------------------------------

    /**
     * 岗位列表（分页 + 筛选），返回平台无关的 {@link JobPage}。
     *
     * <p>各平台的数据源不同，所以真正的查询由平台自己的 {@code DeliveryStore} 实现；
     * 这里只负责把请求参数翻译成 {@link JobQuery} 并把结果包一层响应格式。
     */
    @GetMapping("/{id}/jobs")
    public ResponseEntity<Map<String, Object>> jobs(
            @PathVariable("id") String id,
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "salaryUnit", required = false) String salaryUnit,
            @RequestParam(value = "filterHeadhunter", required = false) Boolean filterHeadhunter,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {

        DeliveryStore store = storeOf(id);
        if (store == null) {
            return notFound(id);
        }
        JobQuery query = toQuery(statuses, location, experience, degree, keyword,
                minK, maxK, salaryUnit, filterHeadhunter, page, size);
        JobPage result = store.listJobs(id, query);
        if (result == null) {
            return unsupported(id, "查看岗位数据");
        }
        return ok(result);
    }

    /**
     * 岗位数据统计（KPI + 分组 + 薪资档位），返回平台无关的 {@link JobStats}。
     *
     * <p>筛选参数与 {@link #jobs} 完全一致 —— 页面上的筛选条同一份条件同时驱动列表和统计，
     * 否则会出现"列表筛过了、统计还是全量"这种对不上的情况。
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @PathVariable("id") String id,
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "salaryUnit", required = false) String salaryUnit,
            @RequestParam(value = "filterHeadhunter", required = false) Boolean filterHeadhunter) {

        DeliveryStore store = storeOf(id);
        if (store == null) {
            return notFound(id);
        }
        JobQuery query = toQuery(statuses, location, experience, degree, keyword,
                minK, maxK, salaryUnit, filterHeadhunter, 1, 20);
        JobStats result = store.jobStats(id, query);
        if (result == null) {
            return unsupported(id, "查看岗位数据");
        }
        return ok(result);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 取平台的数据实现；平台不存在或没提供 store 时返回 null */
    private DeliveryStore storeOf(String id) {
        return platformRegistry.find(id).map(JobPlatform::store).orElse(null);
    }

    /** 请求参数 → 平台无关的查询条件 */
    private static JobQuery toQuery(String statuses, String location, String experience, String degree,
                                    String keyword, Double minK, Double maxK, String salaryUnit,
                                    Boolean filterHeadhunter, Integer page, Integer size) {
        JobQuery q = new JobQuery();
        q.statuses = parseStatuses(statuses);
        q.location = blankToNull(location);
        q.experience = blankToNull(experience);
        q.degree = blankToNull(degree);
        q.keyword = blankToNull(keyword);
        q.minK = minK;
        q.maxK = maxK;
        // 只认 DAY，其余（含不传）一律按月薪 —— 免得拼错个单位就把筛选语义悄悄改掉
        q.salaryUnit = "DAY".equalsIgnoreCase(salaryUnit) ? "DAY" : "MONTH";
        q.filterHeadhunter = filterHeadhunter != null && filterHeadhunter;
        q.page = page == null ? 1 : page;
        q.size = size == null ? 20 : size;
        return q;
    }

    /** {@code "已投递,已过滤"} → 列表；空串按"不限"处理 */
    private static List<String> parseStatuses(String statuses) {
        if (statuses == null || statuses.isBlank()) {
            return null;
        }
        List<String> list = Arrays.stream(statuses.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        return list.isEmpty() ? null : list;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", "没有这个平台：" + id);
        return ResponseEntity.status(404).body(result);
    }

    /**
     * 平台不支持这项能力时的回应。
     *
     * <p>用 501 而不是"200 + 空数据"：空数据会让调用方以为"确实没有"，而事实是
     * "这个平台还没实现"。两者在排查时是完全不同的结论。
     */
    private static ResponseEntity<Map<String, Object>> unsupported(String id, String what) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", "平台 " + id + " 暂不支持" + what + "（未实现该数据能力）");
        return ResponseEntity.status(501).body(result);
    }
}
