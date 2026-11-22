---
name: package-pintop
description: 用户要求给 PinTop 打包或发布 APK 时使用，构建可安装 APK 并上传到此项目的 GitHub Release。
---

在 PinTop 仓库根目录执行。使用和之前分发的 APK 一致的签名密钥。本项目目前分发 assembleDebug 生成的签名 APK，以便已有用户覆盖安装。

1. 打包前执行 `git fetch origin`，用 `git status --porcelain` 检查暂存、未暂存及未跟踪的改动，并确认 GitHub 上对应分支包含当前 HEAD（`git merge-base --is-ancestor HEAD origin/<目标分支>`）。若有未提交改动、远端分支尚未创建或有提交尚未推送，先概述状态，询问用户是否先提交并推送到 GitHub 后再打包；若仅缺推送，只询问是否推送。等待用户答复后再操作。确认同步成功后再构建；若用户明确选择仅本地打包，按其选择生成 APK，跳过发布。无法核实远端时说明原因。
2. 执行 ./gradlew :app:assembleDebug。从 app/build/outputs/apk/debug/output-metadata.json 的 elements[0] 读取 versionName 和 outputFile，将 APK 复制为同目录的 pin-top-<versionName>.apk。使用 Android SDK 的 apksigner verify 检查签名。
3. 标签直接使用 versionName，例如 0.1.0。首次发布执行 gh release create "$version" "$apk" --target "$commit" --title "$version" --generate-notes，其中 commit 是构建时的完整 HEAD。若标签已存在，核对它指向构建提交；已有 Release 可用 gh release upload 补传缺失资产。同名资产已存在时校验 SHA-256，一致即完成，不一致时先确认是否替换。
4. 使用 gh release view 核对标签与资产名称，返回 APK 路径和 Release 链接。
