package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Boss 页面操作的自检 / 诊断。
 *
 * <p><b>为什么需要它</b>：Boss 改版频繁，而投递流程里到处是「找不到元素就静默 return」，
 * 出问题时日志只有一句"跳过"，看不出是<b>哪一步</b>、也看不出<b>哪个选择器失效了</b>。
 * 这里在关键失败点打一份快照：当前步骤 + URL + 一组关键选择器的命中数 + 页面结构候选，
 * 并追加到 {@code target/boss-diagnose.txt}，让改版当天就能定位。
 *
 * <p><b>使用约束</b>：
 * <ul>
 *   <li>只做只读操作（locator().count() / evaluate），绝不点击、绝不导航；</li>
 *   <li>必须在 Playwright 线程上调用（调用点都在页面流程里，天然满足）；</li>
 *   <li>任何异常都被吞掉 —— 诊断本身绝不能把投递流程搞崩；</li>
 *   <li>同一「步骤+原因」30 秒内只打一次，避免批量失败时刷屏。</li>
 * </ul>
 */
final class BossDiagnostics {

    private BossDiagnostics() {
    }

    /** 诊断快照落盘位置（与 target/job.txt 同一目录，方便一起收集） */
    private static final String FILE_PATH = "target/boss-diagnose.txt";

    /** 同一 key 的最小重复打印间隔（毫秒） */
    private static final long THROTTLE_MS = 30_000;

    private static final ConcurrentMap<String, Long> LAST_PRINTED = new ConcurrentHashMap<>();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /**
     * 改版排查看的就这几样：列表容器 / 卡片 / 卡片里的字段 / 详情按钮 / 聊天按钮 / 输入框 / 发送按钮 / 会话项。
     * 命中数为 0 的那一项，基本就是失效的那个选择器。
     */
    private static final Map<String, String> WATCHED = new LinkedHashMap<>();

    static {
        WATCHED.put("列表容器", "ul.rec-job-list, ul.job-list-box, .job-list-box, .search-job-result");
        WATCHED.put("岗位卡片", "ul.rec-job-list li.job-card-box, li.job-card-wrapper");
        WATCHED.put("岗位名", "a.job-name");
        WATCHED.put("公司名", "span.boss-name");
        WATCHED.put("查看更多信息", "a.more-job-btn");
        WATCHED.put("立即沟通", "a.btn-startchat, a.op-btn-chat");
        WATCHED.put("聊天输入框", "div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        WATCHED.put("发送按钮", "div.send-message, button[type='send'].btn-send, button.btn-send");
        WATCHED.put("会话列表项", "li[role='listitem']");
        WATCHED.put("登录入口", "div.btns");
        WATCHED.put("底部", "div#footer, #footer");
    }

    /**
     * 打一份诊断快照。
     *
     * @param page   当前页面（可为 null）
     * @param step   步骤名，例如「打开岗位详情」「发送招呼语」
     * @param reason 失败原因，例如「detail.json 未抓到」
     */
    static void report(Page page, String step, String reason) {
        try {
            String key = step + "|" + reason;
            long now = System.currentTimeMillis();
            Long last = LAST_PRINTED.get(key);
            if (last != null && now - last < THROTTLE_MS) {
                return; // 刚打过，不重复刷屏
            }
            LAST_PRINTED.put(key, now);

            StringBuilder sb = new StringBuilder();
            sb.append("\n========== [诊断] ").append(LocalDateTime.now().format(TS)).append(" ==========\n");
            sb.append("步骤: ").append(step).append('\n');
            sb.append("原因: ").append(reason).append('\n');
            sb.append("URL : ").append(safeUrl(page)).append('\n');
            sb.append("选择器命中数（为 0 的基本就是失效的那个）:\n");
            for (Map.Entry<String, Integer> e : snapshot(page).entrySet()) {
                sb.append("  - ").append(e.getKey()).append(" = ").append(e.getValue())
                        .append(e.getValue() == 0 ? "   <-- 没命中" : "").append('\n');
            }
            String candidates = structureCandidates(page);
            if (!candidates.isEmpty()) {
                sb.append("页面结构候选: ").append(candidates).append('\n');
            }
            sb.append("================================================\n");

            String text = sb.toString();
            System.out.println(text);
            appendToFile(text);
        } catch (Throwable ignore) {
            // 诊断失败绝不能影响投递
        }
    }

    /**
     * 对 WATCHED 里每个选择器取一次 count()。
     * 单个选择器报错只记 -1，不影响其它项。
     */
    private static Map<String, Integer> snapshot(Page page) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (page == null) {
            return result;
        }
        for (Map.Entry<String, String> e : WATCHED.entrySet()) {
            int count;
            try {
                Locator locator = page.locator(e.getValue());
                count = locator.count();
            } catch (Throwable t) {
                count = -1;
            }
            result.put(e.getKey(), count);
        }
        return result;
    }

    /**
     * 把页面上"名字里带 job/list/rec"的容器列出来，列表容器改版时能直接照着改选择器。
     */
    @SuppressWarnings("unchecked")
    private static String structureCandidates(Page page) {
        if (page == null) {
            return "";
        }
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 12)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + '(' + el.childElementCount + ')');
                      return uls.join(' ');
                    }""");
            return info == null ? "" : info.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safeUrl(Page page) {
        if (page == null) {
            return "(无页面)";
        }
        try {
            String url = page.url();
            return url == null ? "(取不到 URL)" : url;
        } catch (Throwable t) {
            return "(取不到 URL)";
        }
    }

    private static void appendToFile(String text) {
        try {
            File dir = new File("target");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            try (FileWriter fw = new FileWriter(new File(dir, "boss-diagnose.txt"), true)) {
                fw.write(text);
            }
        } catch (Throwable ignore) {
            // 落盘失败不影响主流程
        }
    }
}
