# 我的输入法(拼音 / 笔画 / 英文)

自用 Android 输入法。无任何权限(不联网),零第三方依赖,Kotlin 自绘键盘。

## 构建(只能手动触发)

1. 把本目录推到 GitHub 仓库。
2. 仓库页 → **Actions** → *Build release APK (manual)* → **Run workflow**。
3. 跑完后在该次运行页底部的 Artifacts 下载名为 `myime-v版本号-build序号` 的产物,解压得到 `app-release.apk`。
4. 手机安装 → 打开「我的输入法」App → 按两个按钮启用并切换。

不会自动构建:工作流只有 `workflow_dispatch` 触发器。

### 签名与覆盖安装
覆盖安装需要三件事同时满足:**包名不变、签名密钥不变、versionCode 只增不减**。

- 包名固定为 `com.example.myime`。
- 签名:仓库里自带 `signing/myime.jks`(密码 `myime-self-use`,别名 `myime`),每次构建都用它,所以后续版本可以直接覆盖安装。
- versionCode 由版本号加 CI 运行序号自动算出,同一版本号重复构建也会继续增大。
- ⚠️ 这把密钥在仓库里,**仓库请设为私有**。如果要公开仓库,请改用 Secrets:删掉 `signing/myime.jks`,自己生成密钥,
  然后在仓库 Settings → Secrets and variables → Actions 添加 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
  有 Secrets 时会优先使用 Secrets。
- 换了密钥后,手机上用旧密钥装的版本必须先卸载一次。

```
keytool -genkeypair -v -keystore release.jks -alias myime -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks        # macOS: base64 -i release.jks
```

### 版本号怎么改
只改根目录 `version.properties`,格式是 **主版本.次版本.修订号**(例如 1.4.2):

| 改动类型 | 怎么升 | 例子 |
|---|---|---|
| 大版本:大改版、重大功能变化 | 主版本 +1,次版本和修订清零 | 1.4.2 → 2.0.0 |
| 小版本:新增功能,向下兼容 | 次版本 +1,修订清零 | 1.4.2 → 1.5.0 |
| 修订:只修 bug、调细节 | 修订 +1 | 1.4.2 → 1.4.3 |

- 还没稳定时用 `0.x.y`,觉得可以长期用了再发 `1.0.0`。
- 不用手动管 versionCode,构建时自动计算:`主×10,000,000 + 次×100,000 + 修订×1,000 + 构建号`。
- 每次构建的产物名里带版本号和构建序号,如 `myime-v0.1.0-build3`,方便对应。

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
