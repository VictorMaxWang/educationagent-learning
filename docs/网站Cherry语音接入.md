# 网站 Cherry（芊悦）朗读

网站课堂教师、课堂同学、成长问答和全局语音球统一通过 `ClassroomVoiceService → WebsiteTtsService`。
默认使用用户原 MaaS 已通过真实短句验证的 WebSocket 通道 `qwen3-tts-flash-realtime` / `Cherry`，不更改视频旁白慧慧、微信或英语原有的腾讯指定音色，也不更改腾讯 ASR。

## 配置与凭证边界

| 配置 | 默认值 / 来源 | 用途 |
| --- | --- | --- |
| `website.tts.provider` | `WEBSITE_TTS_PROVIDER`，默认 `maas-qwen-realtime` | 已验证实时通道；`maas-qwen` HTTP 仅显式诊断，另可选官方 `aliyun` 或兼容 `tencent` |
| `website.tts.voice` | `WEBSITE_TTS_VOICE`，默认 `Cherry` | 网站各角色共用的真实 Qwen 音色 |
| `website.tts.realtime.model` | `WEBSITE_TTS_REALTIME_MODEL`，默认 `qwen3-tts-flash-realtime` | 实时模型 ID |
| `website.tts.realtime.endpoint` | `MAAS_TTS_REALTIME_ENDPOINT` | 默认 `wss://maas.qianwenaiapi.com/api-ws/v1/realtime`，程序添加模型查询参数 |
| `website.tts.model` | `WEBSITE_TTS_MODEL`，默认 `qwen3-tts-flash` | 显式 HTTP 模式的模型 ID |
| `website.tts.maas.api-key` | `AI_CHAT_API_KEY` | 仅向原 MaaS 端点发送 |
| `website.tts.maas.endpoint` | `MAAS_TTS_ENDPOINT` | 默认 `https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation` |
| `website.tts.aliyun.api-key` | `DASHSCOPE_API_KEY` | 仅在显式选择官方 `aliyun` 时使用 |
| `website.tts.aliyun.endpoint` | `ALIYUN_TTS_ENDPOINT` | 默认官方北京端点；不得填第三方域名 |
| `website.tts.ffmpeg` | `WEBSITE_TTS_FFMPEG`，默认 `ffmpeg` | 真实 WAV → MP3 转码程序；服务 PATH 外可填绝对路径 |

不把 `AI_CHAT_API_KEY` 回落到官方百炼。官方模式额外拒绝当前 MaaS 使用的 `sk-ws-` 前缀。两种模式均核对域名和固定路径，禁止 HTTP 跳转；下载签名音频 URL 不附带 API Key，也不输出 Key、签名 URL 或错误正文。

`/api/classroom/voice/health` 返回配置及转码程序是否可用、真实供应商、模型、音色 ID 和名称。这不是计费接口或声音试听探测，不能把 `ttsAvailable=true` 当成云端调用成功。

## 音频与兼容性

默认实时通道收到 `session.created` 后设置 Cherry、commit、PCM 24000；`session.updated` 确认后按顺序发送 append 和 commit。程序累积 `response.audio.delta`，必须收到 `response.done.response.status=completed`，再发送 `session.finish` 并确认 `session.finished`，才封装完整 24kHz 单声道 16bit WAV。提前关闭、超时、错误状态或线程取消均不缓存残缺音频。

WAV 通过本地 ffmpeg 实际编码为单声道 16kHz / 64kbps MP3，继续使用原 `.mp3` URL、`audio/mpeg` 响应及课程导出格式。显式 HTTP 模式从 `output.audio.url` 下载 WAV，当前原 MaaS HTTP 探测返回 400，因此不作为默认，也不在实时失败时自动回退。

长正文仍按不超过 150 字的小段逐句合成，原文字与顺序保留，任何段失败都不给残缺成功播放列表。缓存标识包含供应商、端点、模型、实际音色和编码版本，切换 Cherry 不会复用旧腾讯声音。

缺少凭证、ffmpeg 或模型请求失败时保留文字使用方式；不自动以腾讯声音冒充 Cherry。转码只处理合成课堂音频，学生 ASR 录音仍不落盘。

## 验证

单元测试覆盖实时事件顺序、JSON 分帧、音色/格式确认、完整完成边界、提前关闭、取消、错误和大小限制；另覆盖 MaaS 凭据不发官方域名、下载不附带 API Key、错误/损坏 WAV 拒收、实际离线 MP3 转码、旧腾讯显式音色兼容与缓存失效。

真实模型可用性和声音试听需在对应部署环境单独验收；测试使用模拟响应，不使用生产凭据或计费调用。

参考：[原 MaaS WebSocket 接口](https://platform.qianwenai.com/docs/api-reference/speech-synthesis/qwen-tts-realtime/websocket-api.md)、[Qwen-TTS 官方 API](https://help.aliyun.com/zh/model-studio/qwen-tts-api)、[官方音色列表](https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list)、[原 MaaS HTTP 接口](https://platform.qianwenai.com/docs/api-reference/speech-synthesis/qwen-tts)。
