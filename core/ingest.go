package core

// SAF/DocumentsProvider 目录注入（目录搜索功能的数据通道）。
//
// 背景：目录搜索支持两种授权形态——
//  1. 已授"所有文件访问"且目录可映射为物理路径：走 StartScan 的常规
//     File 遍历（增量/全文解析全部复用）；
//  2. 仅经文件夹选择器（ACTION_OPEN_DOCUMENT_TREE）授权：物理路径
//     不可直接 File 读取，由宿主经 DocumentFile 树遍历 + ContentResolver
//     读取内容，再把条目喂给本方法入索引。
//
// Ingest 语义 = "以本批条目为准的整体重建"（与 full 扫描同构）：
// 先清空三张主索引再全量 upsert。SAF 注入只在"搜索范围 = 仅选定目录"
// 时使用，此时索引恰好等于全部已授权目录的并集。与外部扩展索引
// （extNames/extDirs，Shizuku 通道）互不影响：二者是并行的两个特性，
// 搜索时结果自然合并，本方法不触碰扩展索引。
//
// 全文库走 AddBatch 单次 bleve 提交（与 alpha16 攒批优化同款），
// 避免逐文档 Batch 的段生成开销。

import (
        "encoding/json"
        "fmt"
        "os"
        "path/filepath"
        "time"
)

// IngestEntry 宿主遍历得到的单个条目。
type IngestEntry struct {
        Path  string `json:"path"` // 物理路径（可映射时）或 content URI
        Name  string `json:"name"` // 显示名（URI 路径时 filepath.Base 取不到真名）
        Size  int64  `json:"size"`
        Mtime int64  `json:"mtime"`
        Kind  string `json:"kind"` // "file" | "folder"
        // Content 可选全文（宿主经 ContentResolver 读取并解析的文本）；
        // 为空表示该条目不进全文库。
        Content string `json:"content"`
}

// Ingest 注入一批外部条目并整体重建主索引（详见文件头注释）。
// 与 StartScan 互斥（复用 scanning 标记）；返回 currentStats JSON。
// gobind: ingest(String) String
func (e *Engine) Ingest(entriesJSON string) (string, error) {
        var entries []IngestEntry
        if err := json.Unmarshal([]byte(entriesJSON), &entries); err != nil {
                return "", fmt.Errorf("core: entriesJSON 非法，应为 [{path,name,size,mtime,kind,content}]: %w", err)
        }
        if !e.scanning.CompareAndSwap(false, true) {
                return "", fmt.Errorf("core: 扫描/注入已在进行中")
        }
        // 与 StartScan 相同的落盘串行化：等待上一次快照写完再动手
        e.saveWG.Wait()
        // Ingest 本身在调用线程同步执行（条目已由宿主备好，纯内存操作 +
        // 一次 bleve 批提交），无需 goroutine；调用方（Kotlin）已在 IO 线程。

        start := time.Now().UnixMilli()
        firstBuild := e.names.Count() == 0

        // 计数器复位与 beginScanCycle 同口径（不走它：full 分支语义是
        // "扫描重建"，且会创建解析流水线——注入通道用不到）
        e.cancel.Store(false)
        e.firstBuild = firstBuild
        e.startAt.Store(start)
        e.seenMu.Lock()
        e.seen = nil
        e.seenMu.Unlock()
        e.files.Store(0)
        e.added.Store(0)
        e.updated.Store(0)
        e.removed.Store(0)
        e.docsFound.Store(0)
        e.docsIndexed.Store(0)
        // 解析流水线（pp）本通道用不到：内容由宿主直接提供；保持 nil
        // 即可（引擎构造后、首次扫描前同为 nil，是合法状态）

        e.names.Reset()
        e.dirs.Reset()
        e.content.Reset()
        e.invalidateSearchCache()

        docs := make([]DocText, 0, len(entries))
        for i := range entries {
                if e.cancel.Load() {
                        break
                }
                ent := &entries[i]
                if ent.Path == "" || ent.Name == "" {
                        continue
                }
                kind := ent.Kind
                if kind != "folder" {
                        kind = "file"
                }
                if kind == "folder" {
                        e.dirs.AddNamed(ent.Path, ent.Name, ent.Size, ent.Mtime)
                        continue
                }
                e.names.AddNamed(ent.Path, ent.Name, ent.Size, ent.Mtime)
                e.added.Add(1)
                n := e.files.Add(1)
                if n%2000 == 0 {
                        e.notifyProgress("index", n)
                }
                if ent.Content != "" {
                        docs = append(docs, DocText{Path: ent.Path, Text: ent.Content})
                }
        }
        e.docsFound.Store(int64(len(docs)))
        e.docsIndexed.Store(int64(e.content.AddBatch(docs)))
        e.invalidateSearchCache()

        e.scanning.Store(false)
        // 收尾与扫描同路径：异步落盘（失败不影响注入结果）
        if e.dataDir != "" {
                e.saveWG.Add(1)
                go func() {
                        defer e.saveWG.Done()
                        defer func() { _ = recover() }()
                        _ = e.saveSnapshot()
                }()
        }
        // OnFinished 异步化：扫描路径的通知本就发生在后台扫描 goroutine，
        // 监听器阻塞不影响调用方；Ingest 在调用方 goroutine 同步执行，
        // 若同步通知，监听器阻塞（如无缓冲通道满）会反压死锁调用方。
        statsJSON := e.Stats()
        go func() {
                defer func() { _ = recover() }()
                e.notifyFinished(statsJSON)
        }()
        return statsJSON, nil
}

// ExtractDoc 从内存数据抽取文档正文（SAF/DocumentsProvider 注入通道专用）。
//
// 背景：content:// URI 对 Go 进程不可直接 open（无 ContentResolver），
// 宿主经 ContentResolver 读出字节后调本方法。实现上落一个临时文件再
// 走 extractDoc 分发 —— 与常规扫描完全同一套解析管线（内置 OOXML/PDF
// + 宿主 ExternalParser 兜底旧版 Office），用毕即删。
// gobind: extractDoc(name String, data []byte) String
func (e *Engine) ExtractDoc(name string, data []byte) (string, error) {
        if len(data) == 0 {
                return "", nil
        }
        if len(data) > maxParseSize {
                return "", fmt.Errorf("core: 文档 %d 字节超过解析上限 %d", len(data), maxParseSize)
        }
        dir := e.dataDir
        if dir == "" {
                dir = os.TempDir()
        }
        tmpDir := filepath.Join(dir, "tmp")
        if err := os.MkdirAll(tmpDir, 0o700); err != nil {
                return "", fmt.Errorf("core: 创建临时目录失败: %w", err)
        }
        tmp, err := os.CreateTemp(tmpDir, "extract-*-"+filepath.Base(name))
        if err != nil {
                return "", fmt.Errorf("core: 创建临时文件失败: %w", err)
        }
        path := tmp.Name()
        defer os.Remove(path)
        if _, err := tmp.Write(data); err != nil {
                tmp.Close()
                return "", fmt.Errorf("core: 写入临时文件失败: %w", err)
        }
        if err := tmp.Close(); err != nil {
                return "", fmt.Errorf("core: 关闭临时文件失败: %w", err)
        }
        text, ok := e.extractDoc(path, extOf(name))
        if !ok {
                return "", nil // 不支持的格式或空正文：调用方按无全文处理
        }
        return text, nil
}
