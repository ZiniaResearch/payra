use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header};
use jsonwebtoken_jwks_cache::{CachedJWKS, TimeoutSpec};
use reqwest::Client;
use serde::Deserialize;
use std::time::Duration;
use tokio::time::timeout;

const GOOGLE_KEYS_URL: &str = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const KEY_FETCH_TIMEOUT: Duration = Duration::from_secs(5);
const KEY_WAIT_TIMEOUT: Duration = Duration::from_secs(6);

#[derive(Clone)]
pub(crate) struct GoogleVerifier {
    keys: CachedJWKS,
    http: Client,
    client_id: String,
    client_secret: String,
    redirect_uri: String,
    validation: Validation,
}

#[derive(Deserialize)]
pub(crate) struct GoogleIdentity {
    pub(crate) sub: String,
    pub(crate) email: String,
    pub(crate) name: Option<String>,
    pub(crate) picture: Option<String>,
}
pub(crate) enum VerifyError {
    InvalidToken,
    InvalidCode,
    ExchangeUnavailable,
    KeysUnavailable,
}

#[derive(Deserialize)]
struct GoogleTokenResponse {
    id_token: String,
}

#[derive(Deserialize)]
struct GoogleTokenError {
    error: String,
}

impl GoogleVerifier {
    pub(crate) fn new(
        client_id: &str,
        client_secret: &str,
        redirect_uri: &str,
    ) -> Result<Self, reqwest::Error> {
        let http = Client::builder().timeout(Duration::from_secs(5)).build()?;

        let keys = CachedJWKS::new(
            GOOGLE_KEYS_URL
                .parse()
                .expect("built-in Google signing key URL must be valid"),
            Duration::from_secs(60),
            TimeoutSpec {
                retries: 1,
                retry_after: Duration::from_secs(2),
                backoff: Duration::from_millis(250),
                deadline: KEY_FETCH_TIMEOUT,
            },
        )?;

        let mut validation = Validation::new(Algorithm::RS256);
        validation.set_audience(&[client_id]);
        validation.set_issuer(&["https://accounts.google.com", "accounts.google.com"]);
        validation.set_required_spec_claims(&["aud", "iss", "exp", "sub"]);
        validation.leeway = 0;

        Ok(Self {
            keys,
            validation,
            http,
            client_id: client_id.to_owned(),
            client_secret: client_secret.to_owned(),
            redirect_uri: redirect_uri.to_owned(),
        })
    }

    pub(crate) async fn verify_code(&self, code: &str) -> Result<GoogleIdentity, VerifyError> {
        let response = self
            .http
            .post(GOOGLE_TOKEN_URL)
            .form(&[
                ("code", code),
                ("client_id", self.client_id.as_str()),
                ("client_secret", self.client_secret.as_str()),
                ("redirect_uri", self.redirect_uri.as_str()),
                ("grant_type", "authorization_code"),
            ])
            .send()
            .await
            .map_err(|_| VerifyError::ExchangeUnavailable)?;

        if !response.status().is_success() {
            let status = response.status();

            let error = response
                .json::<GoogleTokenError>()
                .await
                .map_err(|_| VerifyError::ExchangeUnavailable)?;

            return Err(
                if status == reqwest::StatusCode::BAD_REQUEST && error.error == "invalid_grant" {
                    VerifyError::InvalidCode
                } else {
                    VerifyError::ExchangeUnavailable
                },
            );
        }

        let tokens = response
            .json::<GoogleTokenResponse>()
            .await
            .map_err(|_| VerifyError::ExchangeUnavailable)?;

        self.verify(&tokens.id_token).await
    }

    pub(crate) async fn verify(&self, token: &str) -> Result<GoogleIdentity, VerifyError> {
        let header = decode_header(token).map_err(|_| VerifyError::InvalidToken)?;

        if header.alg != Algorithm::RS256 {
            return Err(VerifyError::InvalidToken);
        }

        let kid = header.kid.ok_or(VerifyError::InvalidToken)?;

        let keys = timeout(KEY_WAIT_TIMEOUT, self.keys.get())
            .await
            .map_err(|_| VerifyError::KeysUnavailable)?
            .map_err(|_| VerifyError::KeysUnavailable)?;

        let jwk = keys.find(&kid).ok_or(VerifyError::InvalidToken)?;
        let key = DecodingKey::from_jwk(jwk).map_err(|_| VerifyError::KeysUnavailable)?;

        let identity = decode::<GoogleIdentity>(token, &key, &self.validation)
            .map_err(|_| VerifyError::InvalidToken)?
            .claims;

        if identity.sub.trim().is_empty() || identity.email.trim().is_empty() {
            return Err(VerifyError::InvalidToken);
        }

        Ok(identity)
    }
}
