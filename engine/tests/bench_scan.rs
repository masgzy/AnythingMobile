//! 引擎扫描基准（手动运行，#[ignore] 不进 CI 门禁）。
//!
//! 目的：量化对标原版闭源 Anything 的"速度差距"到底在哪一段 ——
//! 纯遍历 / 全量建索引 / 增量无变化 / 增量有变化 / 引擎重启恢复 / 搜索。
//!
//! 运行方式（沙箱或桌面）：
//! ```text
//! cargo test --test bench_scan -- --ignored --nocapture
//! ```
//! 可用环境变量调节规模：BENCH_FILES（文件总数，默认 12000）、
//! BENCH_DOCS（其中 docx 数，默认 250）。
//!
//! 注意：绝对数值随机器（沙箱 SSD vs 手机 flash/FUSE）浮动，
//! 关注**相对占比**与优化前后的差值。

use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use std::sync::Arc;

use anything::engine::EngineImpl;
use anything::walker::{FileRecord, WalkHooks};

fn env_or(name: &str, default: usize) -> usize {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

/// 构造合成目录树：`files` 个文件（`docs` 个真 docx + 文本 + 其他扩展名），
/// 每目录约 30 条目、每 5 层一个子树分叉，总目录数 ~files/30。
fn make_tree(root: &Path, files: usize, docs: usize) {
    std::fs::create_dir_all(root).unwrap();
    // 预生成一段中文正文（docx 与 txt 共用，含重复词以测分词）
    let body = "这是一段用于索引基准测试的中文正文内容，包含文件管理、 \
                全文检索、增量扫描等关键词，重复重复重复以便统计词条频率。"
        .repeat(12);
    let docx_body_len = body.len();

    let mut made_docs = 0usize;
    for i in 0..files {
        // 目录布局：root/mdir_<i/30>/<sub_i%5>/f<i>.<ext>
        let dir = root
            .join(format!("mdir_{}", i / 30))
            .join(format!("sub_{}", i % 5));
        std::fs::create_dir_all(&dir).unwrap();
        let (name, bytes): (String, Vec<u8>) = if made_docs < docs && i % 48 == 0 {
            // 真 docx：zip 内 word/document.xml
            made_docs += 1;
            let mut buf = Vec::new();
            {
                let mut zw = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
                zw.start_file(
                    "word/document.xml",
                    zip::write::SimpleFileOptions::default(),
                )
                .unwrap();
                zw.write_all(
                    format!(
                        "<w:document><w:body><w:p><w:t>{}</w:t></w:p></w:body></w:document>",
                        body
                    )
                    .as_bytes(),
                )
                .unwrap();
                zw.finish().unwrap();
            }
            (format!("报告第{i}号.docx"), buf)
        } else {
            match i % 10 {
                0..=6 => (format!("笔记_{i}.txt"), body.as_bytes().to_vec()),
                7 => (format!("图片_{i}.jpg"), vec![0u8; 2048]),
                8 => (
                    format!("数据_{i}.csv"),
                    format!("col1,col2\na,{}\n", i).into_bytes(),
                ),
                _ => (format!("无扩展名文件_{i}"), b"no ext".to_vec()),
            }
        };
        std::fs::write(dir.join(name), &bytes).unwrap();
    }
    let _ = docx_body_len;
}

/// 纯遍历（无索引副作用）：walk 成本基线。
fn bench_walk(roots: &[String], workers: usize) -> Duration {
    let files = AtomicUsize::new(0);
    let hooks = WalkHooks {
        on_files_batch: &|fs: &[FileRecord]| {
            files.fetch_add(fs.len(), Ordering::Relaxed);
        },
        on_dirs_batch: &|_| {},
        on_dir_visited: &|_| {},
        on_error: &|_| {},
    };
    let cancel = AtomicBool::new(false);
    let t = Instant::now();
    let cancelled = anything::walker::traverse(roots, workers, &cancel, &hooks);
    assert!(!cancelled);
    let d = t.elapsed();
    println!(
        "  [walk] {:>8.1?}  files={} workers={}",
        d.as_secs_f64() * 1000.0,
        files.load(Ordering::Relaxed),
        workers
    );
    d
}

/// 等待引擎空闲（轮询 2ms；返回自 t0 起的耗时）。
fn wait_idle(eng: &EngineImpl, t0: Instant) -> Duration {
    while eng.is_scanning() {
        std::thread::sleep(Duration::from_millis(2));
    }
    t0.elapsed()
}

fn scan(eng: &Arc<EngineImpl>, roots: &[String], mode: &str) -> Duration {
    let t0 = Instant::now();
    eng.start_scan(&format!(
        r#"{{"roots":[{}],"mode":"{mode}"}}"#,
        roots
            .iter()
            .map(|r| format!(r#""{r}""#))
            .collect::<Vec<_>>()
            .join(",")
    ))
    .unwrap();
    wait_idle(eng, t0)
}

#[test]
#[ignore]
fn bench_scan_pipeline() {
    let files = env_or("BENCH_FILES", 12_000);
    let docs = env_or("BENCH_DOCS", 250);
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("tree");
    let data = tmp.path().join("data");

    let t = Instant::now();
    make_tree(&root, files, docs);
    println!("== 造树: {:?}  files={files} docs={docs}", t.elapsed());

    let roots = vec![root.to_string_lossy().into_owned()];

    println!("== ① 纯遍历基线（无索引写入）");
    bench_walk(&roots, 4);

    println!("== ② 引擎创建（含 tantivy 打开 + jieba 词典）");
    let t = Instant::now();
    let eng = EngineImpl::new(4, data.to_str().unwrap()).unwrap();
    println!("  [create] {:>7.1?}", t.elapsed().as_secs_f64() * 1000.0);

    println!("== ③ 全量扫描（首次建索引）");
    let d = scan(&eng, &roots, "full");
    println!("  [full  ] {:>7.1?}", d.as_secs_f64() * 1000.0);
    println!("  stats: {}", eng.stats_json());

    println!("== ④ 增量扫描 · 零变化（对标原版 <1s 场景）");
    let d = scan(&eng, &roots, "incremental");
    println!("  [inc-0 ] {:>7.1?}", d.as_secs_f64() * 1000.0);
    println!("  stats: {}", eng.stats_json());

    println!("== ⑤ 增量扫描 · 50 个文件变更");
    for i in 0..50 {
        let dir = root
            .join(format!("mdir_{}", i * 7 / 30))
            .join(format!("sub_{}", i % 5));
        std::fs::write(
            dir.join(format!("笔记_{}.txt", i * 7)),
            "变更后的新内容：性能基准增量写入测试。".repeat(20),
        )
        .unwrap();
    }
    let d = scan(&eng, &roots, "incremental");
    println!("  [inc-50] {:>7.1?}", d.as_secs_f64() * 1000.0);
    println!("  stats: {}", eng.stats_json());

    println!("== ⑥ 引擎重启（快照恢复 + tantivy 重开）");
    drop(eng);
    let t = Instant::now();
    let eng2 = EngineImpl::new(4, data.to_str().unwrap()).unwrap();
    println!("  [restore] {:>6.1?}", t.elapsed().as_secs_f64() * 1000.0);

    println!("== ⑦ 搜索（名称 + 全文混合查询）");
    for q in ["笔记", "报告", "全文检索", "不存在的词xyz"] {
        let t = Instant::now();
        let _ = eng2.search(q, 200).unwrap();
        println!(
            "  [search \"{q}\"] {:>7.1?}",
            t.elapsed().as_secs_f64() * 1000.0
        );
    }

    println!("== ⑧ 落盘（等待异步快照写完再退出，避免测到半程）");
    let d = scan(&eng2, &roots, "incremental");
    println!(
        "  [inc-final] {:>6.1?}（含收尾后快照异步落盘竞窗口）",
        d.as_secs_f64() * 1000.0
    );
}
