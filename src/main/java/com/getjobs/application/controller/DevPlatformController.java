package com.getjobs.application.controller;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.ConfigFileService;
import com.getjobs.application.service.DeliveryPolicyService;
import com.getjobs.worker.platform.DeliveryRunner;
import com.getjobs.worker.platform.fake.FakePlatform;
import com.getjobs.worker.platform.fake.InMemoryDeliveryStore;
import com.getjobs.worker.dto.JobProgressMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发/验收专用接口（只在 dev profile 生效）。
 *
 * <p>唯一的用途：用「假平台」跑通一轮投递流程，证明<b>流程层与具体平台解耦</b> ——
 * 不启浏览器、不连数据库、不发网络请求，就能看到搜索 → 详情 → 过滤 → 投递 → 落库的完整结果。
 *
 * <p>为什么手动 {@code new} 出 store / runner，而不是让 Spring 注入：
 * 假平台用的 {@code InMemoryDeliveryStore} 一旦成为 bean，就会和真实的
 * {@code BossDeliveryStore} 抢同一个注入点；手动构造既能验收，又绝不可能碰到真实数据。
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@CrossOrigin(origins = "*")
public class DevPlatformController {

    private final AiService aiService;
    private final DeliveryPolicyService deliveryPolicyService;
    /** 假平台验收也要按"当前生效的配置"去找规则文件，所以这里一并注入 */
    private final ConfigFileService configFileService;

    public DevPlatformController(AiService aiService,
                                 DeliveryPolicyService deliveryPolicyService,
                                 ConfigFileService configFileService) {
        this.aiService = aiService;
        this.deliveryPolicyService = deliveryPolicyService;
        this.configFileService = configFileService;
    }

    /**
     * 跑一次「假平台」投递（不触碰真实数据）。
     *
     * <p>看结果的方式：{@code records} 里应有 6 条岗位入库 + 正确的状态更新；
     * {@code result} 应为 delivered=1、filtered=3、skipped=1（对应 FakePlatform 里设计的 6 个岗位）。
     *
     * <p>用一个"不间隔、不调 AI"的快速策略（{@link FakePlatform#fastPolicyForVerification()}），
     * 免得 6 个岗位之间真要各等 10 秒。
     */
    @PostMapping("/fake-delivery")
    public Map<String, Object> fakeDelivery() {
        InMemoryDeliveryStore store = new InMemoryDeliveryStore();
        FakePlatform platform = new FakePlatform(store);
        DeliveryRunner runner = new DeliveryRunner(aiService, deliveryPolicyService, configFileService);

        List<JobProgressMessage> progress = new ArrayList<>();
        DeliveryRunner.RunResult result = runner.run(
                platform, FakePlatform.fastPolicyForVerification(), progress::add, () -> false, 0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("platform", platform.id());
        out.put("result", Map.of(
                "delivered", result.delivered,
                "filtered", result.filtered,
                "skipped", result.skipped));
        out.put("progress", progress);
        out.put("records", store.records());
        out.put("blacklists", store.blacklists());
        return out;
    }
}
