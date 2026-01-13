#!/bin/bash
set -e

# 确保环境变量正确
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin
export HOME=/root

# 配置 DNS
if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

# 美化终端提示符
export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@localhost \[\033[39m\]\w \[\033[0m\]\\$ "

# 检查是否安装了 Node.js
if ! command -v node &> /dev/null; then
    echo -e "\e[34;1m[*] \e[0mInitializing Environment (Alpine 3.19)...\e[0m"

    # 换源 (可选，如果下载慢可以把下面两行注释去掉)
    # echo "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/main" > /etc/apk/repositories
    # echo "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/community" >> /etc/apk/repositories

    # 1. 安装基础依赖和 Node.js (v20)
    apk update
    apk add bash gcompat glib nano nodejs npm

    # 2. 安装 LSP 服务
    echo -e "\e[34;1m[*] \e[0mInstalling Language Servers...\e[0m"
    # 清理可能存在的残留
    rm -rf /usr/local/lib/node_modules

    # 全局安装 LSP
    npm install -g typescript typescript-language-server vscode-langservers-extracted

    echo -e "\e[32;1m[+] \e[0mEnvironment Ready! Please restart the app if LSP doesn't work immediately.\e[0m"
fi

# 启动 Shell
if [ "$#" -eq 0 ]; then
    source /etc/profile
    cd $HOME
    /bin/bash
else
    exec "$@"
fi