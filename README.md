# AI 对话助手

Android 端纯 Java 实现的 OpenAI 兼容接口对话客户端，不依赖 AndroidX 与第三方库，
最低支持 Android 4.4（minSdk 19）。

## 功能

- 多会话管理，历史记录保存在本地 SQLite
- 流式输出，可随时中断
- Markdown 富文本渲染（标题、列表、表格、代码块、公式）
- 图片上传与图片内容分析
- 深色模式

## 构建

```
./gradlew assembleDebug
```

## 版本

仓库按 0.1 的小版本递进，每个版本对应一个 tag（`v1.5` … ）。
