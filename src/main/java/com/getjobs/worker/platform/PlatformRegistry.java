package com.getjobs.worker.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已注册平台的注册表 —— 平台"挂载 / 取下"的唯一入口。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是 {@code List<JobPlatform>}</b>：
 * Spring 在"没有任何候选 bean"时对集合注入的处理很不友好（可能直接抛
 * NoSuchBeanDefinitionException），而这个项目的硬要求是
 * <b>「删掉某个平台的代码后，程序依然要能启动、能跑」</b>。
 * ObjectProvider 的 {@code stream()} 在没有实现时返回空流，天然满足这个要求。
 *
 * <p>因此：<b>任何地方都不许直接注入具体的平台实现</b>（如 {@code Boss} / {@code BossJobService}），
 * 只能通过本注册表按 id 取。这是"插件式"的纪律，破一次就会在删平台时启动失败。
 */
@Slf4j
@Component
public class PlatformRegistry {

    /** id -> 平台实现（保持注册顺序，便于日志与前端稳定展示） */
    private final Map<String, JobPlatform> platforms = new LinkedHashMap<>();

    public PlatformRegistry(ObjectProvider<JobPlatform> platformProvider) {
        platformProvider.stream()
                .sorted(Comparator.comparing(JobPlatform::id))
                .forEach(platform -> {
                    String id = platform.id();
                    if (id == null || id.isBlank()) {
                        log.warn("忽略 id 为空的平台实现: {}", platform.getClass().getName());
                        return;
                    }
                    JobPlatform previous = platforms.putIfAbsent(id, platform);
                    if (previous != null) {
                        log.warn("平台 id 重复，保留先注册的那个: id={} 保留={} 忽略={}",
                                id, previous.getClass().getSimpleName(), platform.getClass().getSimpleName());
                    }
                });

        if (platforms.isEmpty()) {
            // 不抛异常：没有平台代码时应用照常启动（前端会显示"当前没有可用平台"）
            log.warn("没有发现任何 JobPlatform 实现 —— 应用仍会正常启动，但无法执行投递任务");
        } else {
            log.info("已注册求职平台 {} 个: {}", platforms.size(), platforms.keySet());
        }
    }

    /** 按 id 取平台 */
    public Optional<JobPlatform> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(platforms.get(id));
    }

    /** 全部平台（按 id 排序，前端与日志都用它） */
    public List<JobPlatform> all() {
        return new ArrayList<>(platforms.values());
    }

    /** 全部平台 id */
    public List<String> ids() {
        return new ArrayList<>(platforms.keySet());
    }

    /** 是否一个平台都没有 */
    public boolean isEmpty() {
        return platforms.isEmpty();
    }

    /** 平台数量 */
    public int size() {
        return platforms.size();
    }
}
