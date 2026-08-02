use iced::widget::{button, column, container, image, operation, row, scrollable, text, text_input, Column, Row, Scrollable};
use iced::{Element, Length, Task};
use std::collections::HashMap;
use std::sync::Arc;

use super::Navigation;
use crate::vault::Vault;
use crate::widgets::common;
use vault_native::db::queries::DocumentRow;
use vault_native::pdf::{PdfHandle, PdfPageRender};

const PAGE_GAP: f32 = 8.0;
const PAD_TOP: f32 = 10.0;
const PAD_BOTTOM: f32 = 10.0;
const BASE_WIDTH: f32 = 880.0;
const ZOOM_STEPS: [f32; 6] = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];
const MAX_PRELOAD_PAGES: usize = 12;
const PREVIEW_SCALE: f32 = 0.5;
const PRELOAD_BATCH: usize = 20;
const CACHE_BYTE_CAP: usize = 192 * 1024 * 1024;
const KEEP_RADIUS: usize = 24;
const HOLE_WINDOW: usize = 5;
const MEASURE_WIDTH: i32 = 8;
const MEASURE_LIMIT: usize = 600;
const SCROLL_ID: &str = "pdf-scroll";

#[derive(Debug, Clone)]
pub struct RenderedPage {
    pub index: usize,
    pub data: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone)]
pub struct LoadedDoc {
    pub handle: Arc<PdfHandle>,
    pub tmp_dir: Arc<tempfile::TempDir>,
    pub page_count: usize,
    pub first: RenderedPage,
}

impl std::fmt::Debug for LoadedDoc {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LoadedDoc")
            .field("page_count", &self.page_count)
            .field("first", &self.first)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct JumpTarget {
    pub page: usize,
    pub offset_within_page: f32,
}

#[derive(Clone)]
pub struct CachedPdf {
    pub doc_id: String,
    pub handle: Arc<PdfHandle>,
    pub tmp_dir: Arc<tempfile::TempDir>,
    pub page_count: usize,
    pub heights: Vec<Option<f32>>,
    pub zoom: f32,
    pub scroll_y: f32,
    pub current_page: usize,
    pub viewport_visible: f32,
}

#[derive(Debug, Clone)]
pub enum Message {
    Back,
    OpenExternally,
    Loaded(Result<LoadedDoc, String>),
    PageRendered(Result<RenderedPage, String>),
    ViewportChanged(f32, f32),
    ZoomIn,
    ZoomOut,
    ResetZoom,
    SearchChanged(String),
    SearchSubmit,
    SearchNext,
    SearchPrev,
    SearchDone(Result<Vec<usize>, String>),
    MeasureDone(Result<Vec<f32>, String>),
}

pub struct State {
    vault: Arc<Vault>,
    doc: DocumentRow,
    handle: Option<Arc<PdfHandle>>,
    tmp_dir: Option<Arc<tempfile::TempDir>>,
    page_count: usize,
    pages: HashMap<usize, image::Handle>,
    heights: Vec<Option<f32>>,
    page_bytes: Vec<usize>,
    render_cursor: usize,
    render_limit: usize,
    zoom: f32,
    scroll_id: iced::widget::Id,
    pending_jump: Option<JumpTarget>,
    restore: Option<JumpTarget>,
    current_page: usize,
    last_scroll_y: f32,
    last_persisted_page: usize,
    rendering: bool,
    measuring: bool,
    viewport_y: f32,
    viewport_visible: f32,
    loading: bool,
    error: Option<String>,
    search_query: String,
    search_results: Vec<usize>,
    search_index: usize,
    searching: bool,
    search_status: Option<String>,
}

fn target_width(zoom: f32) -> i32 {
    ((BASE_WIDTH * zoom).round() as i32).max(8)
}

fn render_page(handle: &PdfHandle, index: usize, width: i32) -> Result<RenderedPage, String> {
    let PdfPageRender {
        data,
        width: w,
        height: h,
    } = handle.render_page(index as i32, width).map_err(|e| e.to_string())?;
    Ok(RenderedPage {
        index,
        data,
        width: w.max(0) as u32,
        height: h.max(0) as u32,
    })
}

fn measure_heights(handle: &PdfHandle, count: usize, width: i32) -> Result<Vec<f32>, String> {
    let mut heights = Vec::with_capacity(count);
    for i in 0..count {
        let r = render_page(handle, i, width)?;
        heights.push(r.height as f32 * (BASE_WIDTH / width as f32));
    }
    Ok(heights)
}

fn load_document(vault: &Vault, doc: &DocumentRow) -> Result<LoadedDoc, String> {
    let data = vault
        .db
        .export_document_file(vault.base_dir.to_string_lossy().to_string(), doc.id.clone())
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("File data not found for {}", doc.id))?;

    let tmp_dir = Arc::new(tempfile::TempDir::new().map_err(|e| e.to_string())?);
    let tmp_path = tmp_dir.path().join(&doc.file_name);
    std::fs::write(&tmp_path, &data).map_err(|e| e.to_string())?;

    let handle = PdfHandle::open(tmp_path.to_str().unwrap_or("").to_string())
        .map_err(|e| e.to_string())?;
    let page_count = handle.page_count().map_err(|e| e.to_string())?;
    if page_count <= 0 {
        return Err("This PDF has no pages".to_string());
    }
    let first = render_page(&handle, 0, target_width(PREVIEW_SCALE))?;

    Ok(LoadedDoc {
        handle,
        tmp_dir,
        page_count: page_count as usize,
        first,
    })
}

fn parse_restore(doc: &DocumentRow) -> (f32, Option<JumpTarget>) {
    let page = doc.current_page.max(0) as usize;
    let mut offset = 0.0f32;
    let mut zoom = 1.0f32;
    if let Some(pos) = doc.reading_position.as_deref() {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(pos) {
            offset = v.get("offsetY").and_then(|x| x.as_f64()).unwrap_or(0.0) as f32;
            zoom = v.get("zoom").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
        }
    }
    if zoom < ZOOM_STEPS[0] || zoom > ZOOM_STEPS[ZOOM_STEPS.len() - 1] {
        zoom = 1.0;
    }
    let restore = if page > 0 || offset > 0.0 {
        Some(JumpTarget {
            page,
            offset_within_page: offset,
        })
    } else {
        None
    };
    (zoom, restore)
}

impl State {
    pub fn new(doc: DocumentRow, vault: Arc<Vault>) -> (Self, Task<crate::app::Message>) {
        let state = Self::base(doc, vault);
        let task = state.load();
        (state, task)
    }

    fn base(doc: DocumentRow, vault: Arc<Vault>) -> Self {
        let (zoom, restore) = parse_restore(&doc);
        Self {
            vault,
            doc,
            handle: None,
            tmp_dir: None,
            page_count: 0,
            pages: HashMap::new(),
            heights: Vec::new(),
            page_bytes: Vec::new(),
            render_cursor: 0,
            render_limit: 0,
            zoom,
            scroll_id: iced::widget::Id::new(SCROLL_ID),
            pending_jump: None,
            restore,
            current_page: 0,
            last_scroll_y: 0.0,
            last_persisted_page: 0,
            rendering: false,
            measuring: false,
            viewport_y: 0.0,
            viewport_visible: 0.0,
            loading: true,
            error: None,
            search_query: String::new(),
            search_results: Vec::new(),
            search_index: 0,
            searching: false,
            search_status: None,
        }
    }

    fn load(&self) -> Task<crate::app::Message> {
        let vault = self.vault.clone();
        let doc = self.doc.clone();
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || load_document(&vault, &doc))
                    .await
                    .map_err(|e| e.to_string())?
            },
            |res| crate::app::Message::Pdf(Message::Loaded(res)),
        )
    }

    pub fn from_cached(
        doc: DocumentRow,
        vault: Arc<Vault>,
        cached: CachedPdf,
    ) -> (Self, Task<crate::app::Message>) {
        let mut state = Self::base(doc, vault);
        state.handle = Some(cached.handle);
        state.tmp_dir = Some(cached.tmp_dir);
        state.page_count = cached.page_count;
        state.heights = cached.heights;
        state.page_bytes = vec![0; cached.page_count];
        state.zoom = cached.zoom;
        state.render_limit = cached.page_count.min(MAX_PRELOAD_PAGES);
        state.render_cursor = 0;
        state.loading = false;
        state.current_page = cached.current_page.min(cached.page_count.saturating_sub(1));
        state.last_scroll_y = cached.scroll_y;
        state.last_persisted_page = state.current_page;
        state.viewport_visible = cached.viewport_visible;
        let page = state.current_page;
        let offset = (cached.scroll_y - state.exact_offset_of(page)).max(0.0);
        state.viewport_y = cached.scroll_y;
        let task = state.jump_to(page, offset);
        (state, task)
    }

    fn persist_position(&mut self) {
        if self.loading || self.handle.is_none() || self.page_count == 0 {
            return;
        }
        let page = self.current_page.min(self.page_count - 1);
        let offset = (self.last_scroll_y - self.exact_offset_of(page)).max(0.0);
        let json = serde_json::json!({
            "page": page,
            "offsetY": offset,
            "zoom": self.zoom,
        });
        if let Err(e) = self
            .vault
            .db
            .set_current_page(self.doc.id.clone(), page as i32)
        {
            tracing::warn!("Failed to persist current page: {e}");
        }
        if let Err(e) = self
            .vault
            .db
            .set_reading_position(self.doc.id.clone(), json.to_string())
        {
            tracing::warn!("Failed to persist reading position: {e}");
        }
        self.last_persisted_page = page;
    }

    pub fn leaving(&mut self) -> Option<CachedPdf> {
        self.persist_position();
        let handle = self.handle.clone()?;
        let tmp_dir = self.tmp_dir.clone()?;
        Some(CachedPdf {
            doc_id: self.doc.id.clone(),
            handle,
            tmp_dir,
            page_count: self.page_count,
            heights: self.heights.clone(),
            zoom: self.zoom,
            scroll_y: self.last_scroll_y,
            current_page: self.current_page,
            viewport_visible: self.viewport_visible,
        })
    }

    pub fn update(&mut self, message: Message) -> Task<crate::app::Message> {
        match message {
            Message::Back => {
                let vault = self.vault.clone();
                Task::done(crate::app::Message::Navigate(Navigation::PdfExit(vault)))
            }
            Message::OpenExternally => {
                let vault = self.vault.clone();
                let doc = self.doc.clone();
                std::thread::spawn(move || {
                    if let Err(e) = vault.open_document(&doc) {
                        tracing::error!("Failed to open document externally: {e}");
                    }
                });
                let vault = self.vault.clone();
                Task::done(crate::app::Message::Navigate(Navigation::PdfExit(vault)))
            }
            Message::Loaded(Ok(loaded)) => {
                let LoadedDoc {
                    handle,
                    tmp_dir,
                    page_count,
                    first,
                } = loaded;
                self.handle = Some(handle);
                self.tmp_dir = Some(tmp_dir);
                self.page_count = page_count;
                self.heights = vec![None; page_count];
                self.page_bytes = vec![0; page_count];
                self.render_limit = page_count.min(MAX_PRELOAD_PAGES);
                self.render_cursor = 1;
                self.loading = false;
                self.insert_preview(first);
                let mut tasks = vec![self.spawn_render(0)];
                if let Some(jump) = self.restore.take() {
                    let page = jump.page.min(self.page_count - 1);
                    self.current_page = page;
                    self.last_persisted_page = page;
                    tasks.push(self.jump_to(page, jump.offset_within_page));
                }
                if tasks.len() == 1 {
                    tasks.remove(0)
                } else {
                    Task::batch(tasks)
                }
            }
            Message::Loaded(Err(e)) => {
                self.loading = false;
                self.error = Some(e);
                Task::none()
            }
            Message::PageRendered(Ok(page)) => {
                self.rendering = false;
                let page_index = page.index;
                self.insert_rendered(page);
                let mut tasks: Vec<Task<crate::app::Message>> = Vec::new();
                if let Some(jump) = self
                    .pending_jump
                    .as_ref()
                    .filter(|j| j.page == page_index && self.all_heights_up_to(page_index))
                {
                    let y = self.exact_offset_of(page_index) + jump.offset_within_page;
                    self.pending_jump = None;
                    tasks.push(operation::scroll_to(
                        self.scroll_id.clone(),
                        scrollable::AbsoluteOffset { x: 0.0, y },
                    ));
                }
                if let Some(t) = self.next_render_task() {
                    tasks.push(t);
                }
                if tasks.is_empty() {
                    Task::none()
                } else {
                    Task::batch(tasks)
                }
            }
            Message::PageRendered(Err(e)) => {
                self.rendering = false;
                self.error = Some(e);
                Task::none()
            }
            Message::ViewportChanged(y, visible) => {
                self.viewport_y = y;
                self.viewport_visible = visible;
                self.last_scroll_y = y;
                let top = self.page_at_y(y);
                if top != self.current_page {
                    self.current_page = top;
                    if top != self.last_persisted_page {
                        self.persist_position();
                    }
                }
                let bottom_page = self.page_at_y(y + visible);
                let target_limit = bottom_page.saturating_add(PRELOAD_BATCH).min(self.page_count);
                if target_limit > self.render_limit {
                    self.render_limit = target_limit;
                }
                self.next_render_task().unwrap_or_else(Task::none)
            }
            Message::ZoomIn => self.set_zoom(self.zoom_index().saturating_add(1)),
            Message::ZoomOut => self.set_zoom(self.zoom_index().saturating_sub(1)),
            Message::ResetZoom => self.set_zoom(self.default_zoom_index()),
            Message::SearchChanged(q) => {
                self.search_query = q;
                self.search_status = None;
                Task::none()
            }
            Message::SearchSubmit => self.run_search(),
            Message::SearchNext => self.step_search(1),
            Message::SearchPrev => self.step_search(-1),
            Message::SearchDone(Ok(pages)) => {
                self.searching = false;
                self.search_results = pages;
                self.search_index = 0;
                self.search_status = if self.search_results.is_empty() {
                    Some("No matches".to_string())
                } else {
                    None
                };
                if let Some(&page) = self.search_results.first() {
                    self.jump_to(page, 0.0)
                } else {
                    Task::none()
                }
            }
            Message::SearchDone(Err(e)) => {
                self.searching = false;
                self.error = Some(e);
                Task::none()
            }
            Message::MeasureDone(Ok(heights_base)) => {
                self.measuring = false;
                if let Some(jump) = self.pending_jump.take() {
                    let scale = self.zoom;
                    for (k, h) in heights_base.iter().enumerate() {
                        if k < self.page_count && self.heights[k].is_none() {
                            self.heights[k] = Some(h * scale);
                        }
                    }
                    let page = jump.page.min(self.page_count.saturating_sub(1));
                    let y = self.exact_offset_of(page) + jump.offset_within_page;
                    let mut tasks = vec![operation::scroll_to(
                        self.scroll_id.clone(),
                        scrollable::AbsoluteOffset { x: 0.0, y },
                    )];
                    if let Some(t) = self.next_render_task() {
                        tasks.push(t);
                    }
                    Task::batch(tasks)
                } else {
                    Task::none()
                }
            }
            Message::MeasureDone(Err(e)) => {
                self.measuring = false;
                self.error = Some(e);
                Task::none()
            }
        }
    }

    fn insert_rendered(&mut self, page: RenderedPage) {
        let expected = target_width(self.zoom);
        if (page.width as i32 - expected).abs() > 2 {
            return;
        }
        let i = page.index;
        self.heights[i] = Some(page.height as f32);
        self.page_bytes[i] = page.data.len();
        let handle = image::Handle::from_rgba(page.width, page.height, page.data);
        self.pages.insert(i, handle);
        self.evict_pages();
    }

    fn insert_preview(&mut self, first: RenderedPage) {
        let handle = image::Handle::from_rgba(first.width, first.height, first.data);
        self.pages.insert(first.index, handle);
    }

    fn evict_pages(&mut self) {
        let total: usize = self
            .pages
            .keys()
            .map(|&i| self.page_bytes.get(i).copied().unwrap_or(0))
            .sum();
        if total <= CACHE_BYTE_CAP {
            return;
        }
        let center = self.page_at_y(self.viewport_y + self.viewport_visible / 2.0);
        let mut candidates: Vec<usize> = self
            .pages
            .keys()
            .copied()
            .filter(|&i| i.abs_diff(center) > KEEP_RADIUS)
            .collect();
        candidates.sort_by_key(|&i| std::cmp::Reverse(i.abs_diff(center)));
        let mut freed = 0usize;
        for idx in candidates {
            if total - freed <= CACHE_BYTE_CAP {
                break;
            }
            if let Some(b) = self.page_bytes.get(idx).copied() {
                freed += b;
            }
            self.pages.remove(&idx);
        }
    }

    fn next_render_task(&mut self) -> Option<Task<crate::app::Message>> {
        if self.rendering || self.measuring || self.handle.is_none() || self.loading || self.page_count == 0 {
            return None;
        }
        let center = self.page_at_y(self.viewport_y + self.viewport_visible / 2.0);
        let window_start = center.saturating_sub(HOLE_WINDOW);
        let window_end = (center + HOLE_WINDOW + 1).min(self.page_count);
        for idx in window_start..window_end {
            if !self.pages.contains_key(&idx) {
                return Some(self.spawn_render(idx));
            }
        }
        if self.render_cursor < self.render_limit {
            let idx = self.render_cursor;
            self.render_cursor += 1;
            return Some(self.spawn_render(idx));
        }
        None
    }

    fn spawn_render(&mut self, index: usize) -> Task<crate::app::Message> {
        self.rendering = true;
        let handle = self.handle.clone().expect("handle present");
        let width = target_width(self.zoom);
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || render_page(&handle, index, width))
                    .await
                    .map_err(|e| e.to_string())?
            },
            |res| crate::app::Message::Pdf(Message::PageRendered(res)),
        )
    }

    fn page_at_y(&self, y: f32) -> usize {
        if self.page_count == 0 {
            return 0;
        }
        let mut acc = PAD_TOP;
        let mut last_known = 0usize;
        for i in 0..self.page_count {
            let Some(h) = self.heights.get(i).copied().flatten() else {
                return last_known;
            };
            if y <= acc + h {
                return i;
            }
            acc += h + PAGE_GAP;
            last_known = i;
        }
        self.page_count - 1
    }

    fn all_heights_up_to(&self, i: usize) -> bool {
        (0..=i.min(self.page_count.saturating_sub(1)))
            .all(|k| self.heights.get(k).copied().flatten().is_some())
    }

    fn exact_offset_of(&self, i: usize) -> f32 {
        let mut acc = PAD_TOP;
        for k in 0..i {
            acc += self.heights.get(k).copied().flatten().unwrap_or(0.0) + PAGE_GAP;
        }
        acc
    }

    fn estimated_offset_of(&self, i: usize) -> f32 {
        let mut known_sum = 0.0;
        let mut known = 0usize;
        for k in 0..i {
            if let Some(h) = self.heights.get(k).copied().flatten() {
                known_sum += h;
                known += 1;
            }
        }
        let missing = i - known;
        let avg = if known > 0 {
            known_sum / known as f32
        } else {
            (BASE_WIDTH * self.zoom) * 1.4
        };
        PAD_TOP + known_sum + missing as f32 * avg + i as f32 * PAGE_GAP
    }

    fn jump_to(&mut self, page: usize, offset_within_page: f32) -> Task<crate::app::Message> {
        if self.page_count == 0 || self.handle.is_none() {
            return Task::none();
        }
        let page = page.min(self.page_count - 1);
        if page + 1 > self.render_limit {
            self.render_limit = (page + 1).min(self.page_count);
        }
        if self.render_cursor < page {
            self.render_cursor = page;
        }

        let mut tasks: Vec<Task<crate::app::Message>> = Vec::new();
        if self.pages.contains_key(&page) && self.all_heights_up_to(page) {
            let y = self.exact_offset_of(page) + offset_within_page;
            tasks.push(operation::scroll_to(
                self.scroll_id.clone(),
                scrollable::AbsoluteOffset { x: 0.0, y },
            ));
        } else {
            let missing_count = (0..page)
                .filter(|&k| self.heights.get(k).copied().flatten().is_none())
                .count();
            if missing_count <= MEASURE_LIMIT && !self.measuring {
                self.measuring = true;
                self.pending_jump = Some(JumpTarget {
                    page,
                    offset_within_page,
                });
                let handle = self.handle.clone().expect("handle present");
                tasks.push(Task::perform(
                    async move {
                        tokio::task::spawn_blocking(move || {
                            measure_heights(&handle, page, MEASURE_WIDTH)
                        })
                        .await
                        .map_err(|e| e.to_string())?
                    },
                    |res| crate::app::Message::Pdf(Message::MeasureDone(res)),
                ));
            } else {
                let y = self.estimated_offset_of(page) + offset_within_page;
                tasks.push(operation::scroll_to(
                    self.scroll_id.clone(),
                    scrollable::AbsoluteOffset { x: 0.0, y },
                ));
            }
        }
        if let Some(t) = self.next_render_task() {
            tasks.push(t);
        }
        if tasks.is_empty() {
            Task::none()
        } else {
            Task::batch(tasks)
        }
    }

    fn set_zoom(&mut self, index: usize) -> Task<crate::app::Message> {
        let idx = index.min(ZOOM_STEPS.len() - 1);
        let new_zoom = ZOOM_STEPS[idx];
        if (new_zoom - self.zoom).abs() < f32::EPSILON {
            return Task::none();
        }
        let old_zoom = self.zoom;
        let anchor = self.page_at_y(self.viewport_y);
        let offset_old = (self.viewport_y - self.exact_offset_of(anchor)).max(0.0);
        self.zoom = new_zoom;
        self.pages.clear();
        self.heights = vec![None; self.page_count];
        self.page_bytes = vec![0; self.page_count];
        self.render_cursor = 0;
        self.render_limit = self.page_count.min(MAX_PRELOAD_PAGES);
        self.pending_jump = Some(JumpTarget {
            page: anchor,
            offset_within_page: offset_old * (new_zoom / old_zoom),
        });
        self.next_render_task().unwrap_or_else(Task::none)
    }

    fn zoom_index(&self) -> usize {
        ZOOM_STEPS
            .iter()
            .position(|z| (*z - self.zoom).abs() < 1e-5)
            .unwrap_or(ZOOM_STEPS.len() / 2)
    }

    fn default_zoom_index(&self) -> usize {
        ZOOM_STEPS
            .iter()
            .position(|&z| (z - 1.0).abs() < 1e-5)
            .unwrap_or(ZOOM_STEPS.len() / 2)
    }

    fn run_search(&mut self) -> Task<crate::app::Message> {
        let query = self.search_query.trim().to_lowercase();
        if query.is_empty() {
            self.search_status = None;
            return Task::none();
        }
        if self.searching {
            return Task::none();
        }
        let Some(handle) = self.handle.clone() else {
            return Task::none();
        };
        self.searching = true;
        self.search_status = Some("Searching…".to_string());
        let page_count = self.page_count;
        let q = query.clone();
        Task::perform(
            async move {
                tokio::task::spawn_blocking(move || -> Result<Vec<usize>, String> {
                    let mut matches = Vec::new();
                    for i in 0..page_count {
                        if let Ok(text) = handle.extract_text(i as i32) {
                            if text.to_lowercase().contains(&q) {
                                matches.push(i);
                            }
                        }
                    }
                    Ok(matches)
                })
                .await
                .map_err(|e| e.to_string())?
            },
            |res| crate::app::Message::Pdf(Message::SearchDone(res)),
        )
    }

    fn step_search(&mut self, delta: i64) -> Task<crate::app::Message> {
        if self.search_results.is_empty() {
            return Task::none();
        }
        let n = self.search_results.len() as i64;
        self.search_index = ((self.search_index as i64 + delta).rem_euclid(n)) as usize;
        let page = self.search_results[self.search_index];
        self.jump_to(page, 0.0)
    }

    pub fn view(&self) -> Element<'_, Message> {
        let navbar = common::navbar(&self.doc.title, Some(Message::Back));

        let body: Element<'_, Message> = if self.loading {
            container(text("Opening document…").size(16))
                .center_x(Length::Fill)
                .center_y(Length::Fill)
                .width(Length::Fill)
                .height(Length::Fill)
                .into()
        } else if let Some(e) = &self.error {
            container(
                column![
                    text(e).size(14).color(iced::Color::from_rgb(1.0, 0.4, 0.4)),
                    button("Back").on_press(Message::Back),
                ]
                .spacing(12),
            )
            .center_x(Length::Fill)
            .center_y(Length::Fill)
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
        } else {
            self.viewer()
        };

        column![navbar, body]
            .width(Length::Fill)
            .height(Length::Fill)
            .into()
    }

    fn viewer(&self) -> Element<'_, Message> {
        let zoom_pct = (self.zoom * 100.0).round() as i64;
        let top_page = (self.page_at_y(self.viewport_y) + 1).min(self.page_count);

        let toolbar = Row::new()
            .push(button("-").on_press(Message::ZoomOut))
            .push(text(format!("{zoom_pct}%")).size(12).width(Length::Fixed(52.0)))
            .push(button("+").on_press(Message::ZoomIn))
            .push(button("Reset").on_press(Message::ResetZoom))
            .push(text(format!("Page {top_page}/{count}", count = self.page_count)).size(12))
            .push(
                text_input("Search in this document…", &self.search_query)
                    .on_input(Message::SearchChanged)
                    .on_submit(Message::SearchSubmit)
                    .width(Length::Fixed(220.0)),
            )
            .push(button("Search").on_press(Message::SearchSubmit))
            .push(button("↑").on_press(Message::SearchPrev))
            .push(button("↓").on_press(Message::SearchNext))
            .push(button("Open externally").on_press(Message::OpenExternally))
            .spacing(6)
            .align_y(iced::Alignment::Center)
            .width(Length::Fill);

        let status: Element<'_, Message> = if self.searching {
            text("Searching…").size(12).into()
        } else if let Some(s) = &self.search_status {
            text(s).size(12).into()
        } else if !self.search_results.is_empty() {
            text(format!(
                "Result {}/{}",
                self.search_index + 1,
                self.search_results.len()
            ))
            .size(12)
            .into()
        } else {
            text("").size(12).into()
        };

        let scroll = Scrollable::new(
            container(self.pages_view())
                .width(Length::Fill)
                .height(Length::Fill)
                .center_x(Length::Fill)
                .style(|_| container::Style {
                    background: Some(iced::Background::Color(iced::Color::WHITE)),
                    ..Default::default()
                }),
        )
        .id(self.scroll_id.clone())
        .on_scroll(|vp| {
            let abs = vp.absolute_offset();
            Message::ViewportChanged(abs.y, vp.bounds().height)
        })
        .width(Length::Fill)
        .height(Length::Fill);

        column![
            toolbar,
            row![status].padding(iced::Padding::new(0.0).left(16.0).right(16.0)),
            scroll,
        ]
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    fn pages_view(&self) -> Element<'_, Message> {
        let page_width = target_width(self.zoom) as f32;
        let current = self.search_results.get(self.search_index).copied();

        let mut col = Column::new()
            .spacing(PAGE_GAP)
            .padding(iced::Padding::new(0.0).top(PAD_TOP).bottom(PAD_BOTTOM))
            .width(Length::Fixed(page_width));

        for i in 0..self.page_count {
            let Some(handle) = self.pages.get(&i) else {
                continue;
            };
            let img = image::Image::new(handle.clone())
                .width(Length::Fixed(page_width));
            col = col.push(if current == Some(i) {
                container(img)
                    .style(|_| container::Style {
                        border: iced::Border {
                            color: iced::Color::from_rgb(0.3, 0.6, 1.0),
                            width: 2.0,
                            ..Default::default()
                        },
                        ..Default::default()
                    })
            } else {
                container(img)
            });
        }

        col.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::tests::make_test_vault_with_dir;

    fn fixture_path(name: &str) -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("test_resources")
            .join(name)
    }

    fn import_sample(vault: &Vault, name: &str) -> DocumentRow {
        let path = fixture_path(name);
        assert!(path.exists(), "fixture missing: {}", path.display());
        let id = vault.import_file(&path).unwrap();
        vault
            .list_documents()
            .unwrap()
            .into_iter()
            .find(|d| d.id == id)
            .unwrap()
    }

    fn loaded_state(vault: &Arc<Vault>, doc: DocumentRow) -> State {
        let (mut state, _task) = State::new(doc.clone(), vault.clone());
        assert!(state.loading);
        let loaded = load_document(vault, &doc).unwrap();
        let _ = state.update(Message::Loaded(Ok(loaded)));
        assert!(!state.loading);
        assert!(state.error.is_none());
        state
    }

    #[test]
    fn test_load_document_opens_real_pdf() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let loaded = load_document(&vault, &doc).unwrap();
        assert_eq!(loaded.page_count, 3);
        assert_eq!(loaded.first.index, 0);
        assert!(!loaded.first.data.is_empty());
        assert!(loaded.first.width > 0 && loaded.first.height > 0);
    }

    #[test]
    fn test_load_document_rejects_fake_pdf() {
        let (vault, _dir) = make_test_vault_with_dir();
        let tmp = tempfile::tempdir().unwrap();
        let fake = tmp.path().join("fake.pdf");
        std::fs::write(&fake, b"%PDF-1.4 fake content for testing").unwrap();
        let id = vault.import_file(&fake).unwrap();
        let doc = vault
            .list_documents()
            .unwrap()
            .into_iter()
            .find(|d| d.id == id)
            .unwrap();
        assert!(load_document(&vault, &doc).is_err());
    }

    #[test]
    fn test_state_loads_and_renders_all_pages() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        assert_eq!(state.page_count, 3);
        assert_eq!(state.pages.len(), 1);
        assert!(state.heights[0].is_none());
        assert_eq!(state.render_limit, 3);

        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }
        assert_eq!(state.pages.len(), 3);
        assert!(state.heights.iter().all(|h| h.is_some()));
    }

    #[test]
    fn test_page_offset_math() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        assert_eq!(state.page_at_y(0.0), 0);
        assert_eq!(state.exact_offset_of(0), PAD_TOP);
        let h0 = state.heights[0].unwrap();
        let y1 = state.exact_offset_of(1);
        assert!((y1 - (PAD_TOP + h0 + PAGE_GAP)).abs() < 0.5);
        assert_eq!(state.page_at_y(y1), 1);
    }

    #[test]
    fn test_zoom_clears_and_rerenders_at_new_width() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        assert_eq!(state.zoom, 1.0);

        let _ = state.update(Message::ZoomOut);
        assert!((state.zoom - 0.75).abs() < 1e-5);
        assert!(state.pages.is_empty());
        assert!(state.heights.iter().all(|h| h.is_none()));
        assert_eq!(
            state.pending_jump,
            Some(JumpTarget {
                page: 0,
                offset_within_page: 0.0,
            })
        );

        let handle = state.handle.clone().unwrap();
        let page = render_page(&handle, 0, target_width(state.zoom)).unwrap();
        let _ = state.update(Message::PageRendered(Ok(page)));
        assert!(state.pages.contains_key(&0));
        assert!(state.heights[0].is_some());

        let _ = state.update(Message::ResetZoom);
        assert_eq!(state.zoom, 1.0);
    }

    #[test]
    fn test_search_finds_real_text_and_wraps() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        let handle = state.handle.clone().unwrap();
        let t0 = handle.extract_text(0).unwrap().to_lowercase();
        let t1 = handle.extract_text(1).unwrap().to_lowercase();
        let t2 = handle.extract_text(2).unwrap().to_lowercase();
        assert!(t0.contains("page 1"), "page 1 text was: {t0:?}");
        assert!(t1.contains("page 2"), "page 2 text was: {t1:?}");
        assert!(t2.contains("page 3"), "page 3 text was: {t2:?}");

        let _ = state.update(Message::SearchDone(Ok(vec![0, 2])));
        assert_eq!(state.search_results, vec![0, 2]);
        assert_eq!(state.search_index, 0);
        assert!(state.search_status.is_none());

        let _ = state.update(Message::SearchNext);
        assert_eq!(state.search_index, 1);
        let _ = state.update(Message::SearchNext);
        assert_eq!(state.search_index, 0);
        let _ = state.update(Message::SearchPrev);
        assert_eq!(state.search_index, 1);
    }

    #[test]
    fn test_search_no_matches() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let _ = state.update(Message::SearchDone(Ok(vec![])));
        assert!(state.search_results.is_empty());
        assert_eq!(state.search_status, Some("No matches".to_string()));
    }

    #[test]
    fn test_view_toolbar_when_loaded() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Open externally").is_ok());
        assert!(ui.find("Reset").is_ok());
        assert!(ui.find("Search in this document…").is_ok());
    }

    #[test]
    fn test_view_loading_and_error_states() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let (state, _task) = State::new(doc.clone(), vault.clone());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("Opening document…").is_ok());

        let (mut state, _task) = State::new(doc, vault.clone());
        state.loading = false;
        state.error = Some("boom".into());
        let mut ui = iced_test::simulator(state.view());
        assert!(ui.find("boom").is_ok());
    }

    #[test]
    fn test_position_round_trip_via_db_and_restore() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc.clone());
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        let _ = state.update(Message::ZoomIn);
        assert!((state.zoom - 1.25).abs() < 1e-5);
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        let y2 = state.exact_offset_of(2);
        let _ = state.update(Message::ViewportChanged(y2, 600.0));
        assert_eq!(state.current_page, 2);

        let cached = state.leaving().unwrap();
        assert_eq!(cached.current_page, 2);
        assert!((cached.zoom - 1.25).abs() < 1e-5);

        let fresh_doc = vault
            .list_documents()
            .unwrap()
            .into_iter()
            .find(|d| d.id == doc.id)
            .unwrap();
        assert_eq!(fresh_doc.current_page, 2);
        let pos = fresh_doc.reading_position.as_deref().unwrap();
        assert!(pos.contains("\"page\":2"), "position was: {pos}");

        let (state2, _task) = State::new(fresh_doc, vault.clone());
        assert!((state2.zoom - 1.25).abs() < 1e-5);
        let restore = state2.restore.expect("restore target set");
        assert_eq!(restore.page, 2);
        assert_eq!(restore.offset_within_page, 0.0);
    }

    #[test]
    fn test_cached_reopen_keeps_position() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc.clone());
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        let y2 = state.exact_offset_of(2);
        let _ = state.update(Message::ViewportChanged(y2, 600.0));
        let cached = state.leaving().unwrap();

        let (mut state2, _task) = State::from_cached(doc, vault, cached);
        assert!(!state2.loading);
        assert!(state2.handle.is_some());
        assert!(state2.error.is_none());
        assert_eq!(state2.page_count, 3);
        assert_eq!(state2.current_page, 2);
        assert_eq!(state2.restore, None);
        assert_eq!(state2.zoom, 1.0);
        assert!(state2.heights.iter().all(|h| h.is_some()));
        assert_eq!(
            state2.pending_jump,
            Some(JumpTarget {
                page: 2,
                offset_within_page: 0.0,
            })
        );
    }
}
