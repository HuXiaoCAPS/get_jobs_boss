package com.getjobs.worker.platform;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HR 活跃度判定 —— 纯文本逻辑，平台无关。
 *
 * <p>从 {@code Boss} 里搬出来（那份等阶段 3 改造 Boss 时删除）。
 * 各平台都会给一个"活跃描述"文本（"刚刚活跃" / "本周活跃" / "3天内活跃"…），
 * 解析规则与阈值判定不依赖任何平台，所以放在这里复用。
 */
public final class HrActivity {

    private HrActivity() {
    }

    /**
     * 把活跃描述解析成「距今的天数上界」，解析不出来返回 -1（未知）。
     *
     * <p>覆盖常见写法：刚刚(0)、今日(1)、本周(7)、本月(30)、半年前(180)、一年(365)，
     * 以及 N日内(N)、N周内(7N)、N月内(30N)、N年内(365N)。
     *
     * <p>fail-open：解析不出来就返回 -1，调用方据此放行（宁可多投也不误杀）——
     * 这样即使平台换成"三天内活跃"这种中文数字，也只是不过滤，不会把岗位全挡掉。
     */
    public static int parseActiveDays(String activeText) {
        if (activeText == null || activeText.isEmpty()) {
            return -1;
        }
        String s = activeText.trim();
        if (s.contains("刚刚")) return 0;
        if (s.contains("今日") || s.contains("今天")) return 1;
        if (s.contains("本周")) return 7;
        if (s.contains("本月")) return 30;
        if (s.contains("半年")) return 180;
        if (s.contains("一年")) return 365;

        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        if (!m.find()) {
            return -1;
        }
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (s.contains("日") || s.contains("天")) return n;
        if (s.contains("周")) return n * 7;
        if (s.contains("月")) return n * 30;
        if (s.contains("年")) return n * 365;
        return -1;
    }

    /**
     * HR 是否算「不活跃」：是则返回可直接打日志/落库的原因，否则返回 null。
     *
     * @param maxDays 阈值（天）。null 按 30 天处理；&lt;= 0 表示退回旧的「含年」判定。
     */
    public static String inactiveReason(String hrActiveText, Integer maxDays) {
        if (hrActiveText == null || hrActiveText.isEmpty()) {
            return null;
        }
        int limit = maxDays == null ? 30 : maxDays;
        if (limit > 0) {
            int days = parseActiveDays(hrActiveText);
            if (days >= 0 && days > limit) {
                return String.format("HR活跃 %d 天 > %d 天（%s）", days, limit, hrActiveText);
            }
            return null;
        }
        return hrActiveText.contains("年")
                ? "HR活跃状态含「年」（" + hrActiveText + "）"
                : null;
    }
}
