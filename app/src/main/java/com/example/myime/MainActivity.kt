package com.example.myime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType

/** 只做三件事:引导启用、切换输入法、提供一个测试输入框。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPageBackground()
        val pad = dpi(20f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "搜猪输入法"
            textSize = 24f
        })
        root.addView(foldNote(
            sp = 14f,
            content = "拼音 / 笔画 / 英文 / 表情 / 剪贴板 / 输入翻译。联网只有两处:「词库管理」页点下载时;以及你在键盘「功能」页打开「输入翻译」并在翻译框里打字时(原文会发给微软 / 谷歌翻译)。其余时候键盘不联网。\n" +
                "剪贴板历史加密后保存在本机应用私有目录(用手机的安全存储密钥加密),本身不联网上传(只有你开了坚果云备份时,才会随加密的备份包一起上传);输入习惯(词频)只存在本机应用私有目录,可在词库管理里清除。\n" +
                "「备份与还原」可以把输入习惯和设置加密后存到你选的文件夹(本地、网盘),备份文件没有你的密码就打不开;网盘上传由网盘 App 完成,本输入法自己不联网。\n\n" +
                "先点第一个按钮,在系统设置里打开本输入法;再点第二个按钮切换过来,然后在下面的输入框里试试。",
        ).apply { setPadding(0, pad / 2, 0, pad) })
        root.addView(smallButton("1. 去系统设置启用").apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, smallButtonParams())
        root.addView(smallButton("2. 切换到本输入法").apply {
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        }, smallButtonParams())
        root.addView(smallButton("3. 词库管理(下载笔画 / 拼音 / 英文词库)").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, DictManagerActivity::class.java)) }
        }, smallButtonParams())
        root.addView(smallButton("4. 备份与还原(加密,本地 + 网盘)").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, BackupActivity::class.java)) }
        }, smallButtonParams())
        addTranslateSettings(root, pad)
        addEdgeSwitch(root, pad)
        addDebugSwitch(root, pad)
        root.addView(EditText(this).apply {
            hint = "在这里测试输入"
            minLines = 3
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Edge 搜索框自动弹键盘的过滤开关:默认开,只对 Edge 生效。 */
    private fun addEdgeSwitch(root: LinearLayout, pad: Int) {
        root.addView(TextView(this).apply {
            text = "Edge 浏览器"
            textSize = 20f
            setPadding(0, pad, 0, 0)
        })
        root.addView(foldNote(
            "在 Edge 里点收藏夹时,Edge 会让历史搜索框自动要求弹出键盘,过一秒左右又自己收起(换别的输入法也一样)。" +
                "开启后,键盘会在这种情况下先等一小会儿(约 0.12 秒):焦点马上转走就不弹;" +
                "是你自己点了搜索框,就只晚这一点点时间再弹出来。" +
                "在「下载 / 历史记录 / 收藏夹」页面,搜索框是页面自己自动对焦的,键盘不会自动弹出,点一下搜索框才弹。" +
                "只对 Edge 生效,别的应用不受影响,没有定时任务,不增加耗电。",
        ))
        root.addView(CheckBox(this).apply {
            text = "不让 Edge 点收藏夹 / 下载时弹出键盘"
            textSize = 15f
            minHeight = dpi(48f)
            isChecked = DictConfig.edgeFilter(this@MainActivity)
            setOnCheckedChangeListener { _, v -> DictConfig.setEdgeFilter(this@MainActivity, v) }
        })
    }

    /** 调试日志开关:默认关。开了以后键盘往系统日志(logcat,标签 MyIme)写耗时信息,用电脑 adb 才看得到。 */
    private fun addDebugSwitch(root: LinearLayout, pad: Int) {
        root.addView(TextView(this).apply {
            text = "调试日志"
            textSize = 20f
            setPadding(0, pad, 0, 0)
        })
        root.addView(foldNote(
            "开启后,键盘会记下:服务启动、键盘弹出、词库加载耗时、超过 40 毫秒的慢查询(同时写进系统日志 logcat,标签 MyIme)。" +
                "键盘每次弹出还会记下是哪个应用的什么输入框要的(应用包名、输入框类型、是应用主动要求还是焦点自动弹出),用来查「键盘莫名弹出来」。" +
                "不含你输入的内容,密码框连提示文字都不记;记录存在本机缓存目录的一个小文件里,不上传、不进备份,点「查看日志」就能看。" +
                "默认关闭,排查时再开;改了以后,下次键盘弹出时生效。关闭后弹出来源的记录逻辑也一起停掉。词库加载失败这类出错信息不管开关都会记。",
        ))
        root.addView(CheckBox(this).apply {
            text = "开启调试日志(排查卡顿 / 键盘弹出原因)"
            textSize = 15f
            minHeight = dpi(48f)
            isChecked = DictConfig.debugLog(this@MainActivity)
            setOnCheckedChangeListener { _, v -> DictConfig.setDebugLog(this@MainActivity, v) }
        })
        root.addView(smallButton("查看日志").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
        }, smallButtonParams())
    }

    private fun field(root: LinearLayout, label: String, hint: String, name: String, secret: Boolean): EditText {
        root.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dpi(10f), 0, 0)
        })
        val e = EditText(this).apply {
            this.hint = hint
            setText(DictConfig.transSetting(this@MainActivity, name))
            setSingleLine(true)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_VARIATION_URI)
        }
        root.addView(e)
        return e
    }

    /** 翻译接口设置:地址 / 密钥 / 区域可改,保存后下一次翻译就用新的;全空 = 用默认(免费接口)。 */
    private fun addTranslateSettings(root: LinearLayout, pad: Int) {
        root.addView(TextView(this).apply {
            text = "翻译接口设置"
            textSize = 20f
            setPadding(0, pad, 0, 0)
        })
        root.addView(foldNote(
            "全部留空 = 用默认的免费接口(微软 Edge / 谷歌 gtx)。接口以后变了,在这里改地址即可。" +
                "填了「密钥」就改用官方接口:微软 Azure Translator、谷歌 Cloud Translation v2(此时地址留空会用官方默认地址)。" +
                "设置只存在本机应用私有目录。",
        ))
        val msUrl = field(root, "微软:接口地址", Translator.DEF_MS_FREE, "ms_url", false)
        val msKey = field(root, "微软:密钥(可空,官方接口才需要)", "Azure Translator 密钥", "ms_key", true)
        val msRegion = field(root, "微软:区域(可空,如 eastasia)", "密钥所在区域", "ms_region", true)
        val gUrl = field(root, "谷歌:接口地址", Translator.DEF_G_FREE, "g_url", false)
        val gKey = field(root, "谷歌:密钥(可空,官方接口才需要)", "Cloud Translation API key", "g_key", true)

        val result = TextView(this).apply {
            textSize = 14f
            setPadding(0, dpi(8f), 0, 0)
        }
        fun save() {
            DictConfig.setTransSettings(
                this, mapOf(
                    "ms_url" to msUrl.text.toString(), "ms_key" to msKey.text.toString(),
                    "ms_region" to msRegion.text.toString(),
                    "g_url" to gUrl.text.toString(), "g_key" to gKey.text.toString(),
                ),
            )
            Translator.load(applicationContext)
        }
        fun test(engine: String) {
            save()
            result.text = "测试中…"
            Thread {
                val r = Translator.test(engine)
                runOnUiThread { result.text = Translator.engineLabel(engine) + ":" + r }
            }.start()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpi(8f), 0, 0)
        }
        row.addView(smallButton("保存").apply {
            setOnClickListener {
                save()
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }, smallButtonParams())
        row.addView(smallButton("恢复默认").apply {
            setOnClickListener {
                for (e in listOf(msUrl, msKey, msRegion, gUrl, gKey)) e.setText("")
                save()
                Toast.makeText(this@MainActivity, "已恢复默认", Toast.LENGTH_SHORT).show()
            }
        }, smallButtonParams())
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton("测试微软").apply { setOnClickListener { test(Translator.MS) } }, smallButtonParams())
        row2.addView(smallButton("测试谷歌").apply { setOnClickListener { test(Translator.GOOGLE) } }, smallButtonParams())
        root.addView(row2)
        root.addView(result)
    }
}
