package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 词库管理:下载笔画 / 拼音 / 英文词库,存进专门的文件夹,键盘下次弹出时自动加载。
 * 这是本应用里唯一联网的地方,只在点「下载并安装」时才访问网络。
 */
class DictManagerActivity : Activity() {

    private class Card(val pack: String) {
        lateinit var status: TextView
        lateinit var source: TextView
        lateinit var bar: ProgressBar
        lateinit var install: Button
        lateinit var delete: Button
    }

    private val cards = LinkedHashMap<String, Card>()
    private lateinit var capButton: Button
    private lateinit var userInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        val pad = dpi(16f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(label("词库管理", 24f, bold = true))
        root.addView(
            label(
                "词库存放的文件夹(可手动部署,见下):\n" + DictStore.root(this).absolutePath, 13f, alpha = 0.7f,
            ).apply {
                setTextIsSelectable(true)
                setPadding(0, pad / 2, 0, 0)
            },
        )
        root.addView(
            label(
                "手动部署:把转换好的 pinyin.txt / stroke.txt / english.txt / english_bigram.txt 直接放进这个文件夹," +
                    "或把原始文件放进其中的 raw/ 子文件夹后点「下载并安装」(下载失败时会用 raw/ 里已有的文件转换)。" +
                    "装好后回到输入框,键盘会自动重新加载。没装时拼音/笔画用内置小样本,英文页没有补全。",
                13f, alpha = 0.7f,
            ).apply { setPadding(0, pad / 2, 0, pad) },
        )

        addCard(
            root, DictPacks.STROKE, "笔画词库",
            "rime-stroke 笔画字表(含繁体和扩展区生僻字),字频取自雾凇 8105 字表。",
        )
        addCard(
            root, DictPacks.PINYIN, "拼音词库",
            "雾凇拼音:8105 字表 + 基础词库 + 扩展词库 + 41448 大字表(生僻字),再加朙月拼音(繁体字)。" +
                "下载量较大,建议连 Wi-Fi;转换需要几十秒。",
        )
        capButton = smallButton().apply {
            setOnClickListener { cycleCap() }
        }
        root.addView(capButton, smallButtonParams())
        addCard(
            root, DictPacks.ENGLISH, "英文词库",
            "Norvig 整理的谷歌语料词频:单词(前缀补全、纠错)+ 双词(下一个词联想),约 10 MB。",
        )

        // ---- 网络
        root.addView(label("网络", 18f, bold = true).apply { setPadding(0, pad, 0, pad / 2) })
        root.addView(
            label(
                "下载地址在 GitHub / norvig.com。连不上时可以填一个镜像前缀(会先试 前缀+原地址,再直连),留空 = 直连。",
                13f, alpha = 0.7f,
            ),
        )
        val mirror = EditText(this).apply {
            hint = "镜像前缀,例如 https://你的镜像/"
            setText(DictConfig.mirror(this@DictManagerActivity))
            setSingleLine(true)
        }
        root.addView(mirror)
        root.addView(
            smallButton("保存镜像前缀").apply {
                setOnClickListener {
                    DictConfig.setMirror(this@DictManagerActivity, mirror.text.toString())
                    Toast.makeText(this@DictManagerActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
            },
            smallButtonParams(),
        )

        // ---- 学习
        root.addView(label("词频学习", 18f, bold = true).apply { setPadding(0, pad, 0, pad / 2) })
        root.addView(
            label(
                "选过的候选会排得更靠前;英文还会记住你常打的新词和词的先后顺序。只存在本机应用私有目录,不上传。" +
                    "密码框、网址/邮箱框,以及应用声明不要建议/个性化学习的输入框里不记录。",
                13f, alpha = 0.7f,
            ),
        )
        root.addView(
            CheckBox(this).apply {
                text = "记录并使用我的用词"
                textSize = 15f
                minHeight = dpi(48f)
                isChecked = DictConfig.learn(this@DictManagerActivity)
                setOnCheckedChangeListener { _, v -> DictConfig.setLearn(this@DictManagerActivity, v) }
            },
        )
        root.addView(
            CheckBox(this).apply {
                text = "英文:按空格时自动改正拼错的词(默认只在候选里给出)"
                textSize = 15f
                minHeight = dpi(48f)
                isChecked = DictConfig.autoCorrect(this@DictManagerActivity)
                setOnCheckedChangeListener { _, v -> DictConfig.setAutoCorrect(this@DictManagerActivity, v) }
            },
        )
        userInfo = label("", 13f, alpha = 0.7f)
        root.addView(userInfo)
        root.addView(
            smallButton("清除学习数据").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@DictManagerActivity)
                        .setMessage("拼音、笔画、英文的词频学习记录都会删除,无法恢复。")
                        .setPositiveButton("清除") { _, _ ->
                            UserData.clearAll(applicationContext)
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            smallButtonParams(),
        )

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 界面搭建

    private fun label(text: String, sp: Float, bold: Boolean = false, alpha: Float = 1f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            this.alpha = alpha
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun addCard(root: LinearLayout, pack: String, title: String, desc: String) {
        val card = Card(pack)
        val pad = dpi(12f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dpf(12f)
                setStroke(dpi(1f), 0x44888888)
            }
        }
        box.addView(label(title, 17f, bold = true))
        box.addView(label(desc, 13f, alpha = 0.7f).apply { setPadding(0, dpi(2f), 0, dpi(6f)) })
        card.status = label("", 14f)
        box.addView(card.status)
        card.source = label("", 12f, alpha = 0.7f)
        box.addView(card.source)
        card.bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        box.addView(card.bar)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.install = smallButton().apply { setOnClickListener { onInstallClick(pack) } }
        card.delete = smallButton("删除").apply {
            setOnClickListener {
                if (busyPack == null) {
                    DictInstaller.delete(applicationContext, pack)
                    notes.remove(pack)
                    refresh()
                }
            }
        }
        val customBtn = smallButton("自定义来源").apply { setOnClickListener { showCustomDialog(pack, title) } }
        row.addView(card.install, smallButtonParams())
        row.addView(customBtn, smallButtonParams())
        row.addView(card.delete, smallButtonParams())
        box.addView(row)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = dpi(10f)
        root.addView(box, lp)
        cards[pack] = card
    }

    // ------------------------------------------------------------ 状态

    private fun sizeText(b: Long): String =
        if (b >= 1048576) String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else String.format(Locale.US, "%d KB", b / 1024)

    private fun statusText(pack: String): String {
        val outs = DictPacks.outputs(pack)
        val f = DictStore.file(this, outs[0])
        val base = if (f.isFile && f.length() > 0) {
            val cnt = DictConfig.metaCount(this, pack)
            val c = if (cnt >= 0) " · $cnt 条" else ""
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
            "已安装$c · ${sizeText(f.length())} · $date"
        } else if (pack == DictPacks.ENGLISH) {
            "未安装(英文页没有补全)"
        } else {
            "未安装(使用内置小样本)"
        }
        val note = notes[pack]
        return if (note == null) base else "$base\n$note"
    }

    fun refresh() {
        val busy = busyPack
        for ((pack, card) in cards) {
            val installed = DictStore.file(this, DictPacks.outputs(pack)[0]).isFile
            card.status.text = statusText(pack)
            val customN = DictConfig.customUrls(this, pack).size
            card.source.text = if (customN == 0) "下载来源:默认" else "下载来源:自定义($customN 个地址)"
            val mine = busy == pack
            card.bar.visibility = if (mine) View.VISIBLE else View.GONE
            card.install.text = if (mine) "取消" else if (installed) "重新下载并更新" else "下载并安装"
            card.install.isEnabled = busy == null || mine
            card.delete.isEnabled = busy == null && installed
        }
        capButton.text = "拼音词条上限:${DictConfig.pinyinCap(this) / 10000} 万(点击切换,重新安装后生效;单字不受限)"
        capButton.isEnabled = busy == null
        userInfo.text = "已学习:拼音 ${UserData.pinyin(this).count()} 个 · 笔画 ${UserData.stroke(this).count()} 个 · " +
            "英文 ${UserData.english(this).count()} 个词 · 联想 ${UserData.chain(this).count()} 条"
    }

    private fun customHint(pack: String): String = when (pack) {
        DictPacks.PINYIN ->
            "每行一个地址(http/https),按顺序合并。每个文件是 Rime 的 *.dict.yaml,或「词<Tab>拼音(音节空格分开)<Tab>词频」文本。"
        DictPacks.STROKE ->
            "第 1 行:笔画字表(Rime stroke.dict.yaml 格式)。\n第 2 行(可选):字频表(Rime 格式,用来决定常用字排前面)。"
        else ->
            "第 1 行:单词词频(每行「词<Tab>次数」,按次数从高到低)。\n第 2 行(可选):双词词频(每行「词1 词2<Tab>次数」,用来做下一个词联想)。"
    }

    private fun showCustomDialog(pack: String, title: String) {
        if (busyPack != null) return
        val pad = dpi(16f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(label(customHint(pack) + "\n留空 = 用默认来源。连不上时可以在页面下方填镜像前缀。", 13f, alpha = 0.7f))
        val input = EditText(this).apply {
            hint = "https://example.com/xxx.dict.yaml"
            setText(DictConfig.customText(this@DictManagerActivity, pack))
            minLines = 3
            maxLines = 8
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            gravity = android.view.Gravity.TOP
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("$title · 自定义来源")
            .setView(box)
            .setPositiveButton("保存并下载") { _, _ ->
                val text = input.text.toString()
                val hasUrl = text.lines().any { it.trim().startsWith("http://") || it.trim().startsWith("https://") }
                if (text.isNotBlank() && !hasUrl) {
                    Toast.makeText(this, "地址要以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
                } else {
                    DictConfig.setCustomText(this, pack, text)
                    refresh()
                    if (busyPack == null) startInstall(pack)
                }
            }
            .setNegativeButton("恢复默认") { _, _ ->
                DictConfig.setCustomText(this, pack, "")
                refresh()
                Toast.makeText(this, "已恢复默认来源,点「下载并更新」生效", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun showProgress(pack: String, text: String, frac: Float) {
        val card = cards[pack] ?: return
        card.bar.visibility = View.VISIBLE
        card.bar.progress = (frac.coerceIn(0f, 1f) * 1000).toInt()
        card.status.text = text
    }

    private fun cycleCap() {
        val options = listOf(100_000, 200_000, 400_000)
        val cur = DictConfig.pinyinCap(this)
        val next = options[(options.indexOf(cur) + 1) % options.size]
        DictConfig.setPinyinCap(this, next)
        refresh()
    }

    // ------------------------------------------------------------ 下载安装

    private fun onInstallClick(pack: String) {
        val busy = busyPack
        if (busy == pack) {
            cancelFlag.set(true)
            notes[pack] = "正在取消…"
            refresh()
            return
        }
        if (busy != null) return
        startInstall(pack)
    }

    private fun startInstall(pack: String) {
        busyPack = pack
        cancelFlag.set(false)
        notes.remove(pack)
        refresh()
        val ctx = applicationContext
        Thread {
            val msg: String = try {
                DictInstaller.install(ctx, pack, cancelFlag) { text, frac ->
                    ui.post { active?.showProgress(pack, text, frac) }
                }
            } catch (e: CancelledException) {
                "已取消"
            } catch (e: OutOfMemoryError) {
                "内存不足,请把拼音词条上限调低后重试"
            } catch (e: Throwable) {
                "失败:" + (e.message ?: e.javaClass.simpleName)
            }
            ui.post {
                busyPack = null
                notes[pack] = msg
                active?.refresh()
            }
        }.also { it.name = "dict-install" }.start()
    }

    private companion object {
        // 安装线程在整个进程里只有一个;页面被关掉再打开,还能看到进行中的状态
        @Volatile
        var busyPack: String? = null
        val cancelFlag = AtomicBoolean(false)
        val notes = HashMap<String, String>()

        @Volatile
        var active: DictManagerActivity? = null
        val ui = Handler(Looper.getMainLooper())
    }
}
