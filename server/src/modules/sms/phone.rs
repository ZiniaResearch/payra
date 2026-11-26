pub(super) fn normalize_phone(phone: &str) -> Option<String> {
    let compact: String = phone
        .chars()
        .filter(|ch| !ch.is_whitespace() && !matches!(ch, '(' | ')' | '.' | '-'))
        .collect();

    let national = compact
        .strip_prefix("+880")
        .or_else(|| compact.strip_prefix('0'))?;
    if national.len() != 10
        || !matches!(national.as_bytes(), [b'1', b'3'..=b'9', ..])
        || !national.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }

    if compact.starts_with('+') {
        Some(compact)
    } else {
        Some(format!("+880{national}"))
    }
}
