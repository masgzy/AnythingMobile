package core

import (
	"strings"
	"unicode/utf8"
)

// sanitizeText 清洗文档抽取出的正文，保证入库文本可安全渲染与搜索：
//   - 无效 UTF-8 字节直接丢弃（不产生 U+FFFD，避免 UI 出现"菱形问号"）；
//   - C0 控制字符中 \v \f 归一为换行（Word 系软换行/分页的常见来源），
//     其余（\x00-\x08、\x0E-\x1F）与 DEL、C1 控制符一并丢弃；
//   - 私有区 PUA（U+E000-U+F8FF，Wingdings/Symbol 等符号字体映射）
//     对搜索无意义且 Android 无字形，丢弃；
//   - 非字符（U+FDD0-U+FDEF 与各平面 U+xFFFE/U+xFFFF）丢弃；
//   - U+2028/U+2029 行/段分隔符归一为 \n。
//
// \t \n \r 与全部合法可打印字符（含 CJK/emoji）原样保留。
func sanitizeText(text string) string {
	if text == "" {
		return text
	}
	if needsSanitize(text) {
		return rebuildSanitize(text)
	}
	return text
}

// needsSanitize 快速扫描：文本是否含任何需要清洗的成分。
// 判定必须与 rebuildSanitize 的改写语义完全一致（\v \f 等按"需清洗"计）。
func needsSanitize(text string) bool {
	for i := 0; i < len(text); {
		c := text[i]
		if c < utf8.RuneSelf {
			// ASCII：控制符（除 \t \n \r）、\v \f、DEL
			if c < 0x20 && c != '\t' && c != '\n' && c != '\r' {
				return true
			}
			if c == 0x7F {
				return true
			}
			i++
			continue
		}
		r, size := utf8.DecodeRuneInString(text[i:])
		if r == utf8.RuneError && size <= 1 {
			return true // 无效字节
		}
		if r >= 0x80 && r <= 0x9F || // C1 控制符
			r == 0xFFFD || // 替换字符：解码残留，Android 渲染为菱形
			r >= 0xE000 && r <= 0xF8FF || // PUA 私有区
			r >= 0xFDD0 && r <= 0xFDEF || // 非字符区
			r&0xFFFE == 0xFFFE || // 各平面 xFFFE/xFFFF（含 xFFFD 不在此列，上已单独处理）
			r == 0x2028 || r == 0x2029 {
			return true
		}
		i += size
	}
	return false
}

// rebuildSanitize 逐 rune 重建清洗后的文本。
func rebuildSanitize(text string) string {
	var b strings.Builder
	b.Grow(len(text))
	for i := 0; i < len(text); {
		r, size := utf8.DecodeRuneInString(text[i:])
		if r == utf8.RuneError && size <= 1 {
			i++ // 无效字节：丢弃
			continue
		}
		switch {
		case r == '\v' || r == '\f' || r == 0x2028 || r == 0x2029:
			b.WriteByte('\n') // 软换行/分页/行段分隔 → 换行
		case r < 0x20 && r != '\t' && r != '\n' && r != '\r':
			// 其余 C0 控制：丢弃
		case r == 0x7F || r >= 0x80 && r <= 0x9F:
			// DEL / C1：丢弃
		case r == 0xFFFD ||
			r >= 0xE000 && r <= 0xF8FF,
			r >= 0xFDD0 && r <= 0xFDEF,
			r&0xFFFE == 0xFFFE:
			// 替换字符 / PUA / 非字符：丢弃
		default:
			b.WriteRune(r)
		}
		i += size
	}
	return b.String()
}

// normalizeForStore 入库前的统一清洗入口：归一各类换行变体后再做字符清洗。
// 返回的文本保证不含除 \t \n 外的任何控制字符，且是合法 UTF-8。
func normalizeForStore(text string) string {
	if strings.ContainsRune(text, '\r') {
		text = strings.ReplaceAll(text, "\r\n", "\n")
		text = strings.ReplaceAll(text, "\r", "\n")
	}
	return sanitizeText(text)
}
