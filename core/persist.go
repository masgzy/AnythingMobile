package core

// 索引磁盘持久化 —— 解决"每次进入应用都从零全量建索引"的慢问题。
//
// 工作方式：
//   1. NewEngine(workers, dataDir) 构造时同步加载快照（若存在），
//      引擎一就绪即可搜索，与原版 Anything"秒开"体验一致；
//   2. 每次扫描成功收尾后异步落盘（gob 编码 + 临时文件原子重命名）；
//   3. "重建索引"等全量模式结束后同样覆盖旧快照。
//
// 一致性设计：
//   - 快照保存与下一次扫描互斥（saveWG 串行化），避免读到半新半旧状态；
//   - 加载失败（文件损坏/版本不符）静默放弃，退回全量首次建索引；
//   - 极端情况下名称索引与全文库不一致时，增量扫描会对
//     "名称未变但全文缺失"的文档自动补解析（见 walker.go handleFile）。

import (
        "bytes"
        "encoding/gob"
        "errors"
        "os"
        "path/filepath"
        "strings"
        "time"
)

const snapshotVersion = 1

// nameRecord 名称索引（文件/目录通用）的持久化条目。
type nameRecord struct {
        Path  string
        Size  int64
        Mtime int64
}

// contentRecord 全文库的持久化条目（保存原文用于生成摘要）。
type contentRecord struct {
        Path string
        Text string
}

// snapshot 快照文件结构。
type snapshot struct {
        Version  int
        SavedAt  int64
        Names    []nameRecord
        Dirs     []nameRecord
        Contents []contentRecord
}

// snapshotPath 快照文件最终路径；临时文件为其加 .tmp 后缀。
func (e *Engine) snapshotPath() string {
        return filepath.Join(e.dataDir, "index.snap")
}

// saveSnapshot 将三张索引编码落盘。写入临时文件后原子重命名，
// 任意时刻崩溃都不会破坏上一次的有效快照。
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
                Contents: e.content.exportRecords(),
        }

        var buf bytes.Buffer
        if err := gob.NewEncoder(&buf).Encode(snap); err != nil {
                return err
        }

        tmp := e.snapshotPath() + ".tmp"
        if err := os.WriteFile(tmp, buf.Bytes(), 0o644); err != nil {
                return err
        }
        return os.Rename(tmp, e.snapshotPath())
}

// restoreSnapshot 从磁盘恢复索引（进程内只执行一次）。
// 首次使用（无快照）与加载失败均静默返回，由后续全量扫描兜底。
func (e *Engine) restoreSnapshot() {
        if e.dataDir == "" {
                return
        }
        e.restoreOnce.Do(func() {
                data, err := os.ReadFile(e.snapshotPath())
                if err != nil {
                        return // 无快照：首次使用
                }
                var snap snapshot
                if err := gob.NewDecoder(bytes.NewReader(data)).Decode(&snap); err != nil {
                        return // 损坏：放弃恢复
                }
                if snap.Version != snapshotVersion {
                        return // 版本不符：放弃恢复
                }
                e.names.importRecords(snap.Names)
                e.dirs.importRecords(snap.Dirs)
                e.content.importRecords(snap.Contents)
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

// ---- ContentStore 导出/导入 ----

func (c *ContentStore) exportRecords() []contentRecord {
        c.mu.RLock()
        defer c.mu.RUnlock()
        out := make([]contentRecord, 0, len(c.orig))
        for p, text := range c.orig {
                out = append(out, contentRecord{Path: p, Text: text})
        }
        return out
}

func (c *ContentStore) importRecords(recs []contentRecord) {
        if len(recs) == 0 {
                return
        }
        c.mu.Lock()
        defer c.mu.Unlock()
        for _, r := range recs {
                if r.Path == "" || strings.TrimSpace(r.Text) == "" {
                        continue
                }
                if _, exists := c.texts[r.Path]; exists {
                        continue
                }
                if c.bytes+int64(len(r.Text)) > c.maxBytes {
                        return // 容量封顶，与运行时 Add 的语义一致
                }
                c.texts[r.Path] = strings.ToLower(r.Text)
                c.orig[r.Path] = r.Text
                c.bytes += int64(len(r.Text))
                c.order = append(c.order, r.Path)
        }
}

// Has 返回全文库中是否已存在该路径的正文（增量扫描的补解析判据）。
func (c *ContentStore) Has(path string) bool {
        c.mu.RLock()
        defer c.mu.RUnlock()
        _, ok := c.texts[path]
        return ok
}
