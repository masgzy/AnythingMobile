package core

// 索引磁盘持久化 —— 解决"每次进入应用都从零全量建索引"的慢问题。
//
// 工作方式：
//   1. NewEngine(workers, dataDir) 构造时同步加载快照（若存在），
//      引擎一就绪即可搜索，与原版 Anything"秒开"体验一致；
//   2. 每次扫描成功收尾后异步落盘（gob 编码 + 临时文件原子重命名）；
//   3. "重建索引"等全量模式结束后同样覆盖旧快照。
//
// v3 起正文不进快照：全文库改由 bleve scorch 持久化（见 fts.go），
// 快照只保存名称/目录/外部条目，体积从"与全文等量"降到 KB 级。
// 旧版（v2）快照的 Contents 字段在解码时被 gob 自动忽略，
// 正文由首次增量扫描的"名称在而全文缺失 → 补解析"机制自动重建。
//
// 一致性设计：
//   - 快照保存与下一次扫描互斥（saveWG 串行化），避免读到半新半旧状态；
//   - 加载失败（文件损坏/版本不符）静默放弃，退回全量首次建索引；
//   - 名称索引与全文库不一致时（如 scorch 崩溃丢尾），增量扫描会对
//     "名称未变但全文缺失"的文档自动补解析（见 walker.go）。

import (
	"bytes"
	"compress/gzip"
	"encoding/gob"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// snapshotVersion v3：正文移出快照（bleve scorch 接管，见 fts.go），
// 快照只保留名称/目录/外部条目。加载 v2 旧快照时 Contents 字段被
// gob 忽略，名称索引照常恢复，正文由首次扫描补解析重建。
const snapshotVersion = 3

// nameRecord 名称索引（文件/目录通用）的持久化条目。
type nameRecord struct {
	Path  string
	Size  int64
	Mtime int64
}

// contentRecord 已废弃（v3 起正文由 bleve 持久化）。保留类型注释仅为
// 说明历史；结构体随 Contents 字段一并移除。

// snapshot 快照文件结构。
// v3：ExtNames/ExtDirs 为特权通道收录的外部条目；Contents 已移除，
// 旧版快照（v2）解码时该字段被 gob 自动忽略。
type snapshot struct {
	Version  int
	SavedAt  int64
	Names    []nameRecord
	Dirs     []nameRecord
	ExtNames []nameRecord
	ExtDirs  []nameRecord
}

// snapshotPath 快照文件最终路径；临时文件为其加 .tmp 后缀。
func (e *Engine) snapshotPath() string {
	return filepath.Join(e.dataDir, "index.snap")
}

// saveSnapshot 将三张索引编码落盘（gob + gzip）。
// 写入临时文件后原子重命名，任意时刻崩溃都不会破坏上一次的有效快照。
func (e *Engine) saveSnapshot() error {
	if e.dataDir == "" {
		return errors.New("core: 未设置数据目录，无法保存索引")
	}
	if err := os.MkdirAll(e.dataDir, 0o755); err != nil {
		return err
	}

	snap := snapshot{
		Version:  snapshotVersion,
		SavedAt:  time.Now().UnixMilli(),
		Names:    e.names.exportRecords(),
		Dirs:     e.dirs.exportRecords(),
		ExtNames: e.extNames.exportRecords(),
		ExtDirs:  e.extDirs.exportRecords(),
	}

	var buf bytes.Buffer
	// BestSpeed：快照以"文本为主"（压缩比 4~6x vs 默认压缩的 5~8x），
	// 但 CPU 开销降为约 1/3 —— 手机上落盘更快、更省电，
	// 且扫描收尾后的落盘窗口与下一次扫描互斥（saveWG），越快越好。
	zw, err := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
	if err != nil {
		return err
	}
	if err := gob.NewEncoder(zw).Encode(snap); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}

	tmp := e.snapshotPath() + ".tmp"
	if err := os.WriteFile(tmp, buf.Bytes(), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, e.snapshotPath())
}

// restoreSnapshot 从磁盘恢复索引（进程内只执行一次）。
// 首次使用（无快照）与加载失败均静默返回，由后续全量扫描兜底；
// v2 旧快照可正常解码（Contents 字段被 gob 忽略），正文随首次
// 增量扫描自动补解析。
func (e *Engine) restoreSnapshot() {
	if e.dataDir == "" {
		return
	}
	e.restoreOnce.Do(func() {
		data, err := os.ReadFile(e.snapshotPath())
		if err != nil {
			return // 无快照：首次使用
		}
		zr, err := gzip.NewReader(bytes.NewReader(data))
		if err != nil {
			return // 非 gzip 格式（v1 旧快照）：放弃恢复
		}
		var snap snapshot
		if err := gob.NewDecoder(zr).Decode(&snap); err != nil {
			return // 损坏：放弃恢复
		}
		if snap.Version != snapshotVersion && snap.Version != 2 {
			return // 版本不符（v1 及更旧）：放弃恢复
		}
		e.names.importRecords(snap.Names)
		e.dirs.importRecords(snap.Dirs)
		e.extNames.importRecords(snap.ExtNames)
		e.extDirs.importRecords(snap.ExtDirs)
	})
}

// ---- NameIndex 导出/导入 ----

func (n *NameIndex) exportRecords() []nameRecord {
	n.mu.RLock()
	defer n.mu.RUnlock()
	out := make([]nameRecord, 0, len(n.docs))
	for _, d := range n.docs {
		out = append(out, nameRecord{Path: d.path, Size: d.size, Mtime: d.mtime})
	}
	return out
}

func (n *NameIndex) importRecords(recs []nameRecord) {
	if len(recs) == 0 {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, r := range recs {
		if r.Path == "" {
			continue
		}
		base := filepath.Base(r.Path)
		lower := strings.ToLower(base)
		if lower == "" {
			continue
		}
		if id, ok := n.byPath[r.Path]; ok {
			n.removeLocked(id) // 快照内理论无重复，防御性处理
		}
		id := n.next
		n.next++
		n.docs[id] = &docInfo{path: r.Path, name: base, lower: lower, size: r.Size, mtime: r.Mtime}
		n.byPath[r.Path] = id
		for _, g := range bigrams(lower) {
			set := n.grams[g]
			if set == nil {
				set = make(map[uint32]struct{})
				n.grams[g] = set
			}
			set[id] = struct{}{}
		}
	}
}
