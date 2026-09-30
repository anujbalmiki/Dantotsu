package ani.dantotsu.parsers.mangayomi

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a Mangayomi extension may reach outside the JS runtime. */
interface MangayomiHost {
    val http: OkHttpClient
    fun prefGet(key: String): String?
    fun prefSet(key: String, value: String)
    fun log(message: String)
    fun unpack(packed: String): String
}

/**
 * Runs Mangayomi JavaScript extensions. The prelude recreates the globals Mangayomi's own runtime
 * (lib/eval/javascript/ in kodjodevf/mangayomi) gives an extension: MProvider, Client, Document,
 * Element, SharedPreferences and the helper functions, backed here by OkHttp and jsoup.
 *
 * Each call gets a fresh runtime, so nothing an extension leaves behind outlives the call.
 * quickjs-kt is used instead of app.cash.quickjs because extensions use native async/await,
 * and app.cash.quickjs never runs QuickJS's pending jobs, so an `await` would never resume.
 */
class MangayomiJsEngine(
    private val sourceJson: String,
    private val sourceCode: String,
    private val host: MangayomiHost,
) {
    /**
     * The requests the last [call] made, one line each with the status or the error. Extensions
     * usually swallow network errors and just return nothing, so this is the only way to see why.
     * ponytail: shared by concurrent calls; per-call traces if that ever confuses a report.
     */
    @Volatile
    var lastTrace: List<String> = emptyList()
        private set

    /** Calls `extention.<method>(...args)` and returns its result as JSON. */
    suspend fun call(method: String, args: JsonArray = JsonArray(emptyList())): String =
        withContext(Dispatchers.IO) {
            val elements = HashMap<Int, Element?>()
            var nextKey = 0
            fun keep(e: Element?): Int = (++nextKey).also { elements[it] = e }
            fun keys(list: List<Element>): String = list.joinToString(",", "[", "]") { keep(it).toString() }
            fun el(args: Array<Any?>) = elements[(args[0] as Number).toInt()]

            var result: String? = null
            var error: String? = null
            val trace = java.util.Collections.synchronizedList(ArrayList<String>())

            quickJs(Dispatchers.IO) {
                function("__result") { result = it[0] as String? }
                function("__error") { error = it[0] as String? }
                function("__log") { host.log(it[0].toString()) }

                function("__dom_parse") { keep(Jsoup.parse(it[0] as String? ?: "")) }
                function("__dom_select") { a -> el(a)?.let { keys(runCatching { it.select(a[1] as String) }.getOrDefault(emptyList())) } ?: "[]" }
                function("__dom_selectFirst") { a -> keep(el(a)?.let { runCatching { it.selectFirst(a[1] as String) }.getOrNull() }) }
                function("__dom_attr") { a -> el(a)?.attr(a[1] as String) ?: "" }
                function("__dom_hasAttr") { a -> el(a)?.hasAttr(a[1] as String) == true }
                function("__dom_str") { a -> el(a)?.let { domString(it, a[1] as String) } ?: "" }
                function("__dom_rel") { a ->
                    val e = el(a)
                    keep(
                        when (a[1] as String) {
                            "nextElementSibling" -> e?.nextElementSibling()
                            "previousElementSibling" -> e?.previousElementSibling()
                            "documentElement" -> (e as? org.jsoup.nodes.Document)?.firstElementChild()
                            "body" -> (e as? org.jsoup.nodes.Document)?.body()
                            "head" -> (e as? org.jsoup.nodes.Document)?.head()
                            else -> e?.parent()
                        }
                    )
                }
                function("__dom_list") { a ->
                    val e = el(a) ?: return@function "[]"
                    val name = a[2] as String? ?: ""
                    keys(
                        when (a[1] as String) {
                            "children" -> e.children()
                            "getElementsByTagName" -> e.getElementsByTag(name)
                            else -> e.getElementsByClass(name)
                        }
                    )
                }
                function("__dom_byId") { a -> keep(el(a)?.getElementById(a[1] as String)) }
                function("__dom_xpath") { a -> Json.encodeToString(JsonArray.serializer(), JsonArray(xpath(el(a), a[1] as String).map(::JsonPrimitive))) }

                function("__pref_get") { host.prefGet(it[0] as String) }
                function("__pref_set") { host.prefSet(it[0] as String, it[1] as String); null }

                function("__b64d") { String(Base64.getDecoder().decode((it[0] as String).trim()), Charsets.ISO_8859_1) }
                function("__b64e") { Base64.getEncoder().encodeToString((it[0] as String).toByteArray(Charsets.ISO_8859_1)) }
                function("__unpack") { host.unpack(it[0] as String) }
                function("__crypto") { a -> runCatching { crypto(a) }.getOrElse { host.log("crypto ${a[0]}: ${it.message}"); "" } }
                function("__parseDates") { a -> parseDates(a[0] as String, a[1] as String?, a[2] as String?) }

                asyncFunction("__http") { a ->
                    http(a[0] as String, a[1] as String, a[2] as String?, a[3] as String?, trace)
                }
                asyncFunction("__sleep") { delay((it[0] as Number?)?.toLong() ?: 0L); null }

                evaluate<Any?>(PRELUDE.replace("__SOURCE_JSON__", sourceJson))
                evaluate<Any?>("$sourceCode\nvar extention = new DefaultExtension();", filename = "extension.js")
                evaluate<Any?>(
                    """
                    (async () => {
                        try {
                            const args = ${Json.encodeToString(JsonArray.serializer(), args)};
                            if (${JsonPrimitive(method)} === "search" && args[2] == null) args[2] = __defaultFilters();
                            const member = extention[${JsonPrimitive(method)}];
                            const value = typeof member === "function" ? await member.apply(extention, args) : member;
                            __result(JSON.stringify(value === undefined ? null : value));
                        } catch (e) {
                            __error(String((e && e.stack) || e));
                        }
                    })();
                    """.trimIndent(),
                    filename = "call.js"
                )
            }
            lastTrace = trace.toList()
            error?.let { throw MangayomiException("$method: $it") }
            result ?: throw MangayomiException("$method returned nothing")
        }

    private suspend fun http(
        method: String,
        url: String,
        headersJson: String?,
        bodyJson: String?,
        trace: MutableList<String>,
    ): String {
        val headers = headersJson?.let { parseObject(it) }.orEmpty()
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val body: RequestBody? = if (method == "GET" || method == "HEAD") null else {
            val raw = bodyJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            when {
                raw == null || raw is JsonNull -> "".toRequestBody(contentType?.toMediaTypeOrNull())
                raw is JsonObject && contentType?.contains("json") != true ->
                    FormBody.Builder().apply {
                        raw.forEach { (k, v) -> add(k, (v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
                    }.build()
                raw is JsonPrimitive && raw.isString -> raw.content.toRequestBody(
                    (contentType ?: "text/plain; charset=utf-8").toMediaTypeOrNull()
                )
                else -> raw.toString().toRequestBody((contentType ?: "application/json").toMediaTypeOrNull())
            }
        }
        val request = Request.Builder().url(url).headers(headers.toHeaders()).method(method, body).build()
        val response = try {
            host.http.newCall(request).execute()
        } catch (e: Exception) {
            trace += "$method $url -> ${e.javaClass.simpleName}: ${e.message}"
            host.log("$method $url failed: $e")
            throw e
        }
        return response.use { res ->
            val text = res.body.string()
            trace += "$method $url -> ${res.code} (${text.length} chars)"
            buildJsonObject {
                put("body", text)
                put("statusCode", res.code)
                put("reasonPhrase", res.message)
                put("isRedirect", res.isRedirect)
                put("contentLength", text.length)
                put("headers", buildJsonObject { res.headers.forEach { (k, v) -> put(k.lowercase(), v) } })
                put("request", buildJsonObject {
                    put("url", res.request.url.toString())
                    put("method", method)
                    put("headers", buildJsonObject { res.request.headers.forEach { (k, v) -> put(k, v) } })
                })
            }.toString()
        }
    }

    private fun domString(e: Element, type: String): String = when (type) {
        "text" -> e.text()
        "innerHtml" -> e.html()
        "outerHtml" -> e.outerHtml()
        "className" -> e.className()
        "localName" -> e.tagName()
        "namespaceUri" -> "http://www.w3.org/1999/xhtml"
        "getSrc" -> e.attr("src")
        "getHref" -> e.attr("href")
        "getImg" -> listOf("data-src", "data-lazy-src", "srcset", "src").map { e.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        else -> e.attr("data-src")
    }

    /** Mangayomi's xpath returns strings: attribute values for `/@x`, text otherwise. */
    private fun xpath(e: Element?, expr: String): List<String> {
        e ?: return emptyList()
        val attr = Regex("/@([\\w:-]+)$").find(expr)
        val base = when {
            attr != null -> expr.removeSuffix(attr.value)
            expr.endsWith("/text()") -> expr.removeSuffix("/text()")
            else -> expr
        }
        val found = runCatching { e.selectXpath(base) }.getOrNull() ?: return emptyList()
        return found.map { if (attr != null) it.attr(attr.groupValues[1]) else it.text() }.map { it.trim() }
    }

    private fun crypto(a: Array<Any?>): String {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return when (a[0] as String) {
            // MBridge.cryptoHandler: AES-CBC, utf8 key and iv, base64 ciphertext.
            "cbc" -> {
                val text = a[1] as String; val iv = a[2] as String; val key = a[3] as String
                val encrypt = a[4] as Boolean
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
                if (encrypt) Base64.getEncoder().encodeToString(c.doFinal(text.toByteArray()))
                else String(c.doFinal(Base64.getDecoder().decode(text)))
            }
            // CryptoJS passphrase format: base64("Salted__" + salt + ciphertext), EVP_BytesToKey(MD5).
            "cryptojs-dec", "cryptojs-enc" -> {
                val encrypt = a[0] == "cryptojs-enc"
                val pass = (a[2] as String).toByteArray()
                val raw = if (encrypt) null else Base64.getDecoder().decode((a[1] as String).trim())
                val salt = raw?.copyOfRange(8, 16) ?: ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
                val md5 = MessageDigest.getInstance("MD5")
                var derived = ByteArray(0); var block = ByteArray(0)
                while (derived.size < 48) {
                    md5.reset(); md5.update(block); md5.update(pass); md5.update(salt)
                    block = md5.digest(); derived += block
                }
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(derived.copyOfRange(32, 48)))
                if (encrypt) Base64.getEncoder().encodeToString("Salted__".toByteArray() + salt + c.doFinal((a[1] as String).toByteArray()))
                else String(c.doFinal(raw!!.copyOfRange(16, raw.size)))
            }
            // MBridge.decryptAESGCM: base64 ciphertext, hex key/iv, optional hex tag.
            "gcm" -> {
                val data = Base64.getDecoder().decode((a[1] as String).trim()) + hex(a[4] as String? ?: "")
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(a[2] as String), "AES"), GCMParameterSpec(128, hex(a[3] as String)))
                String(c.doFinal(data))
            }
            else -> ""
        }
    }

    private fun parseDates(valuesJson: String, format: String?, locale: String?): String {
        val values = runCatching { Json.parseToJsonElement(valuesJson) as JsonArray }.getOrNull().orEmpty()
        val fmt = runCatching { SimpleDateFormat(format ?: "", Locale.forLanguageTag(locale ?: "en")) }.getOrNull()
        return buildJsonArray {
            values.forEach { v ->
                val s = (v as? JsonPrimitive)?.contentOrNull.orEmpty()
                add(JsonPrimitive((runCatching { fmt?.parse(s)?.time }.getOrNull() ?: 0L).toString()))
            }
        }.toString()
    }

    private fun parseObject(s: String): JsonObject? =
        runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull()

    companion object {
        private val PRELUDE = """
            var console = {
                log: function () { __log(Array.prototype.map.call(arguments, function (a) { return typeof a === "object" ? JSON.stringify(a) : String(a); }).join(" ")); }
            };
            console.warn = console.log; console.error = console.log; console.info = console.log; console.debug = console.log;
            function setTimeout(fn, ms) { __sleep(ms || 0).then(function () { fn(); }); return 0; }
            function clearTimeout() {}
            function atob(s) { return __b64d(String(s)); }
            function btoa(s) { return __b64e(String(s)); }

            String.prototype.substringAfter = function (p) { var i = this.indexOf(p); return i === -1 ? this.substring(0) : this.substring(i + p.length); };
            String.prototype.substringAfterLast = function (p) { return this.split(p).pop(); };
            String.prototype.substringBefore = function (p) { var i = this.indexOf(p); return i === -1 ? this.substring(0) : this.substring(0, i); };
            String.prototype.substringBeforeLast = function (p) { var i = this.lastIndexOf(p); return i === -1 ? this.substring(0) : this.substring(0, i); };
            String.prototype.substringBetween = function (l, r) {
                var i = this.indexOf(l); if (i === -1) return "";
                var li = i + l.length; var ri = this.indexOf(r, li); if (ri === -1) return "";
                return this.substring(li, ri);
            };

            class MProvider {
                get source() { return __SOURCE_JSON__; }
                get supportsLatest() { throw new Error("supportsLatest not implemented"); }
                getHeaders(url) { throw new Error("getHeaders not implemented"); }
                async getPopular(page) { throw new Error("getPopular not implemented"); }
                async getLatestUpdates(page) { throw new Error("getLatestUpdates not implemented"); }
                async search(query, page, filters) { throw new Error("search not implemented"); }
                async getDetail(url) { throw new Error("getDetail not implemented"); }
                async getPageList() { throw new Error("getPageList not implemented"); }
                async getVideoList(url) { throw new Error("getVideoList not implemented"); }
                async getHtmlContent(name, url) { throw new Error("getHtmlContent not implemented"); }
                async cleanHtmlContent(html) { throw new Error("cleanHtmlContent not implemented"); }
                getFilterList() { throw new Error("getFilterList not implemented"); }
                getSourcePreferences() { throw new Error("getSourcePreferences not implemented"); }
            }

            class Client {
                constructor(reqcopyWith) { this.reqcopyWith = reqcopyWith; }
                async send(method, url, headers, body) {
                    return JSON.parse(await __http(method, String(url), JSON.stringify(headers || {}), body === undefined ? null : JSON.stringify(body)));
                }
                head(url, headers) { return this.send("HEAD", url, headers); }
                get(url, headers) { return this.send("GET", url, headers); }
                post(url, headers, body) { return this.send("POST", url, headers, body); }
                put(url, headers, body) { return this.send("PUT", url, headers, body); }
                delete(url, headers, body) { return this.send("DELETE", url, headers, body); }
                patch(url, headers, body) { return this.send("PATCH", url, headers, body); }
            }

            class Element {
                constructor(key) { this.key = key; }
                getString(type) { return __dom_str(this.key, type); }
                get text() { return this.getString("text"); }
                get outerHtml() { return this.getString("outerHtml"); }
                get innerHtml() { return this.getString("innerHtml"); }
                get className() { return this.getString("className"); }
                get localName() { return this.getString("localName"); }
                get namespaceUri() { return this.getString("namespaceUri"); }
                get getSrc() { return this.getString("getSrc"); }
                get getImg() { return this.getString("getImg"); }
                get getHref() { return this.getString("getHref"); }
                get getDataSrc() { return this.getString("getDataSrc"); }
                getElementSibling(type) { return new Element(__dom_rel(this.key, type)); }
                get previousElementSibling() { return this.getElementSibling("previousElementSibling"); }
                get nextElementSibling() { return this.getElementSibling("nextElementSibling"); }
                get parent() { return this.getElementSibling("parent"); }
                getElementsListBy(type, name) { return JSON.parse(__dom_list(this.key, type, name || "")).map(function (k) { return new Element(k); }); }
                get children() { return this.getElementsListBy("children"); }
                getElementsByTagName(name) { return this.getElementsListBy("getElementsByTagName", name); }
                getElementsByClassName(name) { return this.getElementsListBy("getElementsByClassName", name); }
                getElementById(id) { return new Element(__dom_byId(this.key, id)); }
                xpath(xpath) { return JSON.parse(__dom_xpath(this.key, xpath)); }
                xpathFirst(xpath) { var r = this.xpath(xpath); return r.length ? r[0] : ""; }
                attr(attr) { return __dom_attr(this.key, attr); }
                hasAttr(attr) { return __dom_hasAttr(this.key, attr); }
                selectFirst(selector) { return new Element(__dom_selectFirst(this.key, selector)); }
                select(selector) { return JSON.parse(__dom_select(this.key, selector)).map(function (k) { return new Element(k); }); }
            }

            class Document extends Element {
                constructor(html) { super(__dom_parse(html == null ? "" : String(html))); this.html = html; }
                get body() { return this.getElementSibling("body"); }
                get documentElement() { return this.getElementSibling("documentElement"); }
                get head() { return this.getElementSibling("head"); }
                get parent() { return this.documentElement; }
                getString(type) { return type === "text" || type === "outerHtml" ? __dom_str(this.key, type) : this.documentElement.getString(type); }
                attr(attr) { return this.documentElement.attr(attr); }
                hasAttr(attr) { return this.documentElement.hasAttr(attr); }
            }

            function __prefDefault(key) {
                try {
                    var prefs = extention.getSourcePreferences();
                    for (var i = 0; i < prefs.length; i++) {
                        var p = prefs[i];
                        if (p.key !== key) continue;
                        if (p.listPreference) return p.listPreference.entryValues[p.listPreference.valueIndex || 0];
                        if (p.checkBoxPreference) return p.checkBoxPreference.value;
                        if (p.switchPreferenceCompat) return p.switchPreferenceCompat.value;
                        if (p.editTextPreference) return p.editTextPreference.value;
                        if (p.multiSelectListPreference) return p.multiSelectListPreference.values;
                    }
                } catch (e) {}
                return null;
            }
            function __defaultFilters() { try { return extention.getFilterList() || []; } catch (e) { return []; } }

            class SharedPreferences {
                get(key) { var v = __pref_get(key); return v == null ? __prefDefault(key) : JSON.parse(v); }
                getString(key, defaultValue) { var v = __pref_get(key); return v == null ? defaultValue : JSON.parse(v); }
                setString(key, value) { __pref_set(key, JSON.stringify(value)); }
            }

            function cryptoHandler(text, iv, secretKeyString, encrypt) { return __crypto("cbc", text, iv, secretKeyString, !!encrypt); }
            function encryptAESCryptoJS(plainText, passphrase) { return __crypto("cryptojs-enc", plainText, passphrase); }
            function decryptAESCryptoJS(encrypted, passphrase) { return __crypto("cryptojs-dec", encrypted, passphrase); }
            function decryptAESGCM(encrypted, keyHex, ivHex, tagHex) { return __crypto("gcm", encrypted, keyHex, ivHex, tagHex || ""); }
            function unpackJs(packedJS) { return __unpack(packedJS); }
            function unpackJsAndCombine(scriptBlock) { return __unpack(scriptBlock); }
            function parseDates(value, dateFormat, dateFormatLocale) { return JSON.parse(__parseDates(JSON.stringify(value), dateFormat, dateFormatLocale)); }
            function deobfuscateJsPassword(inputString) { return inputString; }
            async function evaluateJavascriptViaWebview(url, headers, scripts) { return ""; }

            // Mangayomi's built-in hoster extractors are Dart code with no counterpart here yet.
            ["sibnetExtractor", "myTvExtractor", "okruExtractor", "voeExtractor", "vidBomExtractor",
             "streamlareExtractor", "sendVidExtractor", "yourUploadExtractor", "gogoCdnExtractor",
             "doodExtractor", "streamTapeExtractor", "mp4UploadExtractor", "streamWishExtractor",
             "filemoonExtractor", "quarkVideosExtractor", "ucVideosExtractor"].forEach(function (name) {
                globalThis[name] = async function () { console.log(name + " is not supported in Dantotsu"); return []; };
            });
        """.trimIndent()
    }
}

class MangayomiException(message: String) : Exception(message)
