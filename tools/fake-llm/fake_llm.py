"""
假的大模型服务（兼容 OpenAI /v1/chat/completions），给 Hermes 当模型用，做端到端测试。

不需要任何 API Key。按用户最后一条消息里的关键词走固定剧本：

- 默认：先输出一段思考（reasoning_content，内容含 SECRET-THINKING），再输出正文
  “收到：<你的话>”。用来验证 App 只显示正文、绝不显示思考。
- 含“运行命令”：第一轮调用 terminal 工具执行一条会触发审批的危险命令，
  拿到工具结果后回复“命令执行完了”。用来验证审批卡片。
- 含“慢慢说”：用约 8 秒慢慢输出 20 段文字。用来验证断线重连、停止。
- 含“追问我”：调用 clarify 工具向用户提问（只有支持追问的 Hermes 会转给 App）。
- 带图片：回复“收到 N 张图片：<你的话>”。用来验证 App 发图。

用法：python3 fake_llm.py --port 9900
Hermes 的 config.yaml：
    model:
      provider: custom
      model: fake-model
      base_url: http://127.0.0.1:9900/v1
      api_key: none
"""
import argparse
import asyncio
import json
import time
import uuid

from aiohttp import web


def last_user_text(messages):
    for m in reversed(messages):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                return " ".join(p.get("text", "") for p in c if isinstance(p, dict))
            return c or ""
    return ""


def image_count(messages):
    """最后一条用户消息里有几张图片（验证 App 发图）。"""
    for m in reversed(messages):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                return sum(1 for p in c if isinstance(p, dict) and p.get("type") in ("image_url", "input_image", "image"))
            return 0
    return 0


def has_tool_result(messages):
    """这一轮里，最后一条用户消息之后有没有工具结果。"""
    for m in reversed(messages):
        if m.get("role") == "user":
            return False
        if m.get("role") == "tool":
            return True
    return False


def chunk(delta, finish=None):
    return {
        "id": "chatcmpl-" + uuid.uuid4().hex[:12],
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": "fake-model",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


async def chat(request):
    body = await request.json()
    messages = body.get("messages", [])
    text = last_user_text(messages)
    stream = body.get("stream", False)
    tool_done = has_tool_result(messages)

    # 决定这一轮输出什么
    reasoning = "SECRET-THINKING: 用户说了「%s」，我想一想怎么回答。" % text[:40]
    tool_call = None
    pieces = []
    delay = 0.02
    if "运行命令" in text and not tool_done:
        tool_call = {
            "id": "call_" + uuid.uuid4().hex[:8],
            "type": "function",
            "function": {"name": "terminal", "arguments": json.dumps({"command": "rm -rf /tmp/hermes-mobile-test-dir"})},
        }
    elif "追问我" in text and not tool_done:
        tool_call = {
            "id": "call_" + uuid.uuid4().hex[:8],
            "type": "function",
            "function": {"name": "clarify", "arguments": json.dumps({
                # 新版 Hermes：questions 数组；旧版：顶层 question/choices。两种都给，各取所需
                "questions": [{"question": "你想要哪种颜色？", "choices": ["红色", "蓝色"]}],
                "question": "你想要哪种颜色？", "choices": ["红色", "蓝色"],
            })},
        }
    elif tool_done and "追问我" in text:
        pieces = ["好的，就按你选的来。"]
    elif tool_done:
        pieces = ["命令执行完了。"]
    elif "慢慢说" in text:
        pieces = ["第%d段。" % i for i in range(1, 21)]
        delay = 0.4
    elif image_count(messages):
        pieces = ["收到 %d 张图片：" % image_count(messages), text]
    else:
        pieces = ["收到：", text]

    if not stream:
        message = {"role": "assistant", "content": "".join(pieces) or None, "reasoning_content": reasoning}
        if tool_call:
            message["tool_calls"] = [tool_call]
        return web.json_response({
            "id": "chatcmpl-x", "object": "chat.completion", "created": int(time.time()), "model": "fake-model",
            "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if tool_call else "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
        })

    resp = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
    await resp.prepare(request)

    async def send(obj):
        await resp.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode())

    await send(chunk({"role": "assistant"}))
    for part in (reasoning[:20], reasoning[20:]):
        await send(chunk({"reasoning_content": part}))
        await asyncio.sleep(0.02)
    if tool_call:
        await send(chunk({"tool_calls": [{"index": 0, **tool_call}]}))
        await send(chunk({}, finish="tool_calls"))
    else:
        for p in pieces:
            await send(chunk({"content": p}))
            await asyncio.sleep(delay)
        await send(chunk({}, finish="stop"))
    await send({**chunk({}), "choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}})
    await resp.write(b"data: [DONE]\n\n")
    return resp


async def models(request):
    return web.json_response({"object": "list", "data": [{"id": "fake-model", "object": "model", "owned_by": "test"}]})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9900)
    args = parser.parse_args()
    app = web.Application()
    app.router.add_post("/v1/chat/completions", chat)
    app.router.add_get("/v1/models", models)
    web.run_app(app, host="127.0.0.1", port=args.port, print=None)


if __name__ == "__main__":
    main()
