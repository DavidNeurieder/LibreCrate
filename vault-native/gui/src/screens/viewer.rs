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
const SCROLL_ID: &str = "viewer-scroll";

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
pub struct CachedViewer {
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
    ScrollBy(f32),
    ScrollPage(i8),
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

fn zoom_center_target(anchor_exact: f32, offset_within_anchor: f32, ratio: f32, viewport_visible: f32) -> f32 {
    anchor_exact + offset_within_anchor * ratio - viewport_visible / 2.0
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
            |res| crate::app::Message::Viewer(Message::Loaded(res)),
        )
    }

    pub fn from_cached(
        doc: DocumentRow,
        vault: Arc<Vault>,
        cached: CachedViewer,
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

    pub fn leaving(&mut self) -> Option<CachedViewer> {
        self.persist_position();
        let handle = self.handle.clone()?;
        let tmp_dir = self.tmp_dir.clone()?;
        Some(CachedViewer {
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
                Task::done(crate::app::Message::Navigate(Navigation::ViewerExit(vault)))
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
                Task::done(crate::app::Message::Navigate(Navigation::ViewerExit(vault)))
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
            Message::ScrollBy(delta) => self
                .scroll_to_y(self.viewport_y + delta)
                .unwrap_or_else(Task::none),
            Message::ScrollPage(dir) => self
                .scroll_to_y(self.viewport_y + dir as f32 * self.viewport_visible)
                .unwrap_or_else(Task::none),
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
            |res| crate::app::Message::Viewer(Message::PageRendered(res)),
        )
    }

    fn avg_page_height(&self) -> f32 {
        let mut sum = 0.0f32;
        let mut n = 0usize;
        for h in self.heights.iter().copied().flatten() {
            sum += h;
            n += 1;
        }
        if n > 0 {
            sum / n as f32
        } else {
            (BASE_WIDTH * self.zoom) * 1.4
        }
    }

    fn page_at_y(&self, y: f32) -> usize {
        if self.page_count == 0 {
            return 0;
        }
        let avg = self.avg_page_height();
        let gap = PAGE_GAP * self.zoom;
        let mut acc = PAD_TOP * self.zoom;
        for i in 0..self.page_count {
            let h = self.heights.get(i).copied().flatten().unwrap_or(avg);
            if y <= acc + h {
                return i;
            }
            acc += h + gap;
        }
        self.page_count - 1
    }

    fn all_heights_up_to(&self, i: usize) -> bool {
        (0..=i.min(self.page_count.saturating_sub(1)))
            .all(|k| self.heights.get(k).copied().flatten().is_some())
    }

    fn exact_offset_of(&self, i: usize) -> f32 {
        let avg = self.avg_page_height();
        let gap = PAGE_GAP * self.zoom;
        let mut acc = PAD_TOP * self.zoom;
        for k in 0..i {
            acc += self.heights.get(k).copied().flatten().unwrap_or(avg) + gap;
        }
        acc
    }

    fn estimated_offset_of(&self, i: usize) -> f32 {
        self.exact_offset_of(i)
    }

    fn content_height(&self) -> f32 {
        self.exact_offset_of(self.page_count) + PAD_BOTTOM * self.zoom
    }

    fn scroll_target(&self, y: f32) -> Option<f32> {
        if self.handle.is_none() || self.page_count == 0 || self.viewport_visible <= 0.0 {
            return None;
        }
        let max = (self.content_height() - self.viewport_visible).max(0.0);
        Some(y.clamp(0.0, max))
    }

    fn scroll_to_y(&self, y: f32) -> Option<Task<crate::app::Message>> {
        let y = self.scroll_target(y)?;
        Some(operation::scroll_to(
            self.scroll_id.clone(),
            scrollable::AbsoluteOffset { x: 0.0, y },
        ))
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
                    |res| crate::app::Message::Viewer(Message::MeasureDone(res)),
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
        let ratio = new_zoom / old_zoom;
        let center = self.viewport_y + self.viewport_visible / 2.0;
        let anchor = self.page_at_y(center);
        let offset_old = (center - self.exact_offset_of(anchor)).max(0.0);
        self.zoom = new_zoom;
        for h in self.heights.iter_mut().flatten() {
            *h *= ratio;
        }
        self.pages.clear();
        self.page_bytes = vec![0; self.page_count];
        self.render_cursor = 0;
        self.render_limit = self.page_count.min(MAX_PRELOAD_PAGES);
        if self.viewport_visible <= 0.0 {
            return self.next_render_task().unwrap_or_else(Task::none);
        }
        let target = zoom_center_target(
            self.exact_offset_of(anchor),
            offset_old,
            ratio,
            self.viewport_visible,
        );
        let mut tasks = vec![operation::scroll_to(
            self.scroll_id.clone(),
            scrollable::AbsoluteOffset {
                x: 0.0,
                y: target.max(0.0),
            },
        )];
        if let Some(t) = self.next_render_task() {
            tasks.push(t);
        }
        Task::batch(tasks)
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
            |res| crate::app::Message::Viewer(Message::SearchDone(res)),
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

        let scroll = Scrollable::new(self.pages_view())
            .id(self.scroll_id.clone())
            .on_scroll(|vp| {
                let abs = vp.absolute_offset();
                Message::ViewportChanged(abs.y, vp.bounds().height)
            })
            .width(Length::Fill)
            .height(Length::Fill)
            .direction(scrollable::Direction::Both {
                vertical: scrollable::Scrollbar::default(),
                horizontal: scrollable::Scrollbar::default(),
            });

        column![
            toolbar,
            row![status].padding(iced::Padding::new(0.0).left(16.0).right(16.0)),
            container(scroll)
                .width(Length::Fill)
                .height(Length::Fill)
                .style(|_| container::Style {
                    background: Some(iced::Background::Color(iced::Color::WHITE)),
                    ..Default::default()
                }),
        ]
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    fn pages_view(&self) -> Element<'_, Message> {
        let page_width = target_width(self.zoom) as f32;
        let current = self.search_results.get(self.search_index).copied();

        let mut col = Column::new()
            .spacing(PAGE_GAP * self.zoom)
            .padding(iced::Padding::new(0.0).top(PAD_TOP * self.zoom).bottom(PAD_BOTTOM * self.zoom))
            .width(Length::Fixed(page_width));

        let avg_h = self.avg_page_height();

        for i in 0..self.page_count {
            let page_width = Length::Fixed(page_width);
            let Some(handle) = self.pages.get(&i) else {
                let h = self.heights.get(i).copied().flatten().unwrap_or(avg_h);
                col = col.push(
                    container(Column::new())
                        .width(page_width)
                        .height(Length::Fixed(h)),
                );
                continue;
            };
            let img = image::Image::new(handle.clone())
                .width(page_width);
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
    fn test_load_document_opens_epub() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "mini.epub");
        assert_eq!(doc.mime_type, "application/epub+zip");
        let loaded = load_document(&vault, &doc).unwrap();
        assert!(loaded.page_count > 0);
        assert_eq!(loaded.first.index, 0);
        assert!(!loaded.first.data.is_empty());
        assert!(loaded.first.width > 0 && loaded.first.height > 0);

        let text = loaded.handle.extract_text(0).unwrap();
        assert!(text.contains("LibreCrateMiniEpub"), "epub text was: {text:?}");

        let non_white = loaded
            .first
            .data
            .chunks(4)
            .filter(|p| **p != [255, 255, 255, 255])
            .count();
        assert!(non_white > 0, "epub page rendered blank (font source issue?)");
    }

    #[test]
    fn test_load_document_opens_script_epub() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "mini_scripts.epub");
        let loaded = load_document(&vault, &doc).unwrap();
        assert!(loaded.page_count > 0);
        assert_eq!(loaded.first.index, 0);
        assert!(!loaded.first.data.is_empty());
        assert!(loaded.first.width > 0 && loaded.first.height > 0);

        let text = loaded.handle.extract_text(0).unwrap();
        assert!(text.contains("LibreCrateMiniEpub"), "epub text was: {text:?}");
    }

    #[test]
    fn test_load_document_opens_cbz() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "mini.cbz");
        assert_eq!(doc.mime_type, "application/x-cbr");
        let loaded = load_document(&vault, &doc).unwrap();
        assert_eq!(loaded.page_count, 2);
        let p0 = render_page(&loaded.handle, 0, target_width(1.0)).unwrap();
        assert!(p0.width > 0 && p0.height > 0 && !p0.data.is_empty());
        let p1 = render_page(&loaded.handle, 1, target_width(1.0)).unwrap();
        assert!(p1.width > 0 && p1.height > 0 && !p1.data.is_empty());
    }

    #[test]
    fn test_load_document_opens_fb2() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "mini.fb2");
        let loaded = load_document(&vault, &doc).unwrap();
        assert!(loaded.page_count > 0);
        let text = loaded.handle.extract_text(0).unwrap();
        assert!(text.contains("LibreCrateMiniEpub"), "fb2 text was: {text:?}");
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

        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }
        let h0 = state.heights[0].unwrap();

        let _ = state.update(Message::ZoomOut);
        assert!((state.zoom - 0.75).abs() < 1e-5);
        assert!(state.pages.is_empty());
        assert!(state.heights.iter().all(|h| h.is_some()));
        assert!((state.heights[0].unwrap() - h0 * 0.75).abs() < 0.5);
        assert_eq!(state.pending_jump, None);

        let page = render_page(&handle, 0, target_width(state.zoom)).unwrap();
        let _ = state.update(Message::PageRendered(Ok(page)));
        assert!(state.pages.contains_key(&0));
        assert!(state.heights[0].is_some());

        let _ = state.update(Message::ResetZoom);
        assert_eq!(state.zoom, 1.0);
        assert!(state.heights.iter().all(|h| h.is_some()));
        assert!((state.heights[0].unwrap() - h0).abs() < 0.5);
    }

    #[test]
    fn test_zoom_center_target_keeps_center_fixed() {
        let anchor_exact = 100.0;
        let offset_within_anchor = 250.0;
        let ratio = 2.0;
        let visible = 600.0;
        let center_old = anchor_exact + offset_within_anchor;
        let target = zoom_center_target(anchor_exact, offset_within_anchor, ratio, visible);
        let center_new = target + visible / 2.0;
        assert!((center_new - (anchor_exact + offset_within_anchor * ratio)).abs() < 1e-4);
        assert_eq!(center_old, 350.0);
        assert!((target - 300.0).abs() < 1e-4);

        let shrink = zoom_center_target(100.0, 250.0, 0.5, 600.0);
        assert!((shrink + 75.0).abs() < 1e-4);
        let center_shrink = shrink + 600.0 / 2.0;
        assert!((center_shrink - (100.0 + 250.0 * 0.5)).abs() < 1e-4);
    }

    #[test]
    fn test_zoom_preserves_vertical_anchor() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        let y2 = state.exact_offset_of(2);
        let _ = state.update(Message::ViewportChanged(y2, 600.0));
        assert_eq!(state.current_page, 2);
        assert_eq!(state.page_at_y(y2 + 300.0), 2);

        let center_old = y2 + 300.0;
        let anchor = state.page_at_y(center_old);
        let offset_within_anchor = center_old - state.exact_offset_of(anchor);
        assert_eq!(anchor, 2);
        assert!(offset_within_anchor > 0.0 && offset_within_anchor < 600.0);

        let heights_before: Vec<f32> = state.heights.iter().map(|h| h.unwrap()).collect();
        let _ = state.update(Message::ZoomOut);
        let ratio = state.zoom / 1.0;
        assert!((ratio - 0.75).abs() < 1e-5);
        assert!(state.pages.is_empty());
        assert!(state.heights.iter().all(|h| h.is_some()));
        assert_eq!(state.pending_jump, None);
        for (before, after) in heights_before.iter().zip(state.heights.iter()) {
            let after = after.unwrap();
            assert!((after - before * ratio).abs() < 0.5, "heights should scale with zoom");
        }
        let new_exact = state.exact_offset_of(anchor);
        let target = zoom_center_target(new_exact, offset_within_anchor, ratio, 600.0);
        let new_center = target + 600.0 / 2.0;
        assert!(
            (new_center - (new_exact + offset_within_anchor * ratio)).abs() < 1e-4,
            "viewport center must stay on the same content point"
        );
    }

    #[test]
    fn test_sparse_heights_are_estimated() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        let h1 = state.heights[1].unwrap();
        state.heights[1] = None;

        let avg = (state.heights[0].unwrap() + state.heights[2].unwrap()) / 2.0;
        assert!((state.avg_page_height() - avg).abs() < 0.5);

        let y2 = state.exact_offset_of(2);
        let expected_y2 = PAD_TOP + state.heights[0].unwrap() + PAGE_GAP + avg + PAGE_GAP;
        assert!((y2 - expected_y2).abs() < 0.5, "gap height must be estimated, not zero");

        let y1_mid = PAD_TOP + state.heights[0].unwrap() + PAGE_GAP + avg * 0.5;
        assert_eq!(state.page_at_y(y1_mid), 1, "page_at_y must locate pages across gaps");
        assert_eq!(state.page_at_y(y2), 2);
        assert_eq!(state.page_at_y(0.0), 0);

        let _ = state.pages_view();
    }

    #[test]
    fn test_zoom_with_sparse_heights_keeps_viewport_center() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }

        state.heights[1] = None;
        let y2 = state.exact_offset_of(2);
        let _ = state.update(Message::ViewportChanged(y2, 600.0));
        assert_eq!(state.current_page, 2, "current page must be found across gaps");

        let center_old = y2 + 300.0;
        let anchor = state.page_at_y(center_old);
        let offset_within_anchor = center_old - state.exact_offset_of(anchor);
        assert_eq!(anchor, 2);

        let heights_before: Vec<f32> = state
            .heights
            .iter()
            .enumerate()
            .filter(|(i, h)| *i != 1 && h.is_some())
            .map(|(_, h)| h.unwrap())
            .collect();
        let _ = state.update(Message::ZoomOut);
        let ratio = state.zoom / 1.0;
        assert!((ratio - 0.75).abs() < 1e-5);
        assert!(state.pages.is_empty());
        assert!(state.heights[0].is_some() && state.heights[1].is_none() && state.heights[2].is_some());
        assert_eq!(state.pending_jump, None);
        assert!((state.heights[0].unwrap() - heights_before[0] * ratio).abs() < 0.5);
        assert!((state.heights[2].unwrap() - heights_before[1] * ratio).abs() < 0.5);

        let new_exact = state.exact_offset_of(anchor);
        let target = zoom_center_target(new_exact, offset_within_anchor, ratio, 600.0);
        let new_center = target + 600.0 / 2.0;
        assert!(
            (new_center - (new_exact + offset_within_anchor * ratio)).abs() < 1e-4,
            "zoom must keep viewport center fixed even with sparse heights"
        );
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

    #[test]
    fn test_iced_runtime_applies_zoom_in_offset() {
        use iced_test::core::renderer::Headless;
        use iced_test::core::widget::operation;
        use iced_test::core::{mouse, renderer, window, Event, Size};
        use iced_test::runtime::user_interface;
        use iced_test::runtime::UserInterface;

        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        let handle = state.handle.clone().unwrap();
        let width = target_width(state.zoom);
        for i in 0..state.page_count {
            let page = render_page(&handle, i, width).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }
        assert!(state.heights.iter().all(|h| h.is_some()));

        let size = Size::new(800.0, 600.0);
        let mut renderer = iced_test::futures::futures::executor::block_on(
            iced_test::renderer::Renderer::new(iced::Font::DEFAULT, iced::Pixels(16.0), None),
        )
        .expect("renderer");

        let y2 = state.exact_offset_of(2);
        let _ = state.update(Message::ViewportChanged(y2, 600.0));
        assert_eq!(state.viewport_y, y2);
        assert_eq!(state.viewport_visible, 600.0);

        let center = state.viewport_y + state.viewport_visible / 2.0;
        let anchor = state.page_at_y(center);
        let offset_old = (center - state.exact_offset_of(anchor)).max(0.0);
        assert_eq!(anchor, 2);
        assert!(offset_old > 0.0);

        let ui = UserInterface::build(
            state.view(),
            size,
            user_interface::Cache::default(),
            &mut renderer,
        );
        let cache = ui.into_cache();

        let task = state.update(Message::ZoomIn);
        assert!((state.zoom - 1.25).abs() < 1e-5);
        let ratio = state.zoom / 1.0;
        let target = zoom_center_target(
            state.exact_offset_of(anchor),
            offset_old,
            ratio,
            state.viewport_visible,
        );
        drop(task);

        let content_after = PAD_TOP * state.zoom
            + state.heights.iter().flatten().sum::<f32>()
            + PAGE_GAP * state.zoom * (state.page_count as f32 - 1.0)
            + PAD_BOTTOM * state.zoom;
        let max_scroll_after = content_after - 600.0;
        assert!(
            target <= max_scroll_after,
            "target {target} exceeds max scroll {max_scroll_after}"
        );

        let mut ui2 = UserInterface::build(state.view(), size, cache, &mut renderer);
        let mut op = operation::scrollable::scroll_to::<()>(
            state.scroll_id.clone(),
            scrollable::AbsoluteOffset {
                x: None,
                y: Some(target),
            },
        );
        ui2.operate(&mut renderer, &mut op);

        let mut messages: Vec<Message> = Vec::new();
        ui2.update(
            &[Event::Window(window::Event::RedrawRequested(
                iced_test::core::time::Instant::now(),
            ))],
            mouse::Cursor::Unavailable,
            &mut renderer,
            &mut iced_test::core::clipboard::Null,
            &mut messages,
        );
        ui2.draw(
            &mut renderer,
            &iced::Theme::Dark,
            &renderer::Style {
                text_color: iced::Color::BLACK,
            },
            mouse::Cursor::Unavailable,
        );

        let applied = messages
            .iter()
            .find_map(|m| match m {
                Message::ViewportChanged(y, _) => Some(*y),
                _ => None,
            })
            .expect("viewport message emitted after zoom scroll_to");
        assert!(
            (applied - target).abs() < 1.0,
            "iced applied scroll offset {applied}, expected {target}; drift {}",
            applied - target
        );
    }

    #[test]
    fn test_iced_runtime_scroll_then_zoom_sequence_keeps_center() {
        use iced_test::core::renderer::Headless;
        use iced_test::core::widget::operation;
        use iced_test::core::{mouse, renderer, window, Event, Size};
        use iced_test::runtime::user_interface;
        use iced_test::runtime::UserInterface;

        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        let mut renderer = iced_test::futures::futures::executor::block_on(
            iced_test::renderer::Renderer::new(iced::Font::DEFAULT, iced::Pixels(16.0), None),
        )
        .expect("renderer");
        let size = Size::new(800.0, 600.0);
        let mut cache = user_interface::Cache::default();

        fn render_all(state: &mut State) {
            let handle = state.handle.clone().unwrap();
            let width = target_width(state.zoom);
            for i in 0..state.page_count {
                let page = render_page(&handle, i, width).unwrap();
                let _ = state.update(Message::PageRendered(Ok(page)));
            }
        }

        fn drive(
            state: &mut State,
            renderer: &mut iced_test::renderer::Renderer,
            size: Size,
            cache: user_interface::Cache,
            target: Option<f32>,
        ) -> (f32, f32, user_interface::Cache) {
            let mut ui = UserInterface::build(state.view(), size, cache, renderer);
            if let Some(y) = target {
                let mut op = operation::scrollable::scroll_to::<()>(
                    state.scroll_id.clone(),
                    scrollable::AbsoluteOffset { x: None, y: Some(y) },
                );
                ui.operate(renderer, &mut op);
            }
            let mut msgs: Vec<Message> = Vec::new();
            ui.update(
                &[Event::Window(window::Event::RedrawRequested(
                    iced_test::core::time::Instant::now(),
                ))],
                mouse::Cursor::Unavailable,
                renderer,
                &mut iced_test::core::clipboard::Null,
                &mut msgs,
            );
            ui.draw(
                renderer,
                &iced::Theme::Dark,
                &renderer::Style {
                    text_color: iced::Color::BLACK,
                },
                mouse::Cursor::Unavailable,
            );
            let cache = ui.into_cache();
            let (mut applied, mut visible) = (0.0f32, 0.0f32);
            for m in msgs {
                if let Message::ViewportChanged(y, v) = m {
                    applied = y;
                    visible = v;
                }
            }
            let _ = state.update(Message::ViewportChanged(applied, visible));
            (applied, visible, cache)
        }

        render_all(&mut state);
        let y2 = state.exact_offset_of(2);
        let (_, _, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(y2));
        cache = cache_kept;
        assert!(
            (state.viewport_y - y2).abs() < 1.0,
            "real scroll must land at page 2: viewport_y {} vs {y2}",
            state.viewport_y
        );

        let seq = [3usize, 4, 5, 2, 0];
        let mut prev_zoom = state.zoom;
        for idx in seq {
            let target_zoom = ZOOM_STEPS[idx];
            let ratio = target_zoom / prev_zoom;
            let center_before = state.viewport_y + state.viewport_visible / 2.0;
            let anchor = state.page_at_y(center_before);
            let offset_old = (center_before - state.exact_offset_of(anchor)).max(0.0);

            let task = state.set_zoom(idx);
            drop(task);
            assert!((state.zoom - target_zoom).abs() < 1e-5);

            let target = zoom_center_target(
                state.exact_offset_of(anchor),
                offset_old,
                ratio,
                state.viewport_visible,
            );
            render_all(&mut state);

            let (applied, visible, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(target));
            cache = cache_kept;
            let expected_center = center_before * ratio;
            let actual_center = applied + visible / 2.0;
            assert!(
                (actual_center - expected_center).abs() < 1.5,
                "zoom {target_zoom}: center drifted by {} (expected {expected_center}, actual {actual_center}, anchor {anchor}, offset_old {offset_old}, applied {applied})",
                actual_center - expected_center
            );
            prev_zoom = target_zoom;
        }
    }

    #[test]
    fn test_scroll_target_clamps_and_guards() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        assert_eq!(state.scroll_target(100.0), None, "no viewport yet");

        let handle = state.handle.clone().unwrap();
        for i in 0..state.page_count {
            let page = render_page(&handle, i, target_width(state.zoom)).unwrap();
            let _ = state.update(Message::PageRendered(Ok(page)));
        }
        let _ = state.update(Message::ViewportChanged(100.0, 600.0));

        assert_eq!(state.scroll_target(150.0), Some(150.0));
        assert_eq!(state.scroll_target(-1000.0), Some(0.0));
        let max = (state.content_height() - 600.0).max(0.0);
        assert_eq!(state.scroll_target(f32::MAX), Some(max));
        assert_eq!(state.scroll_target(max + 500.0), Some(max));
    }

    #[test]
    fn test_iced_runtime_scroll_keys_scroll_document() {
        use iced_test::core::renderer::Headless;
        use iced_test::core::widget::operation;
        use iced_test::core::{mouse, renderer, window, Event, Size};
        use iced_test::runtime::user_interface;
        use iced_test::runtime::UserInterface;

        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);

        let mut renderer = iced_test::futures::futures::executor::block_on(
            iced_test::renderer::Renderer::new(iced::Font::DEFAULT, iced::Pixels(16.0), None),
        )
        .expect("renderer");
        let size = Size::new(800.0, 600.0);
        let mut cache = user_interface::Cache::default();

        fn render_all(state: &mut State) {
            let handle = state.handle.clone().unwrap();
            let width = target_width(state.zoom);
            for i in 0..state.page_count {
                let page = render_page(&handle, i, width).unwrap();
                let _ = state.update(Message::PageRendered(Ok(page)));
            }
        }

        fn drive(
            state: &mut State,
            renderer: &mut iced_test::renderer::Renderer,
            size: Size,
            cache: user_interface::Cache,
            target: Option<f32>,
        ) -> (f32, f32, user_interface::Cache) {
            let mut ui = UserInterface::build(state.view(), size, cache, renderer);
            if let Some(y) = target {
                let mut op = operation::scrollable::scroll_to::<()>(
                    state.scroll_id.clone(),
                    scrollable::AbsoluteOffset { x: None, y: Some(y) },
                );
                ui.operate(renderer, &mut op);
            }
            let mut msgs: Vec<Message> = Vec::new();
            ui.update(
                &[Event::Window(window::Event::RedrawRequested(
                    iced_test::core::time::Instant::now(),
                ))],
                mouse::Cursor::Unavailable,
                renderer,
                &mut iced_test::core::clipboard::Null,
                &mut msgs,
            );
            ui.draw(
                renderer,
                &iced::Theme::Dark,
                &renderer::Style {
                    text_color: iced::Color::BLACK,
                },
                mouse::Cursor::Unavailable,
            );
            let cache = ui.into_cache();
            let (mut applied, mut visible) = (0.0f32, 0.0f32);
            for m in msgs {
                if let Message::ViewportChanged(y, v) = m {
                    applied = y;
                    visible = v;
                }
            }
            let _ = state.update(Message::ViewportChanged(applied, visible));
            (applied, visible, cache)
        }

        render_all(&mut state);
        let y2 = state.exact_offset_of(2);
        let (_, _, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(y2));
        cache = cache_kept;
        assert!(state.viewport_visible > 0.0);

        let down = state.scroll_target(state.viewport_y + 50.0).unwrap();
        let _ = state.update(Message::ScrollBy(50.0));
        let (applied, _, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(down));
        cache = cache_kept;
        assert!(
            (applied - down).abs() < 1.0,
            "arrow down: expected {down}, got {applied}"
        );

        let page_down = state.scroll_target(state.viewport_y + state.viewport_visible).unwrap();
        let _ = state.update(Message::ScrollPage(1));
        let (applied, _, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(page_down));
        cache = cache_kept;
        assert!(
            (applied - page_down).abs() < 1.0,
            "page down: expected {page_down}, got {applied}"
        );
        assert_eq!(state.current_page, state.page_at_y(state.viewport_y));

        let page_up = state.scroll_target(state.viewport_y - state.viewport_visible).unwrap();
        let _ = state.update(Message::ScrollPage(-1));
        let (applied, _, cache_kept) = drive(&mut state, &mut renderer, size, cache, Some(page_up));
        cache = cache_kept;
        assert!(
            (applied - page_up).abs() < 1.0,
            "page up: expected {page_up}, got {applied}"
        );
    }

    #[test]
    fn diag_height_drift() {
        let (vault, _dir) = make_test_vault_with_dir();
        let doc = import_sample(&vault, "search_3page.pdf");
        let mut state = loaded_state(&vault, doc);
        let handle = state.handle.clone().unwrap();
        let h100: Vec<f32> = (0..state.page_count)
            .map(|i| render_page(&handle, i, target_width(1.0)).unwrap().height as f32)
            .collect();
        eprintln!("h100 = {h100:?}");
        let scaled: Vec<f32> = h100.iter().map(|h| h * 1.25).collect();
        eprintln!("scaled@1.25 = {scaled:?}");
        let actual: Vec<f32> = (0..state.page_count)
            .map(|i| render_page(&handle, i, target_width(1.25)).unwrap().height as f32)
            .collect();
        eprintln!("actual@1.25 = {actual:?}");
        let deltas: Vec<f32> = actual.iter().zip(scaled.iter()).map(|(a, s)| a - s).collect();
        eprintln!("delta = {deltas:?}");
        eprintln!(
            "cumulative below page 2 = {}",
            deltas.iter().sum::<f32>()
        );

        let scaled_out: Vec<f32> = h100.iter().map(|h| h * 0.75).collect();
        let actual_out: Vec<f32> = (0..state.page_count)
            .map(|i| render_page(&handle, i, target_width(0.75)).unwrap().height as f32)
            .collect();
        let deltas_out: Vec<f32> = actual_out
            .iter()
            .zip(scaled_out.iter())
            .map(|(a, s)| a - s)
            .collect();
        eprintln!("delta_out(0.75) = {deltas_out:?}");
    }
}
