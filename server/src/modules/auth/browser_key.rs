use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use p256::PublicKey;
use serde_json::{Value, json};

#[derive(Debug)]
pub(super) struct InvalidBrowserKey;

pub(super) fn validate_public_key(value: &Value) -> Result<Value, InvalidBrowserKey> {
    let object = value.as_object().ok_or(InvalidBrowserKey)?;

    if object.contains_key("d")
        || object.get("kty").and_then(Value::as_str) != Some("EC")
        || object.get("crv").and_then(Value::as_str) != Some("P-256")
    {
        return Err(InvalidBrowserKey);
    }

    let x = object
        .get("x")
        .and_then(Value::as_str)
        .ok_or(InvalidBrowserKey)?;
    let y = object
        .get("y")
        .and_then(Value::as_str)
        .ok_or(InvalidBrowserKey)?;

    if x.len() != 43 || y.len() != 43 {
        return Err(InvalidBrowserKey);
    }

    let x_bytes = URL_SAFE_NO_PAD.decode(x).map_err(|_| InvalidBrowserKey)?;
    let y_bytes = URL_SAFE_NO_PAD.decode(y).map_err(|_| InvalidBrowserKey)?;

    if x_bytes.len() != 32 || y_bytes.len() != 32 {
        return Err(InvalidBrowserKey);
    }

    let mut encoded_point = [0u8; 65];
    encoded_point[0] = 4;
    encoded_point[1..33].copy_from_slice(&x_bytes);
    encoded_point[33..65].copy_from_slice(&y_bytes);

    PublicKey::from_sec1_bytes(&encoded_point).map_err(|_| InvalidBrowserKey)?;

    Ok(json!({
        "kty": "EC",
        "crv": "P-256",
        "x": x,
        "y": y,
    }))
}
