// ============================================================================
//  Agora live streaming + Nosmai on-device moderation (web).
//
//  Agora captures and publishes the broadcaster's camera. We read the same
//  local video track into a <video> element and run each sampled frame through
//  the Nosmai Moderation Web SDK (NSFW + object detection) with analyzeImage().
//  All moderation runs in the browser; only Agora's own media transport uses
//  the network. Same idea as the Android/iOS examples in this repo.
// ============================================================================

import AgoraRTC from "agora-rtc-sdk-ng";
import { NosmaiModeration } from "@nosmai/moderation-web";

// ---- CONFIG ---------------------------------------------------------------
// Set these in .env.local. For a testing-mode Agora project, leave
// VITE_AGORA_TOKEN empty.
const AGORA_APP_ID = import.meta.env.VITE_AGORA_APP_ID || "";
const AGORA_CHANNEL = import.meta.env.VITE_AGORA_CHANNEL || "moderation_web";
const AGORA_TOKEN = import.meta.env.VITE_AGORA_TOKEN || null;
const NOSMAI_LICENSE_KEY = import.meta.env.VITE_NOSMAI_LICENSE_KEY || "";

// Encrypted model files, served from public/models/ (provided with the license).
const MODELS = {
  nsfw: "/models/nsm_nsfw.onnx",
  detector: "/models/nsm_detector.onnx",
  text: "/models/nsm_text.onnx",
  vocab: "/models/vocab.txt",
};

// Run one analysis per second — the detector samples internally, so pushing
// every frame just wastes work. Plenty for moderation.
const MODERATE_EVERY_MS = 1000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const isConfigured = (v) => v && !v.includes("YOUR_") && !v.includes("NOSMAI-XXXX");

const els = {
  start: document.getElementById("start"),
  switch: document.getElementById("switch"),
  stop: document.getElementById("stop"),
  status: document.getElementById("status"),
  video: document.getElementById("video"),
  overlay: document.getElementById("overlay"),
  verdict: document.getElementById("verdict"),
  textIn: document.getElementById("text-in"),
  textBtn: document.getElementById("text-btn"),
  textStatus: document.getElementById("text-status"),
  textCard: document.getElementById("text-card"),
  textVerdict: document.getElementById("text-verdict"),
  textDetail: document.getElementById("text-detail"),
};
const octx = els.overlay.getContext("2d");

let client = null;
let cameraTrack = null;
let streaming = false;
let visionReady = false;
let textReady = false;
let camIndex = 0;

// ---- Nosmai SDK -----------------------------------------------------------

async function assertEncryptedModel(url) {
  const res = await fetch(url, {
    cache: "no-store",
    headers: { Range: "bytes=0-3" },
  });
  if (!res.ok) {
    throw new Error(`Model not found: ${url}. Put the encrypted model files in web/public/models/.`);
  }

  const reader = res.body?.getReader();
  const first = reader ? (await reader.read()).value : new Uint8Array(await res.arrayBuffer());
  await reader?.cancel();
  const bytes = first || new Uint8Array();
  const isNsm1 =
    bytes.length >= 4 &&
    bytes[0] === 0x4e &&
    bytes[1] === 0x53 &&
    bytes[2] === 0x4d &&
    bytes[3] === 0x31;
  if (!isNsm1) {
    throw new Error(`Invalid model file: ${url}. Expected an encrypted NSM1 model, not HTML/plaintext/wrong format.`);
  }
}

async function initNosmai() {
  if (!isConfigured(NOSMAI_LICENSE_KEY)) {
    els.status.textContent = "Set VITE_NOSMAI_LICENSE_KEY in web/.env.local.";
    els.start.disabled = true;
    return false;
  }

  const lic = await NosmaiModeration.initialize(NOSMAI_LICENSE_KEY);
  if (!lic.success) {
    els.status.textContent = "License failed: " + lic.status + " - " + (lic.error || "");
    els.start.disabled = true;
    return false;
  }
  els.status.textContent = "Ready. Press Start stream.";
  return true;
}

async function ensureVision() {
  if (visionReady) return;
  els.status.textContent = "Loading moderation models...";
  await assertEncryptedModel(MODELS.nsfw);
  await assertEncryptedModel(MODELS.detector);
  await NosmaiModeration.initializeNsfw({ modelUrl: MODELS.nsfw });
  await NosmaiModeration.initializeDetector({ modelUrl: MODELS.detector });
  visionReady = true;
}

async function ensureText() {
  if (textReady) return;
  els.textStatus.textContent = "Loading text model...";
  await assertEncryptedModel(MODELS.text);
  await NosmaiModeration.initializeText({ modelUrl: MODELS.text, vocabUrl: MODELS.vocab });
  textReady = true;
}

// ---- Agora ----------------------------------------------------------------

async function startStream() {
  els.start.disabled = true;
  try {
    if (!isConfigured(AGORA_APP_ID)) {
      throw new Error("Set VITE_AGORA_APP_ID in web/.env.local.");
    }
    await ensureVision();

    client = AgoraRTC.createClient({ mode: "live", codec: "vp8" });
    await client.setClientRole("host");
    await client.join(AGORA_APP_ID, AGORA_CHANNEL, AGORA_TOKEN || null, null);

    // Video-only, matching the mobile examples (no microphone permission).
    cameraTrack = await AgoraRTC.createCameraVideoTrack({ encoderConfig: "480p_1" });
    await client.publish([cameraTrack]);

    // Preview + moderation both read this one track.
    els.video.srcObject = new MediaStream([cameraTrack.getMediaStreamTrack()]);
    els.video.muted = true;
    els.video.playsInline = true;
    await els.video.play();

    streaming = true;
    els.stop.disabled = false;
    els.switch.disabled = false;
    els.status.textContent = "Live.";
    moderationLoop();
  } catch (e) {
    els.status.textContent = "Failed to start: " + e;
    els.start.disabled = false;
    console.error(e);
  }
}

async function stopStream() {
  streaming = false;
  els.stop.disabled = true;
  els.switch.disabled = true;
  if (cameraTrack) {
    cameraTrack.stop();
    cameraTrack.close();
    cameraTrack = null;
  }
  if (client) {
    await client.leave();
    client = null;
  }
  els.video.srcObject = null;
  octx.clearRect(0, 0, els.overlay.width, els.overlay.height);
  els.verdict.textContent = "";
  els.verdict.className = "verdict";
  els.start.disabled = false;
  els.status.textContent = "Stopped.";
}

// Cycle through the available cameras (Agora swaps the device on the live track).
async function switchCamera() {
  if (!cameraTrack) return;
  const cams = await AgoraRTC.getCameras();
  if (cams.length < 2) {
    els.status.textContent = "Only one camera available.";
    return;
  }
  camIndex = (camIndex + 1) % cams.length;
  await cameraTrack.setDevice(cams[camIndex].deviceId);
}

// ---- Moderation loop ------------------------------------------------------

async function moderationLoop() {
  while (streaming) {
    const v = els.video;
    if (v.readyState >= 2 && v.videoWidth > 0) {
      try {
        const r = await NosmaiModeration.analyzeImage(v);
        if (streaming) renderVerdict(r);
      } catch (e) {
        console.error(e);
      }
    }
    await sleep(MODERATE_EVERY_MS);
  }
}

function renderVerdict(r) {
  const w = els.video.videoWidth;
  const h = els.video.videoHeight;
  if (!w) return;
  if (els.overlay.width !== w) {
    els.overlay.width = w;
    els.overlay.height = h;
  }
  els.overlay.style.width = els.video.clientWidth + "px";
  els.overlay.style.height = els.video.clientHeight + "px";
  octx.clearRect(0, 0, w, h);

  els.verdict.className = "verdict " + (r.isUnsafe ? "unsafe" : "safe");
  els.verdict.textContent =
    (r.isUnsafe ? "UNSAFE" : "SAFE") +
    " - NSFW: " + r.nsfw.verdict +
    (r.detections.length ? " - " + r.detections.map((d) => d.category).join(", ") : "");
}

// ---- Text moderation ------------------------------------------------------

async function moderateText() {
  const msg = els.textIn.value.trim();
  if (!msg) return;
  els.textBtn.disabled = true;
  try {
    await ensureText();
    els.textStatus.textContent = "Checking...";
    const r = await NosmaiModeration.moderateText(msg);
    els.textCard.style.display = "block";
    els.textCard.className = "card " + (r.blocked ? "block" : "safe");
    els.textVerdict.className = "verdict " + (r.blocked ? "block" : "safe");
    els.textVerdict.textContent = r.blocked ? "BLOCKED" : "ALLOWED";
    els.textDetail.innerHTML =
      `Category: <code>${r.category}</code> &nbsp; Layer: <code>${r.layer}</code> &nbsp; Score: ${r.score.toFixed(3)}` +
      (r.matchedWord ? ` &nbsp; Matched: <code>${r.matchedWord}</code>` : "");
    els.textStatus.textContent = "";
  } catch (e) {
    els.textStatus.textContent = "Error: " + e;
    console.error(e);
  }
  els.textBtn.disabled = false;
}

// ---- Wire up --------------------------------------------------------------

els.start.addEventListener("click", startStream);
els.stop.addEventListener("click", stopStream);
els.switch.addEventListener("click", switchCamera);
els.textBtn.addEventListener("click", moderateText);

initNosmai();
