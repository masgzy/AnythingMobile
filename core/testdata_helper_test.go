package core

import (
	"embed"
	"os"
)

//go:embed testdata/sample_zh.pdf
var sampleZhPDF embed.FS

func testdataRead(name string) ([]byte, error) {
	return sampleZhPDF.ReadFile("testdata/" + name)
}

func writeFixtureFile(p string, data []byte) error {
	return os.WriteFile(p, data, 0o644)
}
