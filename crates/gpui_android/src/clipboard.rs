//! Android's clipboard, through `MainActivity.readClipboardText` and
//! `writeClipboardText`.
//!
//! `ClipboardItem` can carry images and structured entries, but only text
//! round-trips here. Zed reads the clipboard often, such as to enable Paste in
//! menus, and each read is a binder call, so a read within [`CACHE_TTL`] of the
//! last one reuses it.

use std::cell::RefCell;
use std::time::{Duration, Instant};

use android_activity::AndroidApp;
use anyhow::Result;
use gpui::ClipboardItem;
use jni::objects::JString;

const CACHE_TTL: Duration = Duration::from_millis(50);

thread_local! {
    static READ_CACHE: RefCell<Option<(Instant, Option<String>)>> = const { RefCell::new(None) };
}

pub(crate) fn read(android_app: &AndroidApp) -> Option<ClipboardItem> {
    let cached = READ_CACHE.with_borrow(|cache| {
        cache
            .as_ref()
            .filter(|(read_at, _)| read_at.elapsed() < CACHE_TTL)
            .map(|(_, text)| text.clone())
    });
    let text = cached.unwrap_or_else(|| {
        let text = read_text(android_app).unwrap_or_else(|error| {
            log::warn!("clipboard: read failed: {error:#}");
            None
        });
        READ_CACHE.set(Some((Instant::now(), text.clone())));
        text
    });
    text.map(ClipboardItem::new_string)
}

pub(crate) fn write(android_app: &AndroidApp, item: ClipboardItem) {
    let Some(text) = item.entries().iter().find_map(|entry| match entry {
        gpui::ClipboardEntry::String(string) => Some(string.text().to_owned()),
        _ => None,
    }) else {
        return;
    };
    let result = crate::jni_env::with_activity(android_app, |env, activity| {
        let text = env.new_string(&text)?;
        env.call_method(
            activity,
            "writeClipboardText",
            "(Ljava/lang/String;)V",
            &[(&text).into()],
        )?;
        Ok(())
    });
    if let Err(error) = result {
        log::warn!("clipboard: write failed: {error:#}");
    }
    // Android applies the write later, on its UI thread.
    READ_CACHE.set(Some((Instant::now(), Some(text))));
}

fn read_text(android_app: &AndroidApp) -> Result<Option<String>> {
    crate::jni_env::with_activity(android_app, |env, activity| {
        let text = env
            .call_method(activity, "readClipboardText", "()Ljava/lang/String;", &[])?
            .l()?;
        if text.is_null() {
            return Ok(None);
        }
        Ok(Some(env.get_string(&JString::from(text))?.into()))
    })
}
