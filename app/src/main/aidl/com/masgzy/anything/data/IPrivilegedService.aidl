package com.masgzy.anything.data;

import android.os.ParcelFileDescriptor;

// Shizuku / Stellar 用户服务接口（运行于 shell/root 特权进程）。
//
// 设计说明：
//  - 事务码必须显式指定：destroy 的 16777114 是 Shizuku 服务端约定的
//    "销毁用户服务"事务码，其余方法用 1/2 避免排序变化破坏兼容；
//  - 大输出（find 可能数万行）绝不直接经 binder 返回 —— 事务上限 1MB，
//    而是通过调用方持有的 ParcelFileDescriptor 直接写入应用私有缓存文件，
//    既有 O(1) 内存又有任意大小承载能力；
//  - 返回值为进程退出码（0=成功），输出内容一律走 fd。

interface IPrivilegedService {

    /**
     * 枚举 path 树下的文件/目录清单写入 fd（find -type，每行一个路径）。
     * @param type find -type 参数："f"=文件，"d"=目录
     * @return find 进程退出码，0=成功
     */
    int findFiles(String path, String type, in ParcelFileDescriptor fd) = 1;

    /**
     * 把 path 指向的文件内容（cat）写入 fd —— 打开/分享 Android/data
     * 内文件时，由调用方先导出为应用可读的缓存副本。
     * @return cat 进程退出码，0=成功
     */
    int catFile(String path, in ParcelFileDescriptor fd) = 2;

    /** Shizuku 服务端约定的销毁事务（16777114），实现中必须退出进程。 */
    void destroy() = 16777114;
}
