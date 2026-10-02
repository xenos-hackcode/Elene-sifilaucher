package com.example.scifilauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*

/** Exports a standalone document; generated code is never executed in the launcher. */
class AppStarterActivity : Activity() {
    private var pendingHtml: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingHtml = savedInstanceState?.getString("pending_html")
        val ui = TerminalToolsUi(this, "02 / CREATE", "App workshop", "Start small. Make it yours. Keep it offline.")
        val setup = ui.card("01  /  NAME YOUR APP")
        val title = ui.input(setup, "App name", "My checklist", 8151).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            maxLines = 1
        }
        val templates = ui.card("02  /  CHOOSE A STARTER")
        ui.text(templates, "Checklist, notes, or online", 20f, ui.accent)
        ui.text(templates, "Two offline tools with local browser storage, or an online one that fetches from a URL you provide. Edit the exported HTML to build on any of them.")
        val template = RadioGroup(this).apply { id = 8152 }
        listOf("Checklist  /  tasks & progress", "Notes  /  a place to think", "Online  /  fetches data from a URL").forEachIndexed { index, label ->
            template.addView(RadioButton(this).apply {
                id = 8153 + index; text = label; setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                minHeight = ui.dp(52); textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        templates.addView(template)
        template.check(8153)
        val onlineCard = ui.card("02b  /  DATA SOURCE (online apps only)")
        val onlineUrl = ui.input(onlineCard, "https://api.example.com/data", "", 8160).apply { maxLines = 1 }
        ui.text(onlineCard, "The exported app can only reach this exact address (nothing else) - typed here once, baked into the file, not editable later without re-exporting.", 12f)
        val output = ui.card("03  /  EXPORT")
        ui.text(output, "One file. Ready for your browser.", 18f, ui.accent)
        ui.text(output, "Choose where to save your app, then open it in a browser. No account or API key required.")
        ui.button(output, "Export HTML app", true) {
            val mode = when (template.checkedRadioButtonId) {
                8154 -> AppStarterMode.NOTES
                8155 -> AppStarterMode.ONLINE
                else -> AppStarterMode.CHECKLIST
            }
            if (mode == AppStarterMode.ONLINE) {
                val origin = originOf(onlineUrl.text.toString())
                    ?: error("Enter a valid https:// URL for the data source first")
                pendingHtml = AppStarterTemplate.render(title.text.toString().ifBlank { "My app" }, mode, onlineUrl.text.toString().trim(), origin)
            } else {
                pendingHtml = AppStarterTemplate.render(title.text.toString().ifBlank { "My app" }, mode)
            }
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "text/html"
                putExtra(Intent.EXTRA_TITLE, "my-app.html")
            }, 82)
        }
        ui.text(ui.content, "EDITABLE WEB APP / NO INSTALL REQUIRED", 11f, ui.accent)
        ui.text(ui.content, "These are starter templates, not Android APKs. Browser storage can be cleared; keep a separate copy of important notes.", 12f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_html", pendingHtml)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 82) return
        val html = pendingHtml
        pendingHtml = null
        if (resultCode != RESULT_OK || html == null) return
        val uri = data?.data ?: return
        val result = runCatching {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(html) }
        }
        Toast.makeText(this, if (result.isSuccess) "App exported. Open the HTML in a browser."
            else "Export failed. Choose another destination and try again.", Toast.LENGTH_LONG).show()
    }

    /** scheme://host[:port] only, no path/query - the exact string that becomes the exported
     * app's whole connect-src allowlist. Returns null for anything that isn't plain http(s), so a
     * malformed or non-http(s) entry can't end up baked into an exported file's CSP. */
    private fun originOf(rawUrl: String): String? {
        val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}

enum class AppStarterMode { CHECKLIST, NOTES, ONLINE }

object AppStarterTemplate {
    fun render(title: String, mode: AppStarterMode, dataUrl: String = "", dataOrigin: String = ""): String {
        val safeTitle = title.take(80).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        val modeKey = when (mode) { AppStarterMode.NOTES -> "notes"; AppStarterMode.ONLINE -> "online"; AppStarterMode.CHECKLIST -> "checklist" }
        val body = when (mode) {
            AppStarterMode.NOTES -> """
                <label for="notes">Your notes</label><textarea id="notes" rows="16"></textarea>
            """
            AppStarterMode.ONLINE -> """
                <button id="refresh">Fetch data</button>
                <pre id="result">Not fetched yet.</pre>
            """
            AppStarterMode.CHECKLIST -> """
                <form id="form"><label for="entry">New task</label><input id="entry" maxlength="200" required>
                <button>Add task</button></form><ul id="list"></ul>
            """
        }
        val script = when (mode) {
            AppStarterMode.NOTES -> """
                const field=document.getElementById('notes');
                field.value=typeof saved==='string'?saved:'';
                field.addEventListener('input',()=>save(field.value));
            """
            AppStarterMode.ONLINE -> """
                const safeUrl=${jsStringLiteral(dataUrl)};
                const result=document.getElementById('result');
                document.getElementById('refresh').onclick=async()=>{
                  result.textContent='Fetching...';
                  try{
                    const res=await fetch(safeUrl);
                    const text=await res.text();
                    result.textContent=res.ok?text.slice(0,20000):'Request failed: HTTP '+res.status;
                  }catch(e){result.textContent='Could not reach the data source. Check your connection.';}
                };
            """
            AppStarterMode.CHECKLIST -> """
                let items=Array.isArray(saved)?saved.filter(x=>x&&typeof x.text==='string').slice(0,1000):[];
                function draw(){
                  const list=document.getElementById('list'); list.replaceChildren();
                  items.forEach((item,index)=>{
                    const li=document.createElement('li'), label=document.createElement('label');
                    const check=document.createElement('input');check.type='checkbox';check.checked=!!item.done;
                    check.onchange=()=>{item.done=check.checked;save(items);};
                    label.append(check,document.createTextNode(item.text));
                    const remove=document.createElement('button');remove.textContent='Remove';
                    remove.onclick=()=>{items.splice(index,1);save(items);draw();};
                    li.append(label,remove);list.append(li);
                  });
                }
                document.getElementById('form').onsubmit=e=>{
                  e.preventDefault();const entry=document.getElementById('entry');
                  if(!entry.value.trim()||items.length>=1000)return;
                  items.push({text:entry.value.trim(),done:false});entry.value='';save(items);draw();
                };draw();
            """
        }
        // Online apps get exactly one external origin in connect-src (the address typed at
        // export time, baked in - not user-editable inside the running page); offline apps keep
        // connect-src 'none' as before, since they have nothing to fetch from.
        val connectSrc = if (mode == AppStarterMode.ONLINE && dataOrigin.isNotBlank()) dataOrigin else "'none'"
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src $connectSrc; form-action 'none'; base-uri 'none'">
            <title>$safeTitle</title><style>
            body{font:18px system-ui;max-width:720px;margin:32px auto;padding:16px;background:#0c1218;color:#e4fff5}
            input,textarea,button{font:inherit;padding:12px;margin:8px 0;border-radius:8px}
            textarea{box-sizing:border-box;width:100%}button{cursor:pointer}li{margin:12px 0}li button{margin-left:16px}
            pre{white-space:pre-wrap;word-break:break-word;background:#0f1720;padding:12px;border-radius:8px}
            #status{font-size:14px;color:#b4ccbf}label{display:block}
            </style></head><body><h1>$safeTitle</h1>$body
            <p id="status" role="status">${if (mode == AppStarterMode.ONLINE) "This app can only reach one address, fixed when it was exported." else "Data stays in this browser. Browser storage may be cleared; keep a separate copy of anything important."}</p>
            <script>
            const key='xenos-starter:'+location.pathname+':'+document.title+':$modeKey';
            let saved=null;
            try{saved=JSON.parse(localStorage.getItem(key));}catch(e){document.getElementById('status').textContent='Storage unavailable. Changes last only for this session.';}
            function save(value){try{localStorage.setItem(key,JSON.stringify(value));}catch(e){document.getElementById('status').textContent='Could not save. Copy your work before closing this page.';}}
            $script
            </script></body></html>
        """.trimIndent()
    }

    /** JSON-encodes [value] into a JS string literal safe to splice directly into a <script>
     * block - guards against the data URL itself containing `</script>` or other characters that
     * would break out of the literal or the tag. */
    private fun jsStringLiteral(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("</", "<\\/").replace("\n", "\\n").replace("\r", "")
        return "\"$escaped\""
    }
}
