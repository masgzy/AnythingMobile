//! Rust→Kotlin 宿主回调（ProgressListener / ExternalParser）。
//!
//! 对齐 Go 版语义：
//! - 回调失败（Java 异常 / JVM 不可用）绝不向上传播，等价于 Go 的 safeCall recover；
//! - 线程不变式：回调只会在**引擎自建的线程**（扫描/解析工作线程）上触发，
//!   attach_current_thread 的 AttachGuard drop 时 detach 该线程是安全的；
//!   JVM 创建的线程（JNI 导出的调用线程）从不调用本模块。
//! - 桌面 host（cargo test）无 JVM：crate::jvm() 为 None，全部回调退化为 no-op。
//!
//! 每次回调独立 attach/detach 的开销在当前事件频率（进度每 64 个文件节流一次）
//! 下可忽略；若阶段②解析流水线需要高频回调，再改为线程入口 attach 一次、
//! JNIEnv 透传的模式。

use jni::JNIEnv;
use jni::objects::{GlobalRef, JObject, JString, JValue};

/// 进度监听器槽位（Kotlin `ProgressListener` 实现的全局引用）。
pub struct ListenerSlot {
    obj: GlobalRef,
}

impl ListenerSlot {
    /// 从 JNI 线程（导出函数调用线程）构造：全局引用。
    /// JVM 不入槽位 —— 回调时从 crate::jvm()（OnceLock，'static）现取。
    pub fn new(env: &mut JNIEnv, listener: &JObject) -> Option<Self> {
        let obj = env.new_global_ref(listener).ok()?;
        Some(ListenerSlot { obj })
    }

    /// Kotlin: `onProgress(phase: String, done: Long)`
    pub fn on_progress(&self, phase: &str, done: i64) {
        self.call(|env| {
            let jphase = env.new_string(phase)?;
            env.call_method(
                self.obj.as_obj(),
                "onProgress",
                "(Ljava/lang/String;J)V",
                &[JValue::Object(&jphase), JValue::Long(done)],
            )?;
            Ok(())
        });
    }

    /// Kotlin: `onFinished(statsJson: String)`
    pub fn on_finished(&self, stats_json: &str) {
        self.call(|env| {
            let jstats = env.new_string(stats_json)?;
            env.call_method(
                self.obj.as_obj(),
                "onFinished",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jstats)],
            )?;
            Ok(())
        });
    }

    /// Kotlin: `onError(phase: String, message: String)`
    pub fn on_error(&self, phase: &str, message: &str) {
        self.call(|env| {
            let jphase = env.new_string(phase)?;
            let jmsg = env.new_string(message)?;
            env.call_method(
                self.obj.as_obj(),
                "onError",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&jphase), JValue::Object(&jmsg)],
            )?;
            Ok(())
        });
    }

    /// 统一回调通道：attach → 执行 → 异常清理 → detach。
    /// 任何失败静默吞掉（Go safeCall 同语义）。
    fn call<F>(&self, f: F)
    where
        F: FnOnce(&mut JNIEnv) -> jni::errors::Result<()>,
    {
        let Some(vm) = crate::jvm() else { return };
        let Ok(mut guard) = vm.attach_current_thread() else {
            return;
        };
        let env: &mut JNIEnv = &mut guard;
        if f(env).is_err() {
            // Java 侧回调抛异常 / JNI 调用失败：清掉 pending 异常，
            // 扫描流程继续（Go 版 recover 后同样只丢弃该次回调）
            let _ = env.exception_clear();
        }
    }
}

/// 宿主解析器槽位（Kotlin `ExternalParser` 实现的全局引用，兜底旧版 Office）。
pub struct ParserSlot {
    obj: GlobalRef,
}

impl ParserSlot {
    pub fn new(env: &mut JNIEnv, parser: &JObject) -> Option<Self> {
        let obj = env.new_global_ref(parser).ok()?;
        Some(ParserSlot { obj })
    }

    /// Kotlin: `extractText(absPath: String): String?`
    /// 返回 None 表示宿主无法解析（引擎跳过该文件，Go 同语义）。
    pub fn extract_text(&self, path: &str) -> Option<String> {
        let mut out = None;
        self.call_ret(|env| {
            let jpath = env.new_string(path)?;
            let val = env.call_method(
                self.obj.as_obj(),
                "extractText",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&jpath)],
            )?;
            let obj = val.l()?;
            if obj.is_null() {
                return Ok(());
            }
            let s = JString::from(obj);
            out = Some(env.get_string(&s)?.to_string_lossy().into_owned());
            Ok(())
        });
        out
    }

    /// 与 [`ListenerSlot::call`] 同通道，额外把返回值带出闭包。
    fn call_ret<F, T>(&self, f: F) -> Option<T>
    where
        F: FnOnce(&mut JNIEnv) -> jni::errors::Result<T>,
    {
        let vm = crate::jvm()?;
        let Ok(mut guard) = vm.attach_current_thread() else {
            return None;
        };
        let env: &mut JNIEnv = &mut guard;
        match f(env) {
            Ok(v) => Some(v),
            Err(_) => {
                let _ = env.exception_clear();
                None
            }
        }
    }
}
