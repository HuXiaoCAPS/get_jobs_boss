package com.getjobs.worker.platform.fake;

import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.model.JobDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯内存的 {@link DeliveryStore}，只用于"假平台"验收。
 *
 * <p>刻意<b>不注册成 Spring bean</b>（不是 {@code @Component}）：如果它是 bean，
 * 就会和 {@code BossDeliveryStore} 争同一个 {@link DeliveryStore} 注入点。
 * 这里由验收入口手动 new 出来，既能跑通流程，又绝不会碰真实数据库。
 */
public class InMemoryDeliveryStore implements DeliveryStore {

    private final Map<String, Set<String>> blacklists = new HashMap<>();
    private final Map<String, Set<String>> delivered = new HashMap<>();
    private final Map<String, Map<String, String>> snapshots = new HashMap<>();
    private final List<String> records = new ArrayList<>();

    public InMemoryDeliveryStore() {
        // 预置黑名单，用来验证"黑名单命中 → 已过滤"这条路径。
        // 刻意用公司名 + 岗位名（而不是依赖 jd-rules.txt），这样验收结果与机器上的规则文件内容无关。
        blacklists.put(BLACKLIST_COMPANY, new LinkedHashSet<>(Set.of("黑名单公司")));
        blacklists.put(BLACKLIST_RECRUITER, new LinkedHashSet<>());
        blacklists.put(BLACKLIST_JOB, new LinkedHashSet<>(Set.of("数仓开发")));
    }

    @Override
    public Set<String> blacklist(String type) {
        return blacklists.computeIfAbsent(type, k -> new LinkedHashSet<>());
    }

    @Override
    public void addBlacklist(String type, String value) {
        blacklist(type).add(value);
    }

    @Override
    public Set<String> deliveredExternalIds(String platform) {
        return delivered.computeIfAbsent(platform, k -> new LinkedHashSet<>());
    }

    @Override
    public void saveDiscovered(String platform, JobDetail detail, String status, String note) {
        records.add(String.format("入库 %s | %s | 状态=%s | 原因=%s",
                detail.getExternalId(), detail.describe(), status, note == null ? "-" : note));
    }

    @Override
    public void updateStatus(String platform, String externalId, String recruiterId, String status) {
        records.add(String.format("状态更新 %s → %s", externalId, status));
        if (STATUS_DELIVERED.equals(status)) {
            deliveredExternalIds(platform).add(externalId);
        }
    }

    @Override
    public Map<String, String> chatSnapshot(String platform) {
        return snapshots.getOrDefault(platform, Collections.emptyMap());
    }

    @Override
    public void replaceChatSnapshot(String platform, Map<String, String> snapshot) {
        snapshots.put(platform, new LinkedHashMap<>(snapshot));
    }

    /** 验收用：把过程记录下来，接口直接回给调用方看 */
    public List<String> records() {
        return new ArrayList<>(records);
    }

    /** 验收用：当前所有黑名单 */
    public Map<String, Set<String>> blacklists() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        blacklists.forEach((k, v) -> copy.put(k, new LinkedHashSet<>(v)));
        return copy;
    }
}
