#!/bin/bash
# 跑全部测试（含真实 Hermes 和真实 OpenSSH 的集成测试）。参数原样传给 gradle，例如：
#   tools/it-env/run_it.sh --tests '*OpenSshIT.groupChatOverRealSsh' -i
IT_ROOT=${IT_ROOT:-/home/user/ref}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
"$REPO/tools/it-env/ensure_env.sh" || exit 1
cd "$REPO/tools/ui-preview" || exit 1
HERMES_IT_HOST=127.0.0.1 \
HERMES_IT_KEY=$(cat "$IT_ROOT/hermes-key.txt") \
HERMES_IT_SSH_DIR=$IT_ROOT/sshtest \
HERMES_IT_SSH_PORT=2222 \
  gradle cleanTest test --offline "$@"
