package com.getjobs.worker.platform.model;

import java.util.List;

/**
 * 岗位数据的<b>查询条件</b> —— 平台无关。
 *
 * <p>网页端「数据」页的筛选条翻译成它，再交给 {@code DeliveryStore#listJobs} /
 * {@code #jobStats}。字段刻意只用"任何平台都说得清"的概念（城市 / 经验 / 学历 / 关键词 /
 * 薪资区间 / 投递状态），不出现任何平台专属列名。
 *
 * <p><b>薪资区间为什么带单位</b>：各平台计价方式不同 —— Boss 上实习岗按「元/天」、
 * 正职按「K/月」。混在一个区间里比大小没有意义（日薪折出的月薪约 3~5K，
 * 会被错当成超低薪），所以由 {@link #salaryUnit} 说明这两个数字是什么单位，
 * 两种口径互不匹配。统计那边则是分口径出两个平均值，见 {@link JobStats}。
 */
public class JobQuery {

    /** 投递状态（{@code null} / 空 = 不限）。取值见 {@code DeliveryStore.STATUS_*} */
    public List<String> statuses;

    /** 城市（精确匹配；{@code null} / 空 = 不限） */
    public String location;

    /** 经验要求（{@code null} / 空 = 不限） */
    public String experience;

    /** 学历要求（{@code null} / 空 = 不限） */
    public String degree;

    /** 关键词：岗位名 / 公司名 / HR 名，包含匹配（{@code null} / 空 = 不限） */
    public String keyword;

    /** 薪资区间下限 / 上限；{@code null} = 不限。单位由 {@link #salaryUnit} 决定 */
    public Double minK;
    public Double maxK;

    /**
     * 薪资区间的口径：{@code MONTH}（默认，比对 K/月）或 {@code DAY}（比对 元/天）。
     *
     * <p>为什么必须能切：Boss 上实习岗普遍写「200-250元/天」，正职写「20-40K」。
     * 只按一个口径比，另一种口径的岗位在填了区间后会全被排除 ——
     * 想让「日薪 150 以上」这类条件可用，就得让调用方说明这个数字是什么单位。
     * 取值与 {@code BossService.SALARY_UNIT_*} 一致（本模型是平台无关的，
     * 所以只约定字符串，不反向依赖具体平台的常量）。
     */
    public String salaryUnit = "MONTH";

    /** 是否过滤猎头岗位 */
    public boolean filterHeadhunter;

    /** 页码，从 1 开始 */
    public int page = 1;

    /** 每页条数 */
    public int size = 20;

    /** 每页条数上限 —— 防止前端传 size=999999 把整表拉进内存 */
    public static final int MAX_SIZE = 500;

    /** 规范化后的页码 */
    public int safePage() {
        return page <= 0 ? 1 : page;
    }

    /** 规范化后的每页条数（封顶 {@link #MAX_SIZE}） */
    public int safeSize() {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 是否有薪资区间条件 */
    public boolean hasSalaryRange() {
        return minK != null || maxK != null;
    }

    /** 一行摘要，给日志与"当前筛选"提示用 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (statuses != null && !statuses.isEmpty()) sb.append("状态=").append(statuses).append(' ');
        if (location != null && !location.isBlank()) sb.append("城市=").append(location).append(' ');
        if (experience != null && !experience.isBlank()) sb.append("经验=").append(experience).append(' ');
        if (degree != null && !degree.isBlank()) sb.append("学历=").append(degree).append(' ');
        if (keyword != null && !keyword.isBlank()) sb.append("关键词=").append(keyword).append(' ');
        if (hasSalaryRange()) {
            boolean day = "DAY".equalsIgnoreCase(salaryUnit);
            sb.append(day ? "日薪=" : "月薪=")
                    .append(minK).append("~").append(maxK)
                    .append(day ? "元/天 " : "K ");
        }
        if (filterHeadhunter) sb.append("过滤猎头 ");
        return sb.length() == 0 ? "(无筛选)" : sb.toString().trim();
    }
}
