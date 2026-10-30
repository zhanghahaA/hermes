#!/bin/bash
# 确保集成测试用的环境在跑：假模型（9900）、Hermes gateway（8642）、测试 sshd（2222）。
# 环境会时不时整个停掉（容器回收进程），跑 IT 之前先跑一次这个。
# IT_ROOT 下要有：hermes-agent/（Hermes 源码）、hvenv/（装了它的 venv）、hermes-home/（HERMES_HOME）、sshtest/（sshd 配置和钥匙），
# 搭建步骤见 HANDOFF.md 的「集成测试环境」。
IT_ROOT=${IT_ROOT:-/home/user/ref}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
export HERMES_HOME=$IT_ROOT/hermes-home

if ! curl -s -m2 http://127.0.0.1:9900/v1/models >/dev/null; then
  # 不用 ( … & ) 包一层：bash 有时把子 shell 直接 exec 成这个进程，脚本就会一直等它退出
  (cd "$REPO/tools/fake-llm" && exec nohup "$IT_ROOT/hvenv/bin/python" fake_llm.py --port 9900 > "$IT_ROOT/fake_llm.log" 2>&1 < /dev/null) &
  disown
  sleep 2
fi
if ! curl -s -m2 http://127.0.0.1:8642/health >/dev/null; then
  for pid in $(ps -eo pid,args | awk '$2 ~ /python/ && $0 ~ /hvenv\/bin\/hermes gateway/ {print $1}'); do kill "$pid"; done
  sleep 3
  nohup "$IT_ROOT/hvenv/bin/hermes" gateway > "$IT_ROOT/gateway.log" 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do curl -s -m 2 http://127.0.0.1:8642/health >/dev/null && break; sleep 2; done
  curl -s -m 2 http://127.0.0.1:8642/health >/dev/null || { echo "hermes gateway 没起来"; tail -20 "$IT_ROOT/gateway.log"; exit 1; }
fi
if ! (exec 3<>/dev/tcp/127.0.0.1/2222) 2>/dev/null; then
  mkdir -p /run/sshd
  /usr/sbin/sshd -f "$IT_ROOT/sshtest/sshd_config" -E "$IT_ROOT/sshtest/sshd.log"
fi
echo "env ok"
