package branchdam

import (
	"bufio"
	"encoding/json"
	"encoding/xml"
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
