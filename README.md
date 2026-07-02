# New Mipush Enhance

一个用于增强小米推送通知体验的 Xposed / LSPosed 模块。

**本项目地址：https://github.com/codecodegogogo/New-MiPush-Enhance**

---

forked from [vivian8421/MiPush-Enhance](https://github.com/vivian8421/MiPush-Enhance)

## 功能

- 在冻结应用收到 MIPush 通知后，点击通知时自动解冻目标应用。
- 解冻后重新触发原通知的 PendingIntent。
- 针对部分系统场景，会额外清除应用 stopped 状态，提升拉起成功率。
- 提供模块设置页，可隐藏桌面图标、查看说明并快速重启手机。

## 使用

在 LSPosed / Xposed 中启用模块后，请在模块作用域中勾选：

- 系统框架
- 小米服务框架

设置完成后重启手机。

## 重要

**冻结的应用必须支持mipush才可以适应本模块**



## 部分原理

模块主要 Hook 系统进程中的通知点击链路和 Activity 启动链路：

- 监听 `PendingIntentRecord.sendInner`，只在识别到 MIPush 通知点击时处理，避免普通闹钟或后台任务误触发。
- 解析通知 PendingIntent 和 MIPush payload 中的目标应用包名；如果应用处于停用/冻结状态，则通过系统 PackageManager 重新启用。
- 解冻后重新发送原通知的 PendingIntent，让目标应用继续处理原本的通知点击事件。
- 针对 Android 16 / HyperOS 中后台服务启动详情页可能被拦截的情况，会在最近一次通知点击窗口内由系统进程代为启动对应 Activity。

## 说明

本模块主要面向使用 root 停用类冻结方式的场景。不同 Android / MIUI 版本的系统实现可能存在差异，实际效果以真机测试为准。
