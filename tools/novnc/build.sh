#!/bin/bash
# 生成 app/src/main/assets/screen/screen.html：把 noVNC（和 Hermes 桌面版同一个版本）打包成一个 ES 模块，内联进页面。
# 需要 node/npm。生成的文件提交进仓库，平时编译 App 不需要 npm。
set -euo pipefail
NOVNC_VERSION=1.7.0
ESBUILD_VERSION=0.24.0
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../app/src/main/assets/screen"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
npm pack "@novnc/novnc@$NOVNC_VERSION" >/dev/null
tar xzf "novnc-novnc-$NOVNC_VERSION.tgz"
npm init -y >/dev/null
npm i "esbuild@$ESBUILD_VERSION" >/dev/null 2>&1
printf 'import RFB from "./package/core/rfb.js";\nwindow.RFB = RFB;\n' > entry.js
# noVNC 用了顶层 await，只能打成 ES 模块；WebView 里 file:// 加载不了外部模块，所以内联
./node_modules/.bin/esbuild entry.js --bundle --minify --format=esm --target=chrome89 --outfile=novnc.mjs --log-level=warning
if grep -q '</script' novnc.mjs; then echo "bundle contains </script" >&2; exit 1; fi
mkdir -p "$out"
python3 - "$here/screen.template.html" novnc.mjs "$out/screen.html" <<'PY'
import sys
template, bundle, target = sys.argv[1:4]
html = open(template, encoding="utf-8").read()
js = open(bundle, encoding="utf-8").read()
assert "/*NOVNC_BUNDLE*/" in html
open(target, "w", encoding="utf-8").write(html.replace("/*NOVNC_BUNDLE*/", js, 1))
PY
cp package/LICENSE.txt "$out/LICENSE-noVNC.txt"
cp package/vendor/pako/LICENSE "$out/LICENSE-pako.txt"
echo "wrote $out/screen.html ($(wc -c < "$out/screen.html") bytes), noVNC $NOVNC_VERSION"
