// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

//! An example jclaw WebAssembly extension.
//!
//! It takes `{"path": "<workspace-relative path>"}`, reads that file through the host, counts its
//! lines, words and bytes, and answers with JSON.
//!
//! `no_std` on purpose. jclaw's calling convention hands the module a pointer and a length and
//! expects a pointer and a length back, so the module owns its own memory either way — and
//! without `std` there is nothing between the source and the ABI, which is the part worth
//! reading. It also keeps the module a few kilobytes rather than a few hundred, and every
//! instruction the module does not execute is instruction budget it does not spend.

#![no_std]

use core::panic::PanicInfo;
use core::ptr::addr_of_mut;

/// A panic traps the instance. The lane turns that into `module_trapped` — a value, not an
/// exception, because a lane that throws would be a lane the capability host cannot report on.
#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

// --- The host functions this module imports -------------------------------------------------
//
// The import module name must be `jclaw`, and each of these must be listed in the package
// manifest's `permissions`. The host supplies only what was granted, so importing one that was
// not granted does not fail at the call — the module fails to instantiate and never runs at all.
// That is the whole enforcement mechanism: an ungranted capability is not a function that
// refuses, it is a name that does not resolve.
#[link(wasm_import_module = "jclaw")]
extern "C" {
    /// Appends to the call's output, which is prepended to the JSON result.
    fn log(ptr: *const u8, len: usize);

    /// Reads a workspace file into `out`, returning its length, or -1 when the workspace guard
    /// refuses, the file does not exist, or it does not fit in the buffer offered.
    fn read_file(path_ptr: *const u8, path_len: usize, out_ptr: *mut u8, out_cap: usize) -> i32;
}

// --- Memory ---------------------------------------------------------------------------------
//
// A bump allocator over one static buffer. There is no free: a module is instantiated per call
// and thrown away, so the only lifetime that exists is the call itself. That is also why a tool
// call can never leave state for the next one.

const HEAP_BYTES: usize = 256 * 1024;
static mut HEAP: [u8; HEAP_BYTES] = [0; HEAP_BYTES];
static mut BUMP: usize = 0;

fn alloc(len: usize) -> *mut u8 {
    unsafe {
        let base = addr_of_mut!(HEAP) as *mut u8;
        let offset = BUMP;
        if offset + len > HEAP_BYTES {
            // Out of room. Returning null makes the host report module_allocation_invalid
            // rather than letting the module scribble past its own buffer.
            return core::ptr::null_mut();
        }
        BUMP = offset + len;
        base.add(offset)
    }
}

/// The host calls this first, to place the JSON arguments in the module's memory.
///
/// The returned pointer must be greater than zero and the region must lie inside the memory the
/// spec allows; the host checks both before writing.
#[no_mangle]
pub extern "C" fn jclaw_alloc(len: i32) -> i32 {
    if len < 0 {
        return 0;
    }
    alloc(len as usize) as i32
}

/// The entry point. Arguments are UTF-8 JSON at `ptr`; the answer is UTF-8 JSON whose pointer
/// goes in the high half of the result and whose length goes in the low half.
#[no_mangle]
pub extern "C" fn jclaw_call(ptr: i32, len: i32) -> i64 {
    if ptr <= 0 || len < 0 {
        return pack(core::ptr::null(), 0);
    }
    let args = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };

    let path = match json_string(args, b"path") {
        Some(p) if !p.is_empty() => p,
        _ => return reply_error(b"wordcount: expected {\"path\": \"...\"}"),
    };

    // Ask the host for the file. Everything about whether this is allowed — the workspace root,
    // symlink resolution, the path escaping its bounds — is decided on the other side of this
    // call. The module cannot widen it; it can only be told no.
    let capacity = HEAP_BYTES - unsafe { BUMP } - 1024; // leave room for the answer
    let buffer = alloc(capacity);
    if buffer.is_null() {
        return reply_error(b"wordcount: no room to read the file");
    }
    let read = unsafe { read_file(path.as_ptr(), path.len(), buffer, capacity) };
    if read < 0 {
        return reply_error(b"wordcount: the host would not read that path");
    }
    let content = unsafe { core::slice::from_raw_parts(buffer, read as usize) };

    let (lines, words) = count(content);

    // Host output arrives before the JSON result, separated by a newline. Useful for saying what
    // happened; the result is what the model parses.
    let mut note = Writer::new();
    note.put(b"wordcount: read ");
    note.number(read as u64);
    note.put(b" bytes from ");
    note.put(path);
    unsafe { log(note.as_ptr(), note.len()) };

    let mut out = Writer::new();
    out.put(b"{\"path\":\"");
    out.put_escaped(path);
    out.put(b"\",\"lines\":");
    out.number(lines);
    out.put(b",\"words\":");
    out.number(words);
    out.put(b",\"bytes\":");
    out.number(read as u64);
    out.put(b"}");
    pack(out.as_ptr(), out.len())
}

// --- Counting -------------------------------------------------------------------------------

/// Lines and words, counting the way `wc` does: a final line without a newline still counts, and
/// a word is a run of non-whitespace.
fn count(content: &[u8]) -> (u64, u64) {
    let mut lines = 0u64;
    let mut words = 0u64;
    let mut in_word = false;
    for &byte in content {
        if byte == b'\n' {
            lines += 1;
        }
        let space = byte == b' ' || byte == b'\t' || byte == b'\n' || byte == b'\r';
        if space {
            in_word = false;
        } else if !in_word {
            in_word = true;
            words += 1;
        }
    }
    if !content.is_empty() && content[content.len() - 1] != b'\n' {
        lines += 1;
    }
    (lines, words)
}

// --- A very small amount of JSON ------------------------------------------------------------

/// Finds a top-level string field. Not a JSON parser: it looks for `"key"`, skips to the value,
/// and reads a quoted string, handling `\"` and `\\` only.
///
/// A real extension would use a JSON crate. This one does not, so that the whole module stays
/// readable in one sitting and the example is about the integration rather than about parsing.
fn json_string<'a>(json: &'a [u8], key: &[u8]) -> Option<&'a [u8]> {
    let mut i = 0;
    while i + key.len() + 2 <= json.len() {
        if json[i] == b'"' && json[i + 1..].starts_with(key) && json[i + 1 + key.len()] == b'"' {
            let mut j = i + key.len() + 2;
            while j < json.len() && (json[j] == b' ' || json[j] == b':') {
                j += 1;
            }
            if j >= json.len() || json[j] != b'"' {
                return None;
            }
            j += 1;
            let start = j;
            while j < json.len() && json[j] != b'"' {
                if json[j] == b'\\' {
                    j += 1;
                }
                j += 1;
            }
            return if j <= json.len() { Some(&json[start..j]) } else { None };
        }
        i += 1;
    }
    None
}

// --- Building the answer ---------------------------------------------------------------------

/// Appends bytes into freshly bumped memory. The buffer is claimed once and grown by writing,
/// which works only because nothing else allocates while a Writer is alive — true here, and the
/// reason this stays a private detail of the example.
struct Writer {
    start: *mut u8,
    len: usize,
}

impl Writer {
    fn new() -> Self {
        Writer { start: alloc(0), len: 0 }
    }

    fn put(&mut self, bytes: &[u8]) {
        let destination = alloc(bytes.len());
        if destination.is_null() {
            return;
        }
        unsafe { core::ptr::copy_nonoverlapping(bytes.as_ptr(), destination, bytes.len()) };
        self.len += bytes.len();
    }

    /// Enough JSON string escaping for a filesystem path.
    fn put_escaped(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            match byte {
                b'"' => self.put(b"\\\""),
                b'\\' => self.put(b"\\\\"),
                _ => self.put(&[byte]),
            }
        }
    }

    fn number(&mut self, mut value: u64) {
        let mut digits = [0u8; 20];
        let mut at = digits.len();
        loop {
            at -= 1;
            digits[at] = b'0' + (value % 10) as u8;
            value /= 10;
            if value == 0 {
                break;
            }
        }
        self.put(&digits[at..]);
    }

    fn as_ptr(&self) -> *const u8 {
        self.start
    }

    fn len(&self) -> usize {
        self.len
    }
}

fn reply_error(message: &[u8]) -> i64 {
    let mut out = Writer::new();
    out.put(b"{\"error\":\"");
    out.put_escaped(message);
    out.put(b"\"}");
    pack(out.as_ptr(), out.len())
}

/// The result convention: pointer in the high half, length in the low half.
fn pack(pointer: *const u8, len: usize) -> i64 {
    ((pointer as u32 as i64) << 32) | (len as u32 as i64)
}
