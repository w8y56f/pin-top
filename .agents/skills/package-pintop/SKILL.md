---
name: package-pintop
description: 用户要求给 PinTop 打包或发布 APK 时使用，构建可安装 APK 并上传到此项目的 GitHub Release。
---

在 PinTop 仓库根目录执行。使用和之前分发的 APK 一致的签名密钥。本项目目前分发 assembleDebug 生成的签名 APK，以便已有用户覆盖安装。

1. 执行 ./gradlew :app:assembleDebug。从 app/build/outputs/apk/debug/output-metadata.json 的 elements[0] 读取 versionName 和 outputFile，将 APK 复制为同目录的 pin-top-<versionName>.apk。使用 Android SDK 的 apksigner verify 检查签名。
2. 标签直接使用 versionName，例如 0.1.0。检查工作区和当前提交。发布用标签应能还原 APK 源码；若构建含未提交内容，保留已生成的 dirty APK，向用户确认要纳入版本的改动后再提交、重新构建并发布。
3. 推送发布提交。首次发布执行 gh release create "$version" "$apk" --target "$commit" --title "$version" --generate-notes，其中 commit 是构建时的完整 HEAD。若标签已存在，核对它指向构建提交；已有 Release 可用 gh release upload 补传缺失资产。同名资产已存在时校验 SHA-256，一致即完成，不一致时先确认是否替换。
4. 使用 gh release view 核对标签与资产名称，返回 APK 路径和 Release 链接。
