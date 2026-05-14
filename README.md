# 悬浮捕获 (FloatingCatch)

手机端碎片信息采集工具。常驻悬浮球，一键捕获剪贴板/截图/录屏，自动保存到坚果云同步目录。

## 功能

| 操作 | 效果 |
|------|------|
| **单击**悬浮球 | 读取剪贴板（链接/文本），保存到 Inbox |
| **长按**悬浮球 | 截取当前屏幕，保存为 PNG |
| **双击**悬浮球 | 开始/停止录屏，保存为 MP4 |

## 保存目录结构

```
Inbox/
├── clip_20260514_133000.txt   # 剪贴板内容
├── shot_20260514_133005.png   # 截图
└── video_20260514_133010.mp4  # 录屏
```

坚果云同步到电脑后，用 Claude 自动读取分析、归档到 Obsidian 知识库。

## 构建方式

### 方式一：在线构建（推荐，无需装任何东西）

1. 把项目文件夹压缩成 zip
2. 打开 https://github.com 或创建私有仓库
3. 推送代码，启用 GitHub Actions（workflow 文件已包含）
4. Actions 运行完成后，下载 APK artifact

### 方式二：本地构建

1. 安装 Android Studio（https://developer.android.com/studio）
2. 打开项目文件夹 FloatingCatch
3. 等待 Gradle 同步完成
4. Build → Build Bundle(s) / APK(s) → Build APK(s)

### 方式三：命令行（需安装 Android SDK）

```bash
cd FloatingCatch
./gradlew assembleDebug
# APK 输出路径：app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 安装 APK 后，打开 app
2. 修改保存路径为你的坚果云同步目录（如 `/storage/emulated/0/坚果云/Inbox/`）
3. 点击"启动悬浮球"
4. 按提示授予悬浮窗权限和屏幕录制权限
5. 悬浮球出现后，最小化 app，正常使用手机
6. 看到想收藏的内容，操作悬浮球即可
