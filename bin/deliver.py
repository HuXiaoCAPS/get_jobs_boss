#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
定时投递脚本：通过后端 HTTP API 启动 / 等待 / 停止 Boss 投递任务。

用法：
    python bin/deliver.py             启动投递并等待到结束（默认行为）
    python bin/deliver.py --max 60    本次最多投 60 个，达到后自动停止
    python bin/deliver.py --status    只看状态，不做任何操作
    python bin/deliver.py --stop      停止正在运行的投递

前提（很重要）：
    1. 应用必须已经在运行：IDEA 里跑 GetJobsApplication，或命令行 gradlew bootRun。
       本脚本只是调用它的 HTTP 接口，自己不会启动应用、也不会拉起浏览器。
    2. Boss 必须已登录。登录态落在 browser-data/ 里，一般能保持一周，
       过期了要手动去那个 Edge 窗口扫码。
    3. 应用启动后浏览器初始化要 1-2 分钟（四个平台逐个初始化），
       在那之前点投递会一直排队、界面像卡住。用本脚本启动时会自动等，
       但如果你在网页上手动点「开始投递」，请等日志出现「开始初始化智联招聘平台」之后再点。

关于 --max：
    计数方式 = 「boss_data 表里 delivery_status='已投递' 的行数」，
    启动前先记基线，之后轮询增量，达到上限就调 /stop。
    注意：停止是协作式的，投递线程可能正卡在某个浏览器调用里，
    /stop 之后还要等一会儿才真正停下，所以实际可能略微超出 --max（一般 1-3 个）。
    另外：--max 只在「用本脚本启动」时生效；你在网页上手点「开始投递」，脚本管不到。

配合 Windows 任务计划程序即可定时投递，例如每天 09:00 自动投一轮、最多 60 个：
    schtasks /create /tn "get_jobs 每日投递" /sc daily /st 09:00 /f ^
        /tr "\"C:\\path\\to\\python.exe\" \"<项目目录>\\bin\\deliver.py\" --max 60"

企业微信通知：如果 db/getjobs.db 的 config 表里配了 HOOK_URL，脚本会在
「未登录」「投递结束」「达到上限」「等待超时」几种情况下推一条消息；没配就静默跳过。
"""
import argparse
import json
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 后端地址（对应 application.yaml 的 server.port）
BASE_URL = "http://localhost:9527/api/boss"
POLL_SECONDS = 20
MAX_WAIT_MINUTES = 240  # 兜底：最多等 4 小时，避免脚本挂着不走

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = PROJECT_ROOT / "db" / "getjobs.db"


def log(msg):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}", flush=True)


def api(path, method="GET", timeout=15):
    """调后端接口。4xx 也把响应体读出来，方便拿 message。"""
    req = urllib.request.Request(BASE_URL + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "ignore")
    try:
        return json.loads(body) if body else {}
    except json.JSONDecodeError:
        return {"raw": body}


def count_delivered():
    """统计 boss_data 里已投递的岗位数；读不到返回 None。

    用只读 URI（mode=ro）打开，避免和应用进程抢写锁。
    """
    try:
        if not DB_PATH.is_file():
            return None
        conn = sqlite3.connect(f"file:{DB_PATH.as_posix()}?mode=ro", uri=True, timeout=5)
        try:
            row = conn.execute(
                "select count(*) from boss_data where delivery_status = '已投递'"
            ).fetchone()
            return int(row[0]) if row else None
        finally:
            conn.close()
    except Exception as e:
        log(f"读取已投递数量失败：{e}")
        return None


def notify(content):
    """可选的企业微信机器人推送；HOOK_URL 没配就静默跳过，任何失败都不影响投递。"""
    try:
        if not DB_PATH.is_file():
            return
        conn = sqlite3.connect(str(DB_PATH))
        try:
            row = conn.execute(
                "select config_value from config where config_key = 'HOOK_URL'"
            ).fetchone()
        finally:
            conn.close()
        hook = (row[0] if row else "") or ""
        if not hook.strip():
            return
        payload = json.dumps({"msgtype": "text", "text": {"content": content}}).encode("utf-8")
        req = urllib.request.Request(
            hook.strip(), data=payload,
            headers={"Content-Type": "application/json"}, method="POST")
        urllib.request.urlopen(req, timeout=10).read()
        log("企业微信通知已发送")
    except Exception as e:
        log(f"企业微信推送失败（不影响投递）：{e}")


def wait_until_stopped(max_seconds=60):
    """等投递任务真正停下来（停止是协作式的，可能要等看门狗超时）。"""
    waited = 0
    while waited < max_seconds:
        time.sleep(3)
        waited += 3
        try:
            if not api("/status").get("isRunning"):
                return True
        except Exception:
            pass
    return False


def main():
    parser = argparse.ArgumentParser(description="get_jobs 定时投递脚本")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--status", action="store_true", help="只查看状态")
    group.add_argument("--stop", action="store_true", help="停止正在运行的投递")
    parser.add_argument("--max", type=int, metavar="N",
                        help="本次最多投递 N 个岗位，达到后自动停止（按 boss_data 中已投递行数计）")
    args = parser.parse_args()

    # 1. 应用在不在
    try:
        status = api("/status")
    except Exception as e:
        log(f"连不上后端 {BASE_URL}：{e}")
        log("请先启动应用（IDEA 运行 GetJobsApplication，或命令行 gradlew bootRun）")
        return 2

    running = bool(status.get("isRunning"))
    logged_in = bool(status.get("isLoggedIn"))
    log(f"应用状态：投递中={running}，Boss已登录={logged_in}")

    if args.status:
        if args.max:
            log(f"当前 boss_data 已投递行数：{count_delivered()}")
        return 0

    if args.stop:
        if not running:
            log("当前没有正在运行的投递任务")
            return 0
        log(f"已请求停止：{api('/stop', method='POST').get('message', '')}")
        return 0

    # 2. 前置检查：没登录就别启动，否则后端会直接报错
    if not logged_in:
        log("Boss 未登录，无法投递。请到 Edge 窗口扫码登录后重试。")
        notify("get_jobs 定时投递未执行：Boss 未登录，需要手动扫码")
        return 3

    if running:
        log("已有投递任务在运行，本次不重复启动")
        return 0

    max_count = args.max if (args.max and args.max > 0) else None
    base = None
    if max_count:
        base = count_delivered()
        if base is None:
            log("读不到 db/getjobs.db，无法按 --max 限制数量")
            return 6
        log(f"本次投递上限 {max_count} 个（已投递基线 {base}）")

    # 3. 启动
    started = api("/start", method="POST")
    if not started.get("success"):
        log(f"启动失败：{started.get('message', started)}")
        notify(f"get_jobs 定时投递启动失败：{started.get('message', started)}")
        return 4
    log("投递任务已启动，开始轮询状态……")

    # 4. 轮询
    begin = time.time()
    deadline = begin + MAX_WAIT_MINUTES * 60
    while time.time() < deadline:
        time.sleep(POLL_SECONDS)

        try:
            st = api("/status")
        except Exception as e:
            log(f"轮询失败（应用可能被关掉了）：{e}")
            continue

        elapsed = (time.time() - begin) / 60

        # 4a. 达到数量上限就主动停
        if max_count:
            cur = count_delivered()
            if cur is None:
                log(f"投递进行中……（读不到计数，总耗时 {elapsed:.1f} 分钟）")
            else:
                done = cur - base
                log(f"投递进行中……本次已投递 {done}/{max_count}（总耗时 {elapsed:.1f} 分钟）")
                if done >= max_count:
                    log(f"已达上限 {max_count}，请求停止投递")
                    api("/stop", method="POST")
                    stopped = wait_until_stopped()
                    msg = f"get_jobs 已投满 {done} 个并停止（耗时 {elapsed:.1f} 分钟）"
                    log(msg if stopped else msg + "；任务未在 60 秒内响应停止，看门狗会强制复位")
                    notify(msg)
                    return 0
        else:
            log(f"投递进行中……已等待 {elapsed:.1f} 分钟")

        # 4b. 任务自己跑完了
        if not st.get("isRunning"):
            msg = f"get_jobs Boss 投递结束，耗时 {elapsed:.1f} 分钟"
            log(msg)
            notify(msg)
            return 0

    log(f"等待超过 {MAX_WAIT_MINUTES} 分钟，脚本退出（后台任务可能仍在跑）")
    notify(f"get_jobs 定时投递等待超时（超过 {MAX_WAIT_MINUTES} 分钟），请查看日志")
    return 5


if __name__ == "__main__":
    sys.exit(main())
