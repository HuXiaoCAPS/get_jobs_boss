package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import com.getjobs.worker.boss.BossConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss数据服务
 * 统一管理所有Boss相关的数据访问和配置加载
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BossService {

    private final BossOptionMapper bossOptionMapper;
    private final BossConfigMapper bossConfigMapper;
    private final BlacklistMapper blacklistMapper;
    private final BossJobDataMapper bossJobDataMapper;
    private final javax.sql.DataSource dataSource;
    /** config/boss.yaml 的读写（配置以文件为权威来源，见 syncConfigFromFile） */
    private final ConfigFileService configFileService;

    /**
     * 启动时就把表结构补齐（补列 / 建表 / 列顺序迁移）。
     * <p>
     * 必须放在启动阶段：之前只在投递时（prepare）才做，于是存在一个窗口期——
     * 实体加了字段、列却还没建，此时任何查这张表的接口都会 500。实测踩过：
     * 给 BossConfigEntity 加 hrActiveMaxDays 后，/api/boss/config 直接报
     * no such column: hr_active_max_days，前端所有下拉框一起空掉。
     */
    @PostConstruct
    public void initSchema() {
        try {
            ensureBossDataColumnOrder();
        } catch (Exception e) {
            log.warn("启动时确保表结构失败（不影响启动，投递前还会再试一次）：{}", e.getMessage());
        }
        // 启动时同步一次配置：
        //   - config/boss.yaml 不存在而库里有配置 → 把库里的导出成文件（首次迁移）
        //   - 存在 → 把文件的值回写库
        // 必须放在启动阶段，否则"配置文件"要等到第一次点投递才出现
        //（loadBossConfig 只在投递流程里被调用）。
        try {
            syncConfigFromFile();
        } catch (Exception e) {
            log.warn("启动时同步 config/boss.yaml 失败（投递前还会再试一次）：{}", e.getMessage());
        }
    }

    // ==================== Option相关方法 ====================

    /**
     * 根据类型获取选项列表
     */
    public List<BossOptionEntity> getOptionsByType(String type) {
        // 确保数据库存在『不限』选项（code=0），并置顶显示
        // city 与 industry 都需要此默认项
        QueryWrapper<BossOptionEntity> checkWrapper = new QueryWrapper<>();
        checkWrapper.eq("type", type);
        checkWrapper.eq("code", com.getjobs.worker.utils.Constant.UNLIMITED_CODE);
        Long count = bossOptionMapper.selectCount(checkWrapper);
        if (count == null || count == 0) {
            BossOptionEntity unlimited = new BossOptionEntity();
            unlimited.setType(type);
            unlimited.setName("不限");
            unlimited.setCode(com.getjobs.worker.utils.Constant.UNLIMITED_CODE);
            // 置顶显示
            unlimited.setSortOrder(0);
            unlimited.setCreatedAt(java.time.LocalDateTime.now());
            unlimited.setUpdatedAt(java.time.LocalDateTime.now());
            bossOptionMapper.insert(unlimited);
        }

        // 排序：city/industry 按 sort_order 优先，其次 id；其他类型维持原有 id 升序
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        if ("city".equals(type) || "industry".equals(type)) {
            // SQLite 下可用：ORDER BY sort_order IS NULL, sort_order ASC, id ASC
            wrapper.last("ORDER BY sort_order IS NULL, sort_order ASC, id ASC");
        } else {
            wrapper.orderByAsc("id");
        }
        return bossOptionMapper.selectList(wrapper);
    }

    /**
     * 获取所有选项
     */
    public List<BossOptionEntity> getAllOptions() {
        return bossOptionMapper.selectList(null);
    }

    /**
     * 根据类型和代码获取选项
     */
    public BossOptionEntity getOptionByTypeAndCode(String type, String code) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("code", code);
        return bossOptionMapper.selectOne(wrapper);
    }

    /**
     * 根据类型和名称获取代码
     * 如果找不到，返回默认值 "0"
     */
    public String getCodeByTypeAndName(String type, String name) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", type);
        wrapper.eq("name", name);
        BossOptionEntity entity = bossOptionMapper.selectOne(wrapper);
        return entity != null ? entity.getCode() : "0";
    }

    // ==================== City相关方法 ====================

    /**
     * 根据城市名称获取代码（从 boss_option 表中按 type=city 查询）
     * 如果找不到，返回默认值 "0"
     */
    public String getCityCodeByName(String name) {
        QueryWrapper<BossOptionEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "city");
        wrapper.eq("name", name);
        BossOptionEntity entity = bossOptionMapper.selectOne(wrapper);
        return entity != null ? entity.getCode() : "0";
    }

    // ==================== BossConfig相关方法 ====================

    /**
     * 获取所有配置
     */
    public List<BossConfigEntity> getAllConfigs() {
        return bossConfigMapper.selectList(null);
    }

    /**
     * 根据ID获取配置
     */
    public BossConfigEntity getConfigById(Long id) {
        return bossConfigMapper.selectById(id);
    }

    /**
     * 获取第一条配置（通常只有一条）
     */
    public BossConfigEntity getFirstConfig() {
        QueryWrapper<BossConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1");
        return bossConfigMapper.selectOne(wrapper);
    }

    /**
     * 保存配置
     */
    public BossConfigEntity saveConfig(BossConfigEntity config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        bossConfigMapper.insert(config);
        return config;
    }

    /**
     * 更新配置
     */
    public BossConfigEntity updateConfig(BossConfigEntity config) {
        config.setUpdatedAt(LocalDateTime.now());
        bossConfigMapper.updateById(config);
        return config;
    }

    /**
     * 保存或更新（优先更新第一条）配置，支持选择性更新（仅覆盖非空字段）。
     * - 若表中已有记录：合并非空字段并更新该记录
     * - 若表为空：插入新记录
     */
    public BossConfigEntity saveOrUpdateFirstSelective(BossConfigEntity partial) {
        BossConfigEntity existing = getFirstConfig();
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            // 表为空，插入新记录
            partial.setCreatedAt(now);
            partial.setUpdatedAt(now);
            bossConfigMapper.insert(partial);
            return partial;
        }

        // 选择性合并：仅当请求体字段非空时才覆盖
        if (partial.getSayHi() != null) existing.setSayHi(partial.getSayHi());
        if (partial.getDebugger() != null) existing.setDebugger(partial.getDebugger());
        if (partial.getEnableAi() != null) existing.setEnableAi(partial.getEnableAi());
        if (partial.getFilterDeadHr() != null) existing.setFilterDeadHr(partial.getFilterDeadHr());
        if (partial.getSendImgResume() != null) existing.setSendImgResume(partial.getSendImgResume());
        if (partial.getWaitTime() != null) existing.setWaitTime(partial.getWaitTime());

        if (partial.getKeywords() != null) existing.setKeywords(partial.getKeywords());
        if (partial.getCityCode() != null) existing.setCityCode(partial.getCityCode());
        if (partial.getIndustry() != null) existing.setIndustry(partial.getIndustry());
        if (partial.getJobType() != null) existing.setJobType(partial.getJobType());
        if (partial.getExperience() != null) existing.setExperience(partial.getExperience());
        if (partial.getDegree() != null) existing.setDegree(partial.getDegree());
        if (partial.getSalary() != null) existing.setSalary(partial.getSalary());
        if (partial.getScale() != null) existing.setScale(partial.getScale());
        if (partial.getStage() != null) existing.setStage(partial.getStage());

        if (partial.getExpectedSalaryMin() != null) existing.setExpectedSalaryMin(partial.getExpectedSalaryMin());
        if (partial.getExpectedSalaryMax() != null) existing.setExpectedSalaryMax(partial.getExpectedSalaryMax());

        if (partial.getDeadStatus() != null) existing.setDeadStatus(partial.getDeadStatus());

        existing.setUpdatedAt(now);
        bossConfigMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除配置
     */
    public boolean deleteConfig(Long id) {
        return bossConfigMapper.deleteById(id) > 0;
    }

    // ==================== 配置加载方法 ====================

    /**
     * 配置文件与数据库之间的同步（配置以 config/boss.yaml 为权威来源）。
     *
     * <p><b>首次迁移</b>：YAML 不存在、而库里有配置 → 把库里的导出成 YAML。
     * <p><b>日常</b>：YAML 存在 → 把它的值写回 boss_config / ai / config 三张表。
     *
     * <p>为什么绕一圈"写回库"而不是让各处直接读文件：这样下游代码（AiService、
     * ConfigService、Bot、Boss）**一行都不用改**，它们仍旧从库里读，只是库的内容由文件驱动；
     * 原来的读库逻辑也完整保留，随时能退回数据库模式，跟上游合并也几乎零冲突。
     */
    public void syncConfigFromFile() {
        try {
            if (!configFileService.exists()) {
                exportConfigToFile();
                return;
            }
            Map<String, Object> root = configFileService.read();
            if (root == null || root.isEmpty()) {
                return;
            }
            Map<String, Object> search = asMap(root.get("search"));
            Map<String, Object> delivery = asMap(root.get("delivery"));
            Map<String, Object> ai = asMap(root.get("ai"));
            Map<String, Object> notify = asMap(root.get("notify"));

            // ---------- 1) boss_config 表 ----------
            BossConfigEntity cfg = getFirstConfig();
            if (cfg == null) {
                cfg = new BossConfigEntity();
                cfg.setCreatedAt(LocalDateTime.now());
            }
            if (search.containsKey("keywords")) cfg.setKeywords(listJoin(search.get("keywords")));
            if (search.containsKey("city")) cfg.setCityCode(bracket(search.get("city")));
            if (search.containsKey("city_filter_mode")) cfg.setCityFilterMode(flag(search.get("city_filter_mode")));
            if (search.containsKey("city_exclude")) cfg.setCityExclude(bracket(search.get("city_exclude")));
            if (search.containsKey("job_type")) cfg.setJobType(str(search.get("job_type")));
            if (search.containsKey("experience")) cfg.setExperience(bracket(search.get("experience")));
            if (search.containsKey("degree")) cfg.setDegree(bracket(search.get("degree")));
            if (search.containsKey("salary")) cfg.setSalary(bracket(search.get("salary")));
            if (search.containsKey("scale")) cfg.setScale(bracket(search.get("scale")));
            if (search.containsKey("stage")) cfg.setStage(bracket(search.get("stage")));
            if (search.containsKey("industry")) cfg.setIndustry(bracket(search.get("industry")));

            if (delivery.containsKey("say_hi")) cfg.setSayHi(str(delivery.get("say_hi")));
            if (delivery.containsKey("wait_time")) cfg.setWaitTime(toInt(delivery.get("wait_time")));
            if (delivery.containsKey("enable_ai")) cfg.setEnableAi(flag(delivery.get("enable_ai")));
            if (delivery.containsKey("filter_dead_hr")) cfg.setFilterDeadHr(flag(delivery.get("filter_dead_hr")));
            if (delivery.containsKey("send_img_resume")) cfg.setSendImgResume(flag(delivery.get("send_img_resume")));
            if (delivery.containsKey("hr_active_max_days")) cfg.setHrActiveMaxDays(toInt(delivery.get("hr_active_max_days")));
            if (delivery.containsKey("skip_delivered_company")) cfg.setSkipDeliveredCompany(flag(delivery.get("skip_delivered_company")));
            if (delivery.containsKey("debugger")) cfg.setDebugger(flag(delivery.get("debugger")));

            cfg.setUpdatedAt(LocalDateTime.now());
            if (cfg.getId() == null) {
                bossConfigMapper.insert(cfg);
            } else {
                bossConfigMapper.updateById(cfg);
            }

            // ---------- 2) config 表（AI 凭据 + 通知）----------
            if (ai.containsKey("base_url")) updateConfigValue("BASE_URL", str(ai.get("base_url")));
            if (ai.containsKey("api_key")) updateConfigValue("API_KEY", str(ai.get("api_key")));
            if (ai.containsKey("model")) updateConfigValue("MODEL", str(ai.get("model")));
            if (notify.containsKey("hook_url")) updateConfigValue("HOOK_URL", str(notify.get("hook_url")));
            if (notify.containsKey("bot_is_send")) {
                updateConfigValue("BOT_IS_SEND", String.valueOf(flag(notify.get("bot_is_send"))));
            }

            // ---------- 3) ai 表（introduce / prompt）----------
            if (ai.containsKey("introduce") || ai.containsKey("prompt")) {
                updateAiConfig(ai.containsKey("introduce") ? str(ai.get("introduce")) : null,
                        ai.containsKey("prompt") ? str(ai.get("prompt")) : null);
            }

            log.info("已从 config/boss.yaml 同步配置到数据库");
        } catch (Exception e) {
            log.warn("同步 config/boss.yaml 失败，本次仍按数据库里的配置执行：{}", e.getMessage());
        }
    }

    /**
     * 把网页端提交的配置写进 config/boss.yaml（权威来源），然后立即回写数据库保持一致。
     *
     * <p>写入方式是在**现有文件内容上合并**，而不是整体覆盖 —— 因为网页端表单只覆盖
     * search / delivery 两块，ai / notify 由别的页面维护，整体覆盖会把它们清空。
     */
    public boolean saveConfigToFile(BossConfigEntity partial) {
        if (partial == null) {
            return false;
        }
        try {
            Map<String, Object> root = configFileService.exists()
                    ? new java.util.LinkedHashMap<>(configFileService.read())
                    : new java.util.LinkedHashMap<>();
            Map<String, Object> search = new java.util.LinkedHashMap<>(asMap(root.get("search")));
            Map<String, Object> delivery = new java.util.LinkedHashMap<>(asMap(root.get("delivery")));

            if (partial.getKeywords() != null) search.put("keywords", parseListString(partial.getKeywords()));
            if (partial.getJobType() != null) search.put("job_type", partial.getJobType());
            if (partial.getExperience() != null) search.put("experience", parseListString(partial.getExperience()));
            if (partial.getDegree() != null) search.put("degree", parseListString(partial.getDegree()));
            if (partial.getSalary() != null) search.put("salary", parseListString(partial.getSalary()));
            if (partial.getScale() != null) search.put("scale", parseListString(partial.getScale()));
            if (partial.getStage() != null) search.put("stage", parseListString(partial.getStage()));
            if (partial.getIndustry() != null) search.put("industry", parseListString(partial.getIndustry()));
            // 城市：网页端已是逗号分隔的多值输入框，且 BossConfigController.updateConfig
            // 已经把它归一化成『中文名的括号列表』（单城市就是该名字），这里照写即可。
            // null 表示"本次不动这个字段"，保持文件里的原值。
            if (partial.getCityCode() != null) search.put("city", parseListString(partial.getCityCode()));
            // 多城市处理方式：true = 过滤模式（搜全国 + 按岗位城市筛），false = 逐城市轮换
            if (partial.getCityFilterMode() != null) search.put("city_filter_mode", partial.getCityFilterMode() == 1);
            // 排除的城市/省份
            if (partial.getCityExclude() != null) search.put("city_exclude", parseListString(partial.getCityExclude()));

            if (partial.getSayHi() != null) delivery.put("say_hi", partial.getSayHi());
            if (partial.getWaitTime() != null) delivery.put("wait_time", partial.getWaitTime());
            if (partial.getEnableAi() != null) delivery.put("enable_ai", partial.getEnableAi() == 1);
            if (partial.getFilterDeadHr() != null) delivery.put("filter_dead_hr", partial.getFilterDeadHr() == 1);
            if (partial.getSendImgResume() != null) delivery.put("send_img_resume", partial.getSendImgResume() == 1);
            if (partial.getHrActiveMaxDays() != null) delivery.put("hr_active_max_days", partial.getHrActiveMaxDays());
            if (partial.getSkipDeliveredCompany() != null) delivery.put("skip_delivered_company", partial.getSkipDeliveredCompany() == 1);
            if (partial.getDebugger() != null) delivery.put("debugger", partial.getDebugger() == 1);

            root.put("search", search);
            root.put("delivery", delivery);
            root.putIfAbsent("ai", new java.util.LinkedHashMap<>());
            root.putIfAbsent("notify", new java.util.LinkedHashMap<>());

            boolean ok = configFileService.write(root);
            if (ok) {
                syncConfigFromFile(); // 立刻回写库，避免文件与库短暂不一致
            }
            return ok;
        } catch (Exception e) {
            log.warn("保存配置到 config/boss.yaml 失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 把 AI（introduce / prompt）与通知（hook_url / bot_is_send）写进 config/boss.yaml。
     *
     * <p>为什么必须落文件：配置以文件为权威，syncConfigFromFile() 在每次启动/投递前都会用
     * 文件里的 ai / notify 块覆盖数据库。只改库不写文件的话，这次修改会在下一次 sync
     * 时被打回原值。参数为 null 表示"本次不改这一项"。
     */
    public boolean saveAiAndNotifyToFile(String introduce, String prompt, String hookUrl, String botIsSend) {
        try {
            if (!configFileService.exists()) {
                // 先做首次迁移，避免新写出去的文件缺 base_url / api_key 等键
                exportConfigToFile();
            }
            Map<String, Object> root = new java.util.LinkedHashMap<>(configFileService.read());
            Map<String, Object> ai = new java.util.LinkedHashMap<>(asMap(root.get("ai")));
            Map<String, Object> notify = new java.util.LinkedHashMap<>(asMap(root.get("notify")));

            if (introduce != null) ai.put("introduce", introduce);
            if (prompt != null) ai.put("prompt", prompt);
            if (hookUrl != null) notify.put("hook_url", hookUrl);
            // 写成 true/false（和 exportConfigToFile 一致），而不是 1/0，方便手工编辑文件
            if (botIsSend != null) {
                notify.put("bot_is_send", "1".equals(botIsSend) || "true".equalsIgnoreCase(botIsSend));
            }

            root.put("ai", ai);
            root.put("notify", notify);

            boolean ok = configFileService.write(root);
            if (ok) {
                syncConfigFromFile(); // 立刻回写库，避免文件与库短暂不一致
            }
            return ok;
        } catch (Exception e) {
            log.warn("保存 AI / 通知配置到 config/boss.yaml 失败：{}", e.getMessage());
            return false;
        }
    }

    /** YAML 不存在时，把数据库里的现有配置导出成 boss.yaml（首次迁移，不丢配置） */
    private void exportConfigToFile() {
        try {
            BossConfigEntity cfg = getFirstConfig();
            if (cfg == null) {
                return; // 库里也没有配置，那就等用户按模板自己创建文件
            }
            Map<String, Object> search = new java.util.LinkedHashMap<>();
            search.put("keywords", parseListString(cfg.getKeywords()));
            search.put("city", parseListString(cfg.getCityCode()));
            // 与网页端保持一致：多城市处理方式也写进文件
            search.put("city_filter_mode", flagOf(cfg.getCityFilterMode()));
            search.put("city_exclude", parseListString(cfg.getCityExclude()));
            search.put("job_type", cfg.getJobType() == null ? "" : cfg.getJobType());
            search.put("experience", parseListString(cfg.getExperience()));
            search.put("degree", parseListString(cfg.getDegree()));
            search.put("salary", parseListString(cfg.getSalary()));
            search.put("scale", parseListString(cfg.getScale()));
            search.put("stage", parseListString(cfg.getStage()));
            search.put("industry", parseListString(cfg.getIndustry()));

            Map<String, Object> delivery = new java.util.LinkedHashMap<>();
            delivery.put("say_hi", cfg.getSayHi() == null ? "" : cfg.getSayHi());
            delivery.put("wait_time", cfg.getWaitTime());
            delivery.put("enable_ai", flagOf(cfg.getEnableAi()));
            delivery.put("send_img_resume", flagOf(cfg.getSendImgResume()));
            delivery.put("filter_dead_hr", flagOf(cfg.getFilterDeadHr()));
            delivery.put("hr_active_max_days", cfg.getHrActiveMaxDays());
            delivery.put("skip_delivered_company", flagOf(cfg.getSkipDeliveredCompany()));
            delivery.put("debugger", flagOf(cfg.getDebugger()));

            Map<String, Object> ai = new java.util.LinkedHashMap<>();
            ai.put("base_url", readConfigValue("BASE_URL"));
            ai.put("api_key", readConfigValue("API_KEY"));
            ai.put("model", readConfigValue("MODEL"));
            ai.put("introduce", readAiValue("introduce"));
            ai.put("prompt", readAiValue("prompt"));

            Map<String, Object> notify = new java.util.LinkedHashMap<>();
            notify.put("hook_url", readConfigValue("HOOK_URL"));
            notify.put("bot_is_send", "1".equals(readConfigValue("BOT_IS_SEND")));

            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("search", search);
            root.put("delivery", delivery);
            root.put("ai", ai);
            root.put("notify", notify);

            if (configFileService.write(root)) {
                log.info("config/boss.yaml 不存在，已把数据库里的配置导出为该文件（后续以文件为准）");
            }
        } catch (Exception e) {
            log.warn("导出配置到 config/boss.yaml 失败：{}", e.getMessage());
        }
    }

    // ==================== 配置同步用到的小工具 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : java.util.Collections.emptyMap();
    }

    /** 统一转成字符串（多行 YAML 的 | 块会带尾换行，这里只去尾部空白） */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    /** true/false、1/0、"true"/"false" → 1/0（数据库里用 0/1 存布尔） */
    private static Integer flag(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b ? 1 : 0;
        String s = String.valueOf(o).trim().toLowerCase();
        return ("true".equals(s) || "1".equals(s) || "yes".equals(s)) ? 1 : 0;
    }

    private static Boolean flagOf(Integer v) {
        return v != null && v == 1;
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 列表 → 逗号分隔（keywords 在库里就是这种形式） */
    private static String listJoin(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.joining(","));
        }
        return str(o);
    }

    /** 列表 → 括号列表（其他多选项在库里是 [a,b] 形式） */
    private static String bracket(Object o) {
        if (o instanceof List<?> list) {
            List<String> items = list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
            return items.isEmpty() ? "" : "[" + String.join(",", items) + "]";
        }
        return str(o);
    }

    /** 更新 config 表的某个键（表里没有该键时忽略） */
    private void updateConfigValue(String key, String value) {
        if (value == null) return;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE config SET config_value = ?, updated_at = ? WHERE config_key = ?")) {
            ps.setString(1, value);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setString(3, key);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("更新 config.{} 失败：{}", key, e.getMessage());
        }
    }

    /** 读取 config 表的某个键 */
    private String readConfigValue(String key) {
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT config_value FROM config WHERE config_key = ? LIMIT 1")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 读取 ai 表最新一条的指定列（只用于导出配置，列名由本类内部常量传入） */
    private String readAiValue(String column) {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + column + " FROM ai ORDER BY id DESC LIMIT 1")) {
            return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 更新 ai 表最新一条的 introduce / prompt（没有则插入一条） */
    private void updateAiConfig(String introduce, String prompt) {
        try (Connection conn = dataSource.getConnection()) {
            Long id = null;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM ai ORDER BY id DESC LIMIT 1")) {
                if (rs.next()) {
                    id = rs.getLong(1);
                }
            }
            String now = LocalDateTime.now().toString();
            if (id == null) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ai (introduce, prompt, created_at, updated_at) VALUES (?,?,?,?)")) {
                    ps.setString(1, introduce);
                    ps.setString(2, prompt);
                    ps.setString(3, now);
                    ps.setString(4, now);
                    ps.executeUpdate();
                }
            } else {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ai SET introduce = COALESCE(?, introduce), prompt = COALESCE(?, prompt), updated_at = ? WHERE id = ?")) {
                    ps.setString(1, introduce);
                    ps.setString(2, prompt);
                    ps.setString(3, now);
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("更新 ai 表失败：{}", e.getMessage());
        }
    }

    /**
     * 加载Boss配置
     * 从配置文件和数据库加载完整的Boss配置
     */
    public BossConfig loadBossConfig() {
        // 配置以 config/boss.yaml 为权威来源：先把它同步进库，下面照旧从库读。
        // 这样下游（AiService / ConfigService / Bot / Boss）完全不用改，也便于跟上游合并。
        // YAML 不存在时这里会把库里的现有配置导出成 YAML（首次迁移），不丢配置。
        syncConfigFromFile();

        // 直接从数据库 boss_config 加载，并将括号列表解析为集合
        BossConfigEntity entity = getFirstConfig();
        BossConfig config = new BossConfig();

        if (entity == null) {
            log.warn("boss_config 表为空，使用默认空配置");
            return config;
        }

        // 文本与布尔/数值
        config.setSayHi(entity.getSayHi());
        config.setDebugger(entity.getDebugger() != null && entity.getDebugger() == 1);
        config.setEnableAI(entity.getEnableAi() != null && entity.getEnableAi() == 1);
        config.setFilterDeadHR(entity.getFilterDeadHr() != null && entity.getFilterDeadHr() == 1);
        config.setSendImgResume(entity.getSendImgResume() != null && entity.getSendImgResume() == 1);
        config.setWaitTime(entity.getWaitTime() != null ? String.valueOf(entity.getWaitTime()) : null);

        // 关键词（允许逗号或括号列表），直接解析为列表
        config.setKeywords(parseListString(entity.getKeywords()));

        // 将中文名转换为代码，供 Worker 使用
        // 城市：单值或列表，统一转换为代码列表
        config.setCityCode(toCodes("city", parseListString(entity.getCityCode())));
        // 城市的中文名（过滤模式要用它比对岗位详情里的 locationName）
        config.setCityNames(parseListString(entity.getCityCode()));
        // 多城市处理方式：true = 过滤模式（搜全国 + 按岗位城市筛）；false/null = 逐城市轮换（原行为）
        config.setCityFilterMode(entity.getCityFilterMode() != null && entity.getCityFilterMode() == 1);
        // 排除的城市/省份（平台侧用 CityFilter 展开省份后再匹配岗位地点）
        config.setCityExclude(parseListString(entity.getCityExclude()));
        // 同一家公司不重复投递：null 视为开启
        config.setSkipDeliveredCompany(entity.getSkipDeliveredCompany() == null
                || entity.getSkipDeliveredCompany() == 1);
        // 行业/经验/学历/规模/阶段：名称或代码 -> 统一为代码列表
        config.setIndustry(toCodes("industry", parseListString(entity.getIndustry())));
        config.setExperience(toCodes("experience", parseListString(entity.getExperience())));
        config.setDegree(toCodes("degree", parseListString(entity.getDegree())));
        config.setScale(toCodes("scale", parseListString(entity.getScale())));
        config.setStage(toCodes("stage", parseListString(entity.getStage())));

        // 职位类型：统一转换为代码，若为列表取第一个；为空时使用不限代码
        List<String> jobTypeCodes = toCodes("jobType", parseListString(entity.getJobType()));
        String jobTypeCode = null;
        if (!jobTypeCodes.isEmpty()) {
            jobTypeCode = jobTypeCodes.get(0);
        } else if (entity.getJobType() != null && !entity.getJobType().trim().isEmpty()) {
            BossOptionEntity byCode = getOptionByTypeAndCode("jobType", entity.getJobType());
            jobTypeCode = byCode != null && byCode.getCode() != null
                    ? byCode.getCode()
                    : getCodeByTypeAndName("jobType", entity.getJobType());
        }
        if (jobTypeCode == null || jobTypeCode.trim().isEmpty()) {
            jobTypeCode = com.getjobs.worker.utils.Constant.UNLIMITED_CODE;
        }
        config.setJobType(jobTypeCode);
        // 薪资：名称或代码 -> 统一为代码列表（用于URL逗号拼接）
        config.setSalary(toCodes("salary", parseListString(entity.getSalary())));

        // 期望薪资（min,max）
        if (entity.getExpectedSalaryMin() != null || entity.getExpectedSalaryMax() != null) {
            config.setExpectedSalary(java.util.Arrays.asList(
                    entity.getExpectedSalaryMin() != null ? entity.getExpectedSalaryMin() : 0,
                    entity.getExpectedSalaryMax() != null ? entity.getExpectedSalaryMax() : 0
            ));
        }

        // HR不在线状态（括号列表字符串）
        config.setDeadStatus(parseListString(entity.getDeadStatus()));

        // HR 活跃度阈值（天）：字段没配（null）时按 30 天处理；显式配 0 表示不启用细粒度判定
        config.setHrActiveMaxDays(entity.getHrActiveMaxDays() == null ? 30 : entity.getHrActiveMaxDays());

        log.info("已从 boss_config 加载Boss配置，并完成括号列表解析");
        return config;
    }

    /**
     * 解析括号列表或逗号分隔的字符串为列表，例如 "[a,b,c]" 或 "a,b,c"。
     * <p>分隔符同时接受<b>半角逗号与全角逗号</b>（中文输入法下很容易打出「，」），
     * 也接受中文分号/顿号，避免用户手打配置时踩坑。空值返回空列表。
     */
    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.trim().isEmpty()) return java.util.Collections.emptyList();
        return java.util.Arrays.stream(s.split("[,，、;；]"))
                .map(String::trim)
                // 去除项内可能存在的双引号，兼容 JSON 数组序列化存储
                .map(str -> str.replaceAll("^\"|\"$", ""))
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 将列表转换为括号列表字符串，例如 [a,b,c]
     */
    public String toBracketListString(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return "[" + String.join(",", list) + "]";
    }

    /**
     * 规范化：将传入的代码列表转换为名称列表（若传入已是名称则原样返回）。
     */
    public List<String> toNames(String type, List<String> items) {
        if (items == null || items.isEmpty()) return java.util.Collections.emptyList();
        return items.stream().map(it -> {
            // 若是有效code，直接按code查找并取name
            BossOptionEntity byCode = getOptionByTypeAndCode(type, it);
            if (byCode != null && byCode.getName() != null) {
                return byCode.getName();
            }
            // 否则当作name使用（无需转换）
            return it;
        }).collect(Collectors.toList());
    }

    /**
     * 规范化：将传入的名称列表转换为代码列表（若传入已是代码则原样保留）。
     */
    public List<String> toCodes(String type, List<String> items) {
        if (items == null || items.isEmpty()) return java.util.Collections.emptyList();
        return items.stream().map(it -> {
            // 若是有效code，保留
            BossOptionEntity byCode = getOptionByTypeAndCode(type, it);
            if (byCode != null && byCode.getCode() != null) {
                return byCode.getCode();
            }
            // 否则按name查code
            String code = getCodeByTypeAndName(type, it);
            return code != null ? code : "0";
        }).collect(Collectors.toList());
    }

    /**
     * 统一化城市：把 city 值（code / 中文名 / 括号列表 / 逗号分隔）统一成
     * 『城市中文名的括号列表』。
     * <p>
     * 旧实现只取 list.get(0)，于是"多城市"配置只要被保存一次就被压成单个城市。
     * 现在逐个归一化再拼回去；只有单个城市时行为与原来完全一致。
     * <p>
     * ⚠️ 注意：网页端的城市下拉框仍是单选，而且前端在**加载配置时**就只回显第一个
     * （front/app/boss/page.tsx 的 normalizeCityCode 里 `return list[0]`），
     * 所以"在网页端点保存"依然会把多城市写回单个 —— 需要连前端一起改并重建 dist
     * 才能让网页端也保住多城市。
     */
    public String normalizeCityToName(String raw) {
        List<String> list = parseListString(raw);
        if (list.isEmpty()) {
            return raw == null ? "" : raw.trim();
        }
        List<String> names = list.stream()
                .map(city -> {
                    if (city == null) return "";
                    String v = city.trim();
                    if (v.isEmpty()) return "";
                    BossOptionEntity byCode = getOptionByTypeAndCode("city", v);
                    return (byCode != null && byCode.getName() != null) ? byCode.getName() : v;
                })
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toList());
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        // 「不限」是"全选"，跟具体城市混在一起没有意义，直接丢掉
        names.removeIf("不限"::equals);
        if (names.isEmpty()) return "不限";
        return names.size() == 1 ? names.get(0) : "[" + String.join(",", names) + "]";
    }

    // ==================== Blacklist相关方法 ====================

    /**
     * 根据类型获取黑名单列表
     *
     * @param type 类型 (company/recruiter/job)
     * @return 黑名单值集合
     */
    public Set<String> getBlacklistByType(String type) {
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getType, type);
        List<BlacklistEntity> list = blacklistMapper.selectList(wrapper);
        return list.stream()
                .map(BlacklistEntity::getValue)
                .collect(Collectors.toSet());
    }

    /**
     * 获取所有公司黑名单
     */
    public Set<String> getBlackCompanies() {
        return getBlacklistByType("company");
    }

    /**
     * 获取所有招聘者黑名单
     */
    public Set<String> getBlackRecruiters() {
        return getBlacklistByType("recruiter");
    }

    /**
     * 获取所有职位黑名单
     */
    public Set<String> getBlackJobs() {
        return getBlacklistByType("job");
    }

    /**
     * 添加黑名单
     *
     * @param type  类型
     * @param value 值
     * @return 是否成功
     */
    public boolean addBlacklist(String type, String value) {
        // 检查是否已存在
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getType, type)
                .eq(BlacklistEntity::getValue, value);
        if (blacklistMapper.selectCount(wrapper) > 0) {
            return false; // 已存在
        }

        BlacklistEntity entity = new BlacklistEntity();
        entity.setType(type);
        entity.setValue(value);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return blacklistMapper.insert(entity) > 0;
    }

    /**
     * 批量添加黑名单
     *
     * @param type   类型
     * @param values 值集合
     */
    public void addBlacklistBatch(String type, Set<String> values) {
        values.forEach(value -> addBlacklist(type, value));
    }

    /**
     * 删除黑名单
     *
     * @param type  类型
     * @param value 值
     * @return 是否成功
     */
    public boolean removeBlacklist(String type, String value) {
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getType, type)
                .eq(BlacklistEntity::getValue, value);
        return blacklistMapper.delete(wrapper) > 0;
    }

    /**
     * 获取所有黑名单
     *
     * @return 所有黑名单实体
     */
    public List<BlacklistEntity> getAllBlacklist() {
        return blacklistMapper.selectList(null);
    }

    // ==================== boss_data（岗位数据）相关方法 ====================

    /**
     * 确保 boss_data 表的列顺序以 encrypt_id、encrypt_user_id 开头。
     * 若不满足，则进行一次在线迁移：创建新表、复制数据、替换旧表。
     */
    public void ensureBossDataColumnOrder() {
        java.sql.Connection conn = null;
        try {
            conn = dataSource.getConnection();
            try (java.sql.Statement stmt = conn.createStatement()) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info('boss_data')")) {
                    while (rs.next()) {
                        cols.add(rs.getString("name"));
                    }
                }
                // 补列：HR 活跃度阈值（属于 boss_config 表，与 boss_data 无关；
                // 放在这里只因为这个方法已经是「投递前确保表结构」的现成入口）
                try (java.sql.ResultSet rsCfg = stmt.executeQuery("PRAGMA table_info('boss_config')")) {
                    java.util.List<String> cfgCols = new java.util.ArrayList<>();
                    while (rsCfg.next()) {
                        cfgCols.add(rsCfg.getString("name"));
                    }
                    if (!cfgCols.isEmpty() && !cfgCols.contains("hr_active_max_days")) {
                        stmt.execute("ALTER TABLE boss_config ADD COLUMN hr_active_max_days INTEGER");
                        log.info("已为 boss_config 补上 hr_active_max_days 列");
                    }
                    // 多城市处理方式：1 = 过滤模式（搜全国后按岗位城市筛），0/null = 逐城市轮换
                    if (!cfgCols.isEmpty() && !cfgCols.contains("city_filter_mode")) {
                        stmt.execute("ALTER TABLE boss_config ADD COLUMN city_filter_mode INTEGER");
                        log.info("已为 boss_config 补上 city_filter_mode 列");
                    }
                    // 排除的城市/省份（命中即跳过；省份名会展开成该省城市）
                    if (!cfgCols.isEmpty() && !cfgCols.contains("city_exclude")) {
                        stmt.execute("ALTER TABLE boss_config ADD COLUMN city_exclude TEXT");
                        log.info("已为 boss_config 补上 city_exclude 列");
                    }
                    // 同一家公司不重复投递：1/null = 开启，0 = 关闭
                    if (!cfgCols.isEmpty() && !cfgCols.contains("skip_delivered_company")) {
                        stmt.execute("ALTER TABLE boss_config ADD COLUMN skip_delivered_company INTEGER");
                        log.info("已为 boss_config 补上 skip_delivered_company 列");
                    }
                } catch (Exception e) {
                    log.warn("为 boss_config 补 hr_active_max_days 列失败：{}", e.getMessage());
                }

                // 建表：HR 聊天快照，用于对比出「谁回了我」（配合投递前的聊天页扫描）
                try {
                    stmt.execute("CREATE TABLE IF NOT EXISTS hr_chat_snapshot (" +
                            "company_name TEXT PRIMARY KEY, last_message TEXT, updated_at TEXT)");
                } catch (Exception e) {
                    log.warn("创建 hr_chat_snapshot 表失败：{}", e.getMessage());
                }

                if (cols.isEmpty()) return; // 表不存在或无列

                // 补列：JD 规则的 warn 提示（老库缺这一列时补上，已是新库则跳过）
                if (!cols.contains("filter_note")) {
                    try {
                        stmt.execute("ALTER TABLE boss_data ADD COLUMN filter_note TEXT");
                        log.info("已为 boss_data 补上 filter_note 列");
                    } catch (Exception e) {
                        log.warn("为 boss_data 添加 filter_note 列失败：{}", e.getMessage());
                    }
                }

                boolean needMigrate = true;
                if (cols.size() >= 3) {
                    String c0 = cols.get(0) == null ? "" : cols.get(0).toLowerCase();
                    String c1 = cols.get(1) == null ? "" : cols.get(1).toLowerCase();
                    String c2 = cols.get(2) == null ? "" : cols.get(2).toLowerCase();
                    // 允许第一列是 id 或 encrypt_id，但要求前两列满足 encrypt_id、encrypt_user_id 顺序
                    if ("id".equals(c0) && "encrypt_id".equals(c1) && "encrypt_user_id".equals(c2)) {
                        needMigrate = false;
                    } else if ("encrypt_id".equals(c0) && "encrypt_user_id".equals(c1)) {
                        needMigrate = false;
                    }
                }
                if (!needMigrate) return;

                stmt.execute("BEGIN TRANSACTION");
                // 新表：将 encrypt_id、encrypt_user_id 移到最前（紧随 id）
                String createSql = "CREATE TABLE boss_data_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "encrypt_id TEXT, " +
                        "encrypt_user_id TEXT, " +
                        "company_name TEXT, " +
                        "job_name TEXT, " +
                        "salary TEXT, " +
                        "location TEXT, " +
                        "experience TEXT, " +
                        "degree TEXT, " +
                        "hr_name TEXT, " +
                        "hr_position TEXT, " +
                        "hr_active_status TEXT, " +
                        "delivery_status TEXT, " +
                        "job_description TEXT, " +
                        "filter_note TEXT, " +
                        "job_url TEXT, " +
                        "recruitment_status TEXT, " +
                        "company_address TEXT, " +
                        "industry TEXT, " +
                        "introduce TEXT, " +
                        "financing_stage TEXT, " +
                        "company_scale TEXT, " +
                        "created_at TEXT, " +
                        "updated_at TEXT" +
                        ")";
                stmt.execute(createSql);

                String copySql = "INSERT INTO boss_data_new (" +
                        "id, encrypt_id, encrypt_user_id, company_name, job_name, salary, location, experience, degree, " +
                        "hr_name, hr_position, hr_active_status, delivery_status, job_description, job_url, recruitment_status, " +
                        "company_address, industry, introduce, financing_stage, company_scale, created_at, updated_at" +
                        ") SELECT " +
                        "id, encrypt_id, encrypt_user_id, company_name, job_name, salary, location, experience, degree, " +
                        "hr_name, hr_position, hr_active_status, delivery_status, job_description, job_url, recruitment_status, " +
                        "company_address, industry, introduce, financing_stage, company_scale, created_at, updated_at " +
                        "FROM boss_data";
                stmt.execute(copySql);

                stmt.execute("DROP TABLE boss_data");
                stmt.execute("ALTER TABLE boss_data_new RENAME TO boss_data");
                stmt.execute("COMMIT");
                log.info("已调整 boss_data 表列顺序：将 encrypt_id、encrypt_user_id 前置");
            }
        } catch (Exception e) {
            log.warn("调整 boss_data 列顺序失败：{}", e.getMessage());
            try { if (conn != null) conn.createStatement().execute("ROLLBACK"); } catch (Exception ignore) {}
        } finally {
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 判断岗位是否已存在（相同 encrypt_id AND encrypt_user_id）
     */
    public boolean existsBossJob(String encryptId, String encryptUserId) {
        if (encryptId == null || encryptUserId == null) return false;
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("encrypt_id", encryptId)
                .eq("encrypt_user_id", encryptUserId)
                .last("LIMIT 1");
        Long count = bossJobDataMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 仅根据 encrypt_id 判断是否存在（当 encrypt_user_id 缺失时的降级策略）
     */
    public boolean existsBossJobByEncryptId(String encryptId) {
        if (encryptId == null) return false;
        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("encrypt_id", encryptId)
                .last("LIMIT 1");
        Long count = bossJobDataMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 取所有「已投递」岗位的 encrypt_id，供投递前跳过重复岗位用。
     * 一次性加载到内存（投递时逐岗位查库会多几百次往返），失败则返回空集合。
     */
    public java.util.Set<String> getDeliveredEncryptIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try {
            QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
            wrapper.select("encrypt_id")
                    .eq("delivery_status", "已投递")
                    .isNotNull("encrypt_id");
            for (BossJobDataEntity row : bossJobDataMapper.selectList(wrapper)) {
                if (row.getEncryptId() != null && !row.getEncryptId().isEmpty()) {
                    ids.add(row.getEncryptId());
                }
            }
        } catch (Exception e) {
            log.warn("加载已投递岗位列表失败（本次不做重复投递过滤）：{}", e.getMessage());
        }
        return ids;
    }

    /**
     * 读取「已经投递过」的公司名集合，用于"同一家公司不重复投递"。
     *
     * <p>为什么按公司去重：同一家公司在 Boss 上往往挂着多个相近岗位（甚至同一个 HR 换个标题再发一遍），
     * 逐个岗位去重挡不住这种重复打扰。这里以 {@code delivery_status = 已投递} 的记录为准：
     * 只要这家公司已经投过，它名下没投过的岗位也会被跳过。
     */
    public java.util.Set<String> getDeliveredCompanies() {
        java.util.Set<String> companies = new java.util.HashSet<>();
        try {
            QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
            wrapper.select("company_name")
                    .eq("delivery_status", "已投递")
                    .isNotNull("company_name");
            for (BossJobDataEntity row : bossJobDataMapper.selectList(wrapper)) {
                String name = row.getCompanyName();
                if (name != null && !name.isBlank()) {
                    companies.add(name.trim());
                }
            }
        } catch (Exception e) {
            log.warn("加载已投递公司列表失败（本次不做公司级去重）：{}", e.getMessage());
        }
        return companies;
    }

    /**
     * 读取 HR 聊天快照：公司名 -&gt; 最后一条消息。表不存在或读失败时返回空 Map
     * （空 Map 会被上层当作「首次运行」，只建基线不报新回复）。
     */
    public java.util.Map<String, String> getChatSnapshot() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select company_name, last_message from hr_chat_snapshot")) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
        } catch (Exception e) {
            log.warn("读取 HR 聊天快照失败（本次按首次运行处理）：{}", e.getMessage());
        }
        return map;
    }

    /**
     * 覆盖写入 HR 聊天快照（先清空再批量插入，保证和当前会话列表一致）。
     */
    public void replaceChatSnapshot(java.util.Map<String, String> snapshot) {
        if (snapshot == null) return;
        java.sql.Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM hr_chat_snapshot");
            }
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO hr_chat_snapshot(company_name, last_message, updated_at) VALUES (?,?,?)")) {
                String now = java.time.LocalDateTime.now().toString();
                for (java.util.Map.Entry<String, String> e : snapshot.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.setString(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            log.warn("写入 HR 聊天快照失败：{}", e.getMessage());
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
        } finally {
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * 插入新岗位数据（默认 delivery_status 为 未投递，或外部传入值）
     */
    public void insertBossJob(BossJobDataEntity entity) {
        if (entity == null) return;
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        bossJobDataMapper.insert(entity);
    }

    /**
     * 更新投递状态（WHERE encrypt_id = ? AND encrypt_user_id = ?）
     */
    public void updateDeliveryStatus(String encryptId, String encryptUserId, String status) {
        if (encryptId == null || status == null) return;
        BossJobDataEntity update = new BossJobDataEntity();
        update.setDeliveryStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BossJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("encrypt_id", encryptId);
        if (encryptUserId != null) {
            uw.eq("encrypt_user_id", encryptUserId);
        }
        bossJobDataMapper.update(update, uw);
    }

    // ==================== 投递分析（Dashboard）相关方法 ====================

    /**
     * 薪资解析结果
     */
    public static class SalaryInfo {
        /** 薪资单位：{@link #UNIT_MONTH}（月薪，以 K 计）/ {@link #UNIT_DAY}（元/天）/ {@link #UNIT_HOUR}（元/时） */
        public String unit;
        /** 原始单位下的区间下限 / 上限 / 中位数（月薪时单位是 K，日薪/时薪时单位是元） */
        public Double min;
        public Double max;
        public Double median;

        public Integer minK;      // 最小K（单位：K/月），仅月薪口径
        public Integer maxK;      // 最大K（单位：K/月），仅月薪口径
        public Integer months;    // 月数（默认12），仅月薪口径
        public Double medianK;    // 中位数K，<b>仅月薪口径非空</b>（薪资桶/区间筛选沿用这个字段）
        public Long annualTotal;  // 年包（单位：元），仅月薪口径

        /** 中位数日薪（元/天），<b>仅日薪口径非空</b>。时薪不折算成日薪 —— 见 parseSalary 的说明 */
        public Double medianDaily;
    }

    /** 月薪口径（原始数据形如 20-40K、35-65K·16薪） */
    public static final String UNIT_MONTH = "MONTH";
    /** 日薪口径（原始数据形如 200-250元/天 —— 实习岗几乎都是这种） */
    public static final String UNIT_DAY = "DAY";
    /** 时薪口径（原始数据形如 25-30元/小时） */
    public static final String UNIT_HOUR = "HOUR";

    /**
     * 解析薪资字符串。
     *
     * <p>支持的口径：
     * <ul>
     *   <li>月薪 {@code 20-40K} / {@code 30K·15薪} / {@code 8000-12000元/月}</li>
     *   <li>日薪 {@code 200-250元/天} / {@code 200元/天}（实习岗绝大多数是这种）</li>
     *   <li>时薪 {@code 25-30元/小时} / {@code 25元/时}</li>
     *   <li>{@code 面议} 等无法解析的返回 null</li>
     * </ul>
     *
     * <p><b>为什么不把日薪折算成月薪</b>：实习日薪（150~250 元）按 21.75 天折出约 3~5K/月，
     * 和正职月薪（10~40K）根本不是同一件事，混在一起求平均两个数字都会失真。
     * 所以按口径分别统计，月薪看 {@code medianK}，日薪看 {@code medianDaily}，
     * 时薪只解析出区间、不做折算（用它的人自己知道一周排几天班）。
     */
    public static SalaryInfo parseSalary(String salary) {
        if (salary == null) return null;
        String s = salary.trim();
        if (s.isEmpty()) return null;
        if (s.contains("面议")) return null;
        s = s.replace(" ", "");

        // ---------- 日薪 / 时薪：带「元/天」「元/时」「元/小时」单位 ----------
        // 必须放在 K 口径之前：这类字符串里也可能出现别的数字，先按单位认定，
        // 认定不了再退回"K = 月薪"的老逻辑（老数据里 20-40K 这类没有单位词）。
        Matcher mDay = Pattern.compile("^(\\d+)[-~](\\d+)元[/每](天|日)$").matcher(s);
        Matcher mDayOne = Pattern.compile("^(\\d+)元[/每](天|日)$").matcher(s);
        Matcher mHour = Pattern.compile("^(\\d+)[-~](\\d+)元[/每](小?时)$").matcher(s);
        Matcher mHourOne = Pattern.compile("^(\\d+)元[/每](小?时)$").matcher(s);
        Matcher mMonthYuan = Pattern.compile("^(\\d+)[-~](\\d+)元[/每]月$").matcher(s);

        SalaryInfo info = new SalaryInfo();
        // 注意：Matcher.find() 会推进匹配位置，同一个 Matcher 只能判一次 ——
        // 所以每种形态用一个独立分支，不要写成 "if (a.find() || b.find())" 再回头取 a/b。
        if (mDay.find()) {
            info.unit = UNIT_DAY;
            info.min = Double.parseDouble(mDay.group(1));
            info.max = Double.parseDouble(mDay.group(2));
        } else if (mDayOne.find()) {
            info.unit = UNIT_DAY;
            info.min = Double.parseDouble(mDayOne.group(1));
            info.max = info.min;
        } else if (mHour.find()) {
            info.unit = UNIT_HOUR;
            info.min = Double.parseDouble(mHour.group(1));
            info.max = Double.parseDouble(mHour.group(2));
        } else if (mHourOne.find()) {
            info.unit = UNIT_HOUR;
            info.min = Double.parseDouble(mHourOne.group(1));
            info.max = info.min;
        } else if (mMonthYuan.find()) {
            // 8000-12000元/月 → 折算成 K/月，与 "20-40K" 同口径
            info.unit = UNIT_MONTH;
            info.min = Double.parseDouble(mMonthYuan.group(1)) / 1000.0;
            info.max = Double.parseDouble(mMonthYuan.group(2)) / 1000.0;
            info.minK = (int) Math.round(info.min);
            info.maxK = (int) Math.round(info.max);
            info.months = 12;
            info.median = (info.min + info.max) / 2.0;
            info.medianK = info.median;
            info.annualTotal = Math.round(info.medianK * 1000 * 12);
            return info;
        }

        if (info.unit != null) {
            // 日薪 / 时薪：只填原始单位下的区间与中位数
            info.median = (info.min + info.max) / 2.0;
            if (UNIT_DAY.equals(info.unit)) {
                info.medianDaily = info.median;
            }
            return info;
        }

        // ---------- 月薪（K）：保持原有解析，兼容老数据 ----------
        Integer months = 12;
        // 提取 months=xx（如 ·16薪）
        Matcher mMonths = Pattern.compile("[·\\.\\-]?([0-9]+)薪").matcher(s);
        if (mMonths.find()) {
            try { months = Integer.parseInt(mMonths.group(1)); } catch (Exception ignore) {}
            // 去掉薪资后缀以便解析区间
            s = s.substring(0, mMonths.start());
        }

        // 提取区间或单值（K/k）
        Integer minK = null, maxK = null;
        Matcher mRange = Pattern.compile("^(\\d+)-(\\d+)[Kk]$").matcher(s);
        Matcher mSingle = Pattern.compile("^(\\d+)[Kk]$").matcher(s);
        if (mRange.find()) {
            minK = Integer.parseInt(mRange.group(1));
            maxK = Integer.parseInt(mRange.group(2));
        } else if (mSingle.find()) {
            minK = Integer.parseInt(mSingle.group(1));
            maxK = minK;
        } else {
            // 尝试更宽松解析：去掉非数字和K以外字符
            String cleaned = s.replaceAll("[^0-9Kk\\-]", "");
            mRange = Pattern.compile("^(\\d+)-(\\d+)[Kk]$").matcher(cleaned);
            mSingle = Pattern.compile("^(\\d+)[Kk]$").matcher(cleaned);
            if (mRange.find()) {
                minK = Integer.parseInt(mRange.group(1));
                maxK = Integer.parseInt(mRange.group(2));
            } else if (mSingle.find()) {
                minK = Integer.parseInt(mSingle.group(1));
                maxK = minK;
            }
        }

        if (minK == null || maxK == null) return null;

        info.unit = UNIT_MONTH;
        info.min = minK.doubleValue();
        info.max = maxK.doubleValue();
        info.minK = minK;
        info.maxK = maxK;
        info.months = months != null ? months : 12;
        info.medianK = (minK + maxK) / 2.0;
        info.median = info.medianK;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /** KPI 指标 */
    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered;
        public long failed;
        /** 平均月薪（中位数 K/月）；只由月薪口径的岗位算出，没有则 null */
        public Double avgMonthlyK;
        /** 平均日薪（中位数 元/天）；只由日薪口径的岗位算出，没有则 null */
        public Double avgDailyYuan;
    }

    /** 通用 name-value 项 */
    public static class NameValue {
        public String name;
        public long value;
        public NameValue() {}
        public NameValue(String name, long value) { this.name = name; this.value = value; }
    }

    /** 薪资桶 */
    public static class BucketValue {
        public String bucket;
        public long value;
        public BucketValue() {}
        public BucketValue(String bucket, long value) { this.bucket = bucket; this.value = value; }
    }

    /** 图表集合 */
    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byIndustry;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        /** 日薪档位（元/天）。与 salaryBuckets 分开：两套口径的档位没法画在同一根轴上 */
        public List<BucketValue> dailySalaryBuckets;
        public List<NameValue> dailyTrend; // date 作为 name
        public List<NameValue> hrActivity;
    }

    /** 统计响应 */
    public static class StatsResponse {
        public Kpi kpi;
        public Charts charts;
    }

    /** 列表分页响应 */
    public static class PagedResult {
        public List<BossJobDataEntity> items;
        public long total;
        public int page;
        public int size;
    }

    /**
     * 获取投递分析统计与图表数据
     */
    public StatsResponse getBossStats() {
        StatsResponse resp = new StatsResponse();
        resp.kpi = new Kpi();
        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byIndustry = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailySalaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();
        charts.hrActivity = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            // KPI 基本计数
            resp.kpi.total = scalarCount(conn, "SELECT COUNT(*) FROM boss_data");
            resp.kpi.delivered = scalarCount(conn, "SELECT COUNT(*) FROM boss_data WHERE delivery_status='已投递'");
            resp.kpi.pending = scalarCount(conn, "SELECT COUNT(*) FROM boss_data WHERE delivery_status='未投递'");
            resp.kpi.filtered = scalarCount(conn, "SELECT COUNT(*) FROM boss_data WHERE delivery_status='已过滤'");
            resp.kpi.failed = scalarCount(conn, "SELECT COUNT(*) FROM boss_data WHERE delivery_status='投递失败'");

            // 平均薪资：按口径分开算（月薪 K / 日薪 元）。混在一起算会两个数字都失真 ——
            // 实习日薪折成月薪也就 3~5K，和正职 10~40K 不是同一件事。
            double sumMonthlyK = 0.0; long cntMonthly = 0;
            double sumDaily = 0.0; long cntDaily = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT salary FROM boss_data WHERE salary IS NOT NULL")) {
                while (rs.next()) {
                    SalaryInfo info = parseSalary(rs.getString(1));
                    if (info == null) continue;
                    if (info.medianK != null) { sumMonthlyK += info.medianK; cntMonthly++; }
                    if (info.medianDaily != null) { sumDaily += info.medianDaily; cntDaily++; }
                }
            }
            resp.kpi.avgMonthlyK = cntMonthly > 0 ? Math.round((sumMonthlyK / cntMonthly) * 100.0) / 100.0 : null;
            resp.kpi.avgDailyYuan = cntDaily > 0 ? Math.round((sumDaily / cntDaily) * 100.0) / 100.0 : null;

            // byStatus
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT delivery_status, COUNT(*) AS cnt FROM boss_data GROUP BY delivery_status")) {
                while (rs.next()) charts.byStatus.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // byCity TOP10（保留）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT location, COUNT(*) AS cnt FROM boss_data GROUP BY location ORDER BY cnt DESC LIMIT 10")) {
                while (rs.next()) charts.byCity.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // byIndustry TOP10（新增）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT industry, COUNT(*) AS cnt FROM boss_data GROUP BY industry ORDER BY cnt DESC LIMIT 10")) {
                while (rs.next()) charts.byIndustry.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // byCompany TOP10
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT company_name, COUNT(*) AS cnt FROM boss_data GROUP BY company_name ORDER BY cnt DESC LIMIT 10")) {
                while (rs.next()) charts.byCompany.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // byExperience
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT experience, COUNT(*) AS cnt FROM boss_data GROUP BY experience")) {
                while (rs.next()) charts.byExperience.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // byDegree
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT degree, COUNT(*) AS cnt FROM boss_data GROUP BY degree")) {
                while (rs.next()) charts.byDegree.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // dailyTrend（按日期聚合）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT substr(created_at,1,10) AS d, COUNT(*) AS cnt FROM boss_data GROUP BY d ORDER BY d")) {
                while (rs.next()) charts.dailyTrend.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // hrActivity（仅统计活跃状态非空的 hr_name 计数）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT hr_name, COUNT(*) AS cnt FROM boss_data WHERE hr_active_status IS NOT NULL AND TRIM(hr_active_status) <> '' GROUP BY hr_name")) {
                while (rs.next()) charts.hrActivity.add(new NameValue(nullSafe(rs.getString(1)), rs.getLong(2)));
            }

            // salaryBuckets（基于中位数K按档统计，动态上限）
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            List<Double> medians = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT salary FROM boss_data WHERE salary IS NOT NULL")) {
                while (rs.next()) {
                    SalaryInfo info = parseSalary(rs.getString(1));
                    if (info == null || info.medianK == null) continue;
                    double m = info.medianK;
                    medians.add(m);
                    if (m > maxMedian) maxMedian = m;
                }
            }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5; // 向上取整到5的倍数
            if (topEdge <= 20) topEdge = 25; // 避免区间过窄
            for (double m : medians) {
                if (m < 10) b0_10++;
                else if (m < 15) b10_15++;
                else if (m < 20) b15_20++;
                else if (m < topEdge) b20_top++;
                else b_ge_top++;
            }
            charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
            charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
            charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
            charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
            charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

            // dailySalaryBuckets（日薪档位，元/天）：实习岗的薪资几乎都是这个口径，
            // 不分出来的话它们一条也进不了上面的月薪桶，图上看着就像"没有薪资数据"。
            long d0_150=0,d150_200=0,d200_250=0,d250_300=0,d_ge_300=0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT salary FROM boss_data WHERE salary IS NOT NULL")) {
                while (rs.next()) {
                    SalaryInfo info = parseSalary(rs.getString(1));
                    if (info == null || info.medianDaily == null) continue;
                    double m = info.medianDaily;
                    if (m < 150) d0_150++;
                    else if (m < 200) d150_200++;
                    else if (m < 250) d200_250++;
                    else if (m < 300) d250_300++;
                    else d_ge_300++;
                }
            }
            charts.dailySalaryBuckets.add(new BucketValue("0-150元", d0_150));
            charts.dailySalaryBuckets.add(new BucketValue("150-200元", d150_200));
            charts.dailySalaryBuckets.add(new BucketValue("200-250元", d200_250));
            charts.dailySalaryBuckets.add(new BucketValue("250-300元", d250_300));
            charts.dailySalaryBuckets.add(new BucketValue(">=300元", d_ge_300));

            resp.charts = charts;
            return resp;
        } catch (Exception e) {
            log.error("获取Boss统计失败: {}", e.getMessage(), e);
            // 失败时返回空集合，避免前端崩溃
            resp.charts = charts;
            return resp;
        }
    }

    /**
     * 获取投递分析统计与图表数据（按筛选条件）
     */
    public StatsResponse getBossStats(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            boolean filterHeadhunter
    ) {
        StatsResponse resp = new StatsResponse();
        resp.kpi = new Kpi();
        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byIndustry = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();
        charts.hrActivity = new ArrayList<>();

        try {
            // 构造与列表相同的筛选条件
            QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
            if (statuses != null && !statuses.isEmpty()) {
                wrapper.in("delivery_status", statuses);
            }
            if (StringUtils.isNotBlank(location)) wrapper.eq("location", location);
            if (StringUtils.isNotBlank(experience)) wrapper.eq("experience", experience);
            if (StringUtils.isNotBlank(degree)) wrapper.eq("degree", degree);

            if (StringUtils.isNotBlank(keyword)) {
                wrapper.and(w -> w.like("company_name", keyword)
                        .or().like("job_name", keyword)
                        .or().like("hr_name", keyword));
            }
            if (filterHeadhunter) {
                wrapper.and(w -> w.isNull("hr_position").or().notLike("hr_position", "猎头"));
            }
            wrapper.orderByDesc("created_at");

            List<BossJobDataEntity> all = bossJobDataMapper.selectList(wrapper);

            // 内存进行薪资区间过滤。
            // 区间一律按「月薪 K」这一个口径比：日薪岗（如 200-250元/天）的 medianK 是 null，
            // 所以只在填了区间时会被筛掉 —— 不填区间就全部保留，不影响正常浏览。
            List<BossJobDataEntity> filtered = new ArrayList<>();
            double sumMonthlyK = 0.0; long cntMonthly = 0;
            double sumDaily = 0.0; long cntDaily = 0;
            for (BossJobDataEntity e : all) {
                SalaryInfo info = parseSalary(e.getSalary());
                boolean passSalary;
                if (minK == null && maxK == null) {
                    passSalary = true;
                } else {
                    if (info == null || info.medianK == null) passSalary = false; // 面议 / 日薪 / 不可解析
                    else {
                        boolean ok = true;
                        if (minK != null) ok = ok && (info.medianK >= minK);
                        if (maxK != null) ok = ok && (info.medianK <= maxK);
                        passSalary = ok;
                    }
                }
                if (passSalary) {
                    filtered.add(e);
                    if (info != null) {
                        if (info.medianK != null) { sumMonthlyK += info.medianK; cntMonthly++; }
                        if (info.medianDaily != null) { sumDaily += info.medianDaily; cntDaily++; }
                    }
                }
            }

            // KPI
            resp.kpi.total = filtered.size();
            resp.kpi.delivered = filtered.stream().filter(e -> "已投递".equals(e.getDeliveryStatus())).count();
            resp.kpi.pending = filtered.stream().filter(e -> "未投递".equals(e.getDeliveryStatus())).count();
            resp.kpi.filtered = filtered.stream().filter(e -> "已过滤".equals(e.getDeliveryStatus())).count();
            resp.kpi.failed = filtered.stream().filter(e -> "投递失败".equals(e.getDeliveryStatus())).count();
            resp.kpi.avgMonthlyK = cntMonthly > 0 ? Math.round((sumMonthlyK / cntMonthly) * 100.0) / 100.0 : null;
            resp.kpi.avgDailyYuan = cntDaily > 0 ? Math.round((sumDaily / cntDaily) * 100.0) / 100.0 : null;

            // Charts - 分组聚合（在内存中统计）
            Map<String, Long> byStatus = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getDeliveryStatus()), Collectors.counting()));
            byStatus.forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));

            Map<String, Long> byCity = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()));
            byCity.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCity.add(new NameValue(en.getKey(), en.getValue())));

            // byIndustry TOP10
            Map<String, Long> byIndustry = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getIndustry()), Collectors.counting()));
            byIndustry.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byIndustry.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byCompany = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()));
            byCompany.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> charts.byCompany.add(new NameValue(en.getKey(), en.getValue())));

            Map<String, Long> byExp = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()));
            byExp.forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));

            Map<String, Long> byDeg = filtered.stream()
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()));
            byDeg.forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));

            // dailyTrend（created_at 到天）
            Map<String, Long> byDay = filtered.stream()
                    .collect(Collectors.groupingBy(e -> {
                        LocalDateTime t = e.getCreatedAt();
                        return t == null ? "未知" : String.format("%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
                    }, Collectors.counting()));
            byDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

            // hrActivity（活跃状态非空的 hr_name 计数）
            Map<String, Long> hrAct = filtered.stream()
                    .filter(e -> e.getHrActiveStatus() != null && !e.getHrActiveStatus().trim().isEmpty())
                    .collect(Collectors.groupingBy(e -> nullSafe(e.getHrName()), Collectors.counting()));
            hrAct.forEach((k, v) -> charts.hrActivity.add(new NameValue(k, v)));

            // salaryBuckets（动态上限）
            long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
            double maxMedian = 0.0;
            List<Double> medians = new ArrayList<>();
            for (BossJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                double m = info.medianK;
                medians.add(m);
                if (m > maxMedian) maxMedian = m;
            }
            int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
            if (topEdge <= 20) topEdge = 25;
            for (double m : medians) {
                if (m < 10) b0_10++;
                else if (m < 15) b10_15++;
                else if (m < 20) b15_20++;
                else if (m < topEdge) b20_top++;
                else b_ge_top++;
            }
            charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
            charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
            charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
            charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
            charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

            // dailySalaryBuckets（日薪档位，元/天），与无参版口径保持一致
            long d0_150=0,d150_200=0,d200_250=0,d250_300=0,d_ge_300=0;
            for (BossJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianDaily == null) continue;
                double m = info.medianDaily;
                if (m < 150) d0_150++;
                else if (m < 200) d150_200++;
                else if (m < 250) d200_250++;
                else if (m < 300) d250_300++;
                else d_ge_300++;
            }
            charts.dailySalaryBuckets.add(new BucketValue("0-150元", d0_150));
            charts.dailySalaryBuckets.add(new BucketValue("150-200元", d150_200));
            charts.dailySalaryBuckets.add(new BucketValue("200-250元", d200_250));
            charts.dailySalaryBuckets.add(new BucketValue("250-300元", d250_300));
            charts.dailySalaryBuckets.add(new BucketValue(">=300元", d_ge_300));

            resp.charts = charts;
            return resp;
        } catch (Exception e) {
            log.error("获取Boss筛选统计失败: {}", e.getMessage(), e);
            resp.charts = charts;
            return resp;
        }
    }

    private long scalarCount(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String nullSafe(String s) { return s == null || s.isEmpty() ? "未知" : s; }

    /**
     * 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K）
     */
    public PagedResult listBossJobs(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size,
            boolean filterHeadhunter
    ) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;

        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses);
        }
        if (StringUtils.isNotBlank(location)) wrapper.eq("location", location);
        if (StringUtils.isNotBlank(experience)) wrapper.eq("experience", experience);
        if (StringUtils.isNotBlank(degree)) wrapper.eq("degree", degree);

        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w.like("company_name", keyword)
                    .or().like("job_name", keyword)
                    .or().like("hr_name", keyword));
        }

        // 查询阶段过滤猎头：hr_position 不包含“猎头”或为空
        if (filterHeadhunter) {
            wrapper.and(w -> w.isNull("hr_position").or().notLike("hr_position", "猎头"));
        }

        wrapper.orderByDesc("created_at");

        // 取符合条件的记录（猎头已在查询阶段过滤），随后在内存进行薪资区间过滤与分页
        List<BossJobDataEntity> all = bossJobDataMapper.selectList(wrapper);

        List<BossJobDataEntity> filtered = new ArrayList<>();
        for (BossJobDataEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue; // 面议或不可解析
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<BossJobDataEntity> pageItems = from >= to ? Collections.emptyList() : filtered.subList(from, to);

        PagedResult result = new PagedResult();
        result.items = pageItems;
        result.total = total;
        result.page = page;
        result.size = size;
        return result;
    }

    /**
     * 刷新数据：执行列顺序检查，并执行 VACUUM 以优化数据库；返回当前总数
     */
    public Map<String, Object> reloadBossData() {
        Map<String, Object> resp = new HashMap<>();
        Connection conn = null;
        try {
            ensureBossDataColumnOrder();
            conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                try { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (Exception ignore) {}
                try { st.execute("VACUUM"); } catch (Exception ignore) {}
            }
            long total = scalarCount(conn, "SELECT COUNT(*) FROM boss_data");
            resp.put("success", true);
            resp.put("message", "刷新完成");
            resp.put("total", total);
        } catch (Exception e) {
            log.warn("刷新boss_data失败: {}", e.getMessage());
            resp.put("success", false);
            resp.put("message", "刷新失败: " + e.getMessage());
        } finally {
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
        return resp;
    }
}
