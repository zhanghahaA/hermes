package com.hermes.mobile.data.remote

/**
 * 服务器上的小桥：经 SSH 跑在 Hermes 自己的 Python 里，连上这台电脑正在运行的 Hermes gateway（`/api/ws`，
 * 和电脑版、TUI 是同一个服务），在 stdin / stdout 上一行一个 JSON 和手机来回传。
 *
 * - 手机 → 桥：`{"id": n, "method": "...", "params": {...}}` 转给 gateway；
 *   `{"id": n, "local": "...", "params": {...}}` 在桥里直接用 Hermes 的 Python 函数办（只读的会话列表、技能、
 *   工具集、定时任务——和 API Server 那几个接口调的是同一批函数；gateway 的连接上没有这些方法）。
 * - 桥 → 手机：先一行 `{"ready": true, ...}`；之后是 `{"id": n, "result"|"error": ...}` 和 gateway 推来的
 *   `{"event": {...}}`。gateway 断开时写 `{"closed": "原因"}` 然后退出，手机重新开一条。
 *
 * 会话列表为什么在桥里读：gateway 这条连接上的 `session.list` 只列它自己正管着的会话，
 * 读不到标题、置顶、归档；Hermes 自己的命令行客户端也是“读走只读的 state.db、写走 gateway”。
 */
internal object GatewayBridge {
    val Script = """
import asyncio, json, os, sys
out = os.fdopen(os.dup(1), "w", encoding="utf-8")
sys.stdout = sys.stderr

def emit(obj):
    out.write(json.dumps(obj, ensure_ascii=False, default=str) + "\n")
    out.flush()

# ---- 桥里直接办的只读 / 本机操作 ----

def _flags(row):
    for f in ("pinned", "archived", "hidden"):
        if f in row:
            row[f] = bool(row[f])
    return row

SESSION_KEYS = ("id", "source", "model", "title", "started_at", "ended_at", "end_reason", "message_count",
                "parent_session_id", "last_active", "preview", "_lineage_root_id", "pinned", "archived", "hidden")

def local_sessions(p):
    from pathlib import Path
    from hermes_constants import get_hermes_home
    from hermes_state import SessionDB
    db = SessionDB(Path(get_hermes_home()) / "state.db", read_only=True)
    try:
        rows = db.list_sessions_rich(source=None, limit=int(p.get("limit", 100)), offset=int(p.get("offset", 0)),
                                     include_children=False, order_by_last_active=True, include_pinned=True)
        return {"data": [_flags({k: r.get(k) for k in SESSION_KEYS if k in r}) for r in rows]}
    finally:
        db.close()

def local_session(p):
    from pathlib import Path
    from hermes_constants import get_hermes_home
    from hermes_state import SessionDB
    db = SessionDB(Path(get_hermes_home()) / "state.db", read_only=True)
    try:
        row = db.get_session(p["session_id"])
        return {"session": _flags({k: row.get(k) for k in SESSION_KEYS if k in row}) if row else None}
    finally:
        db.close()

def local_skills(p):
    from tools.skills_tool import _find_all_skills, _sort_skills
    # 参数随 Hermes 版本变过（旧版没有 include_editorial）
    try:
        found = _find_all_skills(skip_disabled=False, include_editorial=True)
    except TypeError:
        found = _find_all_skills(skip_disabled=False)
    return {"data": _sort_skills(found)}

def local_toolsets(p):
    # gateway 上的会话用 cli 平台的工具集（和电脑版、TUI 一样），不是 API Server 的 api_server
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import _get_effective_configurable_toolsets, _get_platform_tools, _toolset_has_keys, get_nous_subscription_features
    from toolsets import resolve_toolset
    config = load_config()
    enabled = _get_platform_tools(config, "cli", include_default_mcp_servers=False)
    features = get_nous_subscription_features(config)
    data = []
    for name, label, desc in _get_effective_configurable_toolsets():
        try:
            tools = sorted(set(resolve_toolset(name)))
        except Exception:
            tools = []
        data.append({"name": name, "label": label, "description": desc, "enabled": name in enabled,
                     "configured": _toolset_has_keys(name, config, features=features), "tools": tools})
    return {"data": data}

def _jobs_changed():
    try:
        from gateway.platforms.api_server import _notify_cron_provider_jobs_changed
        _notify_cron_provider_jobs_changed()
    except Exception:
        pass

def local_jobs(p):
    import cron.jobs as J
    op = p.get("op")
    if op == "list":
        return {"jobs": J.list_jobs(include_disabled=True)}
    if op == "get":
        return {"job": J.get_job(p["job_id"])}
    if op == "create":
        from cron.scheduler import create_job_with_scheduler_registration
        fields = dict(p["fields"])
        kwargs = {"prompt": fields.get("prompt", ""), "schedule": fields["schedule"], "name": fields["name"],
                  "deliver": fields.get("deliver", "local"), "origin": None}
        for key in ("paused", "paused_reason", "skills", "repeat"):
            if fields.get(key) is not None:
                kwargs[key] = fields[key]
        job = create_job_with_scheduler_registration(**kwargs)
        _jobs_changed()
        return {"job": job}
    if op == "update":
        job = J.update_job(p["job_id"], dict(p["fields"]))
        _jobs_changed()
        return {"job": job}
    if op == "delete":
        ok = J.remove_job(p["job_id"])
        _jobs_changed()
        return {"ok": bool(ok)}
    if op in ("pause", "resume"):
        job = (J.pause_job if op == "pause" else J.resume_job)(p["job_id"])
        _jobs_changed()
        return {"job": job}
    if op == "run":
        return {"job": J.trigger_job(p["job_id"])}
    raise ValueError("unknown jobs op")

LOCAL = {"sessions": local_sessions, "session": local_session, "skills": local_skills,
         "toolsets": local_toolsets, "jobs": local_jobs}

def warm_up():
    # 第一次读会话列表要先 import Hermes 的数据库模块（要好几秒）：连上就在后台先 import，手机第一次要列表时不用等
    for name in ("hermes_state", "tools.skills_tool", "hermes_cli.tools_config", "cron.jobs"):
        try:
            __import__(name)
        except Exception:
            pass

async def main():
    try:
        from hermes_constants import get_hermes_home
        from hermes_cli.gateway_runtime import discover_gateway_endpoint, attachable, gateway_profile_home
        from hermes_cli.gateway_client import GatewayClient, GatewayClientError, _session_ticket, gateway_ws_target
        from websockets.asyncio.client import connect
    except Exception as exc:
        emit({"ready": False, "error": "这个版本的 Hermes 不支持经 gateway 连接（%s）" % exc})
        return
    home = gateway_profile_home(get_hermes_home())
    found = await asyncio.to_thread(discover_gateway_endpoint, home)
    if not attachable(found):
        emit({"ready": False, "error": "Hermes gateway 没在运行（%s）。在电脑上运行 hermes gateway 启动它" % (found.reason_code or found.state)})
        return
    try:
        # host 范围：和电脑版一样，这条连接能坐进群聊、能管这台 gateway 服务的各个 bot
        ticket = await asyncio.to_thread(_session_ticket, home, found.endpoint, scope="host")
        url, protocols = gateway_ws_target(found.endpoint, ticket)
        ws_cm = connect(url, subprotocols=protocols, open_timeout=15, max_size=32 * 1024 * 1024, proxy=None)
        ws = await ws_cm.__aenter__()
    except Exception as exc:
        emit({"ready": False, "error": "连不上 Hermes gateway：%s" % exc})
        return
    async with GatewayClient(ws) as client:
        endpoint = found.endpoint
        emit({"ready": True, "code_sha": getattr(endpoint, "code_sha", None),
              "capabilities": sorted(getattr(endpoint, "capabilities", None) or []),
              "home": str(get_hermes_home())})
        loop = asyncio.get_running_loop()
        loop.run_in_executor(None, warm_up)
        reader = asyncio.StreamReader(limit=64 * 1024 * 1024)
        await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)

        async def forward_events():
            while True:
                item = await client.events.get()
                if isinstance(item, Exception):
                    emit({"closed": str(item) or "gateway 断开了"})
                    os._exit(0)
                if item.get("method") == "event":
                    emit({"event": item.get("params") or {}})

        async def handle(req):
            rid = req.get("id")
            try:
                if "local" in req:
                    fn = LOCAL[req["local"]]
                    result = await asyncio.to_thread(fn, req.get("params") or {})
                else:
                    timeout = req.get("timeout")
                    result = await client.rpc(req["method"], _timeout=timeout, **(req.get("params") or {}))
                emit({"id": rid, "result": result})
            except Exception as exc:
                emit({"id": rid, "error": {"reason": str(getattr(exc, "reason", None) or exc)[:500],
                                           "code": getattr(exc, "code", None),
                                           "data": getattr(exc, "data", None)}})

        asyncio.ensure_future(forward_events())
        while True:
            line = await reader.readline()
            if not line:
                break
            try:
                req = json.loads(line)
            except ValueError:
                continue
            asyncio.ensure_future(handle(req))
    await ws_cm.__aexit__(None, None, None)

asyncio.run(main())
""".trimIndent()
}
