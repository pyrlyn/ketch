// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Every TOML file ketch owns is read, written and described here.
//!
//! `config.toml`, the registry's metadata, package files and `ketch.lock`
//! each have Rust types; this module turns text into those types and back, and
//! publishes their JSON Schemas. Keeping the `toml` crate behind one module
//! means one answer to how a parse error names its file and how a file is
//! rendered, and one place to look when the format or the crate changes.
//!
//! A user manifest is also edited in place, and that edit uses `toml_edit`
//! ([`EditDocument`]) because only it renders back what it did not change.

use crate::error::{Error, Result};
use serde::de::DeserializeOwned;
use serde::Serialize;
#[cfg(test)]
use std::path::Path;

/// Parse `text` into `T`. `what` names the file in the error, so a user can
/// find the line serde complains about.
pub(crate) fn parse<T: DeserializeOwned>(text: &str, what: impl Into<String>) -> Result<T> {
    toml::from_str(text).map_err(|e| Error::parse(what, e.to_string()))
}

/// Render `value` as a TOML document, tables expanded and arrays one item per
/// line where the value is long enough to need it.
pub(crate) fn render<T: Serialize>(value: &T, what: impl Into<String>) -> Result<String> {
    toml::to_string_pretty(value).map_err(|e| Error::parse(what, e.to_string()))
}

/// Parse `text` as TOML and return it as a JSON value, for validating a file
/// against a JSON Schema or handing it to code that works on `serde_json`.
/// `what` names the file in either step's error.
pub(crate) fn to_json(text: &str, what: impl Into<String>) -> Result<serde_json::Value> {
    let what = what.into();
    let parsed: toml::Value = parse(text, what.as_str())?;
    serde_json::to_value(parsed).map_err(|e| Error::parse(what, e.to_string()))
}

/// A quoted, escaped TOML string.
///
/// Built by rendering a `toml::Value` so escaping is never hand-rolled: one
/// writer, one answer to what quotes, backslashes and control bytes mean.
pub(crate) fn string_literal(text: &str) -> String {
    toml::Value::String(text.to_string()).to_string()
}

/// A TOML array of strings, escaped the same way [`string_literal`] escapes.
pub(crate) fn string_array_literal(items: &[String]) -> String {
    toml::Value::Array(
        items
            .iter()
            .map(|i| toml::Value::String(i.clone()))
            .collect(),
    )
    .to_string()
}

/// A parsed TOML document whose keys a caller reads, or fills in, before it
/// becomes a typed value.
///
/// A registry folder's `ketch.toml` may leave `name` out, because the folder
/// supplies it; serde cannot see the folder, so the key is added to the
/// document first and the result deserialized as if the file had said it.
pub(crate) struct Document {
    table: toml::Table,
    what: String,
}

impl Document {
    /// Parse `text`; `what` names the file in this and every later error.
    pub(crate) fn parse(text: &str, what: impl Into<String>) -> Result<Self> {
        let what = what.into();
        // A TOML document is a table by definition, so parsing into one is
        // the whole shape check; no other top level can come back.
        let table = parse(text, what.as_str())?;
        Ok(Self { table, what })
    }

    /// The top-level `key`, when it is a string.
    pub(crate) fn str(&self, key: &str) -> Option<&str> {
        self.table.get(key).and_then(toml::Value::as_str)
    }

    /// Set the top-level `key` to the string `value`.
    pub(crate) fn set_str(&mut self, key: &str, value: &str) {
        self.table
            .insert(key.to_string(), toml::Value::String(value.to_string()));
    }

    /// Whether the top-level `key` holds an array.
    pub(crate) fn is_array(&self, key: &str) -> bool {
        self.table.get(key).is_some_and(toml::Value::is_array)
    }

    /// Deserialize the document, keys added or not, into `T`.
    pub(crate) fn deserialize<T: DeserializeOwned>(self) -> Result<T> {
        T::deserialize(toml::Value::Table(self.table))
            .map_err(|e| Error::parse(self.what, e.to_string()))
    }
}

/// A TOML file a key is inserted into while everything else — comments, key
/// order, spacing — renders back exactly as it was read.
///
/// The file is the user's, so a round trip through serde, which keeps only
/// the values, would be a rewrite of what they wrote.
pub(crate) struct EditDocument {
    doc: toml_edit::DocumentMut,
}

/// One table of an [`EditDocument`], borrowed for an edit.
pub(crate) struct EditTable<'a> {
    table: &'a mut toml_edit::Table,
}

impl EditDocument {
    /// Parse `text`; `what` names the file in the error.
    pub(crate) fn parse(text: &str, what: &str) -> Result<Self> {
        let doc = text
            .parse()
            .map_err(|e: toml_edit::TomlError| Error::parse(what, e.to_string()))?;
        Ok(Self { doc })
    }

    /// The table describing one package: the whole document when it is a
    /// single manifest, or else the `[[package]]` entry whose `name`
    /// `is_package` accepts. `None` when no entry does.
    pub(crate) fn package_table(
        &mut self,
        is_package: impl Fn(&str) -> bool,
    ) -> Option<EditTable<'_>> {
        if !self
            .doc
            .get("package")
            .is_some_and(toml_edit::Item::is_array_of_tables)
        {
            return Some(EditTable {
                table: self.doc.as_table_mut(),
            });
        }
        self.doc
            .get_mut("package")?
            .as_array_of_tables_mut()?
            .iter_mut()
            .find(|t| {
                t.get("name")
                    .and_then(toml_edit::Item::as_str)
                    .is_some_and(&is_package)
            })
            .map(|table| EditTable { table })
    }

    /// The document as text, edits included.
    pub(crate) fn render(&self) -> String {
        self.doc.to_string()
    }
}

impl EditTable<'_> {
    /// Whether the table already has `key`, whatever its value.
    pub(crate) fn contains_key(&self, key: &str) -> bool {
        self.table.contains_key(key)
    }

    /// Set `key` to `[{ <field> = "<value>" }, …]`, one inline table per
    /// value, in order.
    pub(crate) fn set_inline_tables(&mut self, key: &str, field: &str, values: &[String]) {
        let mut list = toml_edit::Array::new();
        for value in values {
            let mut entry = toml_edit::InlineTable::new();
            entry.insert(field, value.as_str().into());
            list.push(entry);
        }
        self.table.insert(key, toml_edit::value(list));
    }
}

/// Fails when the JSON Schema committed at `relative` (from the repository
/// root) is not what `T` generates. `KETCH_BLESS=1` rewrites the file
/// instead: the types are the source, the file only publishes them.
///
/// The schema files stay next to the other docs, while this crate's manifest
/// is `crates/ketch-core`, so the repository root is two directories up.
#[cfg(test)]
pub(crate) fn assert_schema_current<T: schemars::JsonSchema>(relative: &str) {
    // TOML has no null: an absent key is how an `Option` says `None`, so a
    // schema allowing `null`, or offering it as a default an editor fills
    // in, would describe a file ketch cannot read.
    let drop_null = schemars::transform::RecursiveTransform(|s: &mut schemars::Schema| {
        if s.get("default").is_some_and(serde_json::Value::is_null) {
            s.remove("default");
        }
        if let Some(serde_json::Value::Array(types)) = s.get_mut("type") {
            types.retain(|t| t != "null");
            if let [only] = types.as_slice() {
                let only = only.clone();
                s.insert("type".into(), only);
            }
        }
    });
    let mut schema = schemars::generate::SchemaSettings::draft2020_12()
        .with_transform(drop_null)
        .into_generator()
        .into_root_schema_for::<T>();
    schema.insert(
        "$comment".into(),
        "Generated from the Rust types by `KETCH_BLESS=1 cargo nextest run schema`. Do not edit."
            .into(),
    );
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(relative);
    let rendered = serde_json::to_string_pretty(&schema).expect("render schema") + "\n";
    if std::env::var_os("KETCH_BLESS").is_some() {
        std::fs::write(&path, &rendered).expect("write schema");
        return;
    }
    // A Windows checkout may have turned LF into CRLF; the schema is the same.
    let committed = std::fs::read_to_string(&path)
        .unwrap_or_default()
        .replace("\r\n", "\n");
    pretty_assertions::assert_eq!(
        committed,
        rendered,
        "{relative} is stale; regenerate it with KETCH_BLESS=1 cargo nextest run schema"
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;
    use serde::Deserialize;

    #[derive(Debug, PartialEq, Serialize, Deserialize)]
    struct Sample {
        name: String,
        tags: Vec<String>,
    }

    #[test]
    fn a_parse_error_names_the_file() {
        let err = parse::<Sample>("name = ", "/root/config.toml").unwrap_err();
        assert!(err.to_string().contains("/root/config.toml"), "{err}");
    }

    #[test]
    fn rendered_text_parses_back_to_the_same_value() {
        let sample = Sample {
            name: "rg \"quoted\"".to_string(),
            tags: vec!["a".to_string(), "b".to_string()],
        };
        let text = render(&sample, "sample").unwrap();
        assert_eq!(parse::<Sample>(&text, "sample").unwrap(), sample);
    }

    #[test]
    fn a_key_set_on_a_document_is_deserialized_as_if_the_file_had_it() {
        let mut doc = Document::parse("tags = [\"a\"]\n", "sample").unwrap();
        assert_eq!(doc.str("name"), None);
        doc.set_str("name", "rg");
        assert_eq!(doc.str("name"), Some("rg"));
        let sample: Sample = doc.deserialize().unwrap();
        assert_eq!(sample.name, "rg");
        assert_eq!(sample.tags, vec!["a".to_string()]);
    }

    #[test]
    fn a_key_that_is_not_a_string_reads_as_absent() {
        let doc = Document::parse("name = 1\n", "sample").unwrap();
        assert_eq!(doc.str("name"), None);
    }

    #[test]
    fn a_document_that_does_not_fit_the_type_names_the_file() {
        let doc = Document::parse("name = \"rg\"\n", "/r/rg/ketch.toml").unwrap();
        let err = doc.deserialize::<Sample>().unwrap_err();
        assert!(err.to_string().contains("/r/rg/ketch.toml"), "{err}");
    }

    #[test]
    fn a_document_tells_an_array_from_any_other_value() {
        let doc = Document::parse("tags = [\"a\"]\nname = \"rg\"\n", "sample").unwrap();
        assert!(doc.is_array("tags"));
        assert!(!doc.is_array("name"));
        assert!(!doc.is_array("missing"));
    }

    #[test]
    fn an_edit_error_names_the_file() {
        let err = EditDocument::parse("name = ", "/root/rg.toml")
            .err()
            .expect("unfinished key must not parse");
        assert!(err.to_string().contains("/root/rg.toml"), "{err}");
    }

    #[test]
    fn an_inserted_key_leaves_every_other_byte_as_it_was() {
        let body = concat!(
            "# kept\n",
            "[[package]]\n",
            "source = \"github:o/a\"  # before name, on purpose\n",
            "name = \"a\"\n",
            "\n",
            "[[package]]\n",
            "name   = \"b\"\n",
            "source = \"github:o/b\"\n",
        );
        let mut doc = EditDocument::parse(body, "sample").unwrap();
        let mut table = doc.package_table(|n| n == "b").unwrap();
        assert!(!table.contains_key("bin"));
        table.set_inline_tables("bin", "name", &["x".to_string(), "y".to_string()]);
        assert!(table.contains_key("bin"));
        let expected = format!("{body}bin = [{{ name = \"x\" }}, {{ name = \"y\" }}]\n");
        assert_eq!(doc.render(), expected);
    }

    #[test]
    fn a_single_manifest_is_its_own_package_table() {
        let mut doc = EditDocument::parse("name = \"a\"\n", "sample").unwrap();
        assert!(doc.package_table(|_| false).is_some());
    }

    #[test]
    fn a_package_array_without_the_name_has_no_package_table() {
        let mut doc = EditDocument::parse("[[package]]\nname = \"a\"\n", "sample").unwrap();
        assert!(doc.package_table(|n| n == "b").is_none());
    }

    #[test]
    fn toml_text_becomes_the_same_json_value() {
        let json = to_json("name = \"rg\"\ntags = [\"a\", \"b\"]\n", "sample").unwrap();
        assert_eq!(json, serde_json::json!({"name": "rg", "tags": ["a", "b"]}));
    }

    #[test]
    fn invalid_toml_names_the_file_when_converted_to_json() {
        let err = to_json("name = ", "/p/ketch.toml").unwrap_err();
        assert!(err.to_string().contains("/p/ketch.toml"), "{err}");
    }

    #[test]
    fn a_string_literal_keeps_quotes_and_backslashes_recoverable() {
        // The writer may choose a literal string over an escaped one; what
        // matters is that the reader gets the same text back.
        for text in ["say \"hi\"", "C:\\bin", "both \" and \\ and '"] {
            let doc = format!("v = {}", string_literal(text));
            let parsed: toml::Table = parse(&doc, "sample").unwrap();
            assert_eq!(parsed["v"].as_str(), Some(text), "{doc}");
        }
        assert_eq!(string_literal("plain"), "\"plain\"");
    }

    #[test]
    fn a_string_literal_escapes_control_bytes_on_one_line() {
        let text = "a\u{1}b\u{7f}c\td";
        let rendered = string_literal(text);
        assert_eq!(rendered, "\"a\\u0001b\\u007Fc\\td\"");
        assert!(!rendered.contains('\n'));
    }

    #[test]
    fn a_string_literal_parses_back_to_the_same_text() {
        let text = "q\" b\\ n\n t\t \u{0} é";
        let doc = format!("v = {}", string_literal(text));
        let parsed: toml::Table = parse(&doc, "sample").unwrap();
        assert_eq!(parsed["v"].as_str(), Some(text));
    }

    #[test]
    fn a_string_array_literal_renders_each_item_and_parses_back() {
        assert_eq!(string_array_literal(&[]), "[]");
        let items = vec!["a".to_string(), "b\"c".to_string(), "d\\e".to_string()];
        let rendered = string_array_literal(&items);
        let parsed: toml::Table = parse(&format!("v = {rendered}"), "sample").unwrap();
        let back: Vec<&str> = parsed["v"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(toml::Value::as_str)
            .collect();
        assert_eq!(back, ["a", "b\"c", "d\\e"]);
    }

    /// Whether `line` names the `toml` or `toml_edit` crate. Whole identifiers
    /// only: `toml_file`, `ketch.toml` and a test called `reads_toml` are not
    /// the crate, and a substring search would fail on every one of them.
    fn names_a_toml_crate(line: &str) -> bool {
        let is_ident = |c: char| c.is_alphanumeric() || c == '_';
        line.match_indices("toml").any(|(at, _)| {
            let before = &line[..at];
            let after = &line[at + "toml".len()..];
            // A `.` before it is a file name such as `ketch.toml`.
            if before.ends_with(|c: char| is_ident(c) || c == '.') {
                return false;
            }
            if after.starts_with("::") {
                return true;
            }
            if let Some(rest) = after.strip_prefix("_edit") {
                return !rest.starts_with(is_ident);
            }
            let imports = before
                .trim_end()
                .strip_suffix("use")
                .is_some_and(|b| !b.ends_with(is_ident));
            imports && !after.starts_with(is_ident)
        })
    }

    #[test]
    fn the_scan_tells_the_crate_from_other_uses_of_the_word() {
        for line in [
            "    toml::from_str(text)",
            "let t: toml::Table = x;",
            "use toml;",
            "pub use toml::Value;",
            "use toml_edit::DocumentMut;",
            "toml_edit::Item::None",
        ] {
            assert!(names_a_toml_crate(line), "{line}");
        }
        for line in [
            "use crate::toml_file;",
            "toml_file::parse(text, what)",
            "let path = root.join(\"ketch.toml\");",
            "fn reads_toml() {}",
            "// a toml file",
            "let registry_toml = 1;",
            "use tomlish::x;",
        ] {
            assert!(!names_a_toml_crate(line), "{line}");
        }
    }

    fn rust_files(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                rust_files(&path, out);
            } else if path.extension().is_some_and(|e| e == "rs") {
                out.push(path);
            }
        }
    }

    /// `tests/` and `fuzz/` are left out: they write fixtures and are separate
    /// crates, not part of the code the guard protects.
    #[test]
    fn only_the_owner_module_names_the_toml_crates() {
        // Canonical, so the owner is recognised however the paths were spelled.
        let core = Path::new(env!("CARGO_MANIFEST_DIR"))
            .canonicalize()
            .expect("crate directory");
        let root = core.join("../..").canonicalize().expect("repository root");
        let mut sources = Vec::new();
        rust_files(&root.join("src"), &mut sources);
        let crates = std::fs::read_dir(root.join("crates")).expect("list crates");
        for krate in crates.flatten() {
            rust_files(&krate.path().join("src"), &mut sources);
        }
        let owner = core.join("src/toml_file.rs");
        assert!(
            sources.iter().any(|p| p == &owner),
            "the scan misses its own crate"
        );

        let mut offenders = Vec::new();
        for path in sources.iter().filter(|p| **p != owner) {
            let text = std::fs::read_to_string(path).expect("read source");
            for (n, line) in text.lines().enumerate() {
                if names_a_toml_crate(line) {
                    let shown = path.strip_prefix(&root).unwrap_or(path);
                    offenders.push(format!("{}:{}: {}", shown.display(), n + 1, line.trim()));
                }
            }
        }
        assert!(
            offenders.is_empty(),
            "only toml_file.rs may use the toml crates; go through it instead:\n{}",
            offenders.join("\n")
        );
    }
}
