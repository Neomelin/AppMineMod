package com.example.mcpacks

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

const val BASE = "/sdcard/Android/data/com.mojang.minecraftpe/files/games/com.mojang"
val DIRS = linkedMapOf(
    "behavior_packs" to "Behavior",
    "resource_packs" to "Resource",
    "development_behavior_packs" to "Dev behavior",
    "development_resource_packs" to "Dev resource"
)

data class Pack(val dir: String, val folder: String, val name: String, val icon: Bitmap?) {
    val path get() = "$BASE/$dir/$folder"
    val label get() = DIRS[dir] ?: dir
}

private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

// Запуск команды от имени shell через Shizuku
private fun sh(cmd: String): ByteArray {
    val m = Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java, Array<String>::class.java, String::class.java
    )
    m.isAccessible = true
    val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    val out = p.inputStream.readBytes()
    p.waitFor()
    return out
}

private fun shText(cmd: String) = String(sh(cmd), Charsets.UTF_8)

private fun loadPacks(): List<Pack> {
    val res = mutableListOf<Pack>()
    for (d in DIRS.keys) {
        val folders = shText("ls -1 ${q("$BASE/$d")} 2>/dev/null").lines().filter { it.isNotBlank() }
        for (f in folders) {
            val base = "$BASE/$d/$f"
            var name = f
            try {
                val raw = shText("cat ${q("$base/manifest.json")} 2>/dev/null").trim().trimStart('\uFEFF')
                name = JSONObject(raw).getJSONObject("header").optString("name", f)
                if (name.startsWith("pack.")) {
                    val lang = shText("cat ${q("$base/texts/en_US.lang")} 2>/dev/null")
                    val line = lang.lines().firstOrNull { it.startsWith("$name=") }
                    val v = line?.substringAfter("=")?.substringBefore("##")?.trim()
                    name = if (!v.isNullOrEmpty()) v else f
                }
            } catch (e: Throwable) {
                name = f
            }
            name = name.replace(Regex("§."), "").trim().ifEmpty { f }
            val bytes = sh("cat ${q("$base/pack_icon.png")} 2>/dev/null")
            val icon = if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            res.add(Pack(d, f, name, icon))
        }
    }
    return res
}

class MainActivity : Activity() {
    private val ex = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val rows = mutableListOf<Pair<Pack, CheckBox>>()

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) refresh()
            else status.text = "Доступ не выдан. Нажми «Обновить» и разреши в окне Shizuku."
        }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        status = TextView(this).apply { textSize = 16f; text = "Запуск..." }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refreshBtn = Button(this).apply { text = "Обновить"; setOnClickListener { refresh() } }
        val shizukuBtn = Button(this).apply {
            text = "Shizuku"
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i)
                else Toast.makeText(this@MainActivity, "Shizuku не установлен", Toast.LENGTH_LONG).show()
            }
        }
        val delBtn = Button(this).apply { text = "Удалить"; setOnClickListener { confirmDelete() } }
        for (b in listOf(refreshBtn, shizukuBtn, delBtn)) {
            buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(buttons)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    private fun refresh() {
        if (!Shizuku.pingBinder()) {
            status.text = "Shizuku не запущен. Открой Shizuku, запусти его и вернись сюда."
            list.removeAllViews(); rows.clear()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status.text = "Разреши доступ в окне Shizuku."
            Shizuku.requestPermission(1)
            return
        }
        status.text = "Загрузка..."
        ex.execute {
            try {
                val packs = loadPacks()
                runOnUiThread { show(packs) }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Ошибка: ${e.message}" }
            }
        }
    }

    private fun show(packs: List<Pack>) {
        list.removeAllViews(); rows.clear()
        status.text = if (packs.isEmpty()) "Паков не найдено" else "Найдено паков: ${packs.size}"
        for (p in packs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val cb = CheckBox(this)
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                if (p.icon != null) setImageBitmap(p.icon)
            }
            val tv = TextView(this).apply {
                text = "${p.name}\n${p.label} · ${p.folder}"
                textSize = 15f
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(cb); row.addView(iv); row.addView(tv)
            row.setOnClickListener { cb.toggle() }
            list.addView(row)
            rows.add(p to cb)
        }
    }

    private fun confirmDelete() {
        val sel = rows.filter { it.second.isChecked }.map { it.first }
        if (sel.isEmpty()) {
            Toast.makeText(this, "Ничего не выбрано", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить ${sel.size} шт.?")
            .setMessage(sel.joinToString("\n") { it.name } + "\n\nОтменить это нельзя.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> delete(sel) }
            .show()
    }

    private fun delete(sel: List<Pack>) {
        status.text = "Удаляю..."
        ex.execute {
            for (p in sel) {
                if (p.folder.isBlank() || p.folder.contains("/") || p.folder.contains("..")) continue
                shText("rm -rf ${q(p.path)} 2>&1")
            }
            runOnUiThread {
                Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }
}
