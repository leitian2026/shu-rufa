package com.example.myime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
        val pad = dpi(20f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "我的输入法"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "拼音 / 笔画 / 英文 / 表情 / 剪贴板 / 输入翻译。联网只有两处:「词库管理」页点下载时;以及你在键盘「功能」页打开「输入翻译」并在翻译框里打字时(原文会发给微软 / 谷歌翻译)。其余时候键盘不联网。\n" +
                "剪贴板历史只保存在内存里,不写入存储;输入习惯(词频)只存在本机应用私有目录,可在词库管理里清除。\n" +
                "「备份与还原」可以把输入习惯和设置加密后存到你选的文件夹(本地、网盘),备份文件没有你的密码就打不开;网盘上传由网盘 App 完成,本输入法自己不联网。\n\n" +
                "先点第一个按钮,在系统设置里打开本输入法;再点第二个按钮切换过来,然后在下面的输入框里试试。"
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })
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
        root.addView(EditText(this).apply {
            hint = "在这里测试输入"
            minLines = 3
            setPadding(0, pad, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
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
        root.addView(TextView(this).apply {
            text = "全部留空 = 用默认的免费接口(微软 Edge / 谷歌 gtx)。接口以后变了,在这里改地址即可。" +
                "填了「密钥」就改用官方接口:微软 Azure Translator、谷歌 Cloud Translation v2(此时地址留空会用官方默认地址)。" +
                "设置只存在本机应用私有目录。"
            textSize = 13f
        })
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
