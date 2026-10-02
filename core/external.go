package core

// 外部扩展索引 —— 经 Shizuku/Stellar 特权通道收录的目录条目。
//
// 背景：Android 11+ 上 /storage/emulated/0/Android/data 对普通应用不可见
// （FUSE 挂载按包名隔离），引擎进程无法像常规扫描那样直接遍历；宿主
// （Kotlin 层）通过 Shizuku 的 shell(uid 2000) 特权进程枚举文件清单后，
// 经 ReplaceExternalEntries 整体喂给引擎。
//
// 设计要点：
//   - 外部条目放在独立的 NameIndex（extNames/extDirs），与常规索引隔离：
//     增量扫描收尾的 RemoveExcept 只作用于常规索引，绝不会误删外部条目，
//     外部条目也不参与 first_build 判定（首用遮罩只关心本地存储）；
//   - 全文内容索引不适用于外部条目（逐文件 cat 的开销不可接受），
//     本通道仅收录名称，支持文件名/目录名搜索；
//   - ReplaceExternalEntries 为全量替换语义：宿主每次枚举完整目录树后
//     调用一次，已消失的条目随替换自然清除，无需增量协议；
//   - 条目随快照持久化。gob 对"编码端未出现的结构体字段"会填零值，
//     因此旧快照（alpha8 及之前）缺少扩展字段时解码安全，
//     下一次外部刷新会重建，无需迁移逻辑。

import (
	"encoding/json"
	"errors"
	"strings"
)

// ExternalFeed ReplaceExternalEntries 的入参结构。
type ExternalFeed struct {
	Root  string   `json:"root"`  // 枚举根目录（如 /storage/emulated/0/Android/data）
	Dirs  []string `json:"dirs"`  // 目录清单（含根自身）
	Files []string `json:"files"` // 文件清单
}

// ReplaceExternalEntries 用宿主提供的完整清单替换外部扩展索引。
// gobind: replaceExternalEntries(String)
//
// 扫描进行中返回错误（宿主应在 onFinished 后串行调用）；
// dataDir 非空且引擎空闲时异步落盘快照，与扫描收尾共用 saveWG 串行化，
// StartScan 入口的 saveWG.Wait() 保证不存在并发写盘。
func (e *Engine) ReplaceExternalEntries(feedJSON string) error {
	if e.scanning.Load() {
		return errors.New("core: 扫描进行中，暂不能更新扩展索引")
	}
	var feed ExternalFeed
	if err := json.Unmarshal([]byte(feedJSON), &feed); err != nil {
		return errors.New(`core: feedJSON 非法，应为 {"root":...,"dirs":[...],"files":[...]}`)
	}
	e.extMu.Lock()
	e.extNames.Reset()
	e.extDirs.Reset()
	e.invalidateSearchCache()
	for _, p := range feed.Dirs {
		if validExternalPath(p, feed.Root) {
			e.extDirs.Add(p, 0, 0)
		}
	}
	for _, p := range feed.Files {
		if validExternalPath(p, feed.Root) {
			e.extNames.Add(p, 0, 0)
		}
	}
	e.extMu.Unlock()

	if e.dataDir != "" {
		e.saveWG.Add(1)
		go func() {
			defer e.saveWG.Done()
			defer func() { _ = recover() }() // 落盘失败绝不拖垮进程
			_ = e.saveSnapshot()
		}()
	}
	return nil
}

// validExternalPath 防御清单质量：拒绝空串、越出根目录、含换行的路径
// （find 以换行分隔输出，含换行的文件名本就无法可靠还原，直接丢弃）；
// 并逐段校验拒绝 ".."/"." 段 —— 纯前缀检查拦不住 root/../secret 这类逃逸。
func validExternalPath(p, root string) bool {
	if p == "" || strings.ContainsAny(p, "\n\r") {
		return false
	}
	if root != "" && !strings.HasPrefix(p, root) {
		return false
	}
	rest := strings.TrimPrefix(p, root)
	for _, seg := range strings.Split(rest, "/") {
		if seg == ".." || seg == "." {
			return false
		}
	}
	return true
}

// externalCount 扩展条目总数（文件+目录）。
func (e *Engine) externalCount() int64 {
	e.extMu.RLock()
	defer e.extMu.RUnlock()
	return e.extNames.Count() + e.extDirs.Count()
}
