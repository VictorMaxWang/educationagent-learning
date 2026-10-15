import assert from 'node:assert/strict';
import test from 'node:test';
import { canRecordClassroomAudio, encodeClassroomWav, startClassroomRecording } from '../src/classroom/recording.ts';

test('48k microphone samples become mono 16k PCM WAV with correct duration and amplitude', async () => {
  const blob = encodeClassroomWav(new Float32Array(48000).fill(0.5), 48000);
  const view = new DataView(await blob.arrayBuffer());
  assert.equal(blob.type, 'audio/wav');
  assert.equal(blob.size, 32044);
  assert.equal(view.getUint32(4, true) + 8, blob.size);
  assert.equal(view.getUint16(20, true), 1);
  assert.equal(view.getUint16(22, true), 1);
  assert.equal(view.getUint32(24, true), 16000);
  assert.equal(view.getUint16(34, true), 16);
  assert.equal(view.getUint32(40, true), 32000);
  assert.equal(view.getInt16(44, true), 16384);
});

test('16k source clips safely and 44.1k source retains accurate duration', async () => {
  const clipped = new DataView(await encodeClassroomWav(new Float32Array([-2, 2, Number.NaN]), 16000).arrayBuffer());
  assert.equal(clipped.getInt16(44, true), -32768);
  assert.equal(clipped.getInt16(46, true), 32767);
  assert.equal(clipped.getInt16(48, true), 0);
  const resampled = encodeClassroomWav(new Float32Array(44100), 44100);
  assert.equal(resampled.size, 32044);
});

test('hard duration bound prevents audio over 60 seconds and rejects empty recordings', () => {
  assert.equal(encodeClassroomWav(new Float32Array(16000 * 61), 16000).size, 1920044);
  assert.throws(() => encodeClassroomWav(new Float32Array(), 48000), /没有录到声音/);
  assert.throws(() => encodeClassroomWav(new Float32Array(1), Number.NaN), /没有录到声音/);
});

function fakeBrowser({ secure = true, resumeFailure = false, resumeGate } = {}) {
  const oldWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const state = { stopped: 0, closed: 0, processor: undefined, requests: 0 };
  const node = () => ({ connect() {}, disconnect() {} });
  class FakeAudioContext {
    sampleRate = 16000;
    state = 'suspended';
    async resume() { if (resumeFailure) throw new Error('permission failed'); if (resumeGate) await resumeGate; if (this.state !== 'closed') this.state = 'running'; }
    createMediaStreamSource() { return node(); }
    createScriptProcessor() { state.processor = { ...node(), onaudioprocess: null }; return state.processor; }
    createGain() { return { ...node(), gain: { value: 1 } }; }
    async close() { this.state = 'closed'; state.closed += 1; }
    destination = {};
  }
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { isSecureContext: secure, AudioContext: FakeAudioContext } });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { mediaDevices: {
    async getUserMedia() {
      state.requests += 1;
      return { getTracks: () => [{ stop() { state.stopped += 1; } }] };
    },
  } } });
  return {
    state,
    emit(samples = 1600) { state.processor.onaudioprocess?.({ inputBuffer: { getChannelData: () => new Float32Array(samples) } }); },
    restore() {
      if (oldWindow) Object.defineProperty(globalThis, 'window', oldWindow); else delete globalThis.window;
      if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator); else delete globalThis.navigator;
    },
  };
}

test('insecure LAN HTTP offers fallback without requesting microphone access', async () => {
  const browser = fakeBrowser({ secure: false });
  try {
    assert.equal(canRecordClassroomAudio(), false);
    await assert.rejects(startClassroomRecording(), /HTTPS/);
    assert.equal(browser.state.requests, 0);
  } finally { browser.restore(); }
});

test('stop produces WAV and releases microphone, audio nodes and timer', async () => {
  const browser = fakeBrowser();
  try {
    const recording = await startClassroomRecording();
    browser.emit();
    const blob = await recording.stop();
    assert.equal(blob.size, 3244);
    assert.equal(await recording.stop(), blob);
    assert.equal(browser.state.stopped, 1);
    assert.equal(browser.state.closed, 1);
    assert.equal(browser.state.processor.onaudioprocess, null);
  } finally { browser.restore(); }
});

test('cancel releases microphone and will not submit recorded audio', async () => {
  const browser = fakeBrowser();
  try {
    const recording = await startClassroomRecording();
    browser.emit(); recording.cancel();
    assert.equal(browser.state.stopped, 1);
    assert.equal(browser.state.closed, 1);
    await assert.rejects(recording.stop(), /已取消/);
  } finally { browser.restore(); }
});

test('aborting a permission request releases global microphone focus and stops its late stream', async () => {
  const browser = fakeBrowser();
  const realGetUserMedia = navigator.mediaDevices.getUserMedia;
  let provideLateStream;
  let lateStops = 0;
  navigator.mediaDevices.getUserMedia = () => new Promise(resolve => { provideLateStream = resolve; });
  const cancellation = new AbortController();
  const pending = startClassroomRecording({ signal: cancellation.signal });
  const rejected = assert.rejects(pending, /录音已取消/);
  cancellation.abort();
  navigator.mediaDevices.getUserMedia = realGetUserMedia;
  try {
    const current = await startClassroomRecording();
    provideLateStream({ getTracks: () => [{ stop() { lateStops++; } }] });
    await rejected;
    assert.equal(lateStops, 1);
    browser.emit(); current.cancel();
    assert.equal(browser.state.stopped, 1);
  } finally { browser.restore(); }
});

test('automatic duration stop also releases microphone when caller has no callback', async () => {
  const browser = fakeBrowser();
  try {
    const recording = await startClassroomRecording({ maxDurationSeconds: 1 });
    browser.emit(32000);
    assert.equal(browser.state.stopped, 1);
    assert.equal((await recording.stop()).size, 32044);
  } finally { browser.restore(); }
});

test('aborting during audio context resume does not create a late processor or timer', async () => {
  let allowResume;
  const browser = fakeBrowser({ resumeGate: new Promise(resolve => { allowResume = resolve; }) });
  const cancellation = new AbortController();
  try {
    const pending = startClassroomRecording({ signal: cancellation.signal });
    const rejected = assert.rejects(pending, /录音已取消/);
    await Promise.resolve();
    cancellation.abort(); allowResume();
    await rejected;
    assert.equal(browser.state.stopped, 1);
    assert.equal(browser.state.closed, 1);
    assert.equal(browser.state.processor, undefined);
  } finally { browser.restore(); }
});

test('failed audio context initialization releases acquired microphone', async () => {
  const browser = fakeBrowser({ resumeFailure: true });
  try {
    await assert.rejects(startClassroomRecording(), /permission failed/);
    assert.equal(browser.state.stopped, 1);
    assert.equal(browser.state.closed, 1);
  } finally { browser.restore(); }
});
