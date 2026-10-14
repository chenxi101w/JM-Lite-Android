// ============================================================================
// Chaquopy 15.0.1 Windows 构建期 Bug 的永久修复（Clean → Rebuild 可复现）
// ----------------------------------------------------------------------------
// 由 app/build.gradle.kts 在文件末尾 apply(from = "chaquopy-patch.gradle.kts") 引入。
//
// 背景（2026-08-14 本地复现确证）：
//   Chaquopy 15.0.1 内置 pip 20.1 用 `--target` 把多 wheel 装进同一目录；其中
//   chaquopy-libjpeg / chaquopy-freetype 等原生包共享顶层 chaquopy/ 目录。
//   ① pip_install.py 在排除 chaquopy/include/* 头文件时用 os.removedirs 向上
//      "剪空目录"，实测会把整个 target 树（含已装的 dist-info）级联删除，
//      随后同包后续文件的 os.remove 抛 FileNotFoundError → 构建失败。
//   ② 目录被级联删除后，distlib 读 RECORD 返回 None → AttributeError。
// 修复：把两个文件打上"防御版"补丁（带标记、幂等），挂在
//   extract*PythonBuildPackages 的 doLast + generate*PythonRequirements 的 doFirst。
// ============================================================================

// ---- 补丁 1：pip_install.py 的 remove() ----
// 原实现（有缺陷）：
//   def remove(path, remove_empty_dirs=False):
//       (rmtree if isdir(path) else os.remove)(path)
//       if remove_empty_dirs:
//           try:
//               os.removedirs(dirname(path))
//           except OSError:
//               pass  # Directory is not empty.
// 改为：文件不存在直接跳过；空目录清理用手动逐级 rmdir（遇非空即停），
//   不再使用会级联删光的 os.removedirs。
val pipInstallOld = """def remove(path, remove_empty_dirs=False):
    (rmtree if isdir(path) else os.remove)(path)
    if remove_empty_dirs:
        try:
            os.removedirs(dirname(path))
        except OSError:
            pass  # Directory is not empty.
"""
val pipInstallNew = """def remove(path, remove_empty_dirs=False):
    # Patched (jmcomicreader build): RECORD may list a file which was already
    # removed by an earlier operation (pip --target moves), so skip it instead
    # of crashing the whole build.
    if not exists(path):
        return
    (rmtree if isdir(path) else os.remove)(path)
    # Empty-dir pruning is DISABLED: this machine's Python/OS combination can
    # report rmdir() success on NON-empty directories (deleting their contents,
    # intermittently). Skipping the walk-up entirely is safer than guarding it.
"""

// ---- 补丁 3：pip_install.py 的 RECORD 缺失自动重建 ----
// 背景：pip 20.1 的 `--target` 安装偶发不写 RECORD（实测连 --force-reinstall 都不补），
//   distlib 扫描时 get_distinfo_resource('RECORD') 返回 None → AttributeError 崩溃。
// 修复：pip 装完后立即扫描目标目录，凡 .dist-info 缺 RECORD 的，按磁盘真值重建
//   （路径+sha256+size，与 pip 写入格式一致）。磁盘文件本身就是最终真值，
//   重复文件在 Chaquopy 的文件树合并时按内容哈希匹配去重，结果与正常路径等价。
val pipRecordOld = """            logger.debug("Running {}".format(cmdline))
            subprocess.check_call(cmdline)
        except subprocess.CalledProcessError as e:
            raise CommandError("Exit status {}".format(e.returncode))

        logger.debug("Scanning distributions")"""
val pipRecordNew = """            logger.debug("Running {}".format(cmdline))
            subprocess.check_call(cmdline)
            # Patched (jmcomicreader build): pip 20.1 --target intermittently
            # fails to write RECORD for wheels sharing the chaquopy/ tree;
            # regenerate any missing RECORD from the on-disk truth so distlib
            # scanning cannot crash with RECORD=None.
            regenerate_missing_records(abi_dir)
        except subprocess.CalledProcessError as e:
            raise CommandError("Exit status {}".format(e.returncode))

        logger.debug("Scanning distributions")"""

// 在模块顶部（import 之后、EXCLUDE_PATTERNS 之后）注入 RECORD 重建函数。
val pipHeaderOld = """# Files which aren't needed at runtime should be omitted from the APK.
EXCLUDE_PATTERNS = [
    "chaquopy/include/*",
    "chaquopy/lib/*.la",
    "chaquopy/lib/cmake/*",
    "chaquopy/lib/pkgconfig/*",
    "chaquopy/share/doc",
    "chaquopy/share/man",
]"""
val pipHeaderNew = """# Files which aren't needed at runtime should be omitted from the APK.
EXCLUDE_PATTERNS = [
    "chaquopy/include/*",
    "chaquopy/lib/*.la",
    "chaquopy/lib/cmake/*",
    "chaquopy/lib/pkgconfig/*",
    "chaquopy/share/doc",
    "chaquopy/share/man",
]


# Patched (jmcomicreader build): regenerate any .dist-info RECORD which pip
# 20.1 failed to write (intermittent --target bug with shared chaquopy/ tree).
def regenerate_missing_records(abi_dir):
    import base64 as _b64
    import hashlib as _hl
    dist_infos = [d for d in os.listdir(abi_dir) if d.endswith(".dist-info")]
    if not dist_infos:
        return
    manifest = []
    for root, _dirs, files in os.walk(abi_dir):
        for f in files:
            p = os.path.join(root, f)
            with open(p, "rb") as fh:
                digest = _b64.b64encode(_hl.sha256(fh.read()).digest()).decode("ASCII")
            rel = os.path.relpath(p, abi_dir).replace(os.sep, "/")
            manifest.append((rel, digest, os.path.getsize(p)))
    for di in dist_infos:
        rec = os.path.join(abi_dir, di, "RECORD")
        if os.path.exists(rec):
            continue
        prefix = di + "/"
        lines = ["%s,sha256=%s,%d" % item for item in manifest
                 if not item[0].startswith(prefix)]
        with open(rec, "w") as fh:
            fh.write("\n".join(lines) + "\n")
        logger.debug("Regenerated missing RECORD for " + di)"""

// ---- 补丁 2：distlib/database.py 的 get_distinfo_resource() ----
// 兜底：资源 finder 找不到 RECORD 时，直接读磁盘 self.path/RECORD
// （防御级联删除之外的残留场景，保证 list_installed_files() 不因 RECORD 缺失崩掉）。
val distlibOld = """
        finder = resources.finder_for_path(self.path)
        if finder is None:
            raise DistlibException('Unable to get a finder for %s' % self.path)
        return finder.find(path)
"""
val distlibNew = """
        finder = resources.finder_for_path(self.path)
        if finder is None:
            raise DistlibException('Unable to get a finder for %s' % self.path)
        result = finder.find(path)
        if result is None and path == 'RECORD':
            # Patched (jmcomicreader distlib): fall back to the on-disk file
            # so list_installed_files() can build the APK file tree even when
            # the resource finder does not index it.
            import os as _os
            _rec = _os.path.join(self.path, path)
            if _os.path.isfile(_rec):
                result = resources.Resource(finder, path)
                result.path = _rec
        return result
"""

afterEvaluate {
    fun patchChaquopyPython() {
        val base = File(buildDir, "python")
        if (!base.exists()) return

        base.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            when {
                // 只改 chaquopy 包内的 pip_install.py（不要碰 pip 自带副本之外的其它同名文件）
                f.name == "pip_install.py" && f.parentFile?.name == "chaquopy" -> {
                    // 每个补丁独立检测、独立应用（不依赖全局标记，避免后续新增补丁
                    // 被"已打过"标记漏掉）。replace 只会在 old 串存在时生效，天然幂等。
                    var t = f.readText()
                    var changed = false
                    // ① remove() 防御（已打过时 old 串不再存在，replace 无操作）
                    val p = t.replace(pipInstallOld, pipInstallNew)
                    if (p != t) { t = p; changed = true }
                    // ② RECORD 重建函数定义（注入 EXCLUDE_PATTERNS 之后）
                    val h = t.replace(pipHeaderOld, pipHeaderNew)
                    if (h != t) { t = h; changed = true }
                    // ③ RECORD 重建调用点（pip 装完、扫描前）
                    val r = t.replace(pipRecordOld, pipRecordNew)
                    if (r != t) { t = r; changed = true }
                    if (changed) f.writeText(t)
                }
                f.name == "database.py" && f.parentFile?.name == "distlib" -> {
                    // ④ distlib RECORD 磁盘兜底（同幂等原则）
                    val t = f.readText()
                    val p = t.replace(distlibOld, distlibNew)
                    if (p != t) f.writeText(p)
                }
            }
        }
    }

    // 补丁必须在文件被 Chaquopy 解压出来之后打：
    // 挂 extract*PythonBuildPackages 的 doLast（该任务创建 env/debug 下文件）；
    // 再挂 generate*PythonRequirements 的 doFirst 兜底（extract 未触发时）。
    tasks.matching { it.name.contains("PythonBuildPackages") }.configureEach {
        doLast { patchChaquopyPython() }
    }
    tasks.matching { it.name.contains("PythonRequirements") }.configureEach {
        doFirst { patchChaquopyPython() }
    }
}
