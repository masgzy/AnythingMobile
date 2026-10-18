//! 引擎句柄注册表：jlong handle → Arc<EngineImpl>。
//!
//! 设计对齐 JNI 生态惯例（CFSTMobile 同款思路的托管升级）：
//! - Kotlin 持有 `handle: Long`，Rust 侧全局映射表负责生命周期；
//! - `with()` 先克隆 Arc 再执行，**不持锁调用引擎方法**——扫描线程与
//!   注册表锁零竞争；
//! - `remove()` 只移除映射，正在进行的扫描因线程持有 Arc 而安全存活，
//!   close() 内部 join 收尾。

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use crate::engine::EngineImpl;
use crate::error::EngineResult;

/// 句柄从 1 起发（0 视为无效值，Kotlin 侧判空即可识别失败）。
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

static ENGINES: OnceLock<Mutex<HashMap<i64, Arc<EngineImpl>>>> = OnceLock::new();

fn map() -> &'static Mutex<HashMap<i64, Arc<EngineImpl>>> {
    ENGINES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 创建引擎并登记，返回新句柄。
pub fn create(workers: i32, data_dir: &str) -> EngineResult<i64> {
    let eng = EngineImpl::new(workers, data_dir)?;
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    map()
        .lock()
        .expect("engine registry 锁中毒（创建时）")
        .insert(handle, eng);
    Ok(handle)
}

/// 以 Arc 形式取出引擎执行 `f`。句柄不存在（未创建/已 close）返回 None。
pub fn with<R>(handle: i64, f: impl FnOnce(Arc<EngineImpl>) -> R) -> Option<R> {
    let eng = {
        let m = map().lock().ok()?;
        m.get(&handle)?.clone()
        // 锁在此丢弃，f 执行期间零持锁
    };
    Some(f(eng))
}

/// 移除并返回引擎（close 通道）。之后对该句柄的一切调用均报"句柄无效"。
pub fn remove(handle: i64) -> Option<Arc<EngineImpl>> {
    map().lock().ok()?.remove(&handle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_handle_is_none() {
        assert!(with(0xdead_beef, |_| 1).is_none());
    }

    #[test]
    fn create_with_remove_roundtrip() {
        let h = create(1, "").unwrap();
        assert!(h > 0);
        let seen = with(h, |e| e.is_scanning()).unwrap();
        assert!(!seen);
        assert!(remove(h).is_some());
        assert!(with(h, |_| ()).is_none());
    }
}
