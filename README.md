# New Mipush Enhance

一个用于增强小米推送通知体验的 Xposed / LSPosed 模块。

**本项目地址：https://github.com/codecodegogogo/New-MiPush-Enhance**

**本项目forked from [vivian8421/MiPush-Enhance**](https://github.com/vivian8421/MiPush-Enhance)

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

- 监听 `PendingIntentRecord.sendInner`，在通知被点击时解析目标应用包名。
- 如果目标应用处于停用/冻结状态，则通过系统 PackageManager 将其重新启用。
- 启用后补发原始 PendingIntent，避免只解冻但不打开通知页面。
- 当 PendingIntent 重放仍失败时，尝试使用通知内保存的原始 Activity Intent 兜底启动。

## 说明

本模块主要面向使用 root 停用类冻结方式的场景。不同 Android / MIUI 版本的系统实现可能存在差异，实际效果以真机测试为准。
