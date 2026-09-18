package com.getjobs.application.controller;

import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件的列出 / 切换 / 新建 / 重命名 / 删除接口（网页端「配置」页顶部的配置文件选择器）。
 *
 * <ul>
 *   <li>{@code GET    /api/boss/config-files} —— 列出 config/ 下所有配置 + 哪个在生效</li>
 *   <li>{@code POST   /api/boss/config-files/switch} —— 切换当前生效的配置（网页端「选择」）</li>
 *   <li>{@code POST   /api/boss/config-files/save-as} —— 另存为（复制当前配置 → 切过去）</li>
 *   <li>{@code POST   /api/boss/config-files/import} —— 载入外部 YAML，覆盖当前配置</li>
 *   <li>{@code POST   /api/boss/config-files} —— 新建（可复制现有配置）</li>
 *   <li>{@code POST   /api/boss/config-files/rename} —— 重命名</li>
 *   <li>{@code DELETE /api/boss/config-files} —— 删除（当前生效的不许删）</li>
 * </ul>
 *
 * <p><b>网页端刻意看不到"文件"</b>：用户面对的是「一份配置」而不是 `xxx.yaml`。
 * 后缀由后端补（{@link #withYamlSuffix(String)}），提示语里也不出现文件名 ——
 * 配置就是配置，不该让人操心它落在磁盘上叫什么。
 *
 * <p><b>切换后立刻生效</b>：配置以文件为权威，而库里的 boss_config / ai / config 三张表是
 * {@link BossService#syncConfigFromFile()} 按文件刷出来的。切完若不立刻同步，配置页会继续显示
 * 上一份配置的值直到下次投递 —— 所以切换成功后这里主动同步一次。
 *
 * <p><b>规则跟着配置走，而且不需要额外代码</b>：JD 过滤规则是配置文件里的
 * {@code jd_rules} 段（不是独立文件），所以新建 / 另存为 / 重命名 / 删除配置时，
 * 规则天然跟着一起走 —— 这里<b>不需要</b>任何"顺带搬一下规则"的联动逻辑。
 * 早先把规则放在独立文件时，这里得配套"复制 / 改名 / 删除"三处代码，
 * 还得防着孤儿规则文件，那正是"两个 yaml"带来的复杂度。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/config-files")
@CrossOrigin(origins = "*")
public class ConfigFileController {

    private final ConfigFileService configFileService;
    private final BossService bossService;

    public ConfigFileController(ConfigFileService configFileService,
                                BossService bossService) {
        this.configFileService = configFileService;
        this.bossService = bossService;
    }

    /** 列出所有配置文件与当前生效的那份 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        try {
            String active = configFileService.activeName();
            List<Map<String, Object>> files = new ArrayList<>();
            for (String name : configFileService.listNames()) {
                files.add(describe(name, active));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("active", active);
            data.put("dir", configFileService.dir().toAbsolutePath().toString());
            data.put("files", files);
            return ok(data, "获取配置文件列表成功");
        } catch (Exception e) {
            log.error("获取配置文件列表失败", e);
            return fail("获取配置文件列表失败: " + e.getMessage());
        }
    }

    /** 切换当前生效的配置文件（body: {"name": "数据开发.yaml"}） */
    @PostMapping("/switch")
    public ResponseEntity<Map<String, Object>> switchTo(@RequestBody Map<String, String> body) {
        String name = value(body, "name");
        if (name == null) {
            return badRequest("缺少 name 字段");
        }
        try {
            if (!configFileService.switchTo(name)) {
                return badRequest("切换失败：文件不存在或文件名非法（" + name + "）");
            }
            // 立刻把新配置刷进库，否则配置页还显示上一份的值
            bossService.syncConfigFromFile();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("active", configFileService.activeName());
            data.put("files", listFiles());
            return ok(data, "已切换到 " + configFileService.activeName());
        } catch (Exception e) {
            log.error("切换配置文件失败: {}", name, e);
            return fail("切换配置文件失败: " + e.getMessage());
        }
    }

    /** 新建配置文件（body: {"name": "数据开发.yaml", "copyFrom": "boss.yaml"}；copyFrom 可省） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String name = value(body, "name");
        if (name == null) {
            return badRequest("缺少 name 字段");
        }
        try {
            if (!configFileService.create(name, value(body, "copyFrom"))) {
                return badRequest("新建失败：同名文件已存在或文件名非法（" + name + "）");
            }
            return ok(listFiles(), "已新建配置文件 " + name);
        } catch (Exception e) {
            log.error("新建配置文件失败: {}", name, e);
            return fail("新建配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 另存为：以<b>当前生效</b>的配置为模板复制成新的一份，并立刻切过去
     * （body: {@code {"name": "数据开发"}}）。
     *
     * <p>与 {@link #create} 的区别只在两处：固定复制"当前"这份、复制完自动切过去 ——
     * 网页端按「另存为」的意思就是"存一份，然后接着改它"。
     */
    @PostMapping("/save-as")
    public ResponseEntity<Map<String, Object>> saveAs(@RequestBody Map<String, String> body) {
        String name = withYamlSuffix(value(body, "name"));
        if (name == null) {
            return badRequest("缺少 name 字段");
        }
        String from = configFileService.activeName();
        try {
            if (!configFileService.create(name, from)) {
                return badRequest("另存为失败：同名文件已存在或文件名非法（" + name + "）");
            }
            if (!configFileService.switchTo(name)) {
                return fail("已新建 " + name + "，但切换失败");
            }
            // 切完立刻刷库，否则配置页还显示上一份的值
            bossService.syncConfigFromFile();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("active", configFileService.activeName());
            data.put("files", listFiles());
            return ok(data, "已另存为 " + name + " 并切换过去");
        } catch (Exception e) {
            log.error("另存为失败: {}", name, e);
            return fail("另存为失败: " + e.getMessage());
        }
    }

    /**
     * 载入外部 YAML，<b>覆盖当前配置</b>（body: {@code {"content": "<YAML 全文>"}}）。
     *
     * <p>覆盖不可撤销（原内容不留副本），所以前端必须先弹确认。
     * 内容校验交给 {@link ConfigFileService#writeRaw(String)} —— 不合法就整份拒绝，
     * 绝不把半截内容写进去。
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importConfig(@RequestBody Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        if (content == null || content.isBlank()) {
            return badRequest("缺少 content 字段（要导入的 YAML 全文）");
        }
        try {
            if (!configFileService.writeRaw(content)) {
                return badRequest("导入失败：内容不是合法的 YAML 映射（顶层应有 search / delivery / ai / notify 等键），"
                        + "当前配置未改动");
            }
            bossService.syncConfigFromFile();
            return ok(listFiles(), "已导入并覆盖当前配置");
        } catch (Exception e) {
            log.error("导入配置失败", e);
            return fail("导入配置失败: " + e.getMessage());
        }
    }

    /** 自动补 .yaml 后缀（网页端不暴露后缀，由这里统一补齐） */
    private static String withYamlSuffix(String name) {
        if (name == null) {
            return null;
        }
        return name.endsWith(".yaml") ? name : name + ".yaml";
    }

    /** 重命名配置文件（body: {"from": "a.yaml", "to": "b.yaml"}），规则随配置一起改 */
    @PostMapping("/rename")
    public ResponseEntity<Map<String, Object>> rename(@RequestBody Map<String, String> body) {
        String from = value(body, "from");
        String to = value(body, "to");
        if (from == null || to == null) {
            return badRequest("缺少 from / to 字段");
        }
        try {
            if (!configFileService.rename(from, to)) {
                return badRequest("重命名失败：源文件不存在、目标已存在或文件名非法");
            }
            return ok(listFiles(), "已重命名为 " + to);
        } catch (Exception e) {
            log.error("重命名配置文件失败: {} -> {}", from, to, e);
            return fail("重命名配置文件失败: " + e.getMessage());
        }
    }

    /** 删除配置文件（?name=x.yaml），规则随配置一起消失；当前生效的不许删 */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> delete(@RequestParam("name") String name) {
        try {
            if (configFileService.activeName().equals(name)) {
                return badRequest("不能删除当前生效的配置文件，请先切换到别的配置");
            }
            if (!configFileService.delete(name)) {
                return badRequest("删除失败：文件不存在或文件名非法（" + name + "）");
            }
            return ok(listFiles(), "已删除 " + name);
        } catch (Exception e) {
            log.error("删除配置文件失败: {}", name, e);
            return fail("删除配置文件失败: " + e.getMessage());
        }
    }

    // ==================== 组装响应 ====================

    /** 一个配置文件的视图：名字、是否生效、有没有配过滤规则 */
    private Map<String, Object> describe(String name, String active) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("active", name.equals(active));

        // 规则就在这份配置里（jd_rules 段），所以直接读它判断有没有配过规则
        view.put("hasRules", !configFileService.readJdRules(name).isEmpty());

        Path path = configFileService.resolve(name);
        try {
            view.put("size", Files.size(path));
            view.put("updatedAt", Files.getLastModifiedTime(path).toMillis());
        } catch (Exception ignored) {
            // 读不到元信息不影响选择器显示
        }
        return view;
    }

    private List<Map<String, Object>> listFiles() {
        String active = configFileService.activeName();
        List<Map<String, Object>> files = new ArrayList<>();
        for (String name : configFileService.listNames()) {
            files.add(describe(name, active));
        }
        return files;
    }

    private static String value(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.trim();
    }

    private ResponseEntity<Map<String, Object>> ok(Object data, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        response.put("message", message);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }

    private ResponseEntity<Map<String, Object>> fail(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.internalServerError().body(response);
    }
}
