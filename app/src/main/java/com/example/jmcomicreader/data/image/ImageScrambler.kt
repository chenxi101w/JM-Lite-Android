package com.example.jmreader.data.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.example.jmreader.data.api.JmApiCrypto

/**
 * JM 图片「加扰 / 还原」的纯 Kotlin 实现（v1.2.8）。
 *
 * 背景：JM 的图片不是加密，而是把整图**横向切成 num 块后打乱顺序**再拼回去，
 * 客户端拿到后需要按同样规则逆向重排才能正常显示。此前这一步依赖 Python（jmcomic 库），
 * 本文件把它移到原生侧 —— 这是「去 Chaquopy」这条路上的关键一块。
 *
 * 算法对应 jmcomic 的两处实现（`jm_toolkit.py` 的 `JmImageTool`）：
 *  - [getNum]  ← `JmImageTool.get_num(scramble_id, aid, filename)`
 *  - [decode]  ← `JmImageTool.decode_and_save(num, img_src, path)`
 *
 * 与原实现逐行对齐，便于日后交叉验证。
 */
internal object ImageScrambler {

    /**
     * 解扰算法版本（写进 `{albumId}/.jm_ver`，供下次判断是否需要自愈重下）。
     *
     * **改动 [decode] 或 [getNum] 的实现时必须 +1** —— 下载侧会用
     * `{saveDir}/{albumId}/{VER_MARKER}` 里的版本号判断「这本的图是不是旧算法写的」，
     * 低于此值就整本重下（自愈）。
     *
     * **54（v1.9.0）**：`num` 的 `aid` 参数从 albumId 修正为 **photo_id**
     * （见 [getNum] 与 `NativeAlbumDownloader.writeImage`）。
     * v53 及更早版本下载的**多章漫画每一张图 num 都算错** → 解扰错位 → 视觉崩坏，
     * 且坏图写成 `.jpg`（内容合法），`isStaleBrokenFile` 识别不出来，
     * **只能靠版本号升级触发整本重下**。
     */
    const val ALGO_VERSION = 54

    /** 记录解扰算法版本的标记文件名（写在 `{saveDir}/{albumId}/` 下）。 */
    const val VER_MARKER = ".jm_ver"

    /** 图片不参与加扰的阈值（jmcomic 的 JmMagicConstants）。 */
    private const val SCRAMBLE_220980 = 220980
    internal const val SCRAMBLE_268850 = 268850

    /** 2023-02-08 之后切割算法变了，分档不同。 */
    private const val SCRAMBLE_421926 = 421926

    /**
     * `aid` 达到此值之后，[getNum] 的结果**与 scrambleId 完全无关**（v1.8.6）。
     *
     * 依据 [getNum] 的分支：`aid >= 268850` 时走
     * `num = ord(md5(f"{aid}{name}")[-1]) % x * 2 + 2`，**公式里根本没有 scrambleId**。
     * 也就是说对现代本子（aid 都在百万级），`/chapter_view_template` 这次请求
     * 是**纯粹浪费** —— 而它恰恰是章节清单慢的主因（每章多一次 HTTP 往返）。
     *
     * ⚠️ 这个结论只依赖 [getNum] 的实现，一旦 [getNum] 改动必须重新核对。
     */
    fun scrambleIrrelevant(aid: Int): Boolean = aid >= SCRAMBLE_268850

    /**
     * 计算「分割块数」num。
     *
     * 移植自 `JmImageTool.get_num`：
     * ```python
     * if aid < scramble_id: return 0
     * elif aid < SCRAMBLE_268850: return 10
     * else:
     *     x = 10 if aid < SCRAMBLE_421926 else 8
     *     num = ord(md5(f"{aid}{filename}").hexdigest()[-1]) % x * 2 + 2
     * ```
     *
     * @param scrambleId 从 `/chapter_view_template` 拿到的加扰参数
     * @param aid 本子（专辑）的 JM 号
     * @param filename 图片文件名。**带不带后缀都可以**（内部会先去掉后缀，见下）
     * @return 需要切分的块数；0 表示未加扰、无需还原
     */
    fun getNum(scrambleId: Int, aid: Int, filename: String): Int {
        if (aid < scrambleId) return 0
        if (aid < SCRAMBLE_268850) return 10
        val x = if (aid < SCRAMBLE_421926) 10 else 8
        // ── v1.8.2：★★ 参与 md5 的必须是**不含扩展名**的文件名 ★★ ──
        // 官方 jmcomic 的 `get_num_by_url` 走的是
        //     of_file_name(url, trim_suffix=True)
        // 而 `trim_suffix=True` 会先 `change_file_suffix(filepath, '')` —— **把后缀去掉**。
        // 所以真值是 md5(f"{aid}{不含后缀名}")，例如 md5("1477121" + "00001")。
        //
        // 此前传的是接口给的 `00001.webp`（带后缀）→ **num 全算错** →
        // 解扰方向对但块数不对 → 图片出现「横向条带错位」的花屏。
        // 实测校准（用条带间距反推真实 num）：
        //   1477121/00001：真值 16；带后缀→14 ✗；不带后缀→16 ✓
        //   1252411/00002：真值 14；带后缀→10 ✗；不带后缀→14 ✓
        val stem = filename.substringBeforeLast('.', filename)
        // 取 md5 十六进制串的**最后一个字符的码点**（与 Python 的 ord(hex[-1]) 一致）
        val lastChar = JmApiCrypto.md5("$aid$stem").last()
        return (lastChar.code % x) * 2 + 2
    }

    /**
     * 还原被加扰的图片。
     *
     * 移植自 `JmImageTool.decode_and_save` —— 把源图**自下而上**逐块搬到目标**自上而下**，
     * 即块顺序整体翻转。
     *
     * ── v1.7.3 修复「有的页花屏、有的页正常」（关键 bug）────────────────
     * **含余数的那一块必须取自源图「顶部」、放到目标「底部」**（与原实现一致）。
     * 之前的写法把它取自源图底部、放到目标顶部 —— 两端搞反了。
     *
     * 为什么症状是"时好时坏"：只有 `h % num != 0` 时才存在余数块，
     * 此时两种写法结果不同；而 `h % num == 0`（如 2048 对 2/4/8/16）两者等价，
     * 所以那些页看起来完全正常。真机实测（2048 高、num=10 → 余数 8）：
     * 修正前边界差异比 10.09（明显断裂），修正后 1.05（内容连续）。
     *
     * 以 h=1000、num=4（move=250、over=0）为例：
     * ```
     * 源块（自下而上）   目标位置（自上而下）
     *   第 4 块    →      第 1 段
     *   第 3 块    →      第 2 段
     *   第 2 块    →      第 3 段
     *   第 1 块    →      第 4 段
     * ```
     * 有余数时（例如 h=1009、num=4 → move=252、over=1）：
     * 源图的**顶部**（move+over 高）搬到目标**底部**，其余自下而上填。
     *
     * @param src 原始（被打乱的）位图
     * @param num 分割块数，由 [getNum] 得出；<= 0 时原样返回
     * @return 还原后的位图
     */
    fun decode(src: Bitmap, num: Int): Bitmap {
        if (num <= 0) return src

        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || num > h) return src

        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val over = h % num

        // ⚠️ 本函数必须与 jmcomic 的 `JmImageTool.decode_and_save` **逐行一致**。
        // 官方实现（jmcomic 2.7.7 源码）：
        //   over = h % num
        //   for i in range(num):
        //       move = floor(h / num)
        //       y_src = h - (move * (i + 1)) - over
        //       y_dst = move * i
        //       if i == 0: move += over
        //       else:      y_dst += over
        //       paste(crop(0, y_src, w, y_src + move), (0, y_dst))
        //
        // ── v1.7.8：**回退 v1.7.3 的错误改动** ─────────────────────
        // v1.7.3 曾把「含余数的块」改成「源顶 → 目标底」，依据是自创的
        // 「块边界行差比」指标。事后用官方库（pip install jmcomic）实测才确认：
        // **原实现才是对的**，那次改动把正确的解扰改坏了。
        // 教训：移植算法**必须拿到参考实现的源码逐行对照**，
        // 自创的启发式指标（尤其是基于图像统计量的）很容易把人带偏。
        for (i in 0 until num) {
            var move = h / num
            val ySrc = h - move * (i + 1) - over
            var yDst = move * i
            if (i == 0) {
                move += over
            } else {
                yDst += over
            }
            if (ySrc < 0 || ySrc + move > h || yDst + move > h) continue
            canvas.drawBitmap(
                src,
                Rect(0, ySrc, w, ySrc + move),
                Rect(0, yDst, w, yDst + move),
                null
            )
        }
        return out
    }
}
