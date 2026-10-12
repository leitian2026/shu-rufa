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
import android.widget.TextView

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
                "剪贴板历史只保存在内存里,不写入存储;输入习惯(词频)只存在本机应用私有目录,可在词库管理里清除。\n\n" +
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
        root.addView(EditText(this).apply {
            hint = "在这里测试输入"
            minLines = 3
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }
}
