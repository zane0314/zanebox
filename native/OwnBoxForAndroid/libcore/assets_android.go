//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()

	extract := func(name string) {
		err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}

	extract(geoipDat)
	extract(geositeDat)
	extract(yacdDstFolder)
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
		} else {
			localVersion = string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if name != yacdDstFolder {
		input, err := asset.Open(apkPrefix + name + ".xz")
		if err != nil {
			return err
		}
		temp, err := os.CreateTemp(dir, ".asset-*.xz")
		if err != nil {
			input.Close()
			return err
		}
		tempName := temp.Name()
		temp.Close()
		defer os.Remove(tempName)
		if err = extractAsset(input, tempName); err != nil {
			return err
		}
		if err = Unxz(tempName, dstName); err != nil {
			return err
		}
	} else {
		input, err := asset.Open("yacd.zip")
		if err != nil {
			return err
		}
		staging, err := os.MkdirTemp(dir, ".yacd-*")
		if err != nil {
			input.Close()
			return err
		}
		preserveStaging := false
		defer func() {
			if !preserveStaging {
				os.RemoveAll(staging)
			}
		}()
		archive := filepath.Join(staging, "panel.zip")
		if err = extractAsset(input, archive); err != nil {
			return err
		}
		unpacked := filepath.Join(staging, "content")
		if err = Unzip(archive, unpacked); err != nil {
			return err
		}
		entries, err := os.ReadDir(unpacked)
		if err != nil {
			return err
		}
		if len(entries) != 1 || !entries[0].IsDir() {
			return fmt.Errorf("invalid bundled panel root")
		}
		old := filepath.Join(staging, "previous")
		exists := false
		if _, err = os.Stat(dstName); err == nil {
			if err = os.Rename(dstName, old); err != nil {
				return err
			}
			exists = true
		} else if !os.IsNotExist(err) {
			return err
		}
		if err = os.Rename(filepath.Join(unpacked, entries[0].Name()), dstName); err != nil {
			if exists {
				if rollback := os.Rename(old, dstName); rollback != nil {
					preserveStaging = true
					return fmt.Errorf("replace: %v; rollback: %w; previous panel retained at %s", err, rollback, old)
				}
			}
			return err
		}
	}
	marker, err := os.CreateTemp(dir, ".version-*")
	if err != nil {
		return err
	}
	defer os.Remove(marker.Name())
	if _, err = io.WriteString(marker, assetVersion); err != nil {
		marker.Close()
		return err
	}
	if err = marker.Close(); err != nil {
		return err
	}
	return os.Rename(marker.Name(), filepath.Join(dir, version))

}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
