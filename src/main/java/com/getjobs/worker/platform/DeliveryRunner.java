package com.getjobs.worker.platform;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.worker.platform.filter.JdRuleFilter;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.platform.model.ChatReply;
import com.getjobs.worker.platform.model.DeliveryPolicy;
import com.getjobs.worker.platform.model.JobCandidate;
import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.SearchQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 投递流程 —— <b>平台无关</b>的那一半。
 *
 * <p>它只做四件事：把 {@link JobPlatform} 提供的步骤串起来、做平台无关的过滤
 * （JD 规则 / 黑名单 / HR 活跃度 / 已投递去重）、调 AI 生成招呼语、把结果写进
 * {@link DeliveryStore} 并限速。
 *
 * <p><b>纪律（"插件式"的验收标准）</b>：
 * <ul>
 *   <li>这个类<b>不许</b> import 任何 {@code com.getjobs.worker.<平台>} 包（boss / liepin …）；</li>
 *   <li><b>不许</b>出现 Playwright 的任何类型（Page / Locator / BrowserContext …）；</li>
 *   <li>所有平台交互都从 {@link JobPlatform} 走，所有数据读写都从 {@link JobPlatform#store()} 走。</li>
 * </ul>
 * 只要这三条成立，"删掉某个平台的代码、程序照常运行、换一个平台不动流程"就是自然结果。
 *
 * <p><b>数据实现来自平台</b>（不是注入的）：Boss 落 {@code boss_data}，假平台落内存 ——
 * 数据落哪里本来就是平台知识。这样"用假平台试跑一遍"绝不会污染真实数据库。
 *
 * <p><b>线程</b>：由 {@code PlatformTaskManager} 通过 {@link JobPlatform#runTask} 调用，
 * 因此这里对平台的所有调用都天然跑在平台要求的线程上；不要把方法拆到别的线程里跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRunner {

    private final AiService aiService;
    private final com.getjobs.application.service.DeliveryPolicyService deliveryPolicyService;
    /** 用来知道「当前生效的是哪份配置」，从而定位对应的 JD 规则文件 */
    private final com.getjobs.application.service.ConfigFileService configFileService;

    /** JD 规则过滤器：每轮开始按当前配置 reload()，改完规则文件重跑任务即生效 */
    private final JdRuleFilter jdRuleFilter = new JdRuleFilter();

    /** 一轮投递的结果 */
    public static class RunResult {
        public final int delivered;
        public final int filtered;
        public final int skipped;

        public RunResult(int delivered, int filtered, int skipped) {
            this.delivered = delivered;
            this.filtered = filtered;
            this.skipped = skipped;
        }
    }

    /**
     * 跑一轮投递。
     *
     * @param platform 平台实现（由 {@link PlatformRegistry} 提供）
     * @param progress 进度回调，可为 null
     * @param shouldStop 停止信号，可为 null
     */
    public RunResult run(JobPlatform platform,
                         Consumer<JobProgressMessage> progress,
                         Supplier<Boolean> shouldStop) {
        return run(platform, progress, shouldStop, 0);
    }

    /**
     * 跑一轮投递，并限制本次最多投出去多少个。
     *
     * <p>投递策略（间隔 / AI 开关 / HR 阈值 / 兜底招呼语…）由全局的
     * {@code DeliveryPolicyService} 提供，<b>不再从平台上取</b> —— 那是"这轮怎么投"的规则，
     * 与用哪个网站招人无关。
     *
     * @param maxDeliveries 本次投递上限（投出去这么多个就收工）；&lt;= 0 表示不限
     */
    public RunResult run(JobPlatform platform,
                         Consumer<JobProgressMessage> progress,
                         Supplier<Boolean> shouldStop,
                         int maxDeliveries) {
        return run(platform, deliveryPolicyService.current(), progress, shouldStop, maxDeliveries);
    }

    /**
     * 跑一轮投递，显式指定投递策略。
     *
     * <p>给验收/测试用（例如假平台想要一个"不间隔、不调 AI"的快速策略）；
     * 正常路径请用上面那个重载，让它去读全局策略。
     */
    public RunResult run(JobPlatform platform,
                         DeliveryPolicy effectivePolicy,
                         Consumer<JobProgressMessage> progress,
                         Supplier<Boolean> shouldStop,
                         int maxDeliveries) {
        String pid = platform.id();
        String label = platform.displayName();
        Consumer<JobProgressMessage> report = progress == null ? m -> { } : progress;
        Supplier<Boolean> stop = shouldStop == null ? () -> false : shouldStop;
        DeliveryPolicy policy = effectivePolicy == null ? new DeliveryPolicy() : effectivePolicy;

        // 数据实现由平台提供：Boss → boss_data；假平台 → 内存
        DeliveryStore store = platform.store();
        if (store == null) {
            report.accept(JobProgressMessage.error(pid, "平台没有提供数据实现（DeliveryStore）"));
            return new RunResult(0, 0, 0);
        }

        // 规则就在当前生效的配置里（jd_rules 段），每轮开始时重新读一次 ——
        // 所以网页端改完规则、或换了配置文件，下一次点「开始投递」即生效
        jdRuleFilter.reloadFrom(configFileService.readJdRules());

        int delivered = 0;
        int filtered = 0;
        int skipped = 0;

        // 1) 投递前先扫一遍聊天页：报告「谁回了我」，顺手拉黑明确拒绝的公司
        try {
            scanReplies(platform, store, pid, report);
        } catch (Exception e) {
            log.warn("[{}] 扫描聊天页失败（不影响本次投递）：{}", pid, e.getMessage());
        }

        // 2) 会话与登录
        try {
            platform.ensureSession();
        } catch (Exception e) {
            log.error("[{}] 初始化会话失败：{}", pid, e.getMessage());
        }
        if (!platform.isLoggedIn()) {
            report.accept(JobProgressMessage.error(pid, "未登录 " + label + "，请先扫码登录"));
            return new RunResult(0, 0, 0);
        }

        platform.pauseMonitoring();
        try {
            Set<String> blackCompanies = safeSet(store.blacklist(DeliveryStore.BLACKLIST_COMPANY));
            Set<String> blackRecruiters = safeSet(store.blacklist(DeliveryStore.BLACKLIST_RECRUITER));
            Set<String> blackJobs = safeSet(store.blacklist(DeliveryStore.BLACKLIST_JOB));
            Set<String> alreadyDelivered = new HashSet<>(safeSet(store.deliveredExternalIds(pid)));

            // 同一家公司不重复投递（默认开启）：公司名集合，本轮投出去的也会加进来
            Set<String> deliveredCompanies = policy.isSkipDeliveredCompany()
                    ? new HashSet<>(safeSet(store.deliveredCompanies(pid)))
                    : new HashSet<>();
            if (!deliveredCompanies.isEmpty()) {
                log.info("[{}] 已有 {} 家公司投递过，其名下岗位会被跳过", pid, deliveredCompanies.size());
            }

            List<SearchQuery> queries = platform.buildQueries();
            if (queries == null) {
                queries = Collections.emptyList();
            }
            report.accept(JobProgressMessage.info(pid, String.format("共 %d 组搜索条件，开始投递…", queries.size())));

            outer:
            for (SearchQuery query : queries) {
                if (isStopped(stop)) {
                    report.accept(JobProgressMessage.info(pid, "用户取消投递"));
                    break;
                }

                List<JobCandidate> candidates;
                try {
                    candidates = platform.search(query);
                } catch (Exception e) {
                    log.error("[{}] 搜索失败，跳过该条件：{} | {}", pid, query.describe(), e.getMessage());
                    continue;
                }
                if (candidates == null || candidates.isEmpty()) {
                    log.info("[{}] 没有搜到岗位 | {}", pid, query.describe());
                    continue;
                }

                int total = candidates.size();
                log.info("[{}] 搜到 {} 个岗位 | {}", pid, total, query.describe());
                report.accept(JobProgressMessage.progress(pid, "岗位加载完成：" + query.describe(), 0, total));

                for (int i = 0; i < total; i++) {
                    if (isStopped(stop)) {
                        report.accept(JobProgressMessage.info(pid, "用户取消投递"));
                        break outer;
                    }

                    // ---- 详情
                    JobDetail detail;
                    try {
                        detail = platform.openDetail(candidates.get(i));
                    } catch (Exception e) {
                        log.warn("[{}] 打开详情失败（跳过）| {} | {}", pid, query.describe(), e.getMessage());
                        continue;
                    }
                    if (detail == null || !detail.isParsed()) {
                        log.warn("[{}] 岗位详情没解析出来（跳过，不盲投）| {}", pid, query.describe());
                        skipped++;
                        continue;
                    }

                    // ---- 过滤（平台层 + 平台无关层）
                    FilterResult fr = evaluate(platform, policy, detail,
                            blackCompanies, blackRecruiters, blackJobs, deliveredCompanies);
                    store.saveDiscovered(pid, detail,
                            fr.blocked() ? DeliveryStore.STATUS_FILTERED : DeliveryStore.STATUS_PENDING,
                            fr.note());
                    if (fr.blocked()) {
                        log.info("[{}] 被过滤：{} | {}", pid, String.join("；", fr.reasons), detail.describe());
                        report.accept(JobProgressMessage.info(pid,
                                "跳过（" + fr.reasons.get(0) + "）：" + detail.getJobName()));
                        filtered++;
                        continue;
                    }
                    for (String warn : fr.warnings) {
                        log.info("[{}] 提示：{}（仍投递）| {}", pid, warn, detail.describe());
                    }

                    // ---- 已投递去重
                    if (detail.getExternalId() != null && alreadyDelivered.contains(detail.getExternalId())) {
                        log.info("[{}] 跳过：该岗位此前已投递过 | {}", pid, detail.describe());
                        continue;
                    }

                    // ---- AI 招呼语（含"方向不对口就 SKIP"）
                    String message = null;
                    if (policy.isAiEnabled() && detail.getJdText() != null && !detail.getJdText().isEmpty()) {
                        message = generateMessage(policy, query, detail);
                    }
                    if (isSkipSignal(message)) {
                        log.info("[{}] AI 判断方向不对口，跳过 | {} | AI回复：{}", pid, detail.describe(), message.trim());
                        report.accept(JobProgressMessage.info(pid, "跳过（AI判断不对口）：" + detail.getJobName()));
                        continue;
                    }
                    String greeting = (message != null && !message.isBlank())
                            ? message
                            : policy.getFallbackGreeting();

                    // ---- 调试模式：只遍历不投递
                    if (policy.isDebug()) {
                        log.info("[{}] 调试模式：只遍历，不投递 | {}", pid, detail.describe());
                        report.accept(JobProgressMessage.info(pid, "调试模式跳过投递：" + detail.getJobName()));
                        continue;
                    }

                    // ---- 投递
                    report.accept(JobProgressMessage.progress(pid, "正在投递：" + detail.getJobName(), i + 1, total));
                    boolean ok;
                    try {
                        ok = platform.sendGreeting(detail, greeting);
                    } catch (Exception e) {
                        log.error("[{}] 投递异常 | {} | {}", pid, detail.describe(), e.getMessage());
                        ok = false;
                    }
                    if (ok) {
                        delivered++;
                        if (detail.getExternalId() != null) {
                            alreadyDelivered.add(detail.getExternalId());
                        }
                        // 本轮投出去的公司也记下来：同一轮里再遇到它的其它岗位照样跳过
                        if (detail.getCompanyName() != null && !detail.getCompanyName().isEmpty()) {
                            deliveredCompanies.add(detail.getCompanyName());
                        }
                        updateStatusQuietly(store, pid, detail, DeliveryStore.STATUS_DELIVERED);
                        log.info("[{}] 投递完成 | {}", pid, detail.describe());
                    } else {
                        updateStatusQuietly(store, pid, detail, DeliveryStore.STATUS_FAILED);
                        log.warn("[{}] 投递失败 | {}", pid, detail.describe());
                    }

                    // ---- 本次上限到了就收工（对应前端「当次投递最大值」）
                    if (maxDeliveries > 0 && delivered >= maxDeliveries) {
                        log.info("[{}] 已达本次投递上限 {} 个，结束本轮", pid, maxDeliveries);
                        report.accept(JobProgressMessage.info(pid,
                                "已达本次上限 " + maxDeliveries + " 个，停止投递"));
                        break outer;
                    }

                    // ---- 限速：别把风控刷出来
                    sleepBetweenJobs(policy);
                }
            }
        } finally {
            try {
                platform.resumeMonitoring();
            } catch (Exception e) {
                log.debug("[{}] 恢复登录监控失败：{}", pid, e.getMessage());
            }
        }

        log.info("[{}] 本轮结束：投递 {} 个，过滤 {} 个，跳过 {} 个", pid, delivered, filtered, skipped);
        return new RunResult(delivered, filtered, skipped);
    }

    // ==================================================================
    // 过滤
    // ==================================================================

    /** 一次岗位的过滤结果：blocking 的原因 + 仅提示的 warnings */
    private static final class FilterResult {
        final List<String> reasons = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        boolean blocked() {
            return !reasons.isEmpty();
        }

        /** 落库用的原因文本：命中原因 + 提示，逗号拼接；都没有则 null */
        String note() {
            List<String> all = new ArrayList<>(reasons);
            all.addAll(warnings);
            return all.isEmpty() ? null : String.join("; ", all);
        }
    }

    private FilterResult evaluate(JobPlatform platform, DeliveryPolicy policy, JobDetail detail,
                                  Set<String> blackCompanies, Set<String> blackRecruiters, Set<String> blackJobs,
                                  Set<String> deliveredCompanies) {
        FilterResult result = new FilterResult();
        try {
            // 平台层过滤（Boss 用它做"多城市筛选 / 排除省份城市"）
            if (!platform.matchesCity(detail)) {
                result.reasons.add("城市不匹配或已排除（岗位：" + nz(detail.getCity()) + "）");
            }

            // 同一家公司已投递过（公司级去重；本轮投出去的也算）
            if (policy.isSkipDeliveredCompany()) {
                String company = detail.getCompanyName();
                if (company != null && !company.isEmpty() && deliveredCompanies.contains(company)) {
                    result.reasons.add("该公司已投递过【" + company + "】");
                }
            }

            // JD 规则：reject 命中即拒 / require 未达标即拒 / warn 仅提示
            JdRuleFilter.Result jd = jdRuleFilter.evaluate(detail.getJdText());
            if (jd.jdEmpty) {
                log.info("提示：JD 为空，未做规则过滤 | {}", detail.describe());
            }
            if (jd.rejected) {
                result.reasons.add(jd.reason);
            }
            result.warnings.addAll(jd.warnings);

            String hit = hitTerm(blackJobs, detail.getJobName());
            if (hit != null) {
                result.reasons.add("岗位黑名单命中【" + hit + "】");
            }

            // HR 活跃度过滤：先看开关（filter_dead_hr），关闭时完全不判活跃度
            if (policy.isFilterDeadHr()) {
                String hrReason = HrActivity.inactiveReason(detail.getHrActiveText(), policy.getHrActiveMaxDays());
                if (hrReason != null) {
                    result.reasons.add(hrReason);
                }
            }

            hit = hitTerm(blackCompanies, detail.getCompanyName());
            if (hit != null) {
                result.reasons.add("公司黑名单命中【" + hit + "】");
            }

            hit = hitTerm(blackRecruiters, detail.getHrPosition());
            if (hit != null) {
                result.reasons.add("招聘者黑名单命中【" + hit + "】");
            }
        } catch (Exception e) {
            // 过滤判定本身出错时放行（宁可多投，也不要因为一个异常把岗位全挡掉）
            log.debug("过滤判定异常（按通过处理）：{}", e.getMessage());
        }
        return result;
    }

    /** 黑名单是"包含匹配"：返回命中的那个词，没命中返回 null */
    private static String hitTerm(Set<String> patterns, String text) {
        if (patterns == null || patterns.isEmpty() || text == null || text.isEmpty()) {
            return null;
        }
        for (String p : patterns) {
            if (p != null && !p.isEmpty() && text.contains(p)) {
                return p;
            }
        }
        return null;
    }

    // ==================================================================
    // 聊天页：谁回了我
    // ==================================================================

    private void scanReplies(JobPlatform platform, DeliveryStore store, String pid,
                             Consumer<JobProgressMessage> report) {
        Map<String, String> previous = store.chatSnapshot(pid);
        boolean firstRun = previous == null || previous.isEmpty();

        List<ChatReply> current = platform.scanReplies();
        if (current == null || current.isEmpty()) {
            return; // 平台不支持聊天页
        }

        Map<String, String> snapshot = new LinkedHashMap<>();
        for (ChatReply reply : current) {
            if (reply.getCompanyName() != null && !reply.getCompanyName().isEmpty()) {
                snapshot.put(reply.getCompanyName(), nz(reply.getLastMessage()));
            }
        }

        // 顺手拉黑：只认「明确拒绝」的强特征（带问号的一律不算，见 ReplyClassifier）
        Set<String> blackCompanies = new HashSet<>(safeSet(store.blacklist(DeliveryStore.BLACKLIST_COMPANY)));
        int added = 0;
        for (ChatReply reply : current) {
            String name = reply.getCompanyName();
            if (!ReplyClassifier.isRejectReply(reply.getLastMessage())) {
                continue;
            }
            if (name == null || !ReplyClassifier.looksLikeCompanyName(name)) {
                continue;
            }
            if (hitTerm(blackCompanies, name) != null) {
                continue;
            }
            blackCompanies.add(name);
            store.addBlacklist(DeliveryStore.BLACKLIST_COMPANY, name);
            added++;
            log.info("拉黑公司：【{}】，其回复：【{}】", name, reply.getLastMessage());
        }

        store.replaceChatSnapshot(pid, snapshot);

        if (firstRun) {
            log.info("[{}] 聊天页扫描完成：首次建立基线，共 {} 个会话（本次不报新回复）", pid, snapshot.size());
            return;
        }

        List<String> replied = new ArrayList<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String before = previous.get(entry.getKey());
            if (before != null && !before.equals(entry.getValue())) {
                replied.add(entry.getKey());
            }
        }

        if (replied.isEmpty()) {
            log.info("[{}] 聊天页扫描完成：共 {} 个会话，没有发现新回复", pid, snapshot.size());
        } else {
            log.info("[{}] ===== 发现 {} 个 HR 有新回复 =====", pid, replied.size());
            for (String company : replied) {
                log.info("[{}]   ★ {} —— 最新消息：{}", pid, company, snapshot.get(company));
            }
            report.accept(JobProgressMessage.info(pid,
                    String.format("发现 %d 个 HR 有新回复：%s", replied.size(), String.join("、", replied))));
        }

        if (added > 0) {
            log.info("[{}] 顺带新增黑名单公司 {} 家", pid, added);
        }
    }

    // ==================================================================
    // AI
    // ==================================================================

    private String generateMessage(DeliveryPolicy policy, SearchQuery query, JobDetail detail) {
        try {
            AiEntity aiConfig = aiService.getAiConfig();
            String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
            String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

            String request = (prompt != null && !prompt.isBlank())
                    ? String.format(prompt, introduce, nz(query.getKeyword()), nz(detail.getJobName()),
                            nz(detail.getJdText()), nz(policy.getFallbackGreeting()))
                    : defaultPrompt(introduce, query.getKeyword(), detail, policy.getFallbackGreeting());

            String result = aiService.sendRequest(request);
            if (result == null) {
                return policy.getFallbackGreeting();
            }
            // 有的模型把"不行"回成 false 字面量
            return result.toLowerCase().contains("false") ? policy.getFallbackGreeting() : result;
        } catch (Exception e) {
            log.warn("AI 请求失败，改用兜底招呼语：{}", e.getMessage());
            return policy.getFallbackGreeting();
        }
    }

    private String defaultPrompt(String introduce, String keyword, JobDetail detail, String fallback) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + nz(keyword) + "\n" +
                "职位名称：" + nz(detail.getJobName()) + "\n" +
                "职位描述：" + nz(detail.getJdText()) + "\n" +
                "参考语：" + nz(fallback);
    }

    /** AI 回复以 SKIP 开头 = 方向不对口（可能写成「SKIP：xxx」，所以用前缀判断） */
    private static boolean isSkipSignal(String message) {
        return message != null && message.trim().regionMatches(true, 0, "SKIP", 0, 4);
    }

    // ==================================================================
    // 其它
    // ==================================================================

    private void updateStatusQuietly(DeliveryStore store, String pid, JobDetail detail, String status) {
        if (detail.getExternalId() == null || detail.getExternalId().isEmpty()) {
            return;
        }
        try {
            store.updateStatus(pid, detail.getExternalId(), detail.getRecruiterId(), status);
        } catch (Exception e) {
            log.warn("[{}] 更新投递状态失败：{}", pid, e.getMessage());
        }
    }

    private void sleepBetweenJobs(DeliveryPolicy policy) {
        int wait = Math.max(1, policy.getWaitSeconds());
        int seconds;
        if (policy.isDebug()) {
            seconds = wait;
        } else {
            int min = Math.max(1, wait / 2);
            seconds = min >= wait ? wait : ThreadLocalRandom.current().nextInt(min, wait + 1);
        }
        log.debug("岗位间停顿 {} 秒（wait_time={}）", seconds, wait);
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isStopped(Supplier<Boolean> stop) {
        return stop != null && Boolean.TRUE.equals(stop.get());
    }

    private static Set<String> safeSet(Set<String> in) {
        return in == null ? Collections.emptySet() : in;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
