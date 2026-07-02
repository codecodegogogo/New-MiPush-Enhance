# New Mipush Enhance

一个用于增强小米推送通知体验的 Xposed / LSPosed 模块。

**本项目地址：https://github.com/codecodegogogo/New-MiPush-Enhance**

---

forked from vivian8421/MiPush-Enhance

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

- 一开始只做了“解冻”：点击通知时 hook PendingIntentRecord.sendInner，发现目标包被停用，就用 PackageManager.setApplicationEnabledSetting(... ENABLED ...) 把它启用，然后重放原来的 PendingIntent。

  后来发现问题是：应用确实被唤醒了，但支付宝、抖音打不开详情页。日志里看到关键原因是 Android 16 / HyperOS 的后台启动限制：目标应用的 PushMessageHandler 在后台服务进程里启动详情页 Activity，被系统拦了，日志是 Background activity launch blocked。

  中间试过几条路：

  - 先试延迟 1-2 秒重发通知 PendingIntent，结果只是再次把消息交给应用，仍然会被后台启动限制拦。
  - 又试解析 MiPush payload 里的跳转信息，尝试直接还原 intent_uri / class_name / notify_effect，但不同应用实现不一致，不够稳。
  - 然后从日志里确认：通知点击已经进了目标应用的 push service，真正失败点是后续 startActivity 被系统挡住。
  - 所以改成 hook 系统进程里的 startActivity / startActivityAsUser，在“刚刚发生通知点击”的短时间窗口内，如果目标应用后台服务要打开详情页，就由 system_server 代启动同一个 Intent。

  之后又遇到误触发：支付宝没通知也会被唤醒。日志显示那不是通知，是支付宝自己的 ALARM_ACTION。于是把触发条件收紧：只处理 MiPush 通知点击 PendingIntent，排除闹钟/后台任务，同时保留 com.xiaomi.xmsf / PushMessageHandler / mipush_payload 这些特征。

  最终链路就是：点击通知 -> 确认是 MiPush 点击 -> 解冻目标应用 -> 重发原通知 PendingIntent -> 目标应用处理消息 -> 如果后台启动详情页被系统拦截，就由系统进程代启动。

## 说明

本模块主要面向使用 root 停用类冻结方式的场景。不同 Android / MIUI 版本的系统实现可能存在差异，实际效果以真机测试为准。
