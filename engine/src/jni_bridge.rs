//! JNI 桥接层 —— 全部导出符号与 FFI 安全边界。
//!
//! ⚠️ 符号名与 Kotlin 类路径强耦合：`com.masgzy.anything.rust.RustEngine`
//! （契约见 `app/src/main/java/com/masgzy/anything/rust/RustEngine.kt`）。
//! 改名必须双侧同步，否则运行时 `UnsatisfiedLinkError`。
//!
//! ## 安全协议（每条导出统一执行）
//! 1. `catch_unwind` 包裹全部工作：内部 panic 转为 Java `RuntimeException`，
//!    绝不杀进程（panic=abort 已在 Cargo.toml 注释中明令禁止）；
//! 2. 错误统一 `throw_new(java/lang/RuntimeException, msg)` + 返回零值
//!    （0 / null / false）——Kotlin 侧按异常捕获（gomobile 时代 error→throws
//!    Exception 的等价物）；
//! 3. 字符串一律 Java String ⇆ Rust String（jni crate 处理 Modified-UTF-8），
//!    复合数据保持 Go 时代约定：JSON 字符串；
//! 4. 本模块只做协议转换，业务一律进 `registry` / `EngineImpl`。

use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{JNI_ERR, JNI_VERSION_1_6, jboolean, jint, jlong, jstring};

use crate::callback::{ListenerSlot, ParserSlot};
use crate::error::EngineResult;
use crate::registry;

// ---------------------------------------------------------------- JVM 缓存

static JVM: OnceLock<jni::JavaVM> = OnceLock::new();

/// `System.loadLibrary` 触发；登记 JVM 供宿主回调 attach 线程。
///
/// # Safety
/// `vm` 必须由 JVM 传入且指向有效的 JavaVM（JNI 规范保证；本函数仅被
/// `System.loadLibrary` 调用）。标记 unsafe 以满足 clippy 对裸指针解引用的
/// 公开函数检查，ABI 不受影响。
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _reserved: *mut core::ffi::c_void,
) -> jint {
    match unsafe { jni::JavaVM::from_raw(vm) } {
        Ok(jvm) => {
            let _ = JVM.set(jvm);
            JNI_VERSION_1_6
        }
        Err(_) => JNI_ERR,
    }
}

/// 供 callback 模块读取的 JVM 单例。
pub(crate) fn cached_jvm() -> Option<&'static jni::JavaVM> {
    JVM.get()
}

// ---------------------------------------------------------------- 安全工具

/// 把任意 panic 载荷转成可读消息。
fn panic_msg(p: Box<dyn Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "未知 panic".to_string()
    }
}

/// FFI 统一护罩：catch_unwind + Result 归一。任何 panic 变成 Err(String)。
fn guarded<F, R>(what: &str, f: F) -> Result<R, String>
where
    F: FnOnce() -> Result<R, String>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(p) => Err(format!("anything: {what} 发生内部错误: {}", panic_msg(p))),
    }
}

/// 抛 Java 异常（已有 pending 异常时不覆盖）。
fn throw(env: &mut JNIEnv, msg: &str) {
    if env.exception_check().unwrap_or(false) {
        return;
    }
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

/// Java String → Rust String；失败时抛异常并返回 None。
fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    match env.get_string(s) {
        Ok(v) => Some(v.to_string_lossy().into_owned()),
        Err(e) => {
            throw(env, &format!("core: 读取字符串参数失败: {e}"));
            None
        }
    }
}

/// 引擎句柄 → 引擎执行。句柄无效时统一报错文案。
fn with_engine<R>(
    handle: jlong,
    f: impl FnOnce(std::sync::Arc<crate::engine::EngineImpl>) -> EngineResult<R>,
) -> Result<R, String> {
    registry::with(handle, f)
        .unwrap_or_else(|| Err(crate::error::EngineError::new("core: 引擎句柄无效或已关闭")))
        .map_err(|e| e.message().to_string())
}

// ---------------------------------------------------------------- 版本

/// Kotlin: `external fun abiVersion(): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_abiVersion(
    _env: JNIEnv,
    _obj: JClass,
) -> jint {
    crate::ABI_VERSION
}

/// Kotlin: `external fun engineVersion(): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_engineVersion<'local>(
    env: JNIEnv<'local>,
    _obj: JClass<'local>,
) -> jstring {
    let v = crate::engine_version();
    match env.new_string(&v) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 生命周期

/// Kotlin: `external fun newEngine(workers: Int, dataDir: String): Long`
/// 失败抛异常并返回 0。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_newEngine<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    workers: jint,
    data_dir: JString<'local>,
) -> jlong {
    let Some(dir) = jstr(&mut env, &data_dir) else {
        return 0;
    };
    match guarded("newEngine", || {
        registry::create(workers, &dir).map_err(|e| e.message().to_string())
    }) {
        Ok(h) => h as jlong,
        Err(msg) => {
            throw(&mut env, &msg);
            0
        }
    }
}

/// Kotlin: `external fun close(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_close(
    _env: JNIEnv,
    _obj: JClass,
    handle: jlong,
) {
    let _ = guarded("close", || {
        if let Some(eng) = registry::remove(handle) {
            eng.close();
        }
        Ok(())
    });
}

/// Kotlin: `external fun setListener(handle: Long, listener: ProgressListener?)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_setListener<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    listener: JObject<'local>,
) {
    // 全局引用创建需要当前 env，在护罩内借用 env 完成后归还
    let result = guarded("setListener", || {
        let slot = if listener.is_null() {
            None
        } else {
            Some(std::sync::Arc::new(
                ListenerSlot::new(&mut env, &listener).ok_or("core: JVM 未初始化，无法注册回调")?,
            ))
        };
        with_engine(handle, |eng| {
            eng.set_listener(slot);
            Ok(())
        })
    });
    if let Err(msg) = result {
        throw(&mut env, &msg);
    }
}

/// Kotlin: `external fun setExternalParser(handle: Long, parser: ExternalParser?)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_setExternalParser<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    parser: JObject<'local>,
) {
    let result = guarded("setExternalParser", || {
        let slot = if parser.is_null() {
            None
        } else {
            Some(std::sync::Arc::new(
                ParserSlot::new(&mut env, &parser).ok_or("core: JVM 未初始化，无法注册解析器")?,
            ))
        };
        with_engine(handle, |eng| {
            eng.set_external_parser(slot);
            Ok(())
        })
    });
    if let Err(msg) = result {
        throw(&mut env, &msg);
    }
}

// ---------------------------------------------------------------- 扫描

/// Kotlin: `external fun startScan(handle: Long, optionsJson: String)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_startScan<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    options_json: JString<'local>,
) {
    let Some(opts) = jstr(&mut env, &options_json) else {
        return;
    };
    if let Err(msg) = guarded("startScan", || {
        with_engine(handle, |eng| eng.start_scan(&opts))
    }) {
        throw(&mut env, &msg);
    }
}

/// Kotlin: `external fun cancelScan(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_cancelScan(
    _env: JNIEnv,
    _obj: JClass,
    handle: jlong,
) {
    let _ = guarded("cancelScan", || {
        registry::with(handle, |eng| eng.cancel_scan())
            .ok_or_else(|| "core: 引擎句柄无效或已关闭".to_string())
    });
}

/// Kotlin: `external fun isScanning(handle: Long): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_isScanning(
    mut env: JNIEnv,
    _obj: JClass,
    handle: jlong,
) -> jboolean {
    match guarded("isScanning", || {
        with_engine(handle, |eng| Ok(eng.is_scanning()))
    }) {
        Ok(b) => b as jboolean,
        Err(msg) => {
            throw(&mut env, &msg);
            0
        }
    }
}

// ---------------------------------------------------------------- 查询与统计

/// Kotlin: `external fun stats(handle: Long): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_stats<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
) -> jstring {
    let json = guarded("stats", || with_engine(handle, |eng| Ok(eng.stats_json())));
    match json {
        Ok(s) => match env.new_string(&s) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            throw(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

/// Kotlin: `external fun search(handle: Long, query: String, limit: Long): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_search<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
    limit: jlong,
) -> jstring {
    let Some(q) = jstr(&mut env, &query) else {
        return std::ptr::null_mut();
    };
    let json = guarded("search", || {
        with_engine(handle, |eng| eng.search(&q, limit))
    });
    match json {
        Ok(s) => match env.new_string(&s) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            throw(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------- 变更通道

/// Kotlin: `external fun removePaths(handle: Long, pathsJson: String): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_removePaths<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    paths_json: JString<'local>,
) -> jstring {
    let Some(paths) = jstr(&mut env, &paths_json) else {
        return std::ptr::null_mut();
    };
    let out = guarded("removePaths", || {
        with_engine(handle, |eng| eng.remove_paths(&paths))
    });
    match out {
        Ok(s) => match env.new_string(&s) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            throw(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

/// Kotlin: `external fun addDocumentText(handle: Long, path: String, text: String)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_addDocumentText<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    path: JString<'local>,
    text: JString<'local>,
) {
    let (Some(p), Some(t)) = (jstr(&mut env, &path), jstr(&mut env, &text)) else {
        return;
    };
    if let Err(msg) = guarded("addDocumentText", || {
        with_engine(handle, |eng| eng.add_document_text(&p, &t))
    }) {
        throw(&mut env, &msg);
    }
}

/// Kotlin: `external fun ingest(handle: Long, entriesJson: String): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_ingest<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    entries_json: JString<'local>,
) -> jstring {
    let Some(entries) = jstr(&mut env, &entries_json) else {
        return std::ptr::null_mut();
    };
    let out = guarded("ingest", || with_engine(handle, |eng| eng.ingest(&entries)));
    match out {
        Ok(s) => match env.new_string(&s) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            throw(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

/// Kotlin: `external fun extractDoc(handle: Long, name: String, data: ByteArray): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_extractDoc<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    name: JString<'local>,
    data: JByteArray<'local>,
) -> jstring {
    let Some(doc_name) = jstr(&mut env, &name) else {
        return std::ptr::null_mut();
    };
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(e) => {
            throw(&mut env, &format!("core: 读取文档字节数组失败: {e}"));
            return std::ptr::null_mut();
        }
    };
    let out = guarded("extractDoc", || {
        with_engine(handle, |eng| eng.extract_doc(&doc_name, &bytes))
    });
    match out {
        Ok(s) => match env.new_string(&s) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(msg) => {
            throw(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

/// Kotlin: `external fun replaceExternalEntries(handle: Long, feedJson: String)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_masgzy_anything_rust_RustEngine_replaceExternalEntries<'local>(
    mut env: JNIEnv<'local>,
    _obj: JClass<'local>,
    handle: jlong,
    feed_json: JString<'local>,
) {
    let Some(feed) = jstr(&mut env, &feed_json) else {
        return;
    };
    if let Err(msg) = guarded("replaceExternalEntries", || {
        with_engine(handle, |eng| eng.replace_external_entries(&feed))
    }) {
        throw(&mut env, &msg);
    }
}
