package com.masgzy.anything.rust

/**
 * Rust 引擎 JNI 符号契约（alpha20 迁移阶段①预置，阶段④接入宿主）。
 *
 * 对应 Rust 侧 `engine/src/jni_bridge.rs` 的
 * `Java_com_masgzy_anything_core_RustEngine_*` 导出 —— 类路径、方法名、
 * 参数类型任何一侧变更都必须双侧同步，否则运行时 UnsatisfiedLinkError。
 *
 * 与 Go/gomobile 时代（`core.Engine` AAR）的 API 对应关系：
 *   Engine(0, dir)            → RustEngine.newEngine(0, dir)
 *   engine.setListener(l)     → RustEngine.setListener(h, l)
 *   engine.startScan(json)    → RustEngine.startScan(h, json)
 *   engine.search(q, n)       → RustEngine.search(h, q, n)
 *   ……其余同名方法一一对应；错误从 throws Exception 改为抛 RuntimeException。
 *
 * 生命周期：newEngine 返回句柄（0 = 失败且伴随异常），所有方法以句柄
 * 首参寻址；close 后句柄失效，继续使用抛「引擎句柄无效或已关闭」。
 *
 * 本对象在阶段④之前不被任何宿主代码引用（惰性加载：
 * System.loadLibrary 位于 init，首次访问才执行），不影响现网 Go AAR 链路。
 */
object RustEngine {
    init {
        System.loadLibrary("anything")
    }

    /** FFI ABI 版本：与 Rust 侧 ABI_VERSION 对齐，不兼容变更时 +1。 */
    external fun abiVersion(): Int

    /** 引擎版本串（如 "0.1.0-alpha.1 (rust, abi=1)"），设置页展示用。 */
    external fun engineVersion(): String

    /** 创建引擎。失败抛 RuntimeException 并返回 0。 */
    external fun newEngine(workers: Int, dataDir: String): Long

    /** 关闭引擎并释放句柄；之后该句柄上的调用一律抛异常。 */
    external fun close(handle: Long)

    /** 注册/清除（传 null）进度监听。 */
    external fun setListener(handle: Long, listener: ProgressListener?)

    /** 注册/清除（传 null）宿主解析器（旧版 Office 兜底通道）。 */
    external fun setExternalParser(handle: Long, parser: ExternalParser?)

    /** 异步扫描。optionsJson = {"roots":[...],"mode":"full|incremental"}（兼容旧数组格式）。失败抛异常。 */
    external fun startScan(handle: Long, optionsJson: String)

    /** 请求取消当前扫描（异步生效）。 */
    external fun cancelScan(handle: Long)

    /** 是否扫描中。 */
    external fun isScanning(handle: Long): Boolean

    /** 当前统计 JSON（Stats 契约见 engine/src/types.rs）。 */
    external fun stats(handle: Long): String

    /** 搜索，返回 SearchResponse JSON；空查询等错误抛异常。 */
    external fun search(handle: Long, query: String, limit: Long): String

    /** 删除文件后同步移出索引，返回 {"removed":n}。 */
    external fun removePaths(handle: Long, pathsJson: String): String

    /** 宿主喂入自行解析的文档正文（POI 兜底通道）。 */
    external fun addDocumentText(handle: Long, path: String, text: String)

    /** 注入外部条目并整体重建索引，返回 Stats JSON。 */
    external fun ingest(handle: Long, entriesJson: String): String

    /** 内存文档字节抽取正文（SAF/DocumentsProvider 通道）。 */
    external fun extractDoc(handle: Long, name: String, data: ByteArray): String

    /** 全量替换 Shizuku/Stellar 外部条目清单。 */
    external fun replaceExternalEntries(handle: Long, feedJson: String)
}

/**
 * 进度监听器（与 Go 版 ProgressListener 接口同构）。
 * 回调在引擎工作线程触发，实现方须自行切换主线程；实现内抛出的异常
 * 被引擎吞掉（等价 Go 版 safeCall recover），不会中断扫描。
 */
interface ProgressListener {
    /** phase 取值 "scan" | "index"；done 为累计处理的文件数。 */
    fun onProgress(phase: String, done: Long)

    /** 流程结束回调一次，statsJson 为 Stats 结构的 JSON。 */
    fun onFinished(statsJson: String)

    /** 非致命错误（如个别目录无权限），不会中断整体流程。 */
    fun onError(phase: String, message: String)
}

/**
 * 宿主侧解析器（与 Go 版 ExternalParser 接口同构）。
 * 返回 null 或抛异常时引擎跳过该文件。
 */
interface ExternalParser {
    fun extractText(absPath: String): String?
}
