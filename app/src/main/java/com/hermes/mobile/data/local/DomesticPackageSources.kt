package com.hermes.mobile.data.local

/** App 安装任务使用独立的源列表与索引缓存，不覆盖用户的 Termux 全局源配置。 */
internal object DomesticPackageSources {
    const val Notice = "安装依赖使用清华国内镜像；Hermes 安装包及 Termux／X11 应用下载仍走官方源。"
    const val Update = "hm_apt -o APT::Update::Error-Mode=any update"

    fun setup(includeHermes: Boolean = true): String = """
        hm_apt_root="§HERMES_MOBILE_ROOT/apt"
        for hm_path in "§hm_apt_root" "§hm_apt_root/lists" "§hm_apt_root/lists/partial" "§hm_apt_root/sources.list"; do
            [ ! -L "§hm_path" ] || { echo '安装源目录不能是符号链接' >&2; exit 25; }
        done
        mkdir -p "§hm_apt_root/lists/partial"
        hm_sources=§(mktemp "§hm_apt_root/sources.XXXXXX")
        printf '%s\n' \
          'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main' \
          'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-root root stable' \
          'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-x11 x11 main' > "§hm_sources"
        @@HERMES@@
        mv -f "§hm_sources" "§hm_apt_root/sources.list"
        hm_apt() {
            apt-get -o "Dir::Etc::sourcelist=§hm_apt_root/sources.list" \
                -o 'Dir::Etc::sourceparts=-' -o "Dir::State::lists=§hm_apt_root/lists" "§@"
        }
    """.trimIndent().replace("@@HERMES@@", if (includeHermes) """
        if [ -f "§PREFIX/etc/apt/sources.list.d/hermes-agent.list" ]; then
            cat "§PREFIX/etc/apt/sources.list.d/hermes-agent.list" >> "§hm_sources"
        fi
    """.trimIndent() else "").replace('§', '$')
}
