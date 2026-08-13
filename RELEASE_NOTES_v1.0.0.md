# Snoy-RawBridge v1.0.0

Snoy-RawBridge 首个正式版本。

这是一个面向索尼相机的 Android 有线传图工具，支持缩略图优先浏览，并按需导入 RAW / JPEG 原文件到手机。

## 本次发布

- 支持 Android 与索尼相机之间的有线 USB `MTP` 导入。
- 支持缩略图优先浏览、大图库增量出图与按时间从近到远显示。
- 支持多选、筛选和批量导入 RAW / JPEG 原文件。
- 修复索尼相机根目录枚举时返回子目录文件导致图库为空的问题。
- 修复 RAW 写入 `Pictures` 目录与导入完成记录丢失的问题。
- 支持导入进度、停止导入、历史记录及导入目录配置。
- 支持浅色、深色和跟随系统主题。

## 使用提示

- 相机 USB 模式请选择 `MTP`。
- 大容量存储卡首次加载时会持续增量显示最新照片。

## 发布附件

- `app-release.apk`：正式安装包。
- `app-debug.apk`：带调试入口的安装包，使用与正式版相同的签名证书。

## 相关链接

- 项目地址: [XavierZane / Snoy-RawBridge](https://github.com/XavierZane/Snoy-RawBridge)
- 使用文档: [使用文档.md](https://github.com/XavierZane/Snoy-RawBridge/blob/main/%E4%BD%BF%E7%94%A8%E6%96%87%E6%A1%A3.md)
