use reqwest::Url;

#[derive(Clone, Copy)]
pub(crate) struct CoverPreset {
    pub(super) id: &'static str,
    pub(super) label: &'static str,
    filename: &'static str,
}
pub(crate) const COVER_PRESETS: [CoverPreset; 3] = [
    CoverPreset {
        id: "cover-1",
        label: "Cover 1",
        filename: "cover-1.webp",
    },
    CoverPreset {
        id: "cover-2",
        label: "Cover 2",
        filename: "cover-2.webp",
    },
    CoverPreset {
        id: "cover-3",
        label: "Cover 3",
        filename: "cover-3.webp",
    },
];

impl CoverPreset {
    pub(crate) fn url(self, base: &Url) -> String {
        asset_url(base, self.filename)
    }

    pub(super) fn matches_url(self, url: &str, base: &Url, legacy_origin: Option<&str>) -> bool {
        let Some((directory, filename)) = url.rsplit_once('/') else {
            return false;
        };

        filename == self.filename
            && (directory == base.as_str().trim_end_matches('/')
                || legacy_origin
                    .is_some_and(|origin| directory.strip_suffix("/profile") == Some(origin)))
    }
}

pub(crate) fn default_avatar_url(base: &Url) -> String {
    asset_url(base, "default-profile.webp")
}

pub(super) fn asset_url(base: &Url, key: &str) -> String {
    format!("{}/{key}", base.as_str().trim_end_matches('/'))
}
