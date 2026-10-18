//! 统一错误类型。
//!
//! 与 Go 版的错误文案保持同前缀（`"core: ..."`），
//! 宿主（Kotlin）对错误只做展示不做语义匹配，前缀一致性仅为可读性。

use std::fmt;

/// 引擎错误：字符串承载，跨 FFI 时转 Java `RuntimeException` 消息。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineError(pub String);

impl EngineError {
    pub fn new(msg: impl Into<String>) -> Self {
        EngineError(msg.into())
    }

    /// 错误消息（FFI 边界直接取用）。
    pub fn message(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for EngineError {}

impl From<serde_json::Error> for EngineError {
    fn from(e: serde_json::Error) -> Self {
        EngineError(format!("core: JSON 解析失败: {e}"))
    }
}

impl From<std::io::Error> for EngineError {
    fn from(e: std::io::Error) -> Self {
        EngineError(format!("core: IO 错误: {e}"))
    }
}

/// 便捷别名。
pub type EngineResult<T> = Result<T, EngineError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_error_maps_with_prefix() {
        let e: EngineError = serde_json::from_str::<serde_json::Value>("{bad")
            .unwrap_err()
            .into();
        assert!(e.message().starts_with("core: JSON 解析失败"));
    }

    #[test]
    fn display_and_eq() {
        let a = EngineError::new("core: 查询词为空");
        assert_eq!(a.to_string(), "core: 查询词为空");
        assert_eq!(a, EngineError::new("core: 查询词为空"));
    }
}
