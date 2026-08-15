# DSH Mobile — DeepSeek Harness 原生安卓客户端

通过扫码配对，在手机上原生操控 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (DSH) Web GUI：查看/新建会话、收发消息、切换模型与思考强度、调整权限预设。服务端需安装 dsh-web-ui 全家桶（含 `dsh-remote-web-ui` 移动端远程插件）。

## 功能

- 扫码配对（一次性限时令牌 → 换取设备 cookie）
- 服务器地址管理：局域网 / 公网隧道地址自由切换
- 工作区 / 会话列表（分页加载）、新建 / 重命名会话
- 聊天：流式接收（SSE），公网隧道下自动降级为轮询
- 模型选择（按 provider 分组、思考强度）、权限预设

## 构建

### 方式一：GitHub Actions（推荐）

推送到本仓库后，`.github/workflows/build-apk.yml` 自动构建，产物在 Actions 运行页的 Artifacts 中下载：

```bash
git add .
git commit -m "init dsh-mobile"
git push origin main
```

### 方式二：本地构建

需要 JDK 17+ 与 Android SDK（API 34）：

```bash
gradle wrapper --gradle-version 8.7   # wrapper 缺失时执行一次
./gradlew assembleDebug               # APK 输出在 app/build/outputs/apk/debug/
```

## 使用

1. 桌面端打开 DSH GUI（`http://127.0.0.1:3080`）→ 侧边栏底部手机图标 → 打开配对面板
2. 手机安装 APK → 首次启动填写服务器地址（局域网 `http://192.168.x.x:3080` 或公网 `https://xxx.trycloudflare.com`）
3. 点「扫码配对」扫描桌面端二维码（或粘贴配对链接）
4. 配对成功后进入工作区列表，即可操作

> 配对令牌一次性且限时；「停止」会吊销所有设备；重启 dsh web 后需重新扫码。

## 项目结构

```
app/src/main/java/com/dsh/mobile/
├── MainActivity.kt        # 入口 + 导航（pair / workspaces / chat）
├── net/                   # 网络层：HTTP、配对、RPC、SSE、降级轮询
└── ui/                    # Compose UI：主题、设置
```

## 协议

本客户端对接 dsh-remote-web-ui 插件的移动端 API：配对 `POST /api/pair/accept`、数据通道 `POST /m/api/<method>`（白名单 9 方法）、实时事件 `GET /m/api/events.mux`（SSE）。
