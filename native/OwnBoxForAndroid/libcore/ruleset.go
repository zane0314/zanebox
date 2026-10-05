package libcore

// 官方内核的 local rule-set 只接受真实文件路径（.srs binary / .json source），
// 不认识 fork 私有的 "geoip:xxx" / "geosite:xxx" 伪路径。
//
// 本文件在 box.New 之前预处理配置中的 local rule-set：
//   - 官方格式（geoip-cn / geosite-cn，可带 .srs 后缀）：优先直接指向
//     <externalAssets>/geoip-cn.srs 等已存在的官方规则集文件；
//     文件不存在时回退到从本地 geoip.db / geosite.db 转换生成。
//   - 老 nb4a 格式（geoip:cn / geosite:cn）：兼容处理，从本地 db 转换生成 .srs。
//
// 生成的 .srs 缓存于 <externalAssets>/srs/，db 更新后自动重建。

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// parseGeoRuleSetPath 识别 rule-set path 中的 geo 引用，
// 返回规则代码、是否 geoip、是否老 nb4a 格式；非 geo 引用返回 ok=false。
func parseGeoRuleSetPath(path string) (code string, isGeoIP bool, legacy bool, ok bool) {
	// 老 nb4a 格式：geoip:cn / geosite:cn
	if rest, found := strings.CutPrefix(path, "geoip:"); found {
		return rest, true, true, rest != ""
	}
	if rest, found := strings.CutPrefix(path, "geosite:"); found {
		return rest, false, true, rest != ""
	}
	// 官方格式：geoip-cn(.srs) / geosite-cn(.srs)
	name := strings.TrimSuffix(filepath.Base(path), ".srs")
	if rest, found := strings.CutPrefix(name, "geoip-"); found {
		return rest, true, false, rest != ""
	}
	if rest, found := strings.CutPrefix(name, "geosite-"); found {
		return rest, false, false, rest != ""
	}
	return "", false, false, false
}

func prepareLocalGeoRuleSets(ruleSets []option.RuleSet) error {
	for i := range ruleSets {
		rs := &ruleSets[i]
		if rs.Type != C.RuleSetTypeLocal {
			continue
		}
		code, isGeoIP, legacy, ok := parseGeoRuleSetPath(rs.LocalOptions.Path)
		if !ok {
			continue
		}
		var dbName string
		if isGeoIP {
			dbName = geoipDat
		} else {
			dbName = geositeDat
		}

		// 官方格式优先：已存在的官方 .srs 文件直接使用
		if !legacy {
			officialPath := filepath.Join(externalAssetsPath, fmt.Sprintf("%s-%s.srs", dbName[:len(dbName)-3], code))
			if _, err := os.Stat(officialPath); err == nil {
				rs.LocalOptions.Path = officialPath
				continue
			}
		}

		tag := ""
		if len(rs.Tag) > 0 {
			tag = rs.Tag[0]
		}
		dstPath, err := convertGeoRuleSetToSRS(tag, code, filepath.Join(externalAssetsPath, dbName), isGeoIP)
		if err != nil {
			return fmt.Errorf("rule-set %v: %w", rs.Tag, err)
		}
		rs.LocalOptions.Path = dstPath
	}
	return nil
}

// ponytail: serialize infrequent Geo conversions; shard locks only if startup profiling requires it.
var geoConversionAccess sync.Mutex

// convertGeoRuleSetToSRS 从 geoip.db/geosite.db 提取指定代码的规则并生成 .srs 缓存文件。
func convertGeoRuleSetToSRS(tag string, code string, dbPath string, isGeoIP bool) (string, error) {
	geoConversionAccess.Lock()
	defer geoConversionAccess.Unlock()
	dir := filepath.Join(externalAssetsPath, "srs")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	key := sha256.Sum256([]byte(fmt.Sprintf("%s\x00%s\x00%t", dbPath, code, isGeoIP)))
	dst := filepath.Join(dir, fmt.Sprintf("%x.srs", key))

	// 缓存复用：.srs 比 db 新则无需重建
	if dbInfo, err := os.Stat(dbPath); err == nil {
		if srsInfo, err := os.Stat(dst); err == nil && srsInfo.ModTime().After(dbInfo.ModTime()) {
			return dst, nil
		}
	}

	var rules []option.HeadlessRule
	var err error
	if isGeoIP {
		rules, err = loadGeoIPRules(dbPath, code)
	} else {
		rules, err = loadGeoSiteRules(dbPath, code)
	}
	if err != nil {
		return "", err
	}

	file, err := os.CreateTemp(dir, ".rules-*.srs")
	if err != nil {
		return "", err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if err != nil {
		return "", err
	}
	if err = file.Sync(); err != nil {
		return "", err
	}
	if err = file.Close(); err != nil {
		return "", err
	}
	if err = os.Rename(file.Name(), dst); err != nil {
		return "", err
	}
	return dst, nil
}
