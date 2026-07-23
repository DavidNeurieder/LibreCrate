#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum Error {
    #[error("Crypto error: {0}")]
    Crypto(String),

    #[error("Format error: {0}")]
    Format(String),

    #[error("KDF error: {0}")]
    Kdf(String),

    #[error("Database error: {0}")]
    Database(String),

    #[error("IO error: {0}")]
    Io(String),

    #[error("Invalid data: {0}")]
    InvalidData(String),

    #[error("Wrong password or corrupt backup")]
    AuthenticationFailed,

    #[error("Missing key file: {0}")]
    MissingKey(String),

    #[error("Compression error: {0}")]
    Compression(String),

    #[error("Reader error: {0}")]
    Reader(String),
}

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Error::Database(e.to_string())
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e.to_string())
    }
}

impl From<crate::reader::ReaderError> for Error {
    fn from(e: crate::reader::ReaderError) -> Self {
        Error::Reader(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
