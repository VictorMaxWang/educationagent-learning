// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

/// 保存转换产物：弹出原生"另存为"对话框（用户自选路径）→ 写入文件。
/// 前端传 { data: base64字符串, suggestedName, expectedSize }。
/// base64 在 Rust 侧解码并校验长度，避免大字节数组经 WebView IPC 传输被截断。
/// 返回 Some(保存路径) 成功，None 表示用户取消对话框，Err 表示失败。
#[tauri::command(rename_all = "camelCase")]
fn save_converted_file(
    data: String,
    suggested_name: String,
    expected_size: usize,
) -> Result<Option<String>, String> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(data.as_bytes())
        .map_err(|e| format!("文件数据解码失败：{}", e))?;
    if bytes.len() != expected_size {
        return Err(format!(
            "文件数据不完整（期望 {} 字节，实际 {} 字节），请重试",
            expected_size,
            bytes.len()
        ));
    }
    let path = rfd::FileDialog::new()
        .set_file_name(&suggested_name)
        .save_file();
    let Some(path) = path else { return Ok(None); };
    std::fs::write(&path, &bytes).map_err(|e| format!("写入文件失败：{}", e))?;
    Ok(Some(path.to_string_lossy().to_string()))
}

/// 小红书发布选图结果：真实路径 + base64 预览（data URL，空串表示无法预览）。
#[derive(serde::Serialize)]
struct ImagePick {
    path: String,
    preview: String,
}

/// 读取图片文件 → base64 data URL（前端缩略图用）。
/// 读失败或超过 15MB 返回空串，避免 WebView IPC 传输被撑爆。
fn read_preview(path: &str) -> String {
    use base64::Engine as _;
    let Ok(bytes) = std::fs::read(path) else { return String::new(); };
    if bytes.is_empty() || bytes.len() > 15 * 1024 * 1024 {
        return String::new();
    }
    let ext = path.rsplit('.').next().unwrap_or("").to_lowercase();
    let mime = match ext.as_str() {
        "png" => "image/png",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "heic" | "heif" => "image/heic",
        _ => "image/jpeg",
    };
    format!("data:{};base64,{}", mime, base64::engine::general_purpose::STANDARD.encode(&bytes))
}

/// 原生多选图片对话框（小红书发布用）：返回 { path, preview } 列表，取消对话框返回空数组。
#[tauri::command]
fn pick_images() -> Result<Vec<ImagePick>, String> {
    let files = rfd::FileDialog::new()
        .add_filter("图片", &["jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"])
        .pick_files();
    let Some(files) = files else { return Ok(Vec::new()); };
    Ok(files
        .iter()
        .map(|f| {
            let p = f.to_string_lossy().to_string();
            ImagePick { preview: read_preview(&p), path: p }
        })
        .collect())
}

/// 读取单个图片路径的 base64 预览（拖拽选图用），失败返回空串。
#[tauri::command]
fn read_image_preview(path: String) -> Result<String, String> {
    Ok(read_preview(&path))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            greet,
            save_converted_file,
            pick_images,
            read_image_preview
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
