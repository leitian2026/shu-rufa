# 我的输入法(拼音 / 笔画 / 英文)

自用 Android 输入法。无任何权限(不联网),零第三方依赖,Kotlin 自绘键盘。

## 构建(只能手动触发)

1. 把本目录推到 GitHub 仓库。
2. 仓库页 → **Actions** → *Build release APK (manual)* → **Run workflow**。
3. 跑完后在该次运行页底部的 Artifacts 下载 `myime-release-apk`,解压得到 `app-release.apk`。
4. 手机安装 → 打开「我的输入法」App → 按两个按钮启用并切换。

不会自动构建:工作流只有 `workflow_dispatch` 触发器。

### 签名(建议配置一次)
不配置时用 debug 密钥,每次构建的密钥不同,**覆盖安装前要先卸载旧版**。想固定密钥:

```
keytool -genkeypair -v -keystore release.jks -alias myime -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks        # macOS: base64 -i release.jks
```
仓库 Settings → Secrets and variables → Actions,添加:
`KEYSTORE_BASE64`(上面的输出)、`KEYSTORE_PASSWORD`、`KEY_ALIAS`(myime)、`KEY_PASSWORD`。
`*.jks` 已在 .gitignore,不要提交。

### 以后开启 R8
`app/build.gradle.kts` 里把 `isMinifyEnabled`、`isShrinkResources` 改成 `true`。

## 使用
- 「切换」键:拼音 → 笔画 → 英文 循环;「123」键进符号页。
- 拼音:支持全拼、首字母(如 `nh`→你好)、连打(`woaini` 可整句,也可逐段选词)。
- 笔画:横 一(h) 竖 丨(s) 撇 丿(p) 点 丶(n) 折 乙(z)。
- 空格选第一个候选;回车在有输入时原样上屏;删除键长按连删。
- 密码框强制英文且不使用组合区;数字/电话框直接进符号页。

## 词库
`app/src/main/assets/` 里是启动用的**小样本**(约 220 词、66 字),只够验证流程。
换完整词库(Rime 格式):
```
python3 tools/convert_rime_dict.py pinyin app/src/main/assets/pinyin.txt 8105.dict.yaml base.dict.yaml
python3 tools/convert_rime_dict.py stroke app/src/main/assets/stroke.txt stroke.dict.yaml
```
词库越大,启动加载越慢、APK 越大,建议从单字表 + 基础词库开始。
雾凇拼音是 GPL-3.0,若基于它的数据公开发布,需遵守 GPL。

## 动画与流畅度
- 所有动画共用 `Motion.kt` 里的 **360ms + 同一条缓动曲线**(范围 300–450ms),要整体调快慢只改一处。
- 动画只改 alpha / translationY,不触发重新布局;键盘高度在所有模式下相同。
- 键盘整体是一个自绘 View,`onDraw` 不分配对象;按键高亮消退按帧时间统一推进。
- 词库查询在后台线程,不占用主线程。
- 候选栏的惯性滑动用的是系统 OverScroller,属于物理滚动,不受固定时长控制。

## 已知限制 / 后续
- 还没有用户词频学习、英文联想、主题自定义。
- 自带笔画样本为手工录入,笔顺以正式字表为准。
- targetSdk 为 34,避免 Android 15 强制全屏边到边布局影响键盘底部。
