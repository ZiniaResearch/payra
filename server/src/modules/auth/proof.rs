use super::tokens::hash_proof_token;
use chrono::Utc;
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header, jwk::Jwk};
use reqwest::Url;
use serde::Deserialize;
use serde_json::Value;
use sqlx::types::Uuid;

const MAX_PROOF_BYTES: usize = 2048;
const MAX_AGE_SECONDS: i64 = 60;
const FUTURE_SKEW_SECONDS: i64 = 5;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BrowserProofClaims {
    htm: String,
    htu: String,
    iat: i64,
    sid: String,
    ath: String,
}

pub(crate) enum ProofPurpose {
    Refresh,
    Logout,
    DeleteProfile,
}

impl ProofPurpose {
    fn token_type(&self) -> &'static str {
        match self {
            Self::Refresh => "payra-refresh-proof+jwt",
            Self::Logout => "payra-logout-proof+jwt",
            Self::DeleteProfile => "payra-delete-profile-proof+jwt",
        }
    }

    fn path(&self) -> &'static str {
        match self {
            Self::Refresh => "/api/auth/refresh",
            Self::Logout => "/api/auth/logout",
            Self::DeleteProfile => "/api/profile",
        }
    }

    fn method(&self) -> &'static str {
        match self {
            Self::Refresh | Self::Logout => "POST",
            Self::DeleteProfile => "DELETE",
        }
    }
}

pub(crate) fn verify_browser_proof(
    proof: &str,
    public_key: &Value,
    proof_token: &str,
    session_id: Uuid,
    api_origin: &Url,
    purpose: ProofPurpose,
) -> bool {
    if proof.len() > MAX_PROOF_BYTES {
        return false;
    }

    let Ok(header) = decode_header(proof) else {
        return false;
    };
    if header.alg != Algorithm::ES256 || header.typ.as_deref() != Some(purpose.token_type()) {
        return false;
    }

    let Ok(jwk) = serde_json::from_value::<Jwk>(public_key.clone()) else {
        return false;
    };
    let Ok(key) = DecodingKey::from_jwk(&jwk) else {
        return false;
    };

    let mut validation = Validation::new(Algorithm::ES256);
    validation.set_required_spec_claims::<&str>(&[]);
    validation.validate_exp = false;
    validation.validate_aud = false;

    let Ok(token) = decode::<BrowserProofClaims>(proof, &key, &validation) else {
        return false;
    };
    let Ok(target_url) = api_origin.join(purpose.path()) else {
        return false;
    };
    let claims = token.claims;
    let now = Utc::now().timestamp();

    claims.htm == purpose.method()
        && claims.htu == target_url.as_str()
        && claims.sid == session_id.to_string()
        && claims.ath == hash_proof_token(proof_token)
        && claims.iat >= now.saturating_sub(MAX_AGE_SECONDS)
        && claims.iat <= now.saturating_add(FUTURE_SKEW_SECONDS)
}
