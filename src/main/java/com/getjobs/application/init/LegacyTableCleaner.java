package com.getjobs.application.init;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 清理上游多平台版本留在数据库里的痕迹 —— 本副本只做 Boss，这些表已经没有任何消费方。
 *
 * <p>为什么放在代码里而不是"手动删一次"：{@code db/} 不入 git，新克隆的人是干净库，
 * 但<b>从上游带库过来的人</b>库里会有这些空表和老 cookie 行，一直留着只会让人困惑
 * （哪个表还有用？）。所以每次启动做一次幂等清理，删过之后就是空操作。
 *
 * <ul>
 *   <li>9 张平台表：{@code job51_*} / {@code liepin_*} / {@code zhilian_*} ——
 *       对应的实体、mapper、service、前端页面在剔除那三个平台时都已经删掉；</li>
 *   <li>{@code cookie} 表里 {@code 51job} / {@code liepin} / {@code zhilian} 三行遗留种子数据
 *       （{@code CookieSeedInitializer} 现在只给 boss 建种子）。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>清理的表：{@code boss_*}（在用）、{@code config} / {@code ai}（AI 与通知配置）、
 * {@code cookie}（登录态记录）、{@code hr_chat_snapshot}（会话快照）、{@code sqlite_sequence}
 * （SQLite 内部表）。删表用 {@code IF EXISTS}，重复启动无副作用；单张表失败只记日志，
 * 不影响启动 —— "清理"这件事不该有能力拦住程序起来。
 */
@Slf4j
@Component
public class LegacyTableCleaner implements CommandLineRunner {

    /** 上游多平台版本留下的表（本副本代码里已无任何引用） */
    private static final List<String> LEGACY_TABLES = List.of(
            "job51_config", "job51_data", "job51_option",
            "liepin_config", "liepin_data", "liepin_option",
            "zhilian_config", "zhilian_data", "zhilian_option");

    /** 上游多平台版本留下的 cookie 行（platform 列的值） */
    private static final List<String> LEGACY_COOKIE_PLATFORMS = List.of("51job", "liepin", "zhilian");

    private final DataSource dataSource;

    public LegacyTableCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        cleanTables();
        cleanCookies();
    }

    private void cleanTables() {
        List<String> dropped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String table : LEGACY_TABLES) {
                try {
                    if (!tableExists(st, table)) {
                        continue;
                    }
                    st.execute("DROP TABLE IF EXISTS " + table);
                    dropped.add(table);
                } catch (Exception e) {
                    failed.add(table + "：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            // 连不上库这种事交给正常的启动流程去报，这里不额外制造噪音
            log.debug("检查遗留平台表时跳过：{}", e.getMessage());
            return;
        }

        if (!dropped.isEmpty()) {
            log.info("已清理上游遗留的平台表 {} 张：{}", dropped.size(), String.join(", ", dropped));
        }
        for (String f : failed) {
            log.warn("清理遗留平台表失败：{}", f);
        }
    }

    private void cleanCookies() {
        int removed = 0;
        try (Connection conn = dataSource.getConnection()) {
            for (String platform : LEGACY_COOKIE_PLATFORMS) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cookie WHERE platform = ?")) {
                    ps.setString(1, platform);
                    removed += ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            // cookie 表还没建（首次运行）等情况：没什么可清的，忽略
            log.debug("清理遗留 cookie 记录时跳过：{}", e.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("已清理上游遗留的 cookie 记录 {} 行（平台：{}）", removed,
                    String.join(", ", LEGACY_COOKIE_PLATFORMS));
        }
    }

    /** 表是否存在。非 SQLite 库上这条查询会失败，按"不存在"处理（外层只记日志）。 */
    private boolean tableExists(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
