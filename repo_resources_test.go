package branchdam

import (
	"bufio"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// releaseManifestVersion is the version release-please last released, read
// from .release-please-manifest.json at the repository root.
func releaseManifestVersion(t *testing.T) string {
	t.Helper()
	raw, err := os.ReadFile(".release-please-manifest.json")
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	var manifest map[string]string
	if err := json.Unmarshal(raw, &manifest); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	v := manifest["."]
	if v == "" {
		t.Fatal(`manifest has no "." version`)
	}
	return v
}

// The engine's Version() is the client version sent to the server when a
// shell passes none. It must be the released version, not a stub.
func TestVersionMatchesReleaseManifest(t *testing.T) {
	if got, want := Version(), releaseManifestVersion(t); got != want {
		t.Fatalf("Version() = %q, release manifest says %q; release-please bumps both via extra-files", got, want)
	}
}

// iOS reads CFBundleShortVersionString for the client version and the
// Settings screen; it is pinned to the release rather than the
// never-bumped MARKETING_VERSION build setting.
func TestIOSBundleVersionMatchesReleaseManifest(t *testing.T) {
	raw, err := os.ReadFile("ios/BranchDAM/Info.plist")
	if err != nil {
		t.Fatalf("read Info.plist: %v", err)
	}
	m := regexp.MustCompile(`<key>CFBundleShortVersionString</key>\s*<string>([^<]*)</string>`).FindSubmatch(raw)
	if m == nil {
		t.Fatal("CFBundleShortVersionString not found in Info.plist")
	}
	if got, want := string(m[1]), releaseManifestVersion(t); got != want {
		t.Fatalf("CFBundleShortVersionString = %q, release manifest says %q", got, want)
	}
}

type androidResources struct {
	Strings []struct {
		Name  string `xml:"name,attr"`
		Value string `xml:",chardata"`
	} `xml:"string"`
	Plurals []struct {
		Name  string `xml:"name,attr"`
		Items []struct {
			Quantity string `xml:"quantity,attr"`
			Value    string `xml:",chardata"`
		} `xml:"item"`
	} `xml:"plurals"`
}

var formatSpec = regexp.MustCompile(`%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])`)

// checkAndroidFormat fails when a value has more than one format argument
// and any of them is not positional (%1$s): translators reorder arguments.
func checkAndroidFormat(t *testing.T, name, value string) {
	t.Helper()
	args, positional := 0, 0
	for _, m := range formatSpec.FindAllStringSubmatch(value, -1) {
		if m[2] == "%" {
			continue
		}
		args++
		if m[1] != "" {
			positional++
		}
	}
	if args > 1 && positional != args {
		t.Errorf("%s: %q has %d format arguments; use positional %%1$… forms", name, value, args)
	}
}

// TestAndroidStringResourcesAreWellFormed reads every values/strings*.xml
// file (strings.xml, strings_otg.xml, ...): aapt merges them into one
// table, so a name may not repeat across files either.
func TestAndroidStringResourcesAreWellFormed(t *testing.T) {
	files, err := filepath.Glob("android/app/src/main/res/values/strings*.xml")
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Fatal("no values/strings*.xml files found")
	}
	var res androidResources
	for _, file := range files {
		raw, err := os.ReadFile(file)
		if err != nil {
			t.Fatalf("read %s: %v", file, err)
		}
		var part androidResources
		if err := xml.Unmarshal(raw, &part); err != nil {
			t.Fatalf("parse %s: %v", file, err)
		}
		res.Strings = append(res.Strings, part.Strings...)
		res.Plurals = append(res.Plurals, part.Plurals...)
	}
	if len(res.Strings) < 100 {
		t.Fatalf("values/strings*.xml have only %d strings; were the UI strings extracted?", len(res.Strings))
	}
	seen := map[string]bool{}
	for _, s := range res.Strings {
		if seen[s.Name] {
			t.Errorf("duplicate resource name %q", s.Name)
		}
		seen[s.Name] = true
		checkAndroidFormat(t, s.Name, s.Value)
		// aapt rejects an unescaped apostrophe.
		if strings.Contains(strings.ReplaceAll(s.Value, `\'`, ""), "'") {
			t.Errorf("%s: unescaped apostrophe in %q", s.Name, s.Value)
		}
	}
	for _, p := range res.Plurals {
		if seen[p.Name] {
			t.Errorf("duplicate resource name %q", p.Name)
		}
		seen[p.Name] = true
		hasOther := false
		for _, item := range p.Items {
			hasOther = hasOther || item.Quantity == "other"
			checkAndroidFormat(t, p.Name+"/"+item.Quantity, item.Value)
		}
		if !hasOther {
			t.Errorf(`plurals %q has no quantity="other" item`, p.Name)
		}
	}
}

var iosStringsLine = regexp.MustCompile(`^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";$`)

func TestIOSLocalizableStringsAreWellFormed(t *testing.T) {
	f, err := os.Open("ios/BranchDAM/en.lproj/Localizable.strings")
	if err != nil {
		t.Fatalf("open Localizable.strings: %v", err)
	}
	defer f.Close()

	seen := map[string]bool{}
	inComment := false
	scanner := bufio.NewScanner(f)
	for n := 1; scanner.Scan(); n++ {
		line := strings.TrimSpace(scanner.Text())
		switch {
		case inComment:
			inComment = !strings.Contains(line, "*/")
			continue
		case line == "" || strings.HasPrefix(line, "//"):
			continue
		case strings.HasPrefix(line, "/*"):
			inComment = !strings.Contains(line, "*/")
			continue
		}
		m := iosStringsLine.FindStringSubmatch(line)
		if m == nil {
			t.Errorf("line %d is not `\"key\" = \"value\";`: %s", n, line)
			continue
		}
		if seen[m[1]] {
			t.Errorf("line %d: duplicate key %q", n, m[1])
		}
		seen[m[1]] = true
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if inComment {
		t.Error("unterminated comment")
	}
	if len(seen) < 100 {
		t.Fatalf("only %d keys; were the UI strings extracted?", len(seen))
	}
}

// plistValue is the subset of an XML property list a .stringsdict uses:
// a <string>, or a <dict> whose entries keep their file order.
type plistValue struct {
	str     string
	isDict  bool
	entries []plistEntry
}

type plistEntry struct {
	key string
	val plistValue
}

func (v plistValue) get(key string) (plistValue, bool) {
	for _, e := range v.entries {
		if e.key == key {
			return e.val, true
		}
	}
	return plistValue{}, false
}

// decodePlistValue reads the element [start] opened: a <string> or a
// <dict> of <key>/value pairs. Anything else is refused, which is all a
// .stringsdict needs.
func decodePlistValue(d *xml.Decoder, start xml.StartElement) (plistValue, error) {
	switch start.Name.Local {
	case "string":
		var s string
		if err := d.DecodeElement(&s, &start); err != nil {
			return plistValue{}, err
		}
		return plistValue{str: s}, nil
	case "dict":
		v := plistValue{isDict: true}
		seen := map[string]bool{}
		var key *string
		for {
			tok, err := d.Token()
			if err != nil {
				return v, err
			}
			switch t := tok.(type) {
			case xml.StartElement:
				if t.Name.Local == "key" {
					if key != nil {
						return v, fmt.Errorf("key %q has no value", *key)
					}
					var k string
					if err := d.DecodeElement(&k, &t); err != nil {
						return v, err
					}
					if seen[k] {
						return v, fmt.Errorf("duplicate key %q", k)
					}
					seen[k] = true
					key = &k
					continue
				}
				if key == nil {
					return v, fmt.Errorf("<%s> without a <key>", t.Name.Local)
				}
				child, err := decodePlistValue(d, t)
				if err != nil {
					return v, fmt.Errorf("%s: %w", *key, err)
				}
				v.entries = append(v.entries, plistEntry{key: *key, val: child})
				key = nil
			case xml.EndElement:
				if key != nil {
					return v, fmt.Errorf("key %q has no value", *key)
				}
				return v, nil
			}
		}
	default:
		return plistValue{}, fmt.Errorf("unsupported plist element <%s>", start.Name.Local)
	}
}

// readPlistRootDict returns the top-level <dict> of the plist at [path].
func readPlistRootDict(t *testing.T, path string) plistValue {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	d := xml.NewDecoder(f)
	inPlist := false
	for {
		tok, err := d.Token()
		if err == io.EOF {
			t.Fatalf("%s: no top-level <dict>", path)
		}
		if err != nil {
			t.Fatalf("parse %s: %v", path, err)
		}
		start, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		if !inPlist {
			if start.Name.Local != "plist" {
				t.Fatalf("%s: root element is <%s>, not <plist>", path, start.Name.Local)
			}
			inPlist = true
			continue
		}
		if start.Name.Local != "dict" {
			t.Fatalf("%s: <plist> holds <%s>, not <dict>", path, start.Name.Local)
		}
		root, err := decodePlistValue(d, start)
		if err != nil {
			t.Fatalf("parse %s: %v", path, err)
		}
		return root
	}
}

var stringsdictVariable = regexp.MustCompile(`%#@([A-Za-z0-9_]+)@`)

// TestIOSLocalizableStringsdictIsWellFormed checks the plural table the
// way Foundation reads it: every entry has an NSStringLocalizedFormatKey,
// every %#@variable@ it names is a plural rule with a value type and an
// "other" form, and no key is also in Localizable.strings (Foundation
// would use the .stringsdict entry and silently ignore the other).
func TestIOSLocalizableStringsdictIsWellFormed(t *testing.T) {
	root := readPlistRootDict(t, "ios/BranchDAM/en.lproj/Localizable.stringsdict")
	if len(root.entries) == 0 {
		t.Fatal("Localizable.stringsdict has no entries")
	}

	stringsKeys := map[string]bool{}
	raw, err := os.ReadFile("ios/BranchDAM/en.lproj/Localizable.strings")
	if err != nil {
		t.Fatalf("read Localizable.strings: %v", err)
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if m := iosStringsLine.FindStringSubmatch(strings.TrimSpace(line)); m != nil {
			stringsKeys[m[1]] = true
		}
	}

	for _, entry := range root.entries {
		name := entry.key
		if !entry.val.isDict {
			t.Errorf("%q: value is not a <dict>", name)
			continue
		}
		if stringsKeys[name] {
			t.Errorf("%q is in both Localizable.strings and Localizable.stringsdict", name)
		}
		format, ok := entry.val.get("NSStringLocalizedFormatKey")
		if !ok || format.isDict || format.str == "" {
			t.Errorf("%q: missing NSStringLocalizedFormatKey string", name)
			continue
		}
		vars := stringsdictVariable.FindAllStringSubmatch(format.str, -1)
		if len(vars) == 0 {
			t.Errorf("%q: NSStringLocalizedFormatKey %q names no %%#@variable@", name, format.str)
		}
		for _, m := range vars {
			rule, ok := entry.val.get(m[1])
			if !ok || !rule.isDict {
				t.Errorf("%q: variable %q has no <dict>", name, m[1])
				continue
			}
			if spec, _ := rule.get("NSStringFormatSpecTypeKey"); spec.str != "NSStringPluralRuleType" {
				t.Errorf("%q/%s: NSStringFormatSpecTypeKey is %q, want NSStringPluralRuleType", name, m[1], spec.str)
			}
			valueType, _ := rule.get("NSStringFormatValueTypeKey")
			if valueType.str == "" {
				t.Errorf("%q/%s: missing NSStringFormatValueTypeKey", name, m[1])
			}
			other, ok := rule.get("other")
			if !ok || other.str == "" {
				t.Errorf("%q/%s: no \"other\" form", name, m[1])
			}
			for _, form := range rule.entries {
				switch form.key {
				case "NSStringFormatSpecTypeKey", "NSStringFormatValueTypeKey":
				case "zero", "one", "two", "few", "many", "other":
					if form.val.isDict {
						t.Errorf("%q/%s/%s: form is not a <string>", name, m[1], form.key)
					}
				default:
					t.Errorf("%q/%s: unknown plural form %q", name, m[1], form.key)
				}
			}
		}
	}
}
