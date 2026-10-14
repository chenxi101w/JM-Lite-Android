package com.example.jmreader.data.api

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 同源 JSON 接口的加解密工具（v1.1.0）。
 *
 * 协议来自对同源客户端（Capacitor + React 版）的协议复刻，全部在客户端完成，无服务端密钥交换：
 *  - 请求签名：`Token = md5(time + APP_TOKEN)`；`Tokenparam = "<time>,<version>"`
 *  - 响应体：`{code:200, data:"<base64>"}` → **AES-256-ECB** 解密，密钥 = `md5(time + APP_TOKEN)`
 *  - 线路配置：AES-256-ECB 解密，密钥 = `md5(HOST_KEY_SEED)`
 *
 * 说明：CryptoJS 的 `enc.Utf8.parse(md5(x))` 得到的是「md5 十六进制字符串的 UTF-8 字节」（32 字节），
 * 对应 AES-256；填充为 PKCS7（等价于 Java 的 PKCS5Padding）。故此处用 32 字节密钥。
 */
internal object JmApiCrypto {

    /** APP 专用令牌（客户端常量）。 */
    const val APP_TOKEN = "185Hcomic3PAPP7R"

    /** 线路配置解密种子。 */
    private const val HOST_KEY_SEED = "diosfjckwpqpdfjkvnqQjsik"

    /** 小写十六进制 MD5。 */
    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * AES-256-ECB 解密：Base64 密文 → 明文。
     * @param keySeed 会先取 md5 再转 UTF-8 字节作为密钥（与 CryptoJS 写法一致）。
     * @return 明文；失败返回 null（调用方据此走回退链路）。
     */
    fun aesDecryptBase64(cipherB64: String, keySeed: String): String? = runCatching {
        val key = md5(keySeed).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val cipherBytes = Base64.decode(cipherB64, Base64.DEFAULT)
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    /** 线路配置解密（密钥固定为 md5(HOST_KEY_SEED)）。 */
    fun decryptHostConfig(cipherB64: String): String? = aesDecryptBase64(cipherB64, HOST_KEY_SEED)

    /** 接口响应体解密（密钥 = md5(time + APP_TOKEN)）。 */
    fun decryptResponse(cipherB64: String, time: Long): String? =
        aesDecryptBase64(cipherB64, time.toString() + APP_TOKEN)

    /** 请求签名 Token（md5(time + APP_TOKEN)）。 */
    fun signToken(time: Long): String = md5(time.toString() + APP_TOKEN)

    /**
     * `/chapter_view_template`（取 scramble_id）**专用**令牌（v1.2.8）。
     *
     * 该端点用的是另一个密钥 —— 对应 jmcomic 的 `JmMagicConstants.APP_TOKEN_SECRET_2`。
     * 见 `jm_async_client.py::_build_api_headers`：
     * ```python
     * if path == self.API_SCRAMBLE:
     *     token, tokenparam = JmCryptoTool.token_and_tokenparam(ts, secret=APP_TOKEN_SECRET_2)
     * ```
     * 签名方式与普通接口一致，仅密钥不同。
     */
    private const val CONTENT_TOKEN = "18comicAPPContent"

    /** `/chapter_view_template` 的签名 Token（md5(time + CONTENT_TOKEN)）。 */
    fun signContentToken(time: Long): String = md5(time.toString() + CONTENT_TOKEN)

    private val HEX = "0123456789abcdef".toCharArray()
}
