use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Utc};
use jsonwebtoken::{
    Algorithm, DecodingKey, EncodingKey, Header, Validation, decode, encode,
    errors::{Error, ErrorKind},
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::types::Uuid;

const ACCESS_LIFETIME_SECONDS: i64 = 10 * 60;
const ISSUER: &str = "payra";
const ACCESS_AUDIENCE: &str = "payra-api";
const REFRESH_AUDIENCE: &str = "payra-auth";

#[derive(Serialize, Deserialize)]
struct AccessClaims {
    sub: String,
    sid: String,
    iss: String,
    aud: String,
    token_type: String,
    iat: i64,
    exp: i64,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct AccessIdentity {
    account_id: Uuid,
}

impl AccessIdentity {
    pub(crate) fn account_id(&self) -> Uuid {
        self.account_id
    }
}

#[derive(Serialize, Deserialize)]
struct RefreshClaims {
    sid: String,
    counter: i64,
    iss: String,
    aud: String,
    token_type: String,
    iat: i64,
    exp: i64,
}

pub(super) struct RefreshIdentity {
    pub(super) session_id: Uuid,
    pub(super) counter: i64,
}

pub(crate) struct IssuedTokens {
    pub(crate) access_token: String,
    pub(crate) refresh_token: String,
    pub(crate) refresh_token_hash: String,
    pub(crate) access_expires_at: i64,
    pub(crate) refresh_expires_at: i64,
}

#[derive(Clone)]
pub(crate) struct TokenSigner {
    access_key: EncodingKey,
    refresh_key: EncodingKey,
    access_verification_key: DecodingKey,
    access_validation: Validation,
    refresh_verification_key: DecodingKey,
    refresh_validation: Validation,
}

impl TokenSigner {
    pub(crate) fn new(access_secret: &str, refresh_secret: &str) -> Self {
        let mut access_validation = Validation::new(Algorithm::HS256);
        access_validation.set_issuer(&[ISSUER]);
        access_validation.set_audience(&[ACCESS_AUDIENCE]);
        access_validation.set_required_spec_claims(&["exp", "iss", "aud", "sub"]);
        access_validation.leeway = 0;
        access_validation.validate_nbf = true;

        let mut refresh_validation = Validation::new(Algorithm::HS256);
        refresh_validation.set_issuer(&[ISSUER]);
        refresh_validation.set_audience(&[REFRESH_AUDIENCE]);
        refresh_validation.set_required_spec_claims(&["exp", "iss", "aud"]);
        refresh_validation.leeway = 0;
        refresh_validation.validate_nbf = true;

        Self {
            access_key: EncodingKey::from_secret(access_secret.as_bytes()),
            refresh_key: EncodingKey::from_secret(refresh_secret.as_bytes()),
            access_verification_key: DecodingKey::from_secret(access_secret.as_bytes()),
            access_validation,
            refresh_verification_key: DecodingKey::from_secret(refresh_secret.as_bytes()),
            refresh_validation,
        }
    }

    pub(super) fn verify_refresh(&self, token: &str) -> Result<RefreshIdentity, Error> {
        let claims = decode::<RefreshClaims>(
            token,
            &self.refresh_verification_key,
            &self.refresh_validation,
        )?
        .claims;

        if claims.token_type != "refresh" || claims.counter < 1 {
            return Err(ErrorKind::InvalidToken.into());
        }

        let session_id =
            Uuid::parse_str(&claims.sid).map_err(|_| Error::from(ErrorKind::InvalidToken))?;

        Ok(RefreshIdentity {
            session_id,
            counter: claims.counter,
        })
    }

    pub(crate) fn verify_access(&self, token: &str) -> Result<AccessIdentity, Error> {
        let claims = decode::<AccessClaims>(
            token,
            &self.access_verification_key,
            &self.access_validation,
        )?
        .claims;

        if claims.token_type != "access" {
            return Err(ErrorKind::InvalidToken.into());
        }

        let account_id =
            Uuid::parse_str(&claims.sub).map_err(|_| Error::from(ErrorKind::InvalidToken))?;
        Uuid::parse_str(&claims.sid).map_err(|_| Error::from(ErrorKind::InvalidToken))?;

        Ok(AccessIdentity { account_id })
    }

    pub(crate) fn issue(
        &self,
        account_id: Uuid,
        session_id: Uuid,
        refresh_counter: i64,
        idle_expires_at: DateTime<Utc>,
        absolute_expires_at: DateTime<Utc>,
    ) -> Result<IssuedTokens, Error> {
        let now = Utc::now().timestamp();
        let refresh_expires_at = idle_expires_at
            .timestamp()
            .min(absolute_expires_at.timestamp());

        if refresh_expires_at <= now {
            return Err(jsonwebtoken::errors::ErrorKind::ExpiredSignature.into());
        }

        let access_expires_at = (now + ACCESS_LIFETIME_SECONDS).min(refresh_expires_at);

        let access_claims = AccessClaims {
            sub: account_id.to_string(),
            sid: session_id.to_string(),
            iss: ISSUER.to_owned(),
            aud: ACCESS_AUDIENCE.to_owned(),
            token_type: "access".to_owned(),
            iat: now,
            exp: access_expires_at,
        };

        let refresh_claims = RefreshClaims {
            sid: session_id.to_string(),
            counter: refresh_counter,
            iss: ISSUER.to_owned(),
            aud: REFRESH_AUDIENCE.to_owned(),
            token_type: "refresh".to_owned(),
            iat: now,
            exp: refresh_expires_at,
        };

        let header = Header::new(Algorithm::HS256);

        let access_token = encode(&header, &access_claims, &self.access_key)?;
        let refresh_token = encode(&header, &refresh_claims, &self.refresh_key)?;
        let refresh_token_hash = hash_refresh_token(&refresh_token);

        Ok(IssuedTokens {
            access_token,
            refresh_token,
            refresh_token_hash,
            access_expires_at,
            refresh_expires_at,
        })
    }
}

pub(super) fn hash_refresh_token(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    URL_SAFE_NO_PAD.encode(digest)
}
