package com.getjobs.worker.platform;

/**
 * HR 回复的语义判定 —— 纯文本逻辑，平台无关。
 *
 * <p>从 {@code Boss} 里搬出来的（那份等阶段 3 改造 Boss 时删除），
 * 放在 platform 包是因为"什么算拒绝"跟平台无关，任何平台都能复用。
 */
public final class ReplyClassifier {

    private ReplyClassifier() {
    }

    /**
     * 明确拒绝的强特征：只有命中这些词才算 HR 拒绝（务必保持「宁漏不误杀」）。
     */
    private static final String[] REJECT_PHRASES = {
            "很遗憾", "不合适", "不符合", "不匹配", "祝您找到", "祝您早日",
            "已招满", "岗位已关闭", "无法安排", "暂时不考虑"
    };

    /**
     * 这条回复是否属于「明确拒绝」（用于自动拉黑）。
     *
     * <p>旧实现用的是「不 / 感谢 / 但 / 遗憾」这类常见字的包含匹配，实测误杀严重：
     * <ul>
     *   <li>「后续是没有课程的，但是在学期结束时可能需要一两天」→ 命中「但」被拉黑</li>
     *   <li>「实习工资较低，可能不够附近租房和吃饭的，关于这个问题，你如何考虑？」→ 命中「不」被拉黑，
     *       而这条 HR 其实是在主动跟你沟通</li>
     * </ul>
     * 现在的规则：<b>带问号的一律不算拒绝</b>（那是在问你话），其余必须命中强特征词才算。
     */
    public static boolean isRejectReply(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        if (message.contains("？") || message.contains("?")) {
            return false;
        }
        for (String phrase : REJECT_PHRASES) {
            if (message.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 公司名是否"像一个真实公司名"——用来避免把乱码/单个符号加进黑名单。
     * （旧逻辑写在 Boss 里：至少两个汉字，或至少 4 个连续字母）
     */
    public static boolean looksLikeCompanyName(String companyName) {
        return companyName != null && companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*");
    }
}
