package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.getjobs.application.entity.ConfigEntity;
import com.getjobs.application.mapper.ConfigMapper;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.boss.BossConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置服务类（本副本只服务 Boss 平台）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    /**
     * 敏感配置键：只允许在 config/boss.yaml 里维护，既不下发给网页端、也不接受网页端写入。
     * <p>
     * config 表是通用键值表，AI 凭据（BASE_URL / API_KEY / MODEL）和通知设置
     * （HOOK_URL / BOT_IS_SEND）混在一起。上游前端有「环境变量配置」页要编辑这些键，
     * 所以 ConfigController 干脆整表下发；本副本已取消网页端编辑入口（API Key 只认配置文件），
     * 那条全量下发就只剩泄漏：GET /api/config 会把 API_KEY 明文吐给浏览器。
     * <p>
     * 集中声明在这里，是为了将来新增 AI 凭据时只需改一处。
     */
    public static final Set<String> SENSITIVE_KEYS = Set.of("API_KEY", "BASE_URL", "MODEL");

    /**
     * 是否为敏感配置键（忽略大小写与首尾空白）
     */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.trim().toUpperCase(Locale.ROOT));
    }

    private final ConfigMapper configMapper;
    private final BossService bossService;

    /**
     * 下发给网页端的配置：剔除 {@link #SENSITIVE_KEYS}。
     * <p>
     * 前端只用到 HOOK_URL / BOT_IS_SEND，仍按"整表减去敏感键"返回而不是写死白名单，
     * 这样将来新增非敏感配置项时不用改接口签名。
     *
     * @return 不含敏感键的配置Map，key为config_key，value为config_value
     */
    public Map<String, String> getPublicConfigsAsMap() {
        Map<String, String> configMap = readAllConfigsAsMap();
        configMap.keySet().removeIf(ConfigService::isSensitiveKey);
        return configMap;
    }

    /**
     * 读取整张 config 表（含敏感键）。
     * <p>
     * 刻意保持私有：任何直接把它塞进 HTTP 响应的写法都会泄漏 API_KEY。
     *
     * @return 配置Map，key为config_key，value为config_value
     */
    private Map<String, String> readAllConfigsAsMap() {
        List<ConfigEntity> configs = configMapper.selectList(null);
        Map<String, String> configMap = new HashMap<>();

        for (ConfigEntity config : configs) {
            configMap.put(config.getConfigKey(), config.getConfigValue());
        }

        return configMap;
    }

    /**
     * 根据配置键获取配置
     * @param configKey 配置键
     * @return 配置实体
     */
    public ConfigEntity getConfigByKey(String configKey) {
        LambdaQueryWrapper<ConfigEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConfigEntity::getConfigKey, configKey);
        return configMapper.selectOne(queryWrapper);
    }

    /**
     * 根据分类获取配置列表
     * @param category 分类
     * @return 配置列表
     */
    public List<ConfigEntity> getConfigsByCategory(String category) {
        LambdaQueryWrapper<ConfigEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConfigEntity::getCategory, category);
        return configMapper.selectList(queryWrapper);
    }

    /**
     * 根据配置键获取配置值（可能为null）
     * @param configKey 配置键
     * @return 配置值或null
     */
    public String getConfigValue(String configKey) {
        ConfigEntity entity = getConfigByKey(configKey);
        return entity != null ? entity.getConfigValue() : null;
    }

    /**
     * 根据配置键获取必填配置值（缺失或空则抛异常）
     * @param configKey 配置键
     * @return 配置值（非空）
     * @throws IllegalStateException 当配置缺失或空白时抛出
     */
    public String requireConfigValue(String configKey) {
        String value = getConfigValue(configKey);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必要配置: " + configKey);
        }
        return value;
    }

    /**
     * 获取AI调用所需的基础配置（BASE_URL, API_KEY, MODEL）
     * @return 配置Map，包含 BASE_URL, API_KEY, MODEL 键
     */
    public Map<String, String> getAiConfigs() {
        Map<String, String> result = new HashMap<>();
        String baseUrl = requireConfigValue("BASE_URL");
        String apiKey = requireConfigValue("API_KEY");
        String model = requireConfigValue("MODEL");
        result.put("BASE_URL", baseUrl);
        result.put("API_KEY", apiKey);
        result.put("MODEL", model);
        return result;
    }

    /**
     * 批量更新配置，键不存在时自动新增
     * @param configMap 配置Map，key为config_key，value为config_value
     * @return 成功写入的配置数量
     */
    @Transactional
    public int batchUpdateConfigs(Map<String, String> configMap) {
        int updateCount = 0;

        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            if (upsertConfig(entry.getKey(), entry.getValue())) {
                updateCount++;
            }
        }

        return updateCount;
    }

    /**
     * 更新单个配置，键不存在时自动新增
     * @param configKey 配置键
     * @param configValue 配置值
     * @return 是否写入成功
     */
    @Transactional
    public boolean updateConfig(String configKey, String configValue) {
        return upsertConfig(configKey, configValue);
    }

    /**
     * 写入一个配置项：存在则更新，不存在则新增。
     * <p>
     * 这里必须是 upsert 而不是单纯的 update。config 表里的键（BASE_URL、API_KEY、
     * MODEL、HOOK_URL、BOT_IS_SEND）原先依赖数据库文件预先带好，一旦某个键不存在，
     * 旧实现只打一行 warn 就返回 false，接口在前端看来是保存成功了，实际一个字都没
     * 落库，而读取侧 requireConfigValue 会持续抛"缺少必要配置"——用户根本查不出原因。
     * 新增配置键时同样会踩这个坑：老用户的库里没有那一行，写入就静默失败。
     */
    private boolean upsertConfig(String configKey, String configValue) {
        if (configKey == null || configKey.isBlank()) {
            log.warn("配置键为空，跳过写入");
            return false;
        }

        ConfigEntity config = getConfigByKey(configKey);

        if (config != null) {
            config.setConfigValue(configValue);
            config.setUpdatedAt(LocalDateTime.now());
            if (configMapper.updateById(config) > 0) {
                log.info("更新配置: {}", configKey);
                return true;
            }
            log.warn("更新配置失败: {}", configKey);
            return false;
        }

        ConfigEntity created = new ConfigEntity();
        created.setConfigKey(configKey);
        created.setConfigValue(configValue);
        created.setConfigType("string");
        created.setCategory("custom");
        created.setDescription("由配置接口自动创建");
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(LocalDateTime.now());

        if (configMapper.insert(created) > 0) {
            log.info("配置键不存在，已自动创建: {}", configKey);
            return true;
        }
        log.warn("创建配置失败: {}", configKey);
        return false;
    }

    /**
     * 创建新配置
     * @param config 配置实体
     * @return 是否创建成功
     */
    @Transactional
    public boolean createConfig(ConfigEntity config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());

        int result = configMapper.insert(config);

        if (result > 0) {
            log.info("创建配置成功: {} = {}", config.getConfigKey(), config.getConfigValue());
            return true;
        }

        return false;
    }

    /**
     * 统一入口：从专表 boss_config 读取并构建 BossConfig
     */
    public BossConfig getBossConfig() {
        return bossService.loadBossConfig();
    }
}
