// Package contracttest checks this app's agent-API DTOs against a pinned
// copy of branchDAM's contract fixtures (core/client/testdata/contract,
// copied from the server's docs/contract/fixtures by `make contract-sync`).
// Each server fixture sets every field the server reads or writes, so a
// DTO that drops, renames, retypes or invents a field shows up here
// instead of in production. Imported by tests only.
package contracttest

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"sort"
	"strings"
	"testing"
)

// Dir is the pinned fixture directory.
func Dir() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "..", "client", "testdata", "contract")
}

// Read returns one pinned fixture.
func Read(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(Dir(), name+".json"))
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return b
}

// Stems lists every pinned fixture.
func Stems(t *testing.T) []string {
	t.Helper()
	entries, err := os.ReadDir(Dir())
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, e := range entries {
		if stem, ok := strings.CutSuffix(e.Name(), ".json"); ok {
			out = append(out, stem)
		}
	}
	sort.Strings(out)
	return out
}

// Case maps one fixture to the DTO the app uses for it.
//
// NotModelled lists fixture fields the DTO deliberately lacks; ClientOnly
// lists DTO fields the server never sends or reads. Both are JSON paths
// ("a.b", "list[].field") mapped to the reason. Anything else that differs
// is drift.
type Case struct {
	DTO         any // pointer to a zero DTO
	NotModelled map[string]string
	ClientOnly  map[string]string
}

// RoundTrip decodes the fixture (minus NotModelled) into c.DTO with no
// unknown field, re-encodes it, and requires the same JSON; then requires
// every JSON field the DTO declares to be in the fixture or ClientOnly.
func RoundTrip(t *testing.T, name string, c Case) {
	t.Helper()
	var want any
	if err := json.Unmarshal(Read(t, name), &want); err != nil {
		t.Fatal(err)
	}
	for path := range c.NotModelled {
		dropPath(want, strings.Split(path, "."))
	}
	stripped, err := json.Marshal(want)
	if err != nil {
		t.Fatal(err)
	}
	dec := json.NewDecoder(bytes.NewReader(stripped))
	dec.DisallowUnknownFields()
	if err := dec.Decode(c.DTO); err != nil {
		t.Fatalf("%s: server fixture has a field %T does not model (add it, or list it in NotModelled with a reason): %v", name, c.DTO, err)
	}
	again, err := json.Marshal(c.DTO)
	if err != nil {
		t.Fatal(err)
	}
	var got any
	if err := json.Unmarshal(again, &got); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("%s: %T does not round-trip the server fixture:\n got  %s\n want %s", name, c.DTO, again, stripped)
	}

	var full any
	if err := json.Unmarshal(Read(t, name), &full); err != nil {
		t.Fatal(err)
	}
	for _, path := range declaredPaths(reflect.TypeOf(c.DTO), "") {
		if _, ok := c.ClientOnly[path]; ok {
			continue
		}
		if !hasPath(full, strings.Split(path, ".")) {
			t.Errorf("%s: %T declares %q, which the server fixture does not have (drift, or list it in ClientOnly with a reason)", name, c.DTO, path)
		}
	}
}

// declaredPaths lists the JSON field paths a DTO type declares, descending
// into nested structs and slices of structs.
func declaredPaths(typ reflect.Type, prefix string) []string {
	for typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	if typ.Kind() != reflect.Struct {
		return nil
	}
	var out []string
	for i := 0; i < typ.NumField(); i++ {
		f := typ.Field(i)
		if !f.IsExported() {
			continue
		}
		tag := strings.Split(f.Tag.Get("json"), ",")[0]
		if tag == "-" {
			continue
		}
		if f.Anonymous && tag == "" {
			out = append(out, declaredPaths(f.Type, prefix)...)
			continue
		}
		if tag == "" {
			tag = f.Name
		}
		path := prefix + tag
		out = append(out, path)
		ft := f.Type
		for ft.Kind() == reflect.Pointer {
			ft = ft.Elem()
		}
		switch {
		case ft.Kind() == reflect.Struct:
			out = append(out, declaredPaths(ft, path+".")...)
		case ft.Kind() == reflect.Slice && ft.Elem().Kind() == reflect.Struct:
			out = append(out, declaredPaths(ft.Elem(), path+"[].")...)
		}
	}
	return out
}

func hasPath(v any, path []string) bool {
	if len(path) == 0 {
		return true
	}
	key, isList := strings.CutSuffix(path[0], "[]")
	obj, ok := v.(map[string]any)
	if !ok {
		return false
	}
	next, ok := obj[key]
	if !ok {
		return false
	}
	if isList {
		list, ok := next.([]any)
		if !ok || len(list) == 0 {
			return false
		}
		return hasPath(list[0], path[1:])
	}
	return hasPath(next, path[1:])
}

func dropPath(v any, path []string) {
	if len(path) == 0 {
		return
	}
	key, isList := strings.CutSuffix(path[0], "[]")
	obj, ok := v.(map[string]any)
	if !ok {
		return
	}
	if len(path) == 1 && !isList {
		delete(obj, key)
		return
	}
	if isList {
		if list, ok := obj[key].([]any); ok {
			for _, el := range list {
				dropPath(el, path[1:])
			}
		}
		return
	}
	dropPath(obj[key], path[1:])
}
