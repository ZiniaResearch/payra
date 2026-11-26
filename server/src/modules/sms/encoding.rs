pub(super) fn part_count(message: &str) -> usize {
    if message.chars().all(|ch| gsm_width(ch).is_some()) {
        count_parts(message.chars().filter_map(gsm_width), 160, 153)
    } else {
        count_parts(message.chars().map(char::len_utf16), 70, 67)
    }
}

fn gsm_width(ch: char) -> Option<usize> {
    match ch {
        ' '..='?' | 'A'..='Z' | 'a'..='z' | '@' | '_' | '\n' | '\r' => Some(1),
        '\u{000c}' | '^' | '{' | '}' | '\\' | '[' | ']' | '~' | '|' | '€' => Some(2),
        ch if "£¥èéùìòÇØøÅåΔΦΓΛΩΠΨΣΘΞÆæßÉ¤¡ÄÖÑÜ§¿äöñüà".contains(ch) => {
            Some(1)
        }
        _ => None,
    }
}

fn count_parts(widths: impl Iterator<Item = usize>, single: usize, multipart: usize) -> usize {
    let mut total = 0;
    let mut used = 0;
    let mut parts = 1;

    for width in widths {
        total += width;
        if used + width > multipart {
            parts += 1;
            used = 0;
        }
        used += width;
    }

    if total <= single { 1 } else { parts }
}
