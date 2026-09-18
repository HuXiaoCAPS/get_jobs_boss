package com.getjobs.worker.platform.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位数据的统计结果 —— 平台无关。
 *
 * <p>{@code DeliveryStore#jobStats} 的返回类型。字段取的都是"任何平台都算得出来"的口径。
 *
 * <p><b>薪资为什么是两个平均值</b>：各平台计价方式不同 —— Boss 上实习岗普遍写
 * 「200-250元/天」，正职写「20-40K」。把日薪折成月薪（×21.75 约 3~5K）再和正职月薪
 * （10~40K）求平均，两个数字都会失真。所以按原始口径分别汇总：
 * {@link #avgMonthlyK}（K/月）与 {@link #avgDailyYuan}（元/天），各自只由同口径的岗位参与，
 * 没有该口径的数据就是 {@code null}。分桶同理分成 {@link #salaryBuckets} 与
 * {@link #dailySalaryBuckets}。
 */
public class JobStats {

    /** 命中筛选条件的总条数 */
    public long total;
    public long delivered;
    public long pending;
    public long filtered;
    public long failed;

    /** 平均月薪（中位数 K/月）；只由月薪口径的岗位算出 */
    public Double avgMonthlyK;

    /** 平均日薪（中位数 元/天）；只由日薪口径的岗位算出 */
    public Double avgDailyYuan;

    /** 月薪档位分布（如 {@code 0-10K}） */
    public List<Count> salaryBuckets = new ArrayList<>();

    /** 日薪档位分布（如 {@code 150-200元}） */
    public List<Count> dailySalaryBuckets = new ArrayList<>();

    /** 按投递状态分组 */
    public List<Count> byStatus = new ArrayList<>();

    /** 按城市分组（各实现自行限制条数，通常在 TOP10 以内） */
    public List<Count> byCity = new ArrayList<>();

    /** 按公司分组（TOP10） */
    public List<Count> byCompany = new ArrayList<>();

    /** 按行业分组（TOP10） */
    public List<Count> byIndustry = new ArrayList<>();

    /** 按经验要求分组 */
    public List<Count> byExperience = new ArrayList<>();

    /** 按学历要求分组 */
    public List<Count> byDegree = new ArrayList<>();

    /** 按天分组（name 是 {@code yyyy-MM-dd}） */
    public List<Count> dailyTrend = new ArrayList<>();

    /** name + 计数的通用分组项 —— 与 {@code BossService} 的同名结构刻意保持一致的形状，
     *  这样平台实现里的映射只是一次字段搬运。 */
    public static class Count {
        public String name;
        public long value;

        public Count() {
        }

        public Count(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }
}
