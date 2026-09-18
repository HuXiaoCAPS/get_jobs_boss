package com.getjobs.worker.platform;

import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.JobPage;
import com.getjobs.worker.platform.model.JobQuery;
import com.getjobs.worker.platform.model.JobStats;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 投递数据的读写契约 —— 流程层与"数据存在哪、长什么样"之间唯一的接口。
 *
 * <p>为什么流程层不能直接调 {@code BossService}：那个 service 操作的是 Boss 专属的
 * {@code boss_data} 表（{@code encrypt_id} / {@code location_name} / HR 字段…）。
 * 一旦流程层认了它，"插件化"就破了 —— 换个平台就得改流程层。
 *
 * <p><b>现状与将来</b>：
 * <ul>
 *   <li>现在的实现 {@code BossDeliveryStore} 内部还是转调 {@code BossService}，数据仍然落在
 *       {@code boss_data} 表里 —— 所以行为与改造前完全一致；</li>
 *   <li>等真要接第二个平台时再决定数据层怎么平台化（给现有表加 {@code platform} 列，
 *       或者新建通用的 {@code delivery_record} + 平台扩展表）。那属于阶段 4/5，不在本次范围内。</li>
 * </ul>
 *
 * <p>约定：{@code platform} 参数就是 {@link JobPlatform#id()}。实现方可以忽略它（单平台时），
 * 但不要把它当成"某个具体平台"来写死。
 */
public interface DeliveryStore {

    /** 黑名单类型：公司 */
    String BLACKLIST_COMPANY = "company";
    /** 黑名单类型：招聘者职位 */
    String BLACKLIST_RECRUITER = "recruiter";
    /** 黑名单类型：岗位名 */
    String BLACKLIST_JOB = "job";

    /** 投递状态：还没投（含刚入库的） */
    String STATUS_PENDING = "未投递";
    /** 投递状态：投出去了 */
    String STATUS_DELIVERED = "已投递";
    /** 投递状态：被规则/黑名单挡住 */
    String STATUS_FILTERED = "已过滤";
    /** 投递状态：尝试了但失败 */
    String STATUS_FAILED = "投递失败";

    // ------------------------------------------------------------------
    // 黑名单
    // ------------------------------------------------------------------

    /** 取某类黑名单（类型见上面的常量） */
    Set<String> blacklist(String type);

    /** 往某类黑名单里加一条 */
    void addBlacklist(String type, String value);

    // ------------------------------------------------------------------
    // 岗位记录
    // ------------------------------------------------------------------

    /** 该平台下"已经投递过"的岗位外部 id 集合（用于跳过重复投递） */
    Set<String> deliveredExternalIds(String platform);

    /**
     * 该平台下"已经投递过"的公司名集合（用于"同一家公司不重复投递"）。
     *
     * <p>同一家公司经常挂着多个相近岗位，只按岗位去重挡不住重复打扰。
     * 默认返回空集合（= 不做公司级去重）。
     */
    default Set<String> deliveredCompanies(String platform) {
        return Collections.emptySet();
    }

    /**
     * 记录一个刚看到的岗位（含被过滤的）。
     *
     * @param status {@link #STATUS_PENDING} 或 {@link #STATUS_FILTERED}
     * @param note   过滤/提示原因（没有就传 null）
     */
    void saveDiscovered(String platform, JobDetail detail, String status, String note);

    /**
     * 更新某个岗位的投递状态。
     *
     * @param status {@link #STATUS_DELIVERED} 或 {@link #STATUS_FAILED}
     */
    void updateStatus(String platform, String externalId, String recruiterId, String status);

    // ------------------------------------------------------------------
    // 数据浏览（网页端「数据」页用）
    // ------------------------------------------------------------------

    /**
     * 按条件查岗位列表（分页）。
     *
     * <p>返回 {@code null} 表示<b>该平台不支持数据浏览</b>（不是"没有数据"）——
     * 接口层据此回一句"该平台不支持查看数据"，而不是给前端一个空列表假装正常。
     * 默认实现就是 null：这类能力要靠平台自己实现（只有它知道自己的数据源能怎么查）。
     *
     * <p>分页在平台侧做：数据源各不相同（Boss 是 SQL、假平台是内存），
     * 怎么高效分页是平台知识。
     */
    default JobPage listJobs(String platform, JobQuery query) {
        return null;
    }

    /**
     * 按条件统计岗位数据。返回 {@code null} 的含义同 {@link #listJobs(String, JobQuery)}。
     */
    default JobStats jobStats(String platform, JobQuery query) {
        return null;
    }

    // ------------------------------------------------------------------
    // 聊天页快照（用来算"谁回了我"）
    // ------------------------------------------------------------------

    /** 上一次会话快照：公司名 -> 最后一条消息 */
    Map<String, String> chatSnapshot(String platform);

    /** 覆盖保存本次会话快照 */
    void replaceChatSnapshot(String platform, Map<String, String> snapshot);
}
