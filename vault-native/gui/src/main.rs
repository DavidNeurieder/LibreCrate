mod app;
mod config;
mod keychain;
mod opener;
mod screens;
mod vault;
mod widgets;

fn main() -> iced::Result {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::builder()
                .with_default_directive(tracing::level_filters::LevelFilter::INFO.into())
                .from_env_lossy(),
        )
        .init();

    iced::application("LibreCrate", app::update, app::view)
        .theme(app::theme)
        .run_with(app::boot)
}
